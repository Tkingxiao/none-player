param(
    [ValidateSet("Snapshot", "Tap", "Back", "Swipe")]
    [string]$Action = "Snapshot",
    [string]$Name = "current",
    [string]$Text,
    [int]$Index = 0,
    [int[]]$Coordinates,
    [switch]$ImageOnly,
    [string]$Device = "emulator-5556",
    [switch]$AllowPhysicalDevice,
    [ValidatePattern("^[a-zA-Z0-9-]+$")]
    [string]$EvidenceSet = "buglist-2026-10-12"
)

$ErrorActionPreference = "Stop"
if ($Device -notmatch "^emulator-\d+$" -and !$AllowPhysicalDevice) {
    throw "Physical devices require explicit -AllowPhysicalDevice."
}
$outputDirectory = Join-Path $PSScriptRoot "screenshots/$EvidenceSet/$Device"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

function Get-DeviceHierarchy {
    $dump = & adb -s $Device shell uiautomator dump /sdcard/noneplayer-buglist.xml 2>&1
    if (($dump -join "`n") -notmatch "dumped to") { throw "Hierarchy dump failed: $dump" }
    [xml]$hierarchy = (& adb -s $Device shell cat /sdcard/noneplayer-buglist.xml) -join "`n"
    return $hierarchy
}

switch ($Action) {
    "Snapshot" {
        & adb -s $Device shell screencap -p /sdcard/noneplayer-buglist.png
        & adb -s $Device pull /sdcard/noneplayer-buglist.png (Join-Path $outputDirectory "$Name.png") | Out-Null
        if ($ImageOnly) { break }
        $hierarchy = Get-DeviceHierarchy
        $hierarchy.Save((Join-Path $outputDirectory "$Name.xml"))
        $hierarchy.SelectNodes("//node[@text!='' or @content-desc!='']") |
            ForEach-Object { "{0} | {1} | {2} | checked={3}" -f $_.text, $_."content-desc", $_.bounds, $_.checked }
    }
    "Tap" {
        if ($Coordinates) {
            & adb -s $Device shell input tap $Coordinates[0] $Coordinates[1]
        } else {
            $hierarchy = Get-DeviceHierarchy
            $matches = @($hierarchy.SelectNodes("//node") | Where-Object {
                $_.text -eq $Text -or $_."content-desc" -eq $Text
            })
            if ($matches.Count -le $Index) { throw "No matching UI node: $Text ($Index)" }
            $bounds = [regex]::Match($matches[$Index].bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
            $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
            $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
            & adb -s $Device shell input tap $x $y
            "Tapped $Text at $x,$y"
        }
    }
    "Back" { & adb -s $Device shell input keyevent KEYCODE_BACK }
    "Swipe" {
        if ($Coordinates.Count -ne 4) { throw "Swipe requires x1,y1,x2,y2" }
        & adb -s $Device shell input swipe $Coordinates[0] $Coordinates[1] $Coordinates[2] $Coordinates[3] 700
    }
}
