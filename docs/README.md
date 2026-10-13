# 文档导航

更新：2026-10-12。

| 文档 | 职责 |
| --- | --- |
| `播放体验与动效审查.md` | 当前全项目审计、bug list状态、优化路线与执行档案 |
| `任务文档.md` | 长期待办及历史交付索引，当前状态以本轮审计为准 |
| `审查文档.md` | 历史验收证据、测量纪律；数字不可当本轮复验 |
| `设计文档.md` | 视觉/组件/动效规格，最新用户需求优先于旧规格 |
| `decoding/extension-decoder-decision.md` | 实际解码链及依赖合规待核 |
| `buglist-device-test.ps1` | 默认模拟器抓图/点按；显式`-AllowPhysicalDevice`允许已授权真机，不清除数据 |
| `audit-device-matrix.ps1` | 窗口尺寸模拟，恢复设置，不等于真实铰链 |
| `playback-regression.ps1` | 后台无自动PiP、缺失文件→正常文件恢复、退播放页回桌面回归 |
| `player-menu-regression.ps1` | B3菜单目标页、跨形态字幕延时、播放中关闭、实际上滑/后台回归；真机只允许专用测试素材 |

截图按测试日期存于 `screenshots/`。不引用已缺失的旧截图，不收录签名密码与私人媒体路径。

新抓图默认存于`screenshots/<EvidenceSet>/<Device>/`，旧批次直接位于日期目录的截图仍保留。

```powershell
.\docs\player-menu-regression.ps1 -Device emulator-5556
.\docs\player-menu-regression.ps1 -Device <真机序列号> -AllowPhysicalDevice -MediaUri "file:///sdcard/Movies/NonePlayerAuditB3/audit-long.mp4"
```

从仓库根目录运行，先安装本批构建并准备合成测试素材。脚本会启动测试视频并临时修改会话字幕延时，测试末尾暂停目标视频，不清除数据或修改系统显示设置；后台断言要求后台播放开关已开启。不要用私人视频替代专用素材。
