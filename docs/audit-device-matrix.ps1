param(
    [string]$Device = "emulator-5556",
    [string]$Screen = "folders"
)

$ErrorActionPreference = "Stop"
if ($Device -notmatch "^emulator-\d+$") { throw "Only emulator devices are permitted for this audit." }
$outputDirectory = Join-Path $PSScriptRoot "screenshots/audit-2026-10-12"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $result" }
    return $result
}

$originalSize = (Invoke-Adb -Arguments @("shell", "wm", "size")) -join "`n"
$originalDensity = (Invoke-Adb -Arguments @("shell", "wm", "density")) -join "`n"
$originalRotation = (Invoke-Adb -Arguments @("shell", "settings", "get", "system", "user_rotation")) -join ""
$originalAutoRotation = (Invoke-Adb -Arguments @("shell", "settings", "get", "system", "accelerometer_rotation")) -join ""
$originalFontScale = (Invoke-Adb -Arguments @("shell", "settings", "get", "system", "font_scale")) -join ""
$profiles = @(
    @{ Name = "phone-portrait"; Size = "1280x2856"; Density = "480"; Font = "1.0" },
    @{ Name = "phone-landscape"; Size = "2856x1280"; Density = "480"; Font = "1.0" },
    @{ Name = "tablet-landscape"; Size = "2560x1600"; Density = "320"; Font = "1.0" },
    @{ Name = "tablet-portrait"; Size = "1600x2560"; Density = "320"; Font = "1.0" },
    @{ Name = "fold-unfolded-size"; Size = "1400x1680"; Density = "320"; Font = "1.0" },
    @{ Name = "narrow-window-large-font"; Size = "960x1800"; Density = "480"; Font = "1.3" }
)
$results = @()
try {
    Invoke-Adb -Arguments @("shell", "settings", "put", "system", "accelerometer_rotation", "0") | Out-Null
    Invoke-Adb -Arguments @("shell", "settings", "put", "system", "user_rotation", "0") | Out-Null
    foreach ($profile in $profiles) {
        Invoke-Adb -Arguments @("shell", "wm", "size", $profile.Size) | Out-Null
        Invoke-Adb -Arguments @("shell", "wm", "density", $profile.Density) | Out-Null
        Invoke-Adb -Arguments @("shell", "settings", "put", "system", "font_scale", $profile.Font) | Out-Null
        Start-Sleep -Seconds 3
        $name = "$Screen-$($profile.Name)"
        Invoke-Adb -Arguments @("shell", "screencap", "-p", "/sdcard/noneplayer-matrix.png") | Out-Null
        Invoke-Adb -Arguments @("pull", "/sdcard/noneplayer-matrix.png", (Join-Path $outputDirectory "$name.png")) | Out-Null
        $dump = (Invoke-Adb -Arguments @("shell", "uiautomator", "dump", "/sdcard/noneplayer-matrix.xml")) -join "`n"
        if ($dump -notmatch "dumped to") { throw "Hierarchy dump failed: $dump" }
        [xml]$xml = (Invoke-Adb -Arguments @("shell", "cat", "/sdcard/noneplayer-matrix.xml")) -join "`n"
        $xml.Save((Join-Path $outputDirectory "$name.xml"))
        if ($Screen -eq "folders" -and
            $xml.SelectNodes("//node[@text='扫描目录']").Count -eq 0) {
            throw "$name is not the folders screen"
        }
        $activity = (Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "activities")) -join "`n"
        $activity | Set-Content -LiteralPath (Join-Path $outputDirectory "$name-activity.txt") -Encoding utf8
        if ($activity -notmatch "ResumedActivity:[^\r\n]*com\.noneplayer\.app" -or
            $activity -match "Task\{[^\r\n]*com\.noneplayer\.app[^\r\n]*mode=pinned") {
            throw "$name is not a resumed, non-PiP application window"
        }
        $root = $xml.SelectSingleNode("/hierarchy/node")
        $bounds = [regex]::Match($root.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
        $actualWidth = [int]$bounds.Groups[3].Value - [int]$bounds.Groups[1].Value
        $actualHeight = [int]$bounds.Groups[4].Value - [int]$bounds.Groups[2].Value
        $results += [pscustomobject]@{
            Name = $name
            Size = $profile.Size
            Density = $profile.Density
            FontScale = $profile.Font
            ActualBounds = $root.bounds
            ActualOrientation = $(if ($actualWidth -gt $actualHeight) { "landscape" } else { "portrait" })
            Rotation = $xml.hierarchy.rotation
            MatchesRequestedBounds = "$($actualWidth)x$actualHeight" -eq $profile.Size
            TextNodes = $xml.SelectNodes("//node[@text!='']").Count
            Evidence = "$name.png"
        }
        Write-Output "$name captured"
    }
} finally {
    $sizeMatch = [regex]::Match($originalSize, "Override size:\s*(\d+x\d+)")
    $densityMatch = [regex]::Match($originalDensity, "Override density:\s*(\d+)")
    Invoke-Adb -Arguments @("shell", "wm", "size", $(if ($sizeMatch.Success) { $sizeMatch.Groups[1].Value } else { "reset" })) | Out-Null
    Invoke-Adb -Arguments @("shell", "wm", "density", $(if ($densityMatch.Success) { $densityMatch.Groups[1].Value } else { "reset" })) | Out-Null
    foreach ($pair in @(
        @("user_rotation", $originalRotation),
        @("accelerometer_rotation", $originalAutoRotation),
        @("font_scale", $originalFontScale)
    )) {
        if ($pair[1] -eq "null") {
            Invoke-Adb -Arguments @("shell", "settings", "delete", "system", $pair[0]) | Out-Null
        } else {
            Invoke-Adb -Arguments @("shell", "settings", "put", "system", $pair[0], $pair[1]) | Out-Null
        }
    }
}
$results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory "$Screen-matrix.json") -Encoding utf8
