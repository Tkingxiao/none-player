# 滚动 jank 复测器（真机 A/B 用）。
#
# 为什么要有这个脚本：§9.7 的纪律是"模拟器绝对 jank% 无判定力，只做同条件 A/B"，
# 而 A/B 的前提是**两次跑的是同一个脚本、同一批坐标、同一段时长**——手敲 adb 一定漂。
#
# 用法：
#   .\tools\jank.ps1 -Tag before -Rounds 6
#   .\tools\jank.ps1 -Tag after  -Rounds 6 -Target grid
#
# -Target home = 首页（纵向滑媒体首页，用户实报场景）
#        grid = 「全部视频」聚合网格（81 项大列表，Compose lazy 新项组合最重的场景）
#
# 输出：Total / Janky / 百分位 / Slow UI / Slow draw / GPU 百分位。
# 判定口径（E125 教训）：**只看绝对计数与百分位，不看 janky%**——
# 帧总数变了时百分比不可比（降档会少产一批廉价帧，剩下都是真活，中位数反而升）。
param(
    [string]$Tag = "run",
    [int]$Rounds = 6,
    [ValidateSet("home", "grid")]
    [string]$Target = "home",
    [string]$Package = "com.noneplayer.app",
    [int]$SettleMs = 6000,
    [switch]$KeepWarm
)

$ErrorActionPreference = "Stop"
$adb = "adb"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb @Args
}

function Get-ScreenSize {
    $out = & $adb shell wm size
    if ($out -match "Override size:\s*(\d+)x(\d+)") { return @([int]$Matches[1], [int]$Matches[2]) }
    if ($out -match "Physical size:\s*(\d+)x(\d+)") { return @([int]$Matches[1], [int]$Matches[2]) }
    throw "无法解析屏幕尺寸: $out"
}

function Start-HomePage {
    & $adb shell am force-stop $Package | Out-Null
    & $adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds $SettleMs
}

# 网格场景：先进「全部视频」聚合（首页 "全部视频" 区块的「查看全部」在右侧）。
# 用 uiautomator 找到它的 bounds，避免写死坐标——不同密度/尺寸下坐标必漂。
function Enter-Grid {
    $dump = "$env:TEMP\np-ui.xml"
    & $adb shell uiautomator dump /sdcard/np-ui.xml | Out-Null
    & $adb pull /sdcard/np-ui.xml $dump | Out-Null
    $xml = Get-Content $dump -Raw
    $m = [regex]::Match($xml, 'text="查看全部"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { throw "首页没找到「查看全部」入口" }
    $cx = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $cy = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $adb shell input tap $cx $cy | Out-Null
    Start-Sleep -Milliseconds 1500
}

function Invoke-ScrollRound {
    param([int]$W, [int]$H)
    $x = [int]($W / 2)
    $yTop = [int]($H * 0.78)
    $yBottom = [int]($H * 0.28)
    # 上滑（内容向上走）→ 停 → 下滑回顶。两段都给 fling 速度，
    # 单次 220ms 的 swipe 会产生真实惯性滚动（不是"慢慢拖"）。
    & $adb shell input swipe $x $yTop $x $yBottom 220 | Out-Null
    Start-Sleep -Milliseconds 350
    & $adb shell input swipe $x $yBottom $x $yTop 220 | Out-Null
    Start-Sleep -Milliseconds 350
}

function Get-GfxSummary {
    $raw = & $adb shell dumpsys gfxinfo $Package
    $text = $raw -join "`n"
    $get = {
        param($pattern)
        $m = [regex]::Match($text, $pattern)
        if ($m.Success) { return $m.Groups[1].Value } else { return "-" }
    }
    [pscustomobject]@{
        Tag       = $Tag
        Target    = $Target
        Total     = & $get "Total frames rendered:\s*(\d+)"
        Janky     = & $get "Janky frames:\s*(\d+)"
        JankyPct  = & $get "Janky frames:\s*\d+\s*\(([\d.]+)%\)"
        P50       = & $get "50th percentile:\s*(\d+ms)"
        P90       = & $get "90th percentile:\s*(\d+ms)"
        P95       = & $get "95th percentile:\s*(\d+ms)"
        P99       = & $get "99th percentile:\s*(\d+ms)"
        SlowUi    = & $get "Number Slow UI thread:\s*(\d+)"
        SlowDraw  = & $get "Number Slow draw:\s*(\d+)"
        GpuP50    = & $get "50th percentile:\s*(\d+ms)"
    }
}

function Get-GpuSummary {
    $raw = & $adb shell dumpsys gfxinfo $Package | Out-String
    # GPU 段在 "GPU HISTOGRAM" 之后；取该段里的百分位
    $idx = $raw.IndexOf("GPU HISTOGRAM")
    if ($idx -lt 0) { return "GPU: -" }
    $gpu = $raw.Substring($idx)
    $p50 = [regex]::Match($gpu, "50th percentile:\s*(\d+ms)")
    $p90 = [regex]::Match($gpu, "90th percentile:\s*(\d+ms)")
    $p99 = [regex]::Match($gpu, "99th percentile:\s*(\d+ms)")
    return "GPU p50=$($p50.Groups[1].Value) p90=$($p90.Groups[1].Value) p99=$($p99.Groups[1].Value)"
}

$size = Get-ScreenSize
Write-Host "[$Tag] 屏幕 $($size[0])x$($size[1]) · target=$Target · rounds=$Rounds"

if (-not $KeepWarm) {
    Start-HomePage
} else {
    & $adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 1500
}

if ($Target -eq "grid") { Enter-Grid }

# 预热一段：把 JIT/首次组合/封面解码等"第一轮成本"跑掉，再重置计数——
# 用户报告明确说冷启动的 JIT 与 GC 污染了首轮样本（§9.7 同族纪律）。
Write-Host "[$Tag] 预热 2 轮…"
Invoke-ScrollRound -W $size[0] -H $size[1]
Invoke-ScrollRound -W $size[0] -H $size[1]
Start-Sleep -Milliseconds 800

& $adb shell dumpsys gfxinfo $Package reset | Out-Null
Start-Sleep -Milliseconds 300

Write-Host "[$Tag] 采集 $Rounds 轮…"
for ($i = 0; $i -lt $Rounds; $i++) {
    Invoke-ScrollRound -W $size[0] -H $size[1]
}

Start-Sleep -Milliseconds 800
$s = Get-GfxSummary
$gpu = Get-GpuSummary
$s | Format-List
Write-Host "$gpu"

$line = "[$Tag][$Target] total=$($s.Total) janky=$($s.Janky) ($($s.JankyPct)%) p50=$($s.P50) p90=$($s.P90) p95=$($s.P95) p99=$($s.P99) slowUI=$($s.SlowUi) slowDraw=$($s.SlowDraw) $gpu"
$outFile = Join-Path $PSScriptRoot "jank-$Tag-$Target.txt"
$line | Set-Content -Path $outFile -Encoding UTF8
Write-Host "已写入 $outFile"
