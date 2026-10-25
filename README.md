# None Player

Android 本地音视频播放器，Kotlin + Jetpack Compose，插件化微内核架构。

面向本地文件库场景：扫描目录建立媒体库，播放音视频，带字幕、弹幕、倍速、仅听音频、断点续播与收藏历史。不做流媒体、不做在线源。

> 当前 `versionName 0.1.0`（versionCode 1），未发布到应用市场，属于自用与验证阶段。
> Room schema、DataStore 键命名空间与内部接口仍可能变动，不承诺跨版本兼容。

## 功能

**媒体库**
目录扫描与文件夹网格/列表；搜索；历史、收藏；同目录队列与断点续播；按文件指纹（大小 + mtime）校验断点有效性。

**播放**
Media3 服务会话承载，三种形态：全屏播放页、详情页内嵌、大屏右栏。倍速（十档 + 0.05 微调）、内外挂字幕、弹幕、仅听音频模式、手势控制（亮度/音量/进度）。

**解码链**
平台 MediaCodec 优先，NextLib FFmpeg 音视频扩展兜底，Media3Avi 提供 AVI 解封装与 MJPEG 软解渲染。
识别容器不等于全部可解——编码/profile/设备能力需分别验证，格式矩阵见 `docs/decoding/extension-decoder-decision.md`。

**PiP**
只允许在播放界面手动触发。后台播放不会自动进入小窗。

## 架构

依赖方向是硬约束，由各模块 `build.gradle.kts` 里声明的依赖边界保证（未声明即不可编译），并记录在 `docs/设计文档.md` §5：

```
plugin-*   →  kernel-api, kernel-ui-api, core-*      （禁止依赖其他 plugin-*）
kernel     →  kernel-api, kernel-ui-api, core-*
app        →  kernel, 所有 plugin-*                    （只做装配，无业务逻辑）
```

| 模块 | 职责 |
| --- | --- |
| `app` | 装配层，Application 与主题入口 |
| `kernel/kernel-api` | 插件契约：`Plugin`、播放器工厂、引擎提供者 |
| `kernel/kernel-ui-api` | UI 侧契约：标签页、导航宿主、详情栏、脚手架 |
| `kernel/kernel-ksp` | `@PluginMarker` 处理器：编译期生成 `PluginIndex` 贡献类，零反射、不受 R8 影响 |
| `kernel/kernel` | 内核实现：注册表、设置页、关于页 |
| `core/common` | 通用类型与工具 |
| `core/media` | 播放领域模型、格式探测、解码能力判定 |
| `core/media-android` | Android 侧 Media3/FFmpeg 探测与探针测试 |
| `core/data` | 设置与偏好（DataStore，按命名空间分键） |
| `core/database` | Room 持久化（媒体库、历史、断点，schema v1→v2） |
| `core/ui` | 设计系统与共享组件 |
| `plugins/plugin-library` | 媒体库、搜索、文件夹与详情页 |
| `plugins/plugin-scanner-local` | 本地目录扫描 |
| `plugins/plugin-player-exo` | Media3 ExoPlayer 引擎与 `PlaybackService` |
| `plugins/plugin-player-ui` | 播放界面、控件、手势、仅听音频页 |
| `plugins/plugin-profile` | 个人页：播放历史、收藏入口 |
| `plugins/plugin-enricher-filename` | 文件名解析与剧集信息推断 |
| `plugins/plugin-danmaku` | 弹幕加载与覆盖层 |

## 技术栈

| 项 | 版本 |
| --- | --- |
| Kotlin | 2.2.10 |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 |
| Compose BOM | 2024.12.01 |
| Media3 | 1.11.1 |
| Room | 2.7.2 |
| NextLib（FFmpeg 扩展） | 1.11.1-0.16.0 |
| Media3Avi | 2.7.1 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |

包名 `com.noneplayer.app`。

## 快速开始

前置：JDK 17、Android SDK（platform 36）。

在 `None Player/` 目录下执行（工程根目录名含空格，命令行需要引号）：

```bash
cd "None Player"
./gradlew :app:assembleDebug          # Debug 包
./gradlew test lint assembleDebug      # 门禁：单测 + lint + 构建
./gradlew assembleRelease              # Release 包（需配置签名，见下）
```

`gradle.properties` 里 `org.gradle.jvmargs=-Xmx4096m`，并行与构建缓存已开。

**Gradle 分发地址**：`gradle/wrapper/gradle-wrapper.properties` 目前指向腾讯云镜像。
非中国大陆网络首次同步失败时，把 `distributionUrl` 换成 `https\://services.gradle.org/distributions/gradle-9.5.0-all.zip`。

**Release 签名**：走 `local.properties` 的可选配置，密钥与口令不进仓库（`.gitignore` 已排除 `*.jks`、`*.keystore`、`local.properties`）。

```properties
sdk.dir=<Android SDK 路径>

noneplayer.release.storeFile=<keystore 路径>
noneplayer.release.storePassword=<口令>
noneplayer.release.keyAlias=<别名>
noneplayer.release.keyPassword=<口令>
```

四项配齐即出正式签名包；未配齐时 `assembleRelease` 显式回落 debug 签名并打印提醒，门禁不会因缺签名而中断。

**lint 口径**：`abortOnError = true`，不维护 baseline 文件——新增 error 直接打断构建。
排查时先排除缓存干扰：`local.properties` 不是声明输入，旧错误可能被持续回放，用 `--rerun-tasks --no-build-cache` 重跑。

## 仓库布局

```
README.md                 本文件
None Player/              Gradle 工程根（源码、构建脚本）
docs/                     设计规格、审计档案、回归脚本
```

`docs/` 与设计稿、审计记录属于本仓库；实机验证产生的截图、日志、性能 trace 一律留在本地，不入库。

## 文档

| 文档 | 职责 |
| --- | --- |
| `docs/播放体验与动效审查.md` | 当前 bug 状态、优化路线与执行档案 |
| `docs/任务文档.md` | 统一任务文档：未完成（A1–A33 + §9 产品增量）、未测试（B1–B20）、等裁定（D1–D7）。原《综合审计》已并入，冲突以其为准 |
| `docs/审查文档.md` | 历史验收证据与测量纪律（旧数字不作本轮复验） |
| `docs/设计文档.md` | 视觉、组件与动效规格 |
| `docs/decoding/extension-decoder-decision.md` | 实际解码链与扩展依赖边界 |
| `docs/*.ps1` | adb 驱动的回归脚本（默认打模拟器，真机需显式授权） |

## 已知边界

以下能力**未承诺**或**未验收**，取用时请按此评估：

- 不承诺 HLS / DASH / RTSP 等流媒体协议。
- 不承诺 HDR 真机表现与硬件直通。
- 折叠屏只有形态识别（`androidx.window` 读铰链状态），没有铰链几何避让。
- 字幕样式在部分路径上被契约压平，复杂 ASS 样式还原不完整。
- PGS 字幕按引擎边界判定为不支持。
- 个人页的「播放列表」与「音频收藏」目前是占位入口，点击提示尚未接入。
- 性能与设备矩阵证据基于有限机型与模拟器批次，未覆盖全部尺寸临界值、真实旋转与铰链场景。
- **许可证**：第三方合规已核实并落档 `None Player/NOTICE.md`（NextLib=GPL-3.0、自有 FFmpeg=LGPL-3.0 静态链、Media3Avi=MIT；许可原文在 `None Player/LICENSES/`）。应用自身 LICENSE 待拍板（保留 NextLib ⇒ 整体 GPL-3.0，或移除该依赖），分发前请先读 NOTICE.md §3。
