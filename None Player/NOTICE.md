# NOTICE — 第三方组件与许可证核实（D7 / E226）

> 核实日期：2026-10-25。本文件是**工程合规文档**，不构成法律意见；
> 再分发或商用前请连同下述许可证原文一起复核。

## 1. 核实结论（D7 三项）

| 组件 | 来源 | 许可证（核实方式） | 结论 |
|---|---|---|---|
| **NextLib**（`io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0`） | github.com/anilbeesetti/nextlib | **GPL-3.0**（GitHub API spdx 字段 + 仓库 LICENSE 文件） | 确认 GPL-3.0。其 `libmedia3ext.so`（扩展渲染器/解码器切换）以 GPL-3.0 发布；捆绑的 FFmpeg **共享库**（libavcodec/libavutil/libswresample/libswscale.so，FFmpeg 9.0.1，解码器清单含 `libdav1d`，未见 `--enable-gpl`）按 LGPL 分发。**分发含此依赖的 APK ⇒ 整体受 GPL-3.0 约束**（见 §3 处置） |
| **自有 vendored FFmpeg**（`core/media-android/src/main/cpp/`，FFmpeg **7.1.1**） | ffmpeg_7.1.1.orig.tar.xz | **LGPL-3.0-or-later**（构建脚本明确不启用 `--enable-gpl`、不链 xvid/libdav1d；静态 `.a` 链入 `libnplegacy.so`） | LGPL **静态链接**义务：分发时须（a）附 LGPL-3.0 文本（本仓 `LICENSES/LGPL-3.0.txt`）；（b）提供 LGPL 部分的 Corresponding Source（FFmpeg 7.1.1 源码包 + 本仓构建脚本）；（c）提供应用目标文件或等效重链接途径（`obj/` 请求即给，见 §4 联系方式占位） |
| **Media3Avi**（`com.github.dburckh:Media3Avi:2.7.1`） | github.com/dburckh/Media3Avi | **MIT**（GitHub API spdx 字段） | 确认 MIT。保留版权与许可声明即可；无专利授意条款（MIT 无显式专利授权——分发行自行承担常规专利风险，D7「专利」项见 §5） |

其余依赖（androidx.media3 1.11.1、androidx 全家、Compose、Coil 2.7.0、
Kotlin/KSP/Kotlinx-Coroutines）均为 **Apache-2.0**；`kxml2 2.3.0` 为 BSD-style
（仅 JVM 单测用）；`junit 4.13.2` 为 EPL-1.0（仅测试，不入 APK）。
逐项原文见 `LICENSES/`。

## 2. 分发包（APK）内的第三方作品清单

1. **NextLib（GPL-3.0）**：`libmedia3ext.so` + 捆绑 FFmpeg 共享库
   （libavcodec/libavutil/libswresample/libswscale，FFmpeg 9.0.1）。
2. **自有 FFmpeg 软解（LGPL-3.0）**：`libnplegacy.so` 静态链接的
   FFmpeg 7.1.1 组件（解码器/解析器/解封装器清单见
   `core/media-android/src/main/cpp/build-ffmpeg.sh`）。
3. **Media3Avi（MIT）**：AVI 解封装。
4. **Media3 / androidx / Compose / Coil（Apache-2.0）**：框架与 UI 基础库。

## 3. NextLib GPL-3.0 的两条合规路径（待项目所有者拍板）

- **路径 A（保留 NextLib）**：应用整体按 **GPL-3.0** 分发——仓库补
  GPL-3.0 `LICENSE`、源码可得性声明；分发行为受 GPL-3.0 §9 自动授权约束。
- **路径 B（移除 NextLib）**：右栏扩展渲染器/解码器切换改用自有 FFmpeg
  链（`NpFfmpegVideoRenderer` 已具备等速能力面）或平台解码——其余组件
  （LGPL 静态 FFmpeg + Apache/MIT）不强制应用整体开源，只需履行 §1(b/c)
  的 LGPL 义务。

**本仓当前动作**：两条路径的许可证原文均已就位（`LICENSES/`）；
`README.md` 的「未核实」字样由本文件替代更新。应用自身 LICENSE 文件
待拍板后落地（A = GPL-3.0 全文已备，B = 无需应用级 copyleft）。

## 4. LGPL-3.0 静态链接义务的履行清单（路径 B 亦需）

- [ ] `LICENSES/LGPL-3.0.txt` 随分发物提供（本仓已入库）；
- [ ] FFmpeg 7.1.1 源码包（`ffmpeg_7.1.1.orig.tar.xz`）与
      `build-ffmpeg.sh` 公开可得（源码包体大，不入库——发布页随发）；
- [ ] 应用目标文件（或「书面要约，三年有效」）：`obj/` 目录随发布物
      提供，或发布说明中给出索取渠道；
- [ ] 「本应用使用 FFmpeg（LGPL-3.0）」的显式署名（设置页「关于」区块，
      落点待 UI 任务）。

## 5. 专利与商标（D7 第三项）

- Media3Avi / NextLib 的上游均未授予专利许可（MIT/GPL 均无显式专利条款；
  GPL-3.0 §11 的专利授权仅在其生态内流转）；
- 软件解码 H.264/HEVC/AV1 等编解码器的**实施专利风险与分发地域、用途
  相关，超出工程核实范围**——不构成法律意见；
- 商标：应用名/图标为本项目自有；未使用 FFmpeg/Android 商标做来源暗示
  （FFmpeg 名称仅在合规署名中出现，属 nominative use）。

## 6. 变更记录

- 2026-10-25（E226）：初次核实（nextlib/Media3Avi 经 GitHub API spdx 字段；
  自有 FFmpeg 经构建脚本与源码包版本）；LICENSES/ 三份原文入库。
