param(
    [switch]$RequireAll
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$assets = @(
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-wmv2.avi"; Sha = "467d7d9254ce14231a8163761f57fdcf885bb7d6d2c5bf72af15e0fe8368c757"; Bytes = 262762 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-mjpeg.avi"; Sha = "808555559e6ef5ce7daf94eddc2d0ad99139042d64e8e867a57b4ac300d5504a"; Bytes = 968652 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/xvid-clip.avi"; Sha = "5afb47beef3733f8a81f477f9d4d1fad7628422199bd9e669898c0061c96332e"; Bytes = 425782 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/xvid-qpel.avi"; Sha = "4347561301efdb950ac2f3b53867fab3d21a4c9545e063a56f9657062d93bdfd"; Bytes = 427318 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-h264.mp4"; Sha = "1bfca7774df0c2949e99ac2b60f3a15659e5d99861672db58931522554528231"; Bytes = 618989 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-wmv2.avi"; Sha = "467d7d9254ce14231a8163761f57fdcf885bb7d6d2c5bf72af15e0fe8368c757"; Bytes = 262762 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-mjpeg.avi"; Sha = "808555559e6ef5ce7daf94eddc2d0ad99139042d64e8e867a57b4ac300d5504a"; Bytes = 968652 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/xvid-clip.avi"; Sha = "5afb47beef3733f8a81f477f9d4d1fad7628422199bd9e669898c0061c96332e"; Bytes = 425782 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/xvid-qpel.avi"; Sha = "4347561301efdb950ac2f3b53867fab3d21a4c9545e063a56f9657062d93bdfd"; Bytes = 427318 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-h264.mp4"; Sha = "1bfca7774df0c2949e99ac2b60f3a15659e5d99861672db58931522554528231"; Bytes = 618989 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/subtitle-queue.mp4"; Sha = "cd6d61959f336da8aaa3da0c94484edf32918b486f314e7c3b802f72c2a26e8d"; Bytes = 449594 }
)

$ok = 0
$missing = 0
$bad = 0

foreach ($asset in $assets) {
    $full = Join-Path $root $asset.Path
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Host "[BLOCKED] $($asset.Path)  (clean checkout: asset intentionally gitignored)"
        $missing++
        continue
    }
    $item = Get-Item -LiteralPath $full
    $hash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($item.Length -ne $asset.Bytes -or $hash -ne $asset.Sha.ToLowerInvariant()) {
        Write-Host "[FAIL] $($asset.Path)  bytes=$($item.Length) expected=$($asset.Bytes) sha=$hash expected=$($asset.Sha)"
        $bad++
    } else {
        Write-Host "[OK] $($asset.Path)"
        $ok++
    }
}

Write-Host ""
Write-Host "assets: ok=$ok blocked-missing=$missing bad=$bad"
if ($bad -gt 0) {
    exit 1
}
if ($RequireAll -and $missing -gt 0) {
    Write-Host "BLOCKED: restore the missing private assets above before running instrumentation tests."
    exit 2
}
exit 0
