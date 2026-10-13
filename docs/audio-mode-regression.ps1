param(
    [ValidatePattern("^emulator-\d+$")]
    [string]$Device = "emulator-5556",
    [switch]$Tablet,
    [string]$VideoUri = "file:///sdcard/Movies/audio-mode-video-audit.mp4"
)

$ErrorActionPreference = "Stop"
$output = Join-Path $PSScriptRoot "screenshots/audio-mode-2026-10-13/$Device"
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($result -join ' ')" }
    return $result -join "`n"
}

function Snapshot {
    param([string]$Name)
    $dumped = $false
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $dump = Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/np-audio-mode.xml")
        if ($dump -match "dumped to") { $dumped = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$dumped) { throw "Hierarchy dump failed: $dump" }
    [xml]$xml = Invoke-Adb @("shell", "cat", "/sdcard/np-audio-mode.xml")
    $xml.Save((Join-Path $output "$Name.xml"))
    Invoke-Adb @("shell", "screencap", "-p", "/sdcard/np-audio-mode.png") | Out-Null
    Invoke-Adb @("pull", "/sdcard/np-audio-mode.png", (Join-Path $output "$Name.png")) | Out-Null
    return $xml
}

function Tap {
    param([string]$Text, [xml]$Hierarchy)
    $match = @($Hierarchy.SelectNodes("//node") | Where-Object {
        $_.text -eq $Text -or $_."content-desc" -eq $Text
    }) | Select-Object -First 1
    if (!$match) { throw "Missing control: $Text" }
    $bounds = [regex]::Match($match.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
    Start-Sleep -Seconds 1
}

function Open-Media {
    param([string]$Uri, [string]$Mime, [string]$Mode)
    $route = "noneplayer://player?uri=$([uri]::EscapeDataString($Uri))&title=audio-mode-audit&mime=$Mime&id=&audio=$Mode"
    Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
        "-d", "'$route'", "com.noneplayer.app") | Out-Null
    Start-Sleep -Seconds 4
}

function Assert-Audio {
    param([xml]$Hierarchy, [switch]$PureAudio)
    if ($Hierarchy.OuterXml -notmatch "正在播放") { throw "Audio page did not open" }
    if ($Hierarchy.OuterXml -match "均衡器|播放选项") { throw "Audio page exposed video or equalizer controls" }
    if ($PureAudio -and $Hierarchy.OuterXml -match "切回视频") { throw "Pure audio offered a video surface" }
}

function Assert-AudioPane {
    param([xml]$Hierarchy, [switch]$PureAudio)
    Assert-Audio $Hierarchy -PureAudio:$PureAudio
    foreach ($label in @("播放进度", "上一首", "下一首", "循环模式", "播放列表", "收藏", "倍速 ")) {
        if ($Hierarchy.OuterXml -notmatch [regex]::Escape($label)) {
            throw "Tablet audio pane missing control: $label"
        }
    }
}

function Assert-VideoFrame {
    param([string]$Name)
    if ($VideoUri -ne "file:///sdcard/Movies/audio-mode-video-audit.mp4") { return }
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new((Join-Path $output "$Name.png"))
    try {
        $green = 0
        foreach ($x in 0..31) {
            foreach ($y in 0..19) {
                $pixel = $bitmap.GetPixel([int](($x + 0.5) * $bitmap.Width / 32), [int](($y + 0.5) * $bitmap.Height / 20))
                if ($pixel.G -gt 100 -and $pixel.G -gt $pixel.R * 1.5 -and $pixel.G -gt $pixel.B * 1.5) { $green++ }
            }
        }
        if ($green -lt 80) { throw "Video surface is black or missing: $Name ($green green samples)" }
    } finally {
        $bitmap.Dispose()
    }
}

# Generate a dedicated tone; no user media or application data is modified.
$wav = Join-Path $output "audio-mode-audit.wav"
& node -e 'const fs=require("fs");const rate=16000,n=rate*180,b=Buffer.alloc(44+n*2);b.write("RIFF");b.writeUInt32LE(36+n*2,4);b.write("WAVEfmt ",8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*2,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write("data",36);b.writeUInt32LE(n*2,40);for(let i=0;i<n;i++)b.writeInt16LE(Math.round(1800*Math.sin(2*Math.PI*440*i/rate)),44+2*i);fs.writeFileSync(process.argv[1],b)' $wav
if ($LASTEXITCODE -ne 0) { throw "Tone generation failed" }
Invoke-Adb @("push", $wav, "/sdcard/Music/audio-mode-audit.wav") | Out-Null

Open-Media "file:///sdcard/Music/audio-mode-audit.wav" "audio/wav" "0"
$pure = Snapshot "pure-audio"
Assert-Audio $pure -PureAudio
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Seconds 2
$pureBack = Snapshot "pure-audio-back"
if ($Tablet) { Assert-AudioPane $pureBack -PureAudio }
if ($pureBack.OuterXml -notmatch "打开音频播放") { throw "Pure audio mini player was missing" }
Tap "打开音频播放" $pureBack
Assert-Audio (Snapshot "pure-audio-reopen") -PureAudio

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Open-Media $VideoUri "video/mp4" "1"
$audio = Snapshot "video-audio-page"
Assert-Audio $audio
Tap "切回视频" $audio
$video = Snapshot "in-page-video-return"
if ($video.OuterXml -match "正在播放|仅听音频模式") { throw "Return-to-video acted like Back" }
Assert-VideoFrame "in-page-video-return"

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Open-Media $VideoUri "video/mp4" "1"
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Seconds 2
$back = Snapshot "audio-back"
if ($back.OuterXml -notmatch "当前为仅听音频") { throw "Back cleared audio mode or lost the mini player" }
if ($back.OuterXml -match "播放选项") { throw "Back restored the video surface during audio mode" }
if ($Tablet) { Assert-AudioPane $back }

# Body tap opens the full audio page without changing mode.
$toggleNode = $back.SelectNodes("//node") | Where-Object { $_."content-desc" -match "当前为仅听音频" } | Select-Object -First 1
if (!$toggleNode) { throw "Mini player audio toggle missing" }
$row = $toggleNode.ParentNode.ParentNode
while ($row -and $row.clickable -ne "true") { $row = $row.ParentNode }
if (!$row) { throw "Mini player body missing" }
$bounds = [regex]::Match($row.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
$bodyY = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
$bodyX = [int]$bounds.Groups[1].Value + 80
Invoke-Adb @("shell", "input", "tap", "$bodyX", "$bodyY") | Out-Null
Start-Sleep -Seconds 2
Assert-Audio (Snapshot "mini-body-audio")
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Seconds 2
Tap "当前为仅听音频 · 切回视频画面" (Snapshot "before-headphone")
$restored = Snapshot "headphone-video-return"
if ($restored.OuterXml -match "当前为仅听音频|仅听音频模式|正在播放") { throw "Headphone toggle reverted to audio mode" }
Assert-VideoFrame "headphone-video-return"
if ($Tablet) {
    if ($restored.OuterXml -notmatch "全屏播放") { throw "Tablet failed to restore inline video" }
    Tap "全屏播放" $restored
    $full = Snapshot "pane-fullscreen"
    Assert-VideoFrame "pane-fullscreen"
    # System Back also exercises the return animation after controls auto-hide.
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    Start-Sleep -Seconds 2
    $returned = Snapshot "fullscreen-pane-return"
    if ($returned.OuterXml -notmatch "全屏播放") { throw "Fullscreen failed to restore the pane" }
    Assert-VideoFrame "fullscreen-pane-return"
    Tap "暂停" $returned
    $paused = Snapshot "paused-pane"
    Assert-VideoFrame "paused-pane"
    Tap "全屏播放" $paused
    $pausedFull = Snapshot "paused-fullscreen"
    Assert-VideoFrame "paused-fullscreen"
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    Start-Sleep -Seconds 2
    $pausedReturned = Snapshot "paused-pane-return"
    Assert-VideoFrame "paused-pane-return"
    if ((Invoke-Adb @("shell", "dumpsys", "media_session")) -notmatch "state=PAUSED\(2\)") {
        throw "Surface handoff resumed paused playback"
    }
}
$session = Invoke-Adb @("shell", "dumpsys", "media_session")
$session | Set-Content -LiteralPath (Join-Path $output "session.txt") -Encoding utf8
if ($session -notmatch "state=PLAYING\(3\)|state=PAUSED\(2\)") { throw "Playback session was lost" }
"PASS: $Device pure audio, Back, full audio entry, video return and mode stability"
