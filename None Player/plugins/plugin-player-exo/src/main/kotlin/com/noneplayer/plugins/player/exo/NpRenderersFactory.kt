package com.noneplayer.plugins.player.exo

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 播放链的渲染器工厂：平台硬解 → FFmpeg 软解 → MJPEG 位图软解。
 *
 * 三段来源各不相同，必须拼在一条链上：
 *
 * 1. `DefaultRenderersFactory` 的平台 MediaCodec 渲染器（硬解优先，由 NextLib 的
 *    `EXTENSION_RENDERER_MODE_ON` 语义决定"平台不支持才落到扩展"）；
 * 2. [NextRenderersFactory] 追加的 `FfmpegVideoRenderer` / `FfmpegAudioRenderer`
 *    （NextLib 内置的 FFmpeg：视频 h264/hevc/vp8/vp9/av1(libdav1d)/mpeg1/mpeg2，
 *    音频 aac/mp3/ac3/eac3/dts(truehd,dca)/flac/alac/opus/vorbis/amr/pcm 等）；
 * 3. [NpMjpegVideoRenderer]（Media3Avi `BitmapFactoryVideoRenderer` 的包装）——AVI 里常见的
 *    MJPEG 流既无平台解码器、也不在 FFmpeg 的编解码表里（见 `FfmpegLibrary.getCodecName`，
 *    无 `video/mjpeg`），只能靠这条纯 Java（BitmapFactory）路径出画。
 *
 * ⚠ 这里**不能**改用 NextLib 的 `DecoderManager`：它要求"一个工厂只产出一次渲染器集合"
 * （`DecoderRendererController.wrapRenderers` 内有 `check(renderers == null)`），
 * 而 `DefaultPreloadManager` 会先用同一个工厂做一次能力枚举
 * （`DefaultRendererCapabilitiesList.Factory.createRendererCapabilitiesList`）、
 * 再由 ExoPlayer 构建时调一次，同一实例必然被调两次 → 服务启动即崩。
 * 因此本项目只用 `EXTENSION_RENDERER_MODE_ON` 这一层"硬解优先 + 软解兜底"，
 * 不做运行期解码器切换。
 */
@OptIn(UnstableApi::class)
open class NpRenderersFactory(context: Context) : NextRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        // 追加在最后：前面的渲染器都不认领 MJPEG（平台无此解码器，FFmpeg 也不含该编码），
        // 这条才会被选中。取值与 Media3Avi 的 MjpegRenderersFactory 一致；
        // 用 NpMjpegVideoRenderer 包装是为了换集时归还 Surface 的 CPU 连接（见该类注释）。
        out.add(
            NpMjpegVideoRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
        // 追加在平台和 FFmpeg 之后，认领范围只有 WMV2 / 专用 ASP mime，碰不到 mp4v-es 和 mjpeg。
        // 构造两次（预载枚举 + 正式建播放器）不得抛；native 留到 supportsFormat。
        out.add(
            LegacyVideoRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
    }

    private companion object {
        /** 与 `MjpegRenderersFactory` 相同：掉帧计数通知阈值 */
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}
