param(
    [string]$Device = "emulator-5556",
    [string]$MediaUri = "file:///sdcard/Movies/long16x9.mp4"
)

$ErrorActionPreference = "Stop"
if ($Device -notmatch "^emulator-\d+$") { throw "Only emulator devices are permitted." }
$output = Join-Path $PSScriptRoot "screenshots/regression-2026-10-12/$Device"
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($result -join ' ')" }
    return ($result -join "`n")
}

function Open-Media {
    param([string]$Uri)
    $route = "noneplayer://player?uri=$([uri]::EscapeDataString($Uri))"
    Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
        "-d", "'$route'", "com.noneplayer.app") | Out-Null
    Start-Sleep -Seconds 4
}

function Get-Hierarchy {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/np-regression.xml") | Out-Null
    [xml]$xml = Invoke-Adb @("shell", "cat", "/sdcard/np-regression.xml")
    return $xml
}

function Save-Evidence {
    param([string]$Name)
    Invoke-Adb @("shell", "screencap", "-p", "/sdcard/np-regression.png") | Out-Null
    Invoke-Adb @("pull", "/sdcard/np-regression.png", (Join-Path $output "$Name.png")) | Out-Null
    $xml = Get-Hierarchy
    $xml.Save((Join-Path $output "$Name.xml"))
    return $xml
}

function Assert-NoAutomaticPip {
    param([string]$Name)
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
    Start-Sleep -Seconds 2
    $activity = Invoke-Adb @("shell", "dumpsys", "activity", "activities")
    $activity | Set-Content -LiteralPath (Join-Path $output "$Name-activity.txt") -Encoding utf8
    if ($activity -match "Task\{[^\r\n]*com\.noneplayer\.app[^\r\n]*mode=pinned") {
        throw "$Name unexpectedly entered PiP"
    }
    if ($activity -notmatch "ResumedActivity:[^\r\n]*nexuslauncher") {
        throw "$Name did not reach launcher"
    }
    Save-Evidence $Name | Out-Null
    Write-Output "$Name PASS: launcher resumed without PiP"
}

Open-Media $MediaUri
Assert-NoAutomaticPip "player-home"
$session = Invoke-Adb @("shell", "dumpsys", "media_session")
$session | Set-Content -LiteralPath (Join-Path $output "background-session.txt") -Encoding utf8
if ($session -notmatch "state=PLAYING\(3\)") { throw "Expected background playback to remain active" }

Open-Media "file:///sdcard/Movies/noneplayer-missing-audit.mp4"
$failure = Save-Evidence "missing-file"
if (($failure.OuterXml -notmatch "无法|失败|错误|不支持|重试")) { throw "Missing file error was not visible" }
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Open-Media $MediaUri
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE") | Out-Null
$recovered = Save-Evidence "error-recovery"
if ($recovered.OuterXml -match "无法播放此文件|资源不可用|文件不存在|无法打开媒体|Source error") {
    throw "Old error survived the next media"
}
$session = Invoke-Adb @("shell", "dumpsys", "media_session")
$session | Set-Content -LiteralPath (Join-Path $output "recovered-session.txt") -Encoding utf8
if ($session -notmatch "state=PAUSED\(2\)|state=PLAYING\(3\)") { throw "Recovered player was not ready" }
if ($session -notmatch [regex]::Escape(($MediaUri -split "/")[-1])) {
    throw "Media session did not switch to the requested sample"
}
Write-Output "error-recovery PASS"

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Seconds 2
Save-Evidence "shell-after-player" | Out-Null
Assert-NoAutomaticPip "shell-home"

[pscustomobject]@{
    Device = $Device
    MediaUri = $MediaUri
    PlayerHome = "PASS"
    BackgroundPlayback = "PASS"
    ErrorRecovery = "PASS"
    ShellHome = "PASS"
    Timestamp = (Get-Date).ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "result.json") -Encoding utf8
