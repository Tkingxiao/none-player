param(
    [string]$Name = "shot",
    [string]$Size = "",
    [switch]$NoInstall
)
# UI 保真度验证脚本：装包 → 起 Activity → 截图到 docs/screenshots/ui-rework/
#
# 用 `-Size 390x844` 之类切换显示分辨率（模拟设计稿的响应式矩阵），
# 不传则用设备默认。切换后要 `wm size reset` 才能恢复。
$ErrorActionPreference = "Stop"
$root = "D:\Code\Player\None Player"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$out = Join-Path $root "docs\screenshots\ui-rework"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"

if ($Size -ne "") {
    & $adb shell wm size $Size | Out-Null
    & $adb shell wm density reset | Out-Null
    Start-Sleep -Seconds 2
}

if (-not $NoInstall) {
    if (-not (Test-Path $apk)) { throw "APK 不存在：$apk（先跑 :app:assembleDebug）" }
    & $adb install -r $apk | Out-Null
}

& $adb shell am force-stop com.noneplayer.app | Out-Null
Start-Sleep -Milliseconds 400
& $adb shell am start -n com.noneplayer.app/.MainActivity | Out-Null
Start-Sleep -Seconds 3

$file = Join-Path $out "$Name.png"
& $adb exec-out screencap -p > $file
Write-Output $file
