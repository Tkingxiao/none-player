# None Player

插件化微内核视频播放器（Android / Kotlin / Compose）。项目文档（仓库根目录）：`../任务文档.md`（待办与已完成）· `../审查文档.md`（完成度审查）· `../设计文档.md`（视觉/组件/动效规格 + 修改建议）。

当前状态（2026-10-09 定档 + UI/动画/性能专项）：**批次 0–8 全部交付**——会话层与迷你条、全屏手势层、历史/续看/搜索/收藏、视觉与动效（令牌收口 / 材质五件套 / Haptics / 两套进度体系 / 三档性能模式 / 共享封面）、本地弹幕插件、工程收尾（R8 release / targetSdk 36 / 诊断面板 / 清缓存）、多格式通用性（容器嗅探 / .cue 分轨 / 拼音搜索）；缺陷修复推进至 **E127**（E126 = 2026-10-11 真机实报回合：切页闪出「选择要观看的文件夹」空态残留，根因是 DataStore 收集初值把"首读未落地"当成"确实没配目录"）（UI/动画/性能专项两轮 E119–E122、E123–E125；第二轮把切集起播的探测等待砍掉 84%（同条件 A/B）、接通 §9.4 播放降档的死管道、收掉三处逐帧浪费，并给切集链路留下常驻尺子 `NpPerf`），UI 走查与实报回合全数收口，**A1–A5 五批任务（E109–E118）定档后追加的 UI / 动画 / 性能专项（E119–E122）也已走完**（动画规格"唯一入口"改为编译期强制、两处重组域实测收窄 41→0 与 43→2 次/12s、逐帧与逐 cue 的必然分配收口）。设计文档 §14 验收 23 项判定：**✅ 20 / 🟡 2 / ❌ 1**——余下三条（🟡 两条 + ❌ 一条）全是取证手段或历史基线问题（人工键盘、真机鼠标热插拔、体积无旧基线可比），无一条实现缺失。现行门禁：test **142 项 0 失败**、lint 全模块 0 issue、assembleDebug 与 assembleRelease 通过；release **36.98 MB**（38,784,391 B，较 36.9 MB 基线累计 +64 KB）。**剩余任务、完成度审查、视觉规格分别见仓库根目录《任务文档.md》《审查文档.md》《设计文档.md》**（旧五档已并入本套，历史见 git）。

## 功能

- **B 站式信息架构**：底部标签栏（首页 / 文件夹 / 我的 / 设置），`TabProvider` 扩展点贡献 Tab，切换 saveState/restoreState 不丢状态
- **媒体库**：首页横滑 rails（继续观看 / 最近添加 / 文件夹 / 全部视频 / 全部音频）+ 聚合列表页（类型筛选、排序、双列/三列、网格视图），全部持久化
- **音频 + 视频**：`LocalMediaSource` 同时收 `MediaStore.Video` 与 `MediaStore.Audio`，`MediaItem.kind` 判定种类；音频用专辑封面、视频用系统缩略图/抽帧
- **封面缩略图**：扫描时系统缩略图落盘缓存，界面瞬时加载
- **文件夹门控扫描**：SAF 选择目录（不选不扫），MediaStore 观察器自动同步增删（去抖 2s）
- **播放内核**：ExoPlayer 驻留 MediaSessionService 后台播放 + 音频焦点/拔耳机暂停 + 系统媒体通知
- **播放页**：倍速（会话回读 + 默认倍速记忆 + 十档与 ±0.05 微调）、自定义双击步长、外挂字幕（per-media 持久化）、断点续播（会话感知，不回退后台进度）、按视频分辨率比例渲染、前台常亮/后台可息屏、B 站式控制层
- **会话层**：`PlaybackSession` 进程级单例——播放器归 `PlaybackService` 独占，页面只经 `MediaController` 驱动，**退出播放页不释放引擎**（L0），退页后仍在后台继续播放
- **同文件夹队列 + 连播**：进入播放页自动收集同目录兄弟项，`⏮ ▶ ⏭` 切换、「选集 N/M」面板选段，播完自动下一集
- **迷你条**：任意 Tab 常驻显示当前播放（左缘 2dp accent 进度线 / 封面 / 标题 / `N / M · 时间` / ⏸🎧⏭✕ / 底部 2dp 进度），点回播放页**无缝续播不重新 prepare**；无会话时不占高度
- **形态判定层**：`NpWindowInfo` + `LocalNpWindowInfo` 单点注入，按 `NpFormFactor`（手机/折叠/平板/桌面）分派，不依赖 `screenWidthDp` 做尺寸档位
- **手势层**：横滑 seek（满宽 ±90s）/ 双击左右 ±10s、中心暂停 / 长按 3× 松手回原速 / 竖滑调亮度与音量（分区、带指示条、可整体关闭）/ 边缘 24dp 不拦截系统侧滑返回 / 锁定态吞返回并隐藏全部控件
- **仅听音频**：per-media 记忆 + 黑胶/唱臂视图（竖窗纵排可滚 / 横窗「黑胶在左、内容在右」两栏不滚）+ **均衡器**（引擎实测段数/量程、归一增益、四预设形状函数），退页后台继续；**画中画 PiP**：手动与自动进入、自定义动作、控制层自动隐藏、与仅听音频互斥
- **视频详情页**：同文件夹列表 + rail 常驻，内嵌播放经 `InlinePlaybackProvider` 扩展点（未注册实现的宿主回退整页播放），进全屏是同一个会话不重启
- **播放历史与收藏（`:core:database` / Room）**：播放中每 5s 及暂停/切集/退页落断点，「继续观看」改读 Room（<10s 且未看完不写），DataStore 旧断点首扫一次性迁入；收藏在长按菜单与「我的」页联动
- **自然序与元数据富化**：`NaturalOrder`（`E2 < E10`、`SxxExx`、`第 N 集/话/回`）同时驱动媒体库排序与连播队列；`MetadataEnricher` 扩展点由 `plugin-enricher-filename` 实现（字幕组文件名 → 短标题 / 集数 / 编码）
- **长按菜单与下拉刷新**：菜单锚定卡片展开、`ContextMenuItem` 扩展点聚合（首个贡献项=系统分享）；首页下拉触发重新扫描
- **容器嗅探与无扩展名拾取**：文件头 64 字节判容器（MP4/MKV/WebM/AVI/WAV/FLAC/MP3/TS…），错扩展名校正 + 无扩展名文件拾取（所有文件访问，fail-open）
- **`.cue` 分轨**：同名 cue 在场的音频文件展开为「一张专辑 N 轨」（INDEX 01 制、绝对微秒精度），轨内 seek 与断点独立
- **拼音搜索**：中文标题全拼/首字母命中（"wode"→「我的视频」、"aq"→首字母），android.icu 离线转写、零新依赖
- **我的页**：用户内容（播放历史 / 收藏 / 播放列表 / 音频收藏）+ 最近添加横滑
- **设置页**（顶层标签）：外观三选一 + 各插件经 `SettingsProvider` 贡献的设置区块
  + 「插件」与「关于」两个下钻入口
- **插件页**：已加载插件清单、扩展点统计、微内核说明；「缓存」区块列出各插件经 `CacheProvider` 暴露的占用并支持一键清理
- **关于页**：版本与构建号、界面框架、运行环境（Android 版本 / 设备）
- **解码诊断**：设置页展开设备能力矩阵（H.264/HEVC/AV1/VP9/MPEG-4 的默认解码器、硬/软解、profile/level 档数，与起播探测同源）
- **主题**：三模式 + 全槽位 320ms 冻结帧交叉淡化 + 首帧主题同步（无闪屏）
- **动效**：iOS push 转场、按压弹性、播放/暂停缩放、网格入场、启动屏淡入、**四标签过渡**（选中胶囊在四格间滑动 + 图标 0.9→1 + 内容 fade/scale/8dp 方向淡移，全部走 `Motion.Tab` 令牌与三档性能模式）

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

要求 JDK 17、Android SDK（compileSdk 36 / minSdk 26 / targetSdk 36）。

```bash
./gradlew assembleDebug   # 打 Debug 包
./gradlew test            # 内核单元测试（拓扑排序/注册表/事件总线）
./gradlew assembleRelease # R8 压缩链路
```

> 门禁约定：每个批次必须同时通过 `assembleDebug` + `test` + `assembleRelease`，并按《审查文档.md》口径附**可判定证据**（截图 / 代码读值 / 抽帧差分 / 单测；R15 起不要求录屏）。批次 1 的 9 项真机验收记录见《审查文档.md》§1。

## P0 关键设计决策

| 架构稿原文（历史稿，条文已并入 git 历史） | 实现取舍 | 理由 |
| --- | --- | --- |
| §3.3 方案 B：KSP 生成 PluginRegistry_Impl | 每个**插件模块**各生成 `<插件类>PluginIndex`，`:app` 装配时收集 | KSP 的注解扫描只覆盖本模块源码，跨模块聚合需运行时服务发现（R8 风险）；逐模块生成 + 宿主一行收集，编译期安全且可追溯 |
| §4.2 Navigator 注册为内核服务 | Navigator 不进 ServiceLocator，经 `ScreenProvider.register(navigator)` 注入 | Navigator 实现绑定 NavController 生命周期，进 ServiceLocator 会成为隐形全局变量 |
| §4 Kernel.shutdown 查询注册表停插件 | 内核内部维护已安装插件列表，逆序 `onStop()` | Plugin 不是 ExtensionPoint，不在注册表内 |
| §10 BackgroundTask 含 WorkRequest | 契约只定义 `suspend execute()`，WorkManager 适配延后到内核实现层 | 维持 kernel-api 纯 Kotlin（§15.1 Checklist） |
| 引导时序 | `boot()` 异步执行，UI 以 `bootState` 门控（Running 前显示启动画面） | 避免阻塞主线程，也避免导航图在插件注册完成前组装 |

## 视觉基线（对齐设计稿 `音乐播放器界面设计/none-player.html`）

UI 是设计稿的 **1:1 移植**，不是"参考风格的重绘"。设计真源（历史稿，已不在仓库）：
`音乐播放器界面设计/none-player.html`（含全部 CSS）；
实现契约（token 表 / 组件 API / 逐条 CSS 摘录 / 紧凑档覆盖）见
[`docs/ui-rework-contract.md`](docs/ui-rework-contract.md)；
**视觉 / 组件 / 动效的规格唯一来源是 `../设计文档.md`**——
令牌与曲线时长照抄其 §3，材质配方照抄其 §4，组件配方照抄其 §5，动效场景照抄其 §6/§7；
历史诊断文档已并入该档或移入 git 历史，冲突时一律以《设计文档.md》为准。

- **色彩**：设计稿的 OKLch 令牌等值落到 sRGB——`--bg`（暗纯黑 / 亮纯白）、
  `--surface`、`--fg`、`--muted`、`--border`、`--accent`（暗 `#F58028` / 亮 `#CE5600`）。
  **accent 是"直播信号"不是装饰**：只出现在①选中的导航项 ②进度填充与滑块
  ③主操作按钮 ④"新增"角标 ⑤当前集标记 ⑥选中排序 chip，其它地方一律不用。
- **排版**：27/22/19/17/15/14/13/11sp 刻度；一切时间码、时长、大小、百分比
  走 `NpMono`（等宽，位数变化不抖动）。
- **材质**：顶栏 / 迷你条 / Tab 栏是**不透明底**（`ink.glass`，R1：全局禁 backdrop-blur）+ 顶边 1px `--edge` 高光；
  卡片靠 1px 发丝描边分层（亮色下 `--surface` 与 `--bg` 同为纯白，**描边是结构而非装饰**）。
- **图标**：`NpIcon` 的路径逐字取自设计稿的 `I` 表（24dp 视口 / **1.8dp 描边** / 圆头圆角）；
  自绘而非引 `material-icons-extended`，形状一致且不给 APK 增重 5MB。
- **形态**：紧凑档 = 底部玻璃 Tab 栏（4 项）；中/扩展档 = 左侧 80dp 玻璃 Rail。
  档位判据只用 `LocalNpWindowInfo.current.isCompact`，不读 `screenWidthDp`。
- **触控**：目标 ≥ 44dp；控件行高 ≥ 48dp。

> 保真验证截图见 [`docs/screenshots/ui-rework/`](docs/screenshots/ui-rework/)：
> 首页（明/暗）、文件夹、文件夹详情、聚合列表、筛选弹层、我的、设置、
> 播放页、横屏 Rail。
