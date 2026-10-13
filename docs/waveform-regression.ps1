param(
    [ValidatePattern("^emulator-\d+$")]
    [string]$Device = "emulator-5556",
    [switch]$Tablet
)

$ErrorActionPreference = "Stop"
$output = Join-Path $PSScriptRoot "screenshots/audio-mode-2026-10-13/$Device"
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb.exe -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($result -join ' ')" }
    return $result -join "`n"
}

function Position {
    $session = Invoke-Adb @("shell", "dumpsys", "media_session")
    $match = [regex]::Match($session, "state=PlaybackState \{state=PAUSED\(2\), position=(\d+)")
    if (!$match.Success) { throw "Expected a paused session: $session" }
    return [long]$match.Groups[1].Value
}

function Snapshot {
    param([string]$Name)
    Invoke-Adb @("shell", "screencap", "-p", "/sdcard/np-waveform.png") | Out-Null
    Invoke-Adb @("pull", "/sdcard/np-waveform.png", (Join-Path $output "$Name.png")) | Out-Null
}

function Bounds {
    param($Node)
    $match = [regex]::Match($Node.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    return @($match.Groups[1..4] | ForEach-Object { [int]$_.Value })
}

$route = "noneplayer://player?uri=file%3A%2F%2F%2Fsdcard%2FMovies%2Faudio-mode-video-audit.mp4&title=waveform-audit&mime=video/mp4&id=&audio=1"
Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "'$route'", "com.noneplayer.app") | Out-Null
Start-Sleep -Seconds 3
if ($Tablet) {
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    Start-Sleep -Seconds 2
}
Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/np-waveform.xml") | Out-Null
[xml]$xml = Invoke-Adb @("shell", "cat", "/sdcard/np-waveform.xml")
$xml.Save((Join-Path $output "waveform.xml"))
$pause = $xml.SelectNodes("//node") | Where-Object { $_."content-desc" -eq "暂停" -and $_.clickable -eq "true" } | Select-Object -First 1
if ($pause) {
    $b = Bounds $pause
    Invoke-Adb @("shell", "input", "tap", "$([int](($b[0] + $b[2]) / 2))", "$([int](($b[1] + $b[3]) / 2))") | Out-Null
    Start-Sleep -Seconds 1
}
$bar = $xml.SelectNodes("//node") | Where-Object { $_."content-desc" -eq "播放进度" } | Select-Object -First 1
if (!$bar) { throw "Waveform seek control missing" }
$b = Bounds $bar
$width = $b[2] - $b[0]
$x = [int]($b[0] + $width * 0.85)
$y = [int](($b[1] + $b[3]) / 2)
$endX = [int]($x - $width * 0.35)
$before = Position
while ($before -gt 400000) {
    Invoke-Adb @("shell", "input", "swipe", "$endX", "$y", "$x", "$y", "1000") | Out-Null
    Start-Sleep -Seconds 1
    $next = Position
    if ($next -ge $before - 1000) { throw "Could not establish a scrub test starting position" }
    $before = $next
}
Snapshot "waveform-before"

# Touching away from the reference must not seek or move the reference.
Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
if ([math]::Abs((Position) - $before) -gt 500) { throw "Absolute touch still seeks" }

# Hold a real drag open: only the waveform preview is allowed to move.
Invoke-Adb @("shell", "input", "motionevent", "DOWN", "$x", "$y") | Out-Null
try {
    foreach ($step in 1..5) {
        $stepX = [int]($x + ($endX - $x) * $step / 5)
        Invoke-Adb @("shell", "input", "motionevent", "MOVE", "$stepX", "$y") | Out-Null
    }
    $held = Position
    Snapshot "waveform-held"
    if ([math]::Abs($held - $before) -gt 500) { throw "Dragging submitted a seek before release" }
} finally {
    Invoke-Adb @("shell", "input", "motionevent", "UP", "$endX", "$y") | Out-Null
}
Start-Sleep -Seconds 1
$after = Position
Snapshot "waveform-released"
$advance = $after - $before
if ($advance -lt 70000 -or $advance -gt 110000) {
    throw "Left drag should advance about 100s on this 600s timeline, actual=$advance ms"
}

# Reverse direction without changing the fixed reference.
Invoke-Adb @("shell", "input", "swipe", "$endX", "$y", "$x", "$y", "1000") | Out-Null
Start-Sleep -Seconds 1
$reversed = Position
Snapshot "waveform-reversed"
if ($reversed -ge $after - 70000) { throw "Right drag did not rewind" }
if ([math]::Abs($reversed - $before) -gt 25000) { throw "Opposite drags did not approximately cancel" }

# A cancelled gesture restores playback position without committing the preview.
Invoke-Adb @("shell", "input", "motionevent", "DOWN", "$x", "$y") | Out-Null
try {
    Invoke-Adb @("shell", "input", "motionevent", "MOVE", "$([int]($x - $width * 0.15))", "$y") | Out-Null
    Invoke-Adb @("shell", "input", "motionevent", "MOVE", "$endX", "$y") | Out-Null
} finally {
    Invoke-Adb @("shell", "input", "motionevent", "CANCEL", "$endX", "$y") | Out-Null
}
Start-Sleep -Seconds 1
if ([math]::Abs((Position) - $reversed) -gt 500) { throw "Cancelled drag committed a seek" }
Snapshot "waveform-cancelled"

# Verify the actual reference pixels, not just the accessibility node.
Add-Type -AssemblyName System.Drawing
$images = @("waveform-before", "waveform-held", "waveform-released") | ForEach-Object {
    [System.Drawing.Bitmap]::new((Join-Path $output "$_.png"))
}
try {
    $referenceX = [int](($b[0] + $b[2]) / 2)
    $reference = $images[0].GetPixel($referenceX, $b[1] + 5)
    foreach ($image in $images) {
        foreach ($row in ($b[1] + 5)..($b[3] - 6)) {
            $pixel = $image.GetPixel($referenceX, $row)
            if ([math]::Abs($pixel.R - $reference.R) -gt 12 -or
                [math]::Abs($pixel.G - $reference.G) -gt 12 -or
                [math]::Abs($pixel.B - $reference.B) -gt 12) {
                throw "Center reference moved or disappeared"
            }
        }
    }
    $changed = 0
    for ($col = $b[0]; $col -lt $b[2]; $col += 2) {
        for ($row = $b[1] + 5; $row -lt $b[3] - 5; $row += 2) {
            if ($images[0].GetPixel($col, $row).ToArgb() -ne $images[1].GetPixel($col, $row).ToArgb()) { $changed++ }
        }
    }
    if ($changed -lt 250) { throw "The waveform did not visibly translate" }
} finally {
    foreach ($image in $images) { $image.Dispose() }
}

"PASS: $Device fixed reference pixels, moving waveform, tap stable, held preview only, release seek, reverse and cancel; positions $before -> $held -> $after -> $reversed"
