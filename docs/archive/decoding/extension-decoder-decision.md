# 解码链与扩展依赖记录

源码核对：2026-10-20，`060f3f4` + 当前工作树。本文下方 2026-10-14 的设备/ABI/体积记录是历史结果，本轮没有重跑 native 或设备测试；最新测试范围见[审查附录 §2](../项目审查与优化路线-2026-10-20.md#2-本轮测试基线)。

## 当前实现

依据版本目录、NpRenderersFactory、PlaybackService、FormatProbe：

1. Media3 `1.11.1` 平台MediaCodec。
2. NextLib `1.11.1-0.16.0` 的NextRenderersFactory，包含FFmpeg音视频扩展，不只是查询。
3. Media3Avi `2.7.1` AVI extractor与末端BitmapFactoryVideoRenderer（MJPEG）。
4. 自有 `libnplegacy` / `LegacyVideoRenderer`，仅认领 `video/x-np-wmv2` 与 `video/x-np-mpeg4-asp` 等专用路径；不无条件接管普通 `video/mp4v-es`。

**当前两档扩展模式均为 ON，不再是“开启 ON、关闭 PREFER”**。关闭硬件优先时，`NpRenderersFactory` 的视频选择器在 API 29+ 只保留硬件平台 codec；API 29 以下保留默认候选。该策略在新服务播放器构建时生效，不是运行期热切换。当前仍缺“只有平台软解且扩展不支持”时的保底，不能把这次源码改动解释成所有格式的兼容性已提高。
setEnableDecoderFallback不保证平台认领但初始化失败时跨任意软解渲染器。
探测与播放使用同源 `NpExtractorsFactory`，查询平台/NextLib/MJPEG/legacy；“extractor 同源”不等于候选策略、profile 与运行期能力全部相同，声明支持不等于首帧实测。

### 当前缺口与实现顺序

| 问题 | 最小路径 | 验收 |
| --- | --- | --- |
| 探测包含平台软解，兼容档却可能全部过滤 | 统一能力快照；扩展确定支持才替代平台软解，保留最终保底（REV-08 / A10） | 硬解、仅平台软解、仅扩展、均不支持、查询异常五组，设备记录实际 decoder |
| profile 信息与实际选中 codec 不一致 | A17 的 profile/level 求交及 A18 一次性运行期回退分开实施；不复活 DecoderManager | Simple 不误抢、ASP/QPel 走正确链；失败次数有界且错误不被 Idle 冲掉 |
| 探测/ASP 改写首样本仍按 length 整段分配 | 分块读取、有界保留头、其余丢弃或原序转发（REV-05 / A20） | 大 length/短输入、EOF、分段样本，输出字节/元数据一致，缓冲有界 |
| WMV2 的 PCM/WMA 映射已补但未证明输出 | 保留 `wmvAudioMimeForTag`、blockAlign 和 `audioUnsupported` 提示；补 WAVEFORMAT/extradata/位深夹具 | 本轮映射 5 项通过；PCM 含浮点、WMA 家族、静音提示与音画同步另测，不把映射当解码器 |
| 仪器素材不随干净检出提供 | 先清点本机素材/校验和，复用既有依赖，编译与执行分开（REV-15） | JVM test 不代替 native-load、ImageReader 出帧、连续切换；缺样本明确 blocked |

方案详见[审查附录](../项目审查与优化路线-2026-10-20.md)；以上均为后续路线，不是本轮修改了生产实现。

## 边界

- 容器、编码、profile/level及设备输出分别验证，不能只按扩展名称“全支持”。
- NextLib编解码表和ABI以实际AAR/native构建为准，不根据FFmpeg总体能力承诺。
- WMV2 与专用 ASP 已有 legacy 路径，不再泛称“没有软解”；VC-1/ASF/其他 WMV 不能由名字推断支持。每种码流分别验证首帧、seek、同步及失败恢复。
- bitmap字幕与ASS富文本分别检查解码出口/渲染契约，不泛称PGS/VobSub不支持。
- 未依赖HLS/DASH/RTSP source模块，不声明网络协议支持。

## 合规待核

NextLib依赖标注GPL-3.0，FFmpeg构建许可证及链接/分发义务须核对。
商用、源代码义务、专利区域与时点需专业评估，本文不作法律认证。
旧“专利红线”“仅旧机受益”推断删除，它们不能由代码或模拟器推出。
依赖升级同步核对版本/ABI/16KB/API及许可材料。

## 验收

FormatProbeDecisionTest、native-load插桩及格式矩阵，存probe/首帧/seek/音轨/失败恢复证据。
模拟器不替代真机HDR/直通/温控/专有解码器验收。

## 2026-10-14 WMV2 / Xvid-Qpel

步骤 1 在 Pixel_10_Pro（`sdk_gphone16k_x86_64`，API 37，16KB 页）上、与播放同源的 `FormatProbe`：

| 文件 | 解封装 | 视频轨 / mime | FormatProbe | FfmpegLibrary | 设备 mp4v |
| --- | --- | --- | --- | --- | --- |
| `np-wmv2.avi` | `AviExtractor`，log 是 `Unknown FourCC: WMV2` 然后 `No video track found`，不是 `Missing Stream Format` | 无视频轨，无 mime | `unknownVideoCodec` | 未调用 | 不适用 |
| `xvid-qpel.avi` | `AviExtractor` | 有轨，`video/mp4v-es` + `audio/mpeg` | `videoProfileUnsupported`（Advanced Simple Profile / 仅 Simple Profile） | `video/mp4v-es` 不在 `getCodecName` 表内，so 已加载，返回 false | `c2.android.mpeg4.decoder` 与别名 `OMX.google.mpeg4.decoder`，只有 Simple/L6，列表非空 |
| `xvid-clip.avi` | 同上 | `video/mp4v-es` + `audio/mpeg` | `Playable`，`Mpeg4ProfileParser` 为 SIMPLE | 同上，不认 mp4v | 同上 |
| `np-mjpeg.avi` | 同上 | `video/mjpeg` | `Playable`，`softwareVideoOnly` | 不认 mjpeg | 不适用 |

因此走「补 WMV2 轨」加「专用 mime + 软解渲染器」。没有改 `MediaCodecSelector`，没有让新渲染器认领 `video/mp4v-es` 或 `video/mjpeg`。

新增解码器只有 FFmpeg 自带的 `wmv2` 和 `mpeg4`（含 `mpeg4video` / `mpegvideo` parser）。configure 没有 `--enable-gpl`，`CONFIG_GPL=0`，`CONFIG_LIBXVID=0`，`FFMPEG_LICENSE` 为 `LGPL version 2.1 or later`。没有链接 libxvid，没有编 avformat。SONAME 是 `libnplegacy.so`，`-fvisibility=hidden` 加 version script，动态符号只有 8 个 JNI，没有 `av_*`。16KB 对齐：`LOAD` 的 Align 是 `0x4000`。加载失败时 `LegacyDecoderLibrary.supports` 返回 false，探测保持不可播，构造渲染器不 `System.load`。

剥离后的 so：arm64-v8a 570400、armeabi-v7a 492560、x86 602152、x86_64 615512，合计约 2.17MB。低于原先 3MB 停手线；按后续口径，只要安装包远离 100MB 即可，这里没有接近。

本文只记录构建开关和符号，不是法律审查。NextLib 自己的 GPL-3.0 标注没有在这次改动里处理。

模拟器验收（硬件优先 ON）：

- `FormatProbeDecisionTest`、`Mpeg4ProfileProbeTest` 通过。qpel 解析仍是 `ADVANCED_SIMPLE`，clip 仍不是 ASP。qpel 探测改为可播，显示名 `MPEG-4 ASP (Qpel)`；clip 仍是 `video/mp4v-es`。
- `LegacyPlaybackTest` 通过：`np-wmv2.avi` 解码器名 `nplegacy-wmv2` 且 ImageReader 收到帧；`xvid-qpel.avi` 为 `nplegacy-mpeg4`，音频 `audio/mpeg`；`xvid-clip.avi` 为 `c2.android.mpeg4.decoder`，不是 nplegacy；`np-mjpeg.avi` 为 `BitmapFactory`。`NpRenderersFactory.buildVideoRenderers` 连续两次不抛。
- `QpelCodecProbeTest` 仍只做平台解码器诊断，这次没有改成播放成功断言，也没有重跑那个最长的逐解码器试探。
- `FfmpegNativeLoadTest` 依赖仓库里没有的 `.audit` so，不算这次播放验收。
- 模拟器出画不等于真机 HDR、直通或温控验收。

## 2026-10-14 修复轮（应用内实测复现）

上一轮交付后在 Pixel_10_Pro（`sdk_gphone16k_x86_64`，API 37）上用应用实测复现两个问题：切集/连播到平台流报 `ERROR_CODE_DECODER_INIT_FAILED`；`np-wmv2.avi` 花屏。

### 根因 1：WMV2 花屏 —— extradata 被 biSize 口径吞掉

- FFmpeg 的 `wmv2` 解码器 `decode_ext_header`（wmv2dec.c）用 extradata 头 4 字节裁定 `j_type/abt_flag/mspel/per_mb_rl` 等标志位与 `slice_height`；avidec 的切分口径是 **strf 总长 − 40**（BITMAPINFOHEADER），与 biSize 字段无关。
- 样本 `np-wmv2.avi` 的 strf=44 且 biSize=44：旧的 `biSize in 40 until strf.size` 判断把 4 字节 extradata 当成了头部的一部分丢弃 → 解码器标志位全零、宏块脱轨（花屏）。
- 修复：`Wmv2AviExtractor.parseStrl` 按 `strf[40..]` 切 extradata（保留 avidec 的 `esize == size-1 && 奇数` 补偿分支）。
- 证据：设备日志从 `emit 320x240 extra=0` 变为 `extra=4`（与 `ffprobe extradata_size=4` 一致）；参考帧（离线 ffmpeg 解码）为彩条测试图；`Wmv2PictureTest` 断言 extradata=4 且六段纯色抽样通过；应用内整链出画无花屏。

### 根因 2：切集后平台流起不来 —— CPU 锁定连接滞留

- CPU 渲染（`ANativeWindow_lock` / `lockCanvas`）把共享 BufferQueue 以 `NATIVE_WINDOW_API_CPU` 连上；该连接只随**执行连接的 Surface 对象被销毁**而断开（`~Surface` 里 disconnect；NDK 无公开 disconnect API）。
- 给定 Surface 由播放器持有、不会销毁 → 下一条平台流 `MediaCodec.configure` 报 `connect: already connected (cur=2 req=3)` → `ERROR_CODE_DECODER_INIT_FAILED`（H264 / mp4v 均可复现）。
- 实验结论（临时用例，已删）：同一 Surface 上 Java lockCanvas 同样留下该连接；MediaCodec 释放会正确断开自己的 api；"Parcel 往返克隆 Surface → 在克隆上锁定 → 释放克隆"可确定性归还连接。
- 修复：`OwnedSurfaceCloner`（克隆由渲染器自己持有，CPU 渲染走克隆，解码器释放/`onDisabled`/`onRelease` 时释放克隆）接入 `LegacyVideoRenderer` 与新增的 `NpMjpegVideoRenderer`（包住 Media3Avi 位图渲染器——它同样直接 lockCanvas，同一隐患）。克隆失败退回直渲（功能优先）。
- 证据：`LegacyPlaybackTest` 的 H264↔WMV2、H264↔MJPEG 切换用例修复前失败（`ERROR_CODE_DECODER_INIT_FAILED`）、修复后通过；应用内 np-h264 → np-mjpeg → np-mpeg2(FFmpeg 软解) → np-wmv2 整链连播无错误面板。

### 其它修复

- 竖屏错误面板按钮被截断：`ErrorPanel` 操作行 `Row` → `FlowRow` 折行（"返回媒体库"不再被圆角裁掉）。
- 运行期错误不再复用过期探测名：`probedUri` 与 `currentMediaUri` 不一致时失败文案不显示容器/编码名（此前切集后报错会把上一条的"视频: WMV2"印在新条目上）。
- 测试修正：`Wmv2PictureTest`（加载 native、DataSource 重开取随机访问、extradata=4、彩条断言）；`wmv2PictureSample` 抽帧保存竞态（先判帧序再 CAS）与端到端彩条断言。

### 已知未处理

- 模拟器上 1080p60 HEVC 经 NextLib `FfmpegVideoRenderer` 渲染到 TextureView 出现过一次 `dequeueBuffer failed (Connection timed out)`（应用启动后 SurfaceTexture 复建期，路径不经过本轮改动），未稳定复现，记录备查。
