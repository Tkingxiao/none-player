# 仪器测试素材清单（REV-15）

> 目的：把 `androidTest` 依赖的媒体素材列成可复核清单，区分 **compiled / executed / blocked**，避免拿 JVM 单测全绿推断字幕/native 集成全绿。
> 约束：不下载新依赖或素材；小型合成夹具可入库，私有大媒体只留匿名清单；素材不齐时明确标 blocked。
> 校验脚本：`docs/verify-instrumentation-assets.ps1`（默认把缺失记为 BLOCKED，加 `-RequireAll` 才在缺失时退出非 0）。

## 1. 源集与用例（7 类 / 23 项）

| 模块 | 测试类 | 项数 | 覆盖 |
| --- | --- | --- | --- |
| `core/media-android` | `Wmv2PictureTest` | 1 | WMV2 解封装/extradata/彩条抽帧 |
| `core/media-android` | `FfmpegNativeLoadTest` | 1 | 5 个 FFmpeg `.so` 加载 |
| `core/media-android` | `FormatProbeDecisionTest` | 3 | MJPEG / WMV2 / Xvid 探测判定 |
| `core/media-android` | `Mpeg4ProfileProbeTest` | 3 | ASP/SP 解析与探测 |
| `core/media-android` | `QpelCodecProbeTest` | 3 | 解码器枚举、QPel 试探、SP 基线 |
| `plugins/plugin-player-exo` | `LegacyPlaybackTest` | 8 | WMV2 / QPel / Xvid / MJPEG 出帧、H264 互切 |
| `plugins/plugin-player-exo` | `SubtitleQueueTest` | 4 | 队列身份、探测期挂字幕、裁剪不泄漏、清队列 |

测试从 `instrumentation.context.assets` 读测试 APK 自带素材，复制到 `targetContext.cacheDir` 后使用；不依赖 `/sdcard` 或 `Movies`。`SubtitleQueueTest` 的 SRT 在代码内联生成，不需要字幕文件。

## 2. 素材清单

所有路径均在模块的 `src/androidTest/assets/` 下；两个模块的公共 5 个文件字节一致（SHA-256 相同）。

| 文件 | 字节 | SHA-256 | 被引用 | 生成/来源见证 | 授权 | 状态 |
| --- | ---: | --- | --- | --- | --- | --- |
| `np-wmv2.avi` | 262,762 | `467d7d9254ce14231a8163761f57fdcf885bb7d6d2c5bf72af15e0fe8368c757` | `Wmv2PictureTest`、`FormatProbeDecisionTest`、`LegacyPlaybackTest` | `Wmv2PictureTest.kt:26-27` 与 `docs/decoding/extension-decoder-decision.md:86` 称其为 ffmpeg `testsrc` 彩条；无命令/版本 | 无授权文件 | **BLOCKED**（补配方+SHA+授权后可作可跟踪合成夹具） |
| `np-mjpeg.avi` | 968,652 | `808555559e6ef5ce7daf94eddc2d0ad99139042d64e8e867a57b4ac300d5504a` | `FormatProbeDecisionTest`、`LegacyPlaybackTest` | 无生成配方/历史中的来源说明 | 无 | **BLOCKED** |
| `xvid-clip.avi` | 425,782 | `5afb47beef3733f8a81f477f9d4d1fad7628422199bd9e669898c0061c96332e` | `FormatProbeDecisionTest`、`Mpeg4ProfileProbeTest`、`QpelCodecProbeTest`、`LegacyPlaybackTest` | 无生成配方；`2f1c89e` 移出版本控制 | 无 | **BLOCKED** |
| `xvid-qpel.avi` | 427,318 | `4347561301efdb950ac2f3b53867fab3d21a4c9545e063a56f9657062d93bdfd` | `Mpeg4ProfileProbeTest`、`QpelCodecProbeTest`、`LegacyPlaybackTest` | 无生成配方；设备侧已见运行时解码失败（`docs/审查文档.md:469`） | 无 | **BLOCKED** |
| `np-h264.mp4` | 618,989 | `1bfca7774df0c2949e99ac2b60f3a15659e5d99861672db58931522554528231` | `LegacyPlaybackTest` | 无 git 历史/来源说明 | 无 | **BLOCKED** |
| `subtitle-queue.mp4` | 449,594 | `cd6d61959f336da8aaa3da0c94484edf32918b486f314e7c3b802f72c2a26e8d` | `SubtitleQueueTest` | 无生成配方/来源说明；仅存在于 `plugin-player-exo` | 无 | **BLOCKED** |

- 六个文件都被 `.gitignore` 的 `**/src/androidTest/assets/*.mp4|avi` 忽略；干净检出**一个都没有**。`core:media-android` 目录缺 `subtitle-queue.mp4`，该文件只在 `plugin-player-exo`。
- 仓库没有 LICENSE/NOTICE；来源与再分发权未核实（见任务文档 D7）。在补齐授权前不要把素材改进入库状态。
- `FfmpegNativeLoadTest` 另需设备 `/data/local/tmp/{x86_64-,}libav*.so.*` 5 个文件；其引用的 `D:\Code\Player\.audit\ffmpeg\build_and_push.sh` 当前不存在 → 该类 **BLOCKED**，与上表素材无关。

## 3. compiled（本轮实测）

离线编译两个测试 APK（不执行、不联网）：

```powershell
cd "D:\Code\Player\None Player"
./gradlew :core:media-android:assembleDebugAndroidTest `
          :plugins:plugin-player-exo:assembleDebugAndroidTest --offline
```

结果（2026-10-21）：首跑 `BUILD SUCCESSFUL in 30s`（`137 actionable tasks`）；`clean` 后复跑 19s，资产仍按 5/6 个打包、哈希一致。

| APK | 大小 | 打包的 assets |
| --- | ---: | --- |
| `core/media-android/build/outputs/apk/androidTest/debug/media-android-debug-androidTest.apk` | 46,274,777 B | 5 个（无 `subtitle-queue.mp4`） |
| `plugins/plugin-player-exo/build/outputs/apk/androidTest/debug/plugin-player-exo-debug-androidTest.apk` | 48,891,083 B | 6 个 |

已从 APK 抽出资产复核：`np-wmv2.avi` 与 `subtitle-queue.mp4` 的 SHA-256 与本地文件一致（其余同上表）。因此 **compiled = yes（仅本机素材存在时）**；干净检出仍是 blocked。

## 4. executed（本轮未执行）

- 本轮没有运行 `connectedDebugAndroidTest`：设备未唤醒/未准备，且 `FfmpegNativeLoadTest` 的 `.so` 与推送脚本缺失。
- 具备条件后的命令（按模块单独跑，避免一个类的失败拖住另一个）：
  ```powershell
  ./gradlew :core:media-android:connectedDebugAndroidTest `
            :plugins:plugin-player-exo:connectedDebugAndroidTest --offline
  ```
- 执行前先恢复素材（放到两个模块的 `src/androidTest/assets/`）并运行：
  ```powershell
  ./docs/verify-instrumentation-assets.ps1 -RequireAll
  ```
- 执行记录必须写设备 ABI/SDK、模块、类名、通过/失败数；`np-wmv2` 等旧素材在授权补齐前不得标记“可跟踪”。

## 5. 小型合成夹具策略

- 已可跟踪且不依赖媒体文件：`SubtitleQueueTest` 的内联 SRT、`core/media` 的 `AviDurationTest` 字节构造夹具。
- 可跟踪候选：`np-wmv2.avi` 的 ffmpeg `testsrc` 配方一旦补上命令、版本、输出 SHA 与授权文件，可生成一个体积更小的合成样本入库，并把大文件留在本地。
- 其余媒体没有可复核的来源/授权，仅按匿名清单保管；不要为了让 `connectedDebugAndroidTest` 变绿而复制不可再分发的素材入库。
