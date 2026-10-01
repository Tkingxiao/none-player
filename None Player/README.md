# None Player

插件化微内核视频播放器（Android / Kotlin / Compose）。架构设计见 `../设计文档.md`。

当前完成 **P0 + P1 + P2 阶段**：内核骨架 + 本地扫描插件 + ExoPlayer 播放内核/播放页 + 主题切换，全链路真机验证通过。

## 功能

- **B 站式信息架构**：底部标签栏（首页 / 文件夹 / 我的），`TabProvider` 扩展点贡献 Tab，切换 saveState/restoreState 不丢状态
- **媒体库**：双列/三列封面墙、时长角标、按压缩放、排序（名称/最新添加/时长/大小 × 升降序）、按文件夹分组浏览，全部持久化
- **文件夹门控扫描**：SAF 选择目录（不选不扫），MediaStore 观察器自动同步增删（去抖 2s）
- **封面缩略图**：扫描时系统缩略图落盘缓存，界面瞬时加载
- **播放内核**：ExoPlayer 驻留 MediaSessionService 后台播放 + 音频焦点/拔耳机暂停 + 系统媒体通知
- **播放页**：倍速（会话回读+默认倍速记忆）、外挂字幕（per-media 持久化）、断点续播（会话感知，不回退后台进度）、按视频分辨率比例渲染、前台常亮/后台可息屏、B 站式控制层
- **我的页**：设置入口、已加载插件清单、版本信息
- **主题**：三模式 + ColorScheme 全槽位 320ms 动画 + 首帧主题同步（无闪屏）
- **动效**：iOS push 转场、按压弹性、播放/暂停缩放、网格入场、启动屏淡入

## 运行效果

| 底部标签栏 | 文件夹 Tab | 我的 Tab |
| --- | --- | --- |
| ![标签栏](docs/screenshots/v4-tabs-home.png) | ![文件夹](docs/screenshots/v4-tabs-folders.png) | ![我的](docs/screenshots/v4-tabs-profile.png) |

## 模块结构

```
:app                     # 宿主：装配 Kernel、收集 PluginIndex，无业务逻辑
:kernel
  ├─ kernel-api          # 扩展点契约（纯 Kotlin）：Plugin / ExtensionPoint / Registry / EventBus / ServiceLocator / Navigator
  ├─ kernel-ui-api       # Compose 扩展点：ScreenProvider / PlaybackOverlay / SettingsProvider / KernelNavHost
  ├─ kernel-ksp          # KSP 处理器：@PluginMarker → 生成 <Xxx>PluginIndex（零反射，R8 安全）
  └─ kernel              # 内核实现：Kernel / DefaultExtensionRegistry / FlowEventBus / PluginSorter / VideoPlayerFactory
:core
  ├─ common              # DispatcherProvider 等工具（纯 Kotlin）
  ├─ media               # 领域模型：MediaItem / SearchResult / PlaybackFailure（纯 Kotlin）
  ├─ media-android       # Android 媒体能力：FormatProbe 起播前格式探测（E39）
  └─ ui                  # 设计系统：语义色令牌（明/暗）、iOS 刻度字号、⊘ 签名组件
:plugins
  └─ plugin-library      # 媒体库插件（演示）：route "home"，聚合 MediaSourceProvider
```

### 依赖铁律

```
plugin-*  →  kernel-api, kernel-ui-api, core-*
plugin-*  ✗→  其他 plugin-*（跨插件通信走扩展点 + 事件总线）
kernel    →  kernel-api, kernel-ui-api, core-*
app       →  kernel, 所有 plugin-*（仅装配）
```

## 新增一个插件（零侵入验证）

1. 新建模块 `:plugins:plugin-xxx`，依赖同 `plugin-library`；
2. 写 `@PluginMarker class XxxPlugin : Plugin`，在 `onInstall` 注册扩展点实现；
3. KSP 自动生成 `com.noneplayer.kernel.generated.XxxPluginIndex`；
4. 在 `:app` 的 `NonePlayerApplication` 中把 `XxxPluginIndex()` 加入 `pluginIndexes`。

不加任何核心代码，页面/设置项/菜单项即被内核发现。

## 构建与测试

要求 JDK 17、Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug   # 打 Debug 包
./gradlew test            # 内核单元测试（拓扑排序/注册表/事件总线）
```

## P0 关键设计决策

| 设计文档原文 | 实现取舍 | 理由 |
| --- | --- | --- |
| §3.3 方案 B：KSP 生成 PluginRegistry_Impl | 每个**插件模块**各生成 `<插件类>PluginIndex`，`:app` 装配时收集 | KSP 的注解扫描只覆盖本模块源码，跨模块聚合需运行时服务发现（R8 风险）；逐模块生成 + 宿主一行收集，编译期安全且可追溯 |
| §4.2 Navigator 注册为内核服务 | Navigator 不进 ServiceLocator，经 `ScreenProvider.register(navigator)` 注入 | Navigator 实现绑定 NavController 生命周期，进 ServiceLocator 会成为隐形全局变量 |
| §4 Kernel.shutdown 查询注册表停插件 | 内核内部维护已安装插件列表，逆序 `onStop()` | Plugin 不是 ExtensionPoint，不在注册表内 |
| §10 BackgroundTask 含 WorkRequest | 契约只定义 `suspend execute()`，WorkManager 适配延后到内核实现层 | 维持 kernel-api 纯 Kotlin（§15.1 Checklist） |
| 引导时序 | `boot()` 异步执行，UI 以 `bootState` 门控（Running 前显示启动画面） | 避免阻塞主线程，也避免导航图在插件注册完成前组装 |

## 视觉基线（Apple HIG 对齐）

- 色彩：语义令牌明/暗双套，跟随系统外观（无应用内切换）；琥珀橙 accent 只用于主操作与品牌时刻，正文灰阶；小字对比度 ≥ 4.5:1
- 字号：iOS 刻度映射 M3 槽位（Body 17sp / Footnote 13sp / Caption 11sp 下限），全部 sp 随系统缩放
- 控件：触控目标 ≥ 44dp，行高 ≥ 48dp
- 签名元素：`⊘ None` 标记（圆 + 斜线）——空状态与启动画面的品牌时刻
