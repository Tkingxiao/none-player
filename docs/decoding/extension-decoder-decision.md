# 解码链与扩展依赖记录

核对：2026-10-12。修正旧稿“默认不内置FFmpeg”“无视频软解”的过时结论。

## 当前实现

依据版本目录、NpRenderersFactory、PlaybackService、FormatProbe：

1. Media3 `1.11.1` 平台MediaCodec。
2. NextLib `1.11.1-0.16.0` 的NextRenderersFactory，包含FFmpeg音视频扩展，不只是查询。
3. Media3Avi `2.7.1` AVI extractor与末端BitmapFactoryVideoRenderer（MJPEG）。

硬件优先开启为ON，关闭为PREFER；影响新服务播放器构建，不是运行期热切换。
setEnableDecoderFallback不保证平台认领但初始化失败时跨任意软解渲染器。
探测与播放使用同源extractor并查平台/FFmpeg/MJPEG能力，声明支持不等于首帧实测。

## 边界

- 容器、编码、profile/level及设备输出分别验证，不能只按扩展名称“全支持”。
- NextLib编解码表和ABI以实际AAR/native构建为准，不根据FFmpeg总体能力承诺。
- WMV/VC1/ASP不能推断有软解兜底，需验证结构化失败及恢复。
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
