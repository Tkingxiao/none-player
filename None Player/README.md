# None Player

Android / Kotlin / Compose 本地音视频播放器，采用插件化微内核架构。
2026-10-12已完成全项目审计及首批修复，后续优化与未验收项见审计档案；历史“全部交付”不能代替本轮验收。

## 文档

仓库根目录 `docs/`：

- `../docs/README.md`：导航。
- `../docs/播放体验与动效审查.md`：当前bug状态、优化方案与测试档案。
- `../docs/任务文档.md`：长期待办和历史索引。
- `../docs/审查文档.md`：历史证据和测量纪律。
- `../docs/设计文档.md`：视觉、组件与动效规格。
- `../docs/decoding/extension-decoder-decision.md`：实际解码链和边界。

## 功能与边界

媒体库支持音视频、目录扫描、文件夹网格/列表、搜索、历史、收藏、断点与同目录队列。
Media3服务会话提供全屏/详情内嵌/大屏右栏、倍速、字幕、弹幕、仅听音频和手势。
PiP按最新要求只允许播放界面手动触发，后台播放不等于自动小窗。

解码链为平台MediaCodec、NextLib FFmpeg音视频扩展及Media3Avi MJPEG渲染器。
识别容器不保证所有编码/profile/设备可解，格式矩阵见审计。
当前不承诺HLS/DASH/RTSP、HDR真机或硬件直通。折叠只有形态识别，没有铰链几何避让。

## 模块

```text
:app                         宿主装配与Activity
:kernel:kernel-api           纯Kotlin扩展点
:kernel:kernel-ui-api        Compose外壳与UI扩展点
:kernel:kernel-ksp           编译期插件索引
:kernel:kernel               注册表、生命周期与服务
:core:common                 调度器
:core:media                  领域模型
:core:media-android          格式探测
:core:data                   设置
:core:database               Room历史与收藏
:core:ui                     设计系统
:plugins:plugin-library      媒体库、搜索与详情
:plugins:plugin-scanner-local 本地扫描
:plugins:plugin-player-exo   播放服务与引擎
:plugins:plugin-player-ui    会话与播放界面
:plugins:plugin-profile      我的页
:plugins:plugin-enricher-filename 文件名富化
:plugins:plugin-danmaku      弹幕
```

插件只依赖kernel-api/kernel-ui-api/core-*，跨插件经扩展点或事件通信。

## 构建与验证

JDK17、Android SDK36，minSdk26、targetSdk36。
`local.properties`配置SDK及可选签名，禁止提交密钥/密码。

```powershell
.\gradlew.bat test lint :app:assembleDebug :app:assembleRelease --console=plain
```

Android lint不等于所有JVM模块通过ktlint/detekt。检查退出码及XML报告，源码Error不得用baseline或全局禁用掩盖。
工具链更新提示独立记录，不为消提示盲升AGP。设备测试明确指定模拟器，不运行pm clear。
