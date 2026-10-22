# 文档导航

更新：2026-10-22。阅读顺序：**本页 → 本轮审查附录 → 统一任务 → 对应设计规格/历史证据**。

| 文档 | 职责 |
| --- | --- |
| [编解码与转码优化方案-2026-10-22.md](编解码与转码优化方案-2026-10-22.md) | 编解码能力/转码/格式拓展的最终路线；R0–R5 阶段与 A/B/C 清单 |
| [decoding/codec-capability-matrix-2026-10-22.md](decoding/codec-capability-matrix-2026-10-22.md) | **R0/T1 实测**：两台基线设备的 decoder/encoder 声明矩阵、快照 schema 与导出命令 |
| [项目审查与优化路线-2026-10-20.md](项目审查与优化路线-2026-10-20.md) | 本轮代码证据、旧结论订正、REV-01–16、分批实现路径与验收；不是另一套独立任务状态表 |
| `播放体验与动效审查.md` | A01–A50 历史审计、bug list对照和 E 系执行档案；头部标明快照日期，不当当前全局完成度 |
| `任务文档.md` | **统一任务入口**：A1–A34 源码/能力条目 + 本轮 REV 补充入口、§9 产品增量、B1–B24 验证范围、D1–D7 裁定。§0 当前施工顺序优先，保留已修项仅供追踪，不重复施工 |
| `审查文档.md` | 历史验收证据、测量纪律；数字不可当本轮复验 |
| `设计文档.md` | 视觉/组件/动效规格，最新用户需求优先于旧规格 |
| `decoding/extension-decoder-decision.md` | 实际解码链及依赖合规待核 |
| `decoding/instrumentation-assets.md` | androidTest 素材/执行清单；R0/T1 能力快照测试已执行 |
| `buglist-device-test.ps1` | 默认模拟器抓图/点按；显式`-AllowPhysicalDevice`允许已授权真机，不清除数据 |
| `audit-device-matrix.ps1` | 窗口尺寸模拟，恢复设置，不等于真实铰链 |
| `playback-regression.ps1` | 后台无自动PiP、缺失文件→正常文件恢复、退播放页回桌面回归 |
| `player-menu-regression.ps1` | B3菜单目标页、跨形态字幕延时、播放中关闭、实际上滑/后台回归；真机只允许专用测试素材 |
| `audio-mode-verification-2026-10-13.md` / `audio-mode-regression.ps1` / `audio-pane-controls-regression.ps1` | 仅听页历史证据与专用回归；不代替当前全项目门禁 |
| `waveform-regression.ps1` | 中央参照波形的拖动、预览、提交/取消回归；点按绝对跳转不是已定修法 |

## 当前验证范围

- **R0/T1 编解码能力快照（2026-10-22，E171）**：两台基线设备都用 `CodecCapabilitySnapshotTest` 导出运行时能力，均 `OK (3 tests)`；骁龙 72 codec（45 解/27 编）、天玑 90 codec（64 解/26 编），查询错误 0。两机 HEVC 硬解均声明 Main10；骁龙 1080p HEVC CQ 无编码器（`.hevc.cq` 上限 512x512），天玑 CQ 可用。矩阵与产物见
  [decoding/codec-capability-matrix-2026-10-22.md](decoding/codec-capability-matrix-2026-10-22.md)。
- **R7/A19 余项（2026-10-22，E172）**：NextLib `FfmpegVideoRenderer` 的 Surface 克隆已用 `Renderer` 委托包上；两机 `NpFfmpegVideoRendererTest` 3/3、相关 `LegacyPlaybackTest` 6/6（天玑首次安装时点掉了 MIUI「USB 安装提示」）；全量 test 116/778、lint 15 份 0 issue、release 19,687,445 B。FFmpeg-only 实片端到端与 AVFrame 直接上屏仍欠（B24 / §0 第 10 行）。
- **§9 排序增量（2026-10-22，E173）**：`SortMode` 增「最近播放 / 最常看」，按 `lastPlayedAt` / `playCount` 排序；修「最近添加」聚合强制按加入时间重排的实测缺陷。骁龙 82 项聚合页六档菜单与两种顺序已截图；全量 test 116/781、lint 15 份 0 issue、release 19,687,445 B。天玑掉线待补。
- **B1 后台关闭语义（2026-10-22，E174）**：骁龙后台关时全屏 Home / 锁屏 / 内嵌视频与仅听 Home / 空队列 Home 均停到 `NONE(0)`；竖屏播放中转到横屏交还右栏也 `NONE(0)`，回详情页停在 3:22；开关已恢复 ON。截图 `screenshots/B1/23043RP34C/`。
- **B2 不可播项后高亮（2026-10-22，E175）**：xvid-qpel 选中并 ERROR 后页头/「当前」仍停在 05；从 np-mjpeg 起播自动推进到 05 后也未退回 01；快速切集 02→04、05→03 均跟随最后一次选择。通知栏按钮单独触发未拍；天玑按要求不补。截图 `screenshots/B2/23043RP34C/`。
- **B3 窄窗/大字体控制栏（2026-10-22，E176）**：播放页控制栏在 320dp、360dp、font 1.3x、360dp 横屏、平板横屏均无溢出/崩溃；1.3x 选集行标题省略。截图 `screenshots/B3/23043RP34C/`；显示参数已恢复。
- **R0 收口后（2026-10-20 18:52–18:53）`test` 通过：70 套件 / 439 项 / 0 失败、错误、跳过**；强制重跑且关闭构建缓存，327 个任务 executed（1m33s）。精确离线命令、模块计数、警告与时间见[审查文档 §6.1](审查文档.md) 与[路线 §2](项目审查与优化路线-2026-10-20.md#2-本轮测试基线)。
- **R1 收口后（2026-10-20 19:24–19:25）`test` 通过：77 套件 / 496 项 / 0 失败、错误、跳过**；337 个任务 executed（46s）。同一工作树补跑 `lint + assembleDebug + assembleRelease`（2m8s，`lintVitalRelease` 通过；模块 lint 0 issue、app release 1 条既有 `UseKtx` 警告），release APK 41,847,947 B（SHA256 `9DF8F0C8…`，装机 19:28:10）；androidTest 未跑。
- **R0（REV-01/02/03/12/13 + A33）已在平板 `60dd2f94` 用最终包完成 REV-01/REV-13 真机复验**（撤音频权限→不完整扫描不删历史/收藏/快照、横幅与 note、恢复后复原）；REV-02/REV-12 的旧 SDK/第二卷分支只有自动测试背书。证据见[审查文档 §9](审查文档.md)、《任务文档》§11 E151。
- **R1（A23 会话侧 + REV-04/11/09/10）已在同一平板完成真机复验**：A23 Ended 重播（32:12 末尾→点播放→position 0 续播）、REV-04 身份化落盘（Zoku 06 7%→23%，Owarimonogatari 第 5 集 13% 未被污染）；REV-10 断连设备面未覆盖（服务与 app 同进程，仅自动测试背书）。证据见[审查文档 §10](审查文档.md)、《任务文档》§11 E152。
- **R2 收口后（2026-10-20 20:50–20:51）`test` 通过：82 套件 / 538 项 / 0 失败、错误、跳过**；337 个任务 executed（1m24s）。同一工作树补跑 `lint + assembleDebug + assembleRelease`（55s；13 个模块 + app debug 报告 0 issue、app release 1 条既有 `UseKtx` 警告），release APK 41,847,947 B（SHA256 `4EFA4E41…`，装机 20:47:49）；androidTest 未跑。
- **R2（REV-06/07 + A23 异常兜底）自动回归新增 5 套件 / 42 项**；真机冒烟发现并修掉 Release/R8 才暴露的引导回归（`SessionHolder` 按接口键取服务 → `SessionFactoryWiringTest` 钉住），平板 `60dd2f94` 完成 REV-07 同 UID 私有命令授权（`controller connected … private=true`）与标准媒体键回归复验；REV-06 的 IO 失败恢复仅自动测试背书。证据见[审查文档 §11](审查文档.md)、《任务文档》§11 E153。
- **R3 收口后（2026-10-20 21:42–21:43）`test` 通过：92 套件 / 630 项 / 0 失败、错误、跳过**；337 个任务 executed（1m55s）。同一工作树补跑 `lint + assembleDebug + assembleRelease`（1m57s；14 份 debug 报告 0 issue、app release 1 条既有 `UseKtx` 警告），release APK 41,864,331 B（SHA256 `96283F97…`，装机 21:46:01）；androidTest 未跑。
- **R3（REV-05/08 + A17/A18）自动回归新增 10 套件 / 92 项**；平板 `60dd2f94` 验证 mp4v 软解保底（兼容档选定平台软解 `OMX.qti.video.decoder.mpeg4sw`，但设备解码器运行时失败、A18 不适用）、A17 重试单次闸门、HEVC 硬解主路径（`OMX.qcom.video.decoder.hevc` + `c2.android.opus.decoder`，2x 正常）；A18 自动回退未在设备触发（仅自动测试背书）。顺带修掉 R2 遗留 `SessionFactoryWiringTest` 偶发 flake（`SessionDependencies` 即时解析）。证据见[审查文档 §12](审查文档.md)、《任务文档》§11 E154。
- **R4 收口后（2026-10-20 23:20–23:21）`test` 通过：103 套件 / 707 项 / 0 失败、错误、跳过**；346 个任务 executed（2m25s）。同一工作树补跑 `lint + assembleDebug + assembleRelease`（1m3s；14 份 debug 报告 0 issue、app release 1 条既有 `UseKtx` 警告），release APK 41,864,459 B（SHA256 `08AC80FB…`，装机 23:18:10）；androidTest 未跑。
- **R4（REV-14 + A27 + A12 特效提示 + B4）自动回归新增 11 套件 / 77 项**；平板 `60dd2f94` 复核 B4 字幕背景/描边/垂直位置/最大行数、A27 字幕面板整行开关单节点语义（dump 仅 4 个整行 `checkable` 节点、行标签点按切换）、倍速六档；B3/B5/B6 受设备形态限制记未覆盖，A12 特效提示因无带 `\pos`/`\k` 的素材仅自动测试背书，删除确认 BackHandler 仅源码实现 + 1.7.6 机制核实。证据见[审查文档 §13](审查文档.md)、《任务文档》§11 E155。
- 工作树含未提交实现（含 R0/R1/R2/R3/R4）。A10/A11/A13、A12 仲裁等已能从源码确认部分落地，不继续按旧“未动”结论返工；设备和格式边界仍按具体判据保留。

## 冲突与维护规则

1. 用户最新明确裁定优先；设计规格回答“应当怎样”，源码回答“已经怎样”，差距写入任务。已退役《综合审计》的旧判定不再无条件覆盖新证据。
2. A/B/D 编号只由《任务文档》承接；《播放体验与动效审查》的两位号 A01–A50 为历史编号，跨档必须写全名。REV 是本轮依据定位号。
3. 一项至少有状态、触发、源码符号、后果、最小修法、测试和边界。源码实现、单测通过、设备验收分开记，缺任何证据就说明缺什么。
4. 不因跑测试安装/下载新依赖；先复用本机 Android Studio/JDK/SDK/Gradle 缓存。离线缺项时记录实际阻塞，先修路径，不盲改锁版。
5. 2026-10-20 的文档审查轮只改文档；随后 R0 批次改了扫描器/数据库/库页生产代码并做了真机验证，R1 批次改了会话层/引擎契约并做了真机验证，R2 批次改了持久化失败恢复/服务私有命令授权并做了真机验证，R3 批次改了探测/解码器能力判据与回退并做了真机验证，R4 批次改了公共选中/开关语义、触控槽与 Popup 键盘焦点、字幕特效提示并做了真机验证。历史 E149/E150 记录保留，不冒称 R0–R4 轮再次验证；新证据分别单列在 E151/E152/E153/E154/E155 与审查文档 §9/§10/§11/§12/§13。

截图新产物按测试日期存于 `screenshots/`。部分历史 E 档使用根目录文件名，查不到时标“历史记录、产物未核”，不把文件名当成已验证证据。不收录签名密码与私人媒体路径。

新抓图默认存于`screenshots/<EvidenceSet>/<Device>/`，旧批次直接位于日期目录的截图仍保留。

```powershell
.\docs\player-menu-regression.ps1 -Device emulator-5556
.\docs\player-menu-regression.ps1 -Device <真机序列号> -AllowPhysicalDevice -MediaUri "file:///sdcard/Movies/NonePlayerAuditB3/audit-long.mp4"
```

从仓库根目录运行，先安装本批构建并准备合成测试素材。脚本会启动测试视频并临时修改会话字幕延时，测试末尾暂停目标视频，不清除数据或修改系统显示设置；后台断言要求后台播放开关已开启。不要用私人视频替代专用素材。
