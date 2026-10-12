param(
    [string]$Device = "emulator-5556",
    [switch]$AllowPhysicalDevice,
    [string]$MediaUri = "file:///sdcard/Movies/long16x9.mp4",
    [ValidatePattern("^[a-zA-Z0-9-]+$")]
    [string]$EvidenceSet = "b4-2026-10-12"
)

$ErrorActionPreference = "Stop"
if ($Device -notmatch "^emulator-\d+$") {
    if (!$AllowPhysicalDevice) { throw "Physical devices require -AllowPhysicalDevice." }
    if ($MediaUri -notlike "file:///sdcard/Movies/NonePlayerAuditB3/*") {
        throw "Physical tests only use the dedicated NonePlayerAuditB3 synthetic media folder."
    }
}
$output = Join-Path $PSScriptRoot "screenshots/$EvidenceSet/$Device"
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($result -join ' ')" }
    return ($result -join "`n")
}

function Get-Hierarchy {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/np-menu-regression.xml") | Out-Null
    [xml]$xml = Invoke-Adb @("shell", "cat", "/sdcard/np-menu-regression.xml")
    return $xml
}

function Get-NodeCenter {
    param($Node)
    $bounds = [regex]::Match($Node.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (!$bounds.Success) { throw "Invalid bounds: $($Node.bounds)" }
    return @(
        [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2),
        [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    )
}

function Tap-Label {
    param([string]$Label)
    $xml = Get-Hierarchy
    $nodes = @($xml.SelectNodes("//node") | Where-Object {
        $_.text -eq $Label -or $_."content-desc" -eq $Label
    })
    if ($nodes.Count -eq 0) { throw "No UI node: $Label" }
    $point = Get-NodeCenter $nodes[0]
    Invoke-Adb @("shell", "input", "tap", "$($point[0])", "$($point[1])") | Out-Null
}

function Save-Evidence {
    param([string]$Name)
    # The physical inline surface has the user's library behind it; retain popup semantics only.
    if ($Device -match "^emulator-\d+$" -or $Name -ne "regression-inline-offset") {
        Invoke-Adb @("shell", "screencap", "-p", "/sdcard/np-menu-regression.png") | Out-Null
        Invoke-Adb @("pull", "/sdcard/np-menu-regression.png", (Join-Path $output "$Name.png")) | Out-Null
    }
    $xml = Get-Hierarchy
    $xml.Save((Join-Path $output "$Name.xml"))
    return $xml
}

function Assert-Text {
    param($Xml, [string]$Text)
    if (@($Xml.SelectNodes("//node") | Where-Object { $_.text -eq $Text }).Count -eq 0) {
        throw "Expected UI text: $Text"
    }
}

function Set-Playback {
    param([bool]$Playing)
    $key = if ($Playing) { "KEYCODE_MEDIA_PLAY" } else { "KEYCODE_MEDIA_PAUSE" }
    $expected = if ($Playing) { "state=PLAYING\(3\)" } else { "state=PAUSED\(2\)" }
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        Invoke-Adb @("shell", "input", "keyevent", $key) | Out-Null
        Start-Sleep -Milliseconds 600
        $state = Invoke-Adb @("shell", "dumpsys", "media_session")
        if ($state -match $expected -and $state -match [regex]::Escape(($MediaUri -split "/")[-1])) {
            return
        }
    }
    throw "The requested sample did not reach the expected playback state."
}

$sampleTitle = [uri]::EscapeDataString(($MediaUri -split "/")[-1])
$route = "noneplayer://player?uri=$([uri]::EscapeDataString($MediaUri))&title=$sampleTitle&mime=video%2Fmp4&id=menu-audit-b3-v2&from=start&fs=1"
Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
    "-d", "'$route'", "com.noneplayer.app") | Out-Null
Start-Sleep -Seconds 6
Set-Playback $false
$xml = Get-Hierarchy
if (@($xml.SelectNodes("//node") | Where-Object { $_."content-desc" -eq "播放选项" }).Count -eq 0) {
    $point = Get-NodeCenter ($xml.SelectSingleNode("/hierarchy/node"))
    Invoke-Adb @("shell", "input", "tap", "$($point[0] / 2)", "$($point[1] / 2)") | Out-Null
}
Tap-Label "播放选项"
$main = Save-Evidence "regression-main"
if ($main.OuterXml -match 'text="播放倍速"|text="字幕"|text="选集"|text="仅听音频"') {
    throw "The main menu still duplicates direct controls."
}
Tap-Label "关闭菜单"
Start-Sleep -Milliseconds 350
Tap-Label "倍速播放"
$speed = Save-Evidence "regression-speed"
foreach ($label in @("0.25x", "0.50x", "1.00x", "1.50x", "2.00x", "3.00x")) {
    Assert-Text $speed $label
}
if ($speed.OuterXml -match 'text="显示字幕"|text="循环模式"') {
    throw "Outgoing page content survived the speed transition."
}
Tap-Label "返回"
Start-Sleep -Milliseconds 350
Tap-Label "字幕"
Tap-Label "+1.0s"
$subtitle = Save-Evidence "regression-subtitle"
Assert-Text $subtitle "+1000ms"
if ($subtitle.OuterXml -match 'text="-0.05"') { throw "Speed content survived the subtitle transition." }
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Milliseconds 300
Tap-Label "返回浏览"
Start-Sleep -Seconds 1
Tap-Label "字幕"
Start-Sleep -Milliseconds 700
$inline = Save-Evidence "regression-inline-offset"
Assert-Text $inline "+1000ms"
Tap-Label "0.0"
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Milliseconds 300
Tap-Label "全屏播放"
$fullscreen = Get-Hierarchy
$options = @($fullscreen.SelectNodes("//node") | Where-Object { $_."content-desc" -eq "播放选项" })
if ($options.Count -eq 0) { throw "Fullscreen controls were not visible." }
$optionPoint = Get-NodeCenter $options[0]
Set-Playback $true
Invoke-Adb @("shell", "input", "tap", "$($optionPoint[0])", "$($optionPoint[1])") | Out-Null
Tap-Label "关闭菜单"
Start-Sleep -Seconds 1
$closed = Save-Evidence "regression-dismiss-playing"
if ($closed.OuterXml -match 'content-desc="关闭菜单"|text="循环模式"|text="显示字幕"') {
    throw "Menu reopened during playback recomposition."
}

# Use the actual window dimensions; reaching Launcher is required before calling the gesture a pass.
$xml = Get-Hierarchy
$bounds = [regex]::Match($xml.SelectSingleNode("/hierarchy/node").bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
$width = [int]$bounds.Groups[3].Value
$height = [int]$bounds.Groups[4].Value
$display = Invoke-Adb @("shell", "dumpsys", "window", "displays")
$displayBounds = [regex]::Match($display, "DisplayFrames w=(\d+) h=(\d+)")
if ($displayBounds.Success) {
    $width = [int]$displayBounds.Groups[1].Value
    $height = [int]$displayBounds.Groups[2].Value
}
$gestureReachedLauncher = $false
for ($attempt = 0; $attempt -lt 2; $attempt++) {
    Invoke-Adb @("shell", "input", "swipe", "$([int]($width / 2))", "$($height - 2)",
        "$([int]($width / 2))", "$([int]($height * 0.4))", "200") | Out-Null
    Start-Sleep -Seconds 1
    $activity = Invoke-Adb @("shell", "dumpsys", "activity", "activities")
    $gestureReachedLauncher = $activity -match "ResumedActivity:[^\r\n]*(com\.miui\.home|nexuslauncher)"
    if ($gestureReachedLauncher) { break }
}
if (!$gestureReachedLauncher) {
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
    Start-Sleep -Seconds 1
    $activity = Invoke-Adb @("shell", "dumpsys", "activity", "activities")
}
if ($activity -notmatch "ResumedActivity:[^\r\n]*(com\.miui\.home|nexuslauncher)") {
    throw "Home did not reach Launcher."
}
if ($activity -match "Task\{[^\r\n]*com\.noneplayer\.app[^\r\n]*mode=(pinned|freeform)") {
    throw "Home unexpectedly entered a small window."
}
$session = Invoke-Adb @("shell", "dumpsys", "media_session")
if ($session -notmatch "state=PLAYING\(3\)" -or
    $session -notmatch [regex]::Escape(($MediaUri -split "/")[-1])) {
    throw "Expected the synthetic sample to keep playing in background."
}
($activity -split "`n" | Where-Object { $_ -match "ResumedActivity:|Task\{.*com\.noneplayer\.app" }) |
    Set-Content -LiteralPath (Join-Path $output "home-task-evidence.txt") -Encoding utf8
($session -split "`n" | Where-Object { $_ -match "state=PlaybackState|description=$([regex]::Escape(($MediaUri -split '/')[-1]))" }) |
    Set-Content -LiteralPath (Join-Path $output "background-sample-evidence.txt") -Encoding utf8
[pscustomobject]@{
    Device = $Device
    SpeedPage = "PASS"
    SubtitlePage = "PASS"
    OffsetAcrossSurfaces = "PASS"
    DismissDuringPlayback = "PASS"
    HomeWithoutSmallWindow = "PASS"
    GestureReachedLauncher = $gestureReachedLauncher
    BackgroundSamplePlayback = "PASS"
    Timestamp = (Get-Date).ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "menu-result.json") -Encoding utf8
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE") | Out-Null
Write-Output "Menu, offset, dismiss and background regression PASS; gesture reached Launcher: $gestureReachedLauncher"
