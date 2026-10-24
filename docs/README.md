# 文档导航

本目录只保留三份任务文档、一份设计规格和配套证据/脚本。

| 文档 | 职责 |
| --- | --- |
| [任务文档.md](任务文档.md) | **未完成任务**：未实现、待补、待测、待取证、待裁定。编解码、格式拓展与转码任务在最后一节 |
| [完成文档.md](完成文档.md) | **已完成且审查/验收通过**的任务 |
| [审查文档.md](审查文档.md) | **已完成但尚未审查/验收**的任务：写明已完成什么、还缺哪次证据；需外接设备/人工测量的条目在文末 §7 封存 |
| [设计文档.md](设计文档.md) | 视觉/组件/动效规格；用户最新裁定优先于旧规格 |
| [archive/](archive/) | 历史文档归档，仅作查阅，不再维护 |
| [decoding/snapshots/](decoding/snapshots/) | 两机编解码能力快照（解码链决策与素材清单已归档到 archive/decoding/） |
| [../screenshots/](../screenshots/) | 证据截图，按 `<证据集>/<设备>/` 存放在**仓库根**（不在本目录，已被 `.gitignore` 排除） |
| `*-regression.ps1`、`audit-device-matrix.ps1`、`buglist-device-test.ps1`、`verify-instrumentation-assets.ps1` | 回归与取证脚本 |

## 证据口径

- 源码实现、单测通过、设备验收分开记；缺哪一层就写明缺哪一层。
- 起播注入：`noneplayer://player?uri=…&title=…&mime=…&id=…&fs=1`。**uri 优先用 `content://media/external/video/media/<_id>` 而不是 `file://`**——长中文路径带括号时 file URI 编码极易出错，`content://` 直接可播（`_id` 用 `content query --uri content://media/external/video/media --projection _id:_display_name --where "_display_name LIKE '%xxx%'"` 取）。
- 合成素材：本机无独立 ffmpeg，但 `python -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())"` 能取出随包 ffmpeg 7.1，可直接合成 HEVC Main10 / AVC High10 / VP9 Profile2 / AV1 10bit / MPEG-1 等夹具补「素材欠账」（`-pix_fmt yuv420p10le`，AV1 也用 10le 不是 10p）。
- 设备基线：骁龙平板 `60dd2f94`（Android 14 / SDK 34，显示 1800×2880 / 400dpi / 144Hz）。**天玑机按裁定不再补测**（2026-10-23），欠账口径里不再把「缺天玑」计为缺口。
- 动效采样仪器：`screenrecord --bit-rate 24000000` 出片平均 **41.5fps**，配 `imageio-ffmpeg` 自带的 ffmpeg 抽帧，足够穿过 560ms 级动画；`screencap` 连拍实测 1586ms/帧，采不到短动画。`settings put global animator_duration_scale` **对本应用动效无效**（`npDuration()` 只走 `npQuality().scaled()`，只缩不放）。
- HyperOS 转屏注入：`settings put system user_rotation` **间歇不生效**，实测需先把 `accelerometer_rotation` 写 1 再写 0 重新断言锁；且 `screenrecord` 占着 shell 时多次失败。
- 验证机：Pixel_Tablet 模拟器（2560×1600 / density 320 / Android 15）；`adb` 位于 `%LOCALAPPDATA%\Android\Sdk\platform-tools`。
- 截图坐标：`screencap` 读图坐标 ≠ 设备坐标，按缩样比例还原；点按坐标以 `uiautomator dump` 的 bounds 为准。

## 门禁与体积

- 完整门禁：`assembleDebug` + `test` + `assembleRelease`（R8 + shrinkResources 全开）+ 全模块 lint。
- 当前读数：test 142 套件 / 1009 项 / 0 失败；lint 15 份 0 issue；release 29,190,315 B；debug 66,222,424 B。
- 体积上限 100MB（104,857,600 B）；单轮增量 >0.5MB 需写明来源但不判红。
- 门禁命令不得 `| head`（SIGPIPE 会顶替退出码）；`classes.jar` 文件锁/kspCaches 冲突 → `gradlew --stop` 后重跑；签名配置 `local.properties` 可选，未配齐回落 debug 签名并打日志。
- 不因跑测试安装/下载新依赖；优先复用本机 SDK/Gradle 缓存，离线缺项记录阻塞原因。

## 脚本用法

从仓库根目录运行，先安装本批构建并准备专用测试素材：

```powershell
.\docs\player-menu-regression.ps1 -Device emulator-5556
.\docs\player-menu-regression.ps1 -Device <真机序列号> -AllowPhysicalDevice -MediaUri "file:///sdcard/Movies/NonePlayerAuditB3/audit-long.mp4"
```

脚本会启动测试视频并临时修改会话字幕延时，结束后暂停目标视频，不清除数据、不修改系统显示设置。不要用私人视频替代专用素材；不收录签名密码与私人媒体路径。

