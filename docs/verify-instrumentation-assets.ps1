param(
    [switch]$RequireAll
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$assets = @(
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-wmv2.avi"; Sha = "97bd108fcfad92e007b677b5bf5a2e17a523661f8cfff87f1cf1b8455c8cf9f1"; Bytes = 32458 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-mjpeg.avi"; Sha = "8612e5d8fc58922d33d8c59c7bfcde01e99685c79f6ca97a0efa7d9a0246df2b"; Bytes = 1228608 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/xvid-clip.avi"; Sha = "5462d72253352c486edf61ce1a9ac93e9f7db9fa8955aa9c94889a225d309ede"; Bytes = 195980 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/xvid-qpel.avi"; Sha = "12c87b0f70dde722455bc6631740e0827d0f494b96c877d436a90918cb6676b4"; Bytes = 195926 },
    @{ Path = "None Player/core/media-android/src/androidTest/assets/np-h264.mp4"; Sha = "e2137690a24c68d8ace9e9eec0b9b1ef426e855de248816c6b34f512c5583058"; Bytes = 616217 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-wmv2.avi"; Sha = "97bd108fcfad92e007b677b5bf5a2e17a523661f8cfff87f1cf1b8455c8cf9f1"; Bytes = 32458 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-mjpeg.avi"; Sha = "8612e5d8fc58922d33d8c59c7bfcde01e99685c79f6ca97a0efa7d9a0246df2b"; Bytes = 1228608 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/xvid-clip.avi"; Sha = "5462d72253352c486edf61ce1a9ac93e9f7db9fa8955aa9c94889a225d309ede"; Bytes = 195980 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/xvid-qpel.avi"; Sha = "12c87b0f70dde722455bc6631740e0827d0f494b96c877d436a90918cb6676b4"; Bytes = 195926 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/np-h264.mp4"; Sha = "e2137690a24c68d8ace9e9eec0b9b1ef426e855de248816c6b34f512c5583058"; Bytes = 616217 },
    @{ Path = "None Player/plugins/plugin-player-exo/src/androidTest/assets/subtitle-queue.mp4"; Sha = "bc3f36850de4297207bb4ba478f566c4e154d117ae6683d67569717cd02488c2"; Bytes = 1195854 }
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
    Write-Host "BLOCKED: run None Player/tools/gen-androidtest-assets.py, then re-check."
    exit 2
}
exit 0
