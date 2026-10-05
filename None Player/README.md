# None Player

插件化微内核视频播放器（Android / Kotlin / Compose）。架构设计见 `../设计文档.md`。

当前完成 **P0 + P1 + P2 阶段 + 批次 1（会话层 / 队列 / 迷你条 / 形态判定层）+ 批次 2（手势层 / 播放页重构）+ 批次 3（画面比例镜像旋转 / 字幕音轨章节 / 仅听音频 / 画中画 / 状态栏）+ 批次 4（内容层：播放历史 / 继续观看 / 搜索 / 收藏 + 自然序与文件名元数据富化）**。批次 1 小米平板 6 真机验收，批次 2–4 虚拟平板（Pixel_Tablet）逐项验收；待办与未覆盖项如实记在《功能审核文档.md》§0，已完成记录见《功能修复档案.md》（批次 4 = §十四，批次 5 前置回合 = §十五）。**批次 5（视觉与动效）代码完成**（令牌/材质五件套/Haptics/封面取色/WaveformSeekBar/三档性能模式/E50 快捷键/共享元素规格），模拟器逐项验收与 §14 记账待收口；规格：`../界面设计路线.md` v1.1。**批次 6（plugin-danmaku）已开工**：三格式解析 / 同名匹配 / 设置区块三任务落地，渲染（PlaybackOverlay）待做。

## 功能

- **B 站式信息架构**：底部标签栏（首页 / 文件夹 / 我的 / 设置），`TabProvider` 扩展点贡献 Tab，切换 saveState/restoreState 不丢状态
- **媒体库**：首页横滑 rails（继续观看 / 最近添加 / 文件夹 / 全部视频 / 全部音频）+ 聚合列表页（类型筛选、排序、双列/三列、网格视图），全部持久化
- **音频 + 视频**：`LocalMediaSource` 同时收 `MediaStore.Video` 与 `MediaStore.Audio`，`MediaItem.kind` 判定种类；音频用专辑封面、视频用系统缩略图/抽帧
- **封面缩略图**：扫描时系统缩略图落盘缓存，界面瞬时加载
- **文件夹门控扫描**：SAF 选择目录（不选不扫），MediaStore 观察器自动同步增删（去抖 2s）
- **播放内核**：ExoPlayer 驻留 MediaSessionService 后台播放 + 音频焦点/拔耳机暂停 + 系统媒体通知
- **播放页**：倍速（会话回读+默认倍速记忆）、外挂字幕（per-media 持久化）、断点续播（会话感知，不回退后台进度）、按视频分辨率比例渲染、前台常亮/后台可息屏、B 站式控制层
- **会话层**：`PlaybackSession` 进程级单例——播放器归 `PlaybackService` 独占，页面只经 `MediaController` 驱动，**退出播放页不释放引擎**（L0），退页后仍在后台继续播放
- **同文件夹队列 + 连播**：进入播放页自动收集同目录兄弟项，`⏮ ▶ ⏭` 切换、「选集 N/M」面板选段，播完自动下一集
- **迷你条**：任意 Tab 常驻显示当前播放（左缘 2dp accent 进度线 / 封面 / 标题 / `N / M · 时间` / ⏸🎧⏭✕ / 底部 2dp 进度），点回播放页**无缝续播不重新 prepare**；无会话时不占高度
- **形态判定层**：`NpWindowInfo` + `LocalNpWindowInfo` 单点注入，按 `NpFormFactor`（手机/折叠/平板/桌面）分派，不依赖 `screenWidthDp` 做尺寸档位
- **手势层**：横滑 seek（满宽 ±90s）/ 双击左右 ±10s、中心暂停 / 长按 3× 松手回原速 / 竖滑调亮度与音量（分区、带指示条、可整体关闭）/ 边缘 24dp 不拦截系统侧滑返回 / 锁定态吞返回并隐藏全部控件
- **仅听音频**：per-media 记忆 + 黑胶/唱臂视图，退页后台继续；**画中画 PiP**：手动与自动进入、自定义动作、控制层自动隐藏、与仅听音频互斥
- **视频详情页**：同文件夹列表 + rail 常驻，内嵌播放经 `InlinePlaybackProvider` 扩展点（未注册实现的宿主回退整页播放），进全屏是同一个会话不重启
- **播放历史与收藏（`:core:database` / Room）**：播放中每 5s 及暂停/切集/退页落断点，「继续观看」改读 Room（<10s 且未看完不写），DataStore 旧断点首扫一次性迁入；收藏在长按菜单与「我的」页联动
- **自然序与元数据富化**：`NaturalOrder`（`E2 < E10`、`SxxExx`、`第 N 集/话/回`）同时驱动媒体库排序与连播队列；`MetadataEnricher` 扩展点由 `plugin-enricher-filename` 实现（字幕组文件名 → 短标题 / 集数 / 编码）
- **长按菜单与下拉刷新**：菜单锚定卡片展开、`ContextMenuItem` 扩展点聚合（首个贡献项=系统分享）；首页下拉触发重新扫描
- **我的页**：用户内容（播放历史 / 收藏 / 播放列表 / 音频收藏）+ 最近添加横滑
- **设置页**（顶层标签）：外观三选一 + 各插件经 `SettingsProvider` 贡献的设置区块
  + 「插件」与「关于」两个下钻入口
- **插件页**：已加载插件清单、扩展点统计、微内核说明
- **关于页**：版本与构建号、界面框架、运行环境（Android 版本 / 设备）
- **主题**：三模式 + 全槽位 320ms 冻结帧交叉淡化 + 首帧主题同步（无闪屏）
- **动效**：iOS push 转场、按压弹性、播放/暂停缩放、网格入场、启动屏淡入

## 运行效果

| 底部标签栏 | 文件夹 Tab | 我的 Tab |
| --- | --- | --- |
| ![标签栏](docs/screenshots/v4-tabs-home.png) | ![文件夹](docs/screenshots/v4-tabs-folders.png) | ![我的](docs/screenshots/v4-tabs-profile.png) |

### 批次 1：迷你条与队列

| 迷你条（标题 + `1 / 4` + 进度） | 播放页 ⏮▶⏭ + 选集 | 选集面板 |
| --- | --- | --- |
| ![迷你条](docs/screenshots/b1-minibar-title-queue.png) | ![播放页](docs/screenshots/b1-player-prevnext-episodechip.png) | ![选集](docs/screenshots/b1-episode-panel.png) |

## 模块结构

```
:app                     # 宿主：装配 Kernel、收集 PluginIndex，无业务逻辑
:kernel
  ├─ kernel-api          # 扩展点契约（纯 Kotlin）：Plugin / ExtensionPoint / Registry / EventBus / ServiceLocator / Navigator
  ├─ kernel-ui-api       # Compose 扩展点：ScreenProvider / TabProvider / PlaybackOverlay / SettingsProvider / BottomChromeProvider / DetailPaneProvider（右栏详情，配 LocalNpPaneOccupied 保证同一时刻只有一路 Surface）/ InlinePlaybackProvider / KernelNavHost / NpWindowInfo
  ├─ kernel-ksp          # KSP 处理器：@PluginMarker → 生成 <Xxx>PluginIndex（零反射，R8 安全）
  └─ kernel              # 内核实现：Kernel / DefaultExtensionRegistry / FlowEventBus / PluginSorter / VideoPlayerFactory
:core
  ├─ common              # DispatcherProvider 等工具（纯 Kotlin）
  ├─ media               # 领域模型：MediaItem / SearchResult / PlaybackFailure（纯 Kotlin）
  ├─ media-android       # Android 媒体能力：FormatProbe 起播前格式探测（E39）
  ├─ data                # 设置持久化（DataStore）：AppSettings
  └─ ui                  # 设计系统：语义色令牌（明/暗）、iOS 刻度字号、⊘ 签名组件
:plugins
  ├─ plugin-library      # 媒体库插件：route "home"，聚合 MediaSourceProvider
  ├─ plugin-scanner-local# 本地 MediaStore 扫描来源（SAF 门控 + RELATIVE_PATH 分组）
  ├─ plugin-player-exo   # ExoPlayer 播放内核：PlaybackService + 预加载（L1/L2）
  └─ plugin-player-ui    # 播放页 + 会话层 + 迷你条（PlaybackSession / MiniPlayerChrome）
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

要求 JDK 17、Android SDK（compileSdk 36 / minSdk 26 / targetSdk 35）。

```bash
./gradlew assembleDebug   # 打 Debug 包
./gradlew test            # 内核单元测试（拓扑排序/注册表/事件总线）
./gradlew assembleRelease # R8 压缩链路
```

> 门禁约定：每个批次必须同时通过 `assembleDebug` + `test` + `assembleRelease`，并附**真机验证截图**（`docs/screenshots/`）。批次 1 的 9 项真机验收记录见《功能审核文档.md》§10.0 台账。

## P0 关键设计决策

| 设计文档原文 | 实现取舍 | 理由 |
| --- | --- | --- |
| §3.3 方案 B：KSP 生成 PluginRegistry_Impl | 每个**插件模块**各生成 `<插件类>PluginIndex`，`:app` 装配时收集 | KSP 的注解扫描只覆盖本模块源码，跨模块聚合需运行时服务发现（R8 风险）；逐模块生成 + 宿主一行收集，编译期安全且可追溯 |
| §4.2 Navigator 注册为内核服务 | Navigator 不进 ServiceLocator，经 `ScreenProvider.register(navigator)` 注入 | Navigator 实现绑定 NavController 生命周期，进 ServiceLocator 会成为隐形全局变量 |
| §4 Kernel.shutdown 查询注册表停插件 | 内核内部维护已安装插件列表，逆序 `onStop()` | Plugin 不是 ExtensionPoint，不在注册表内 |
| §10 BackgroundTask 含 WorkRequest | 契约只定义 `suspend execute()`，WorkManager 适配延后到内核实现层 | 维持 kernel-api 纯 Kotlin（§15.1 Checklist） |
| 引导时序 | `boot()` 异步执行，UI 以 `bootState` 门控（Running 前显示启动画面） | 避免阻塞主线程，也避免导航图在插件注册完成前组装 |

## 视觉基线（对齐设计稿 `音乐播放器界面设计/none-player.html`）

UI 是设计稿的 **1:1 移植**，不是"参考风格的重绘"。设计真源：
`D:\Code\Player\音乐播放器界面设计\none-player.html`（含全部 CSS）；
实现契约（token 表 / 组件 API / 逐条 CSS 摘录 / 紧凑档覆盖）见
[`docs/ui-rework-contract.md`](docs/ui-rework-contract.md)；
**视觉 / 组件 / 动效的施工规格唯一来源是 `../界面设计路线.md`（v1.1，2026-10-05 签字）**——
令牌与曲线时长照抄其 §3，材质配方照抄其 §4，组件配方照抄其 §5，动效场景照抄其 §6/§7；
《功能审核文档.md》§6/§7 与《设计审查报告.md》只保留诊断与原则，冲突时一律以路线文档为准。

- **色彩**：设计稿的 OKLch 令牌等值落到 sRGB——`--bg`（暗纯黑 / 亮纯白）、
  `--surface`、`--fg`、`--muted`、`--border`、`--accent`（暗 `#F58028` / 亮 `#CE5600`）。
  **accent 是"直播信号"不是装饰**：只出现在①选中的导航项 ②进度填充与滑块
  ③主操作按钮 ④"新增"角标 ⑤当前集标记 ⑥选中排序 chip，其它地方一律不用。
- **排版**：27/22/19/17/15/14/13/11sp 刻度；一切时间码、时长、大小、百分比
  走 `NpMono`（等宽，位数变化不抖动）。
- **材质**：顶栏 / 迷你条 / Tab 栏是 `--glass` 半透明底 + 顶边 1px `--edge` 高光；
  卡片靠 1px 发丝描边分层（亮色下 `--surface` 与 `--bg` 同为纯白，**描边是结构而非装饰**）。
- **图标**：`NpIcon` 的路径逐字取自设计稿的 `I` 表（24 视口 / 2px 描边 / round）；
  自绘而非引 `material-icons-extended`，形状一致且不给 APK 增重 5MB。
- **形态**：紧凑档 = 底部玻璃 Tab 栏（4 项）；中/扩展档 = 左侧 80dp 玻璃 Rail。
  档位判据只用 `LocalNpWindowInfo.current.isCompact`，不读 `screenWidthDp`。
- **触控**：目标 ≥ 44dp；控件行高 ≥ 48dp。

> 保真验证截图见 [`docs/screenshots/ui-rework/`](docs/screenshots/ui-rework/)：
> 首页（明/暗）、文件夹、文件夹详情、聚合列表、筛选弹层、我的、设置、
> 播放页、横屏 Rail。
