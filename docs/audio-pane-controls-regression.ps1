param(
    [ValidatePattern("^emulator-\d+$")]
    [string]$Device = "emulator-5556",
    [switch]$Narrow
)

$ErrorActionPreference = "Stop"
$output = Join-Path $PSScriptRoot "screenshots/audio-mode-2026-10-13/$Device"
$prefix = if ($Narrow) { "pane-narrow" } else { "pane-controls" }

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb.exe -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($result -join ' ')" }
    return $result -join "`n"
}

function Snapshot {
    param([string]$Name)
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/np-pane-controls.xml") | Out-Null
    [xml]$xml = Invoke-Adb @("shell", "cat", "/sdcard/np-pane-controls.xml")
    $xml.Save((Join-Path $output "$prefix-$Name.xml"))
    Invoke-Adb @("shell", "screencap", "-p", "/sdcard/np-pane-controls.png") | Out-Null
    Invoke-Adb @("pull", "/sdcard/np-pane-controls.png", (Join-Path $output "$prefix-$Name.png")) | Out-Null
    return $xml
}

function Tap {
    param([xml]$Hierarchy, [string]$Label)
    $node = $Hierarchy.SelectNodes("//node") | Where-Object {
        $_.text -eq $Label -or $_."content-desc" -eq $Label
    } | Select-Object -First 1
    if (!$node) { throw "Missing control: $Label" }
    $b = [regex]::Match($node.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    $x = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
    $y = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
    Start-Sleep -Seconds 1
}

$sizeChanged = $false
try {
    if ($Narrow) {
        $initialSize = Invoke-Adb @("shell", "wm", "size")
        if ($initialSize -match "Override size") { throw "An existing simulator size override must be preserved" }
        Invoke-Adb @("shell", "wm", "size", "2000x1300") | Out-Null
        $sizeChanged = $true
        Start-Sleep -Seconds 2
    }
    $route = "noneplayer://player?uri=file%3A%2F%2F%2Fsdcard%2FMovies%2Faudio-mode-video-audit.mp4&title=pane-controls-audit&mime=video/mp4&id=&audio=1"
    Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "'$route'", "com.noneplayer.app") | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    Start-Sleep -Seconds 2
    $pane = Snapshot "initial"
    foreach ($label in @("正在播放", "播放进度", "播放列表", "循环模式", "上一首", "下一首", "切回视频")) {
        if ($pane.OuterXml -notmatch [regex]::Escape($label)) { throw "Pane missing: $label" }
    }
    $favoriteLabel = if ($pane.OuterXml -match "已收藏") { "已收藏" } else { "收藏" }
    Tap $pane $favoriteLabel
    $favorite = Snapshot "favorite"
    $opposite = if ($favoriteLabel -eq "收藏") { "已收藏" } else { "收藏" }
    if ($favorite.OuterXml -notmatch [regex]::Escape($opposite)) { throw "Pane favorite did not update" }
    Tap $favorite $opposite

    $pane = Snapshot "before-speed"
    $speedLabel = ($pane.SelectNodes("//node") | Where-Object { $_.text -match "^倍速 " } | Select-Object -First 1).text
    Tap $pane $speedLabel
    $speed = Snapshot "speed"
    foreach ($label in @("0.25x", "0.50x", "1.00x", "1.50x", "2.00x", "3.00x")) {
        if ($speed.OuterXml -notmatch [regex]::Escape($label)) { throw "Speed preset missing: $label" }
    }
    Tap $speed "1.50x"
    $pane = Snapshot "speed-selected"
    if ($pane.OuterXml -notmatch "倍速 1.5x") { throw "Pane speed selection did not update" }
    if ((Invoke-Adb @("shell", "dumpsys", "media_session")) -notmatch "speed=1.5") { throw "Playback speed did not change" }
    Tap $pane "倍速 1.5x"
    Tap (Snapshot "speed-restore") "1.00x"

    Tap (Snapshot "before-queue") "播放列表"
    $queue = Snapshot "queue"
    if ($queue.OuterXml -notmatch "选集（1）") { throw "Pane queue did not open" }
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    Start-Sleep -Seconds 1
    $pane = Snapshot "queue-closed"
    if ($pane.OuterXml -match "选集（1）") { throw "Back did not close the queue" }
    if ($pane.OuterXml -notmatch "正在播放") { throw "Back left the pane instead of closing the queue" }

    "PASS: $Device $prefix layout, favorite toggle, speed presets and engine state, queue, modal Back"
} finally {
    if ($sizeChanged) { Invoke-Adb @("shell", "wm", "size", "reset") | Out-Null }
}
