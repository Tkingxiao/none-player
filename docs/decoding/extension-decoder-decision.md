# 扩展解码器（decoder_* / FFmpeg）引入决策（E44，批次 8）

> 决策：**默认不内置**。本文是按 §12.2 代价表给出的正式决策记录，"评估后出决策文档（默认不内置）"即此件。

## 结论

| 选项 | 决策 | 依据 |
| --- | --- | --- |
| media3 官方扩展（decoder_av1 / vp9 / opus / flac / midi） | **不引入** | ① 均未发布到 Google Maven（官方 README 明示，ExoPlayer issue 2781）→ 需 clone 整仓 + NDK r26b+ 自建，构建链成本永久化；② 本项目 minSdk 26 的目标设备基本都有这些编码的平台解码器，扩展只惠及极旧机型；③ 每个 ABI 一份 .so，包体收益比为负 |
| FFmpeg 扩展（音频专用 `FfmpegAudioRenderer`） | **不内置；保留为未来可选插件**（`plugin-player-ffmpeg`，EXTENSION_RENDERER_MODE_ON 排平台之后兜底） | ① 它是 §12.1 ③ 表中 AC-3/E-AC3/DTS/TrueHD/ALAC 的**唯一**正当解法（家庭影院片源"有声无画变无音"）；② 但 AC-3/E-AC3/DTS 仍在专利池——本项目（个人 / 非商用 / LGPL 路线）引入即踩合规线（P0-2 专利评估对 VC-1/WMV2 已实测不过，同类风险）；③ 项目已内置 NextLib（`FfmpegLibrary`）作为**解码查询**与若干软解通道（AV1/MPEG-1/2 视频兜底），纯音频扩展的增量收益主要剩 DTS/TrueHD 两类，属低频场景 |
| 视频软解（`decoder_ffmpeg` 的视频面 / `ExperimentalFfmpegVideoRenderer`） | **明确不做** | media3 无视频软解通道是既定事实（§12.1 ②）：官方 FFmpeg 扩展对视频 MIME 返回 `FORMAT_UNSUPPORTED_TYPE`，`ExperimentalFfmpegVideoRenderer` 源码自述 under development and not yet functional。RMVB/WMV/VC-1 的"体面失败 + 转码指引 + 外唤"路线（批次 0 已落地）继续有效 |

## 引入 FFmpeg 音频扩展时的前置清单（供未来翻案用）

1. **裁剪编解码表**：只编 AC-3/E-AC3/DTS/TrueHD/ALAC 对应解码器，`FfmpegLibrary` 的全表几十 MB 会把 APK 拖爆；
2. **按 ABI 拆分** + 16KB page 对齐（与 E36 的 AGP/NDK 版本矩阵核对，media3 扩展 1.5.0 起支持 16KB）；
3. **注册顺序**：`DefaultRenderersFactory.setEnableDecoderFallback(true)` + `EXTENSION_RENDERER_MODE_ON`（绝不 PREFER 抢硬解）——本仓库探测链已按此顺序写死（`FormatProbe` 三链一致），插件必须复用同序；
4. **法务确认**（商用前）：LGPL 动态链接合规 + AC-3/DTS 专利授权；
5. **探测一致性回归**：`FormatProbe.ffmpegSupports` 已覆盖 `FfmpegLibrary.supportsFormat`，新库换入后跑 `QpelCodecProbeTest`/`FormatProbeDecisionTest` 防止"探测说不支持、播放能解"的自相矛盾。

## 实证存档

- 构建能力证明：FFmpeg 6.0 + NDK 27.2 双 ABI 最小构建（只开 wmv2/vc1）一次走通、16KB 对齐、模拟器 `System.load` 5/5 PASS（2026-10-02，`D:\Code\Player\.audit\ffmpeg\`，gitignored）——"能不能编"不是问题，"该不该进库"才是决策点；
- 专利红线：VC-1/WMV2 在 MPEG LA 池，P0-2 评估 🟠 不通过（§0 已记账）。
