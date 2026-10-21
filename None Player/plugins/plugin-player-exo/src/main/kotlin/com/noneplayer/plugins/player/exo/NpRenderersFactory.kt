package com.noneplayer.plugins.player.exo

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.noneplayer.core.media.probe.PlatformDecoderSupport
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
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
 *
 * **兼容档（A10 + REV-08）**：设置关掉「硬件解码优先」时不再把全局模式翻成 `PREFER`——
 * 那会让 H.264/HEVC 这些有硬解的编码也先走 FFmpeg。改为在视频选择器里**按轨过滤**：
 * 平台有硬解就仍走硬解；没有硬解时看扩展能不能接手（REV-08：探测与播放共用同一份
 * 能力快照），扩展能解才让平台不认领、落到后面的 FFmpeg / MJPEG / legacy，扩展接不了
 * 则保留平台软解保底。音频选择器保持默认。
 */
@OptIn(UnstableApi::class)
open class NpRenderersFactory(
    context: Context,
    /** true = 兼容档：平台只有软解时才让扩展渲染器接手（逐轨判定，不是全局反转） */
    private val preferSoftwareWhenNoHardware: Boolean = false,
) : NextRenderersFactory(context) {

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
        // A18：在兼容档选择器外面再套一层解码器回退过滤（排除失败 codec / mp4v 只走软解）。
        // 未武装回退状态时零开销直通，不改变 REV-08 的矩阵语义。
        val baseSelector = if (preferSoftwareWhenNoHardware) HardwareOnlyVideoCodecSelector else mediaCodecSelector
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            DecoderFallbackAwareSelector(baseSelector),
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        // A19：NextLib 默认用 availableProcessors() 做 FFmpeg 线程数，8 核上软解 HEVC
        // 会吃满大核。替换成 min(4, max(2, 核数/2))，不打开 DecoderManager。
        val ffmpegThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        for (index in out.indices) {
            if (out[index] is FfmpegVideoRenderer) {
                out[index] = FfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                    ffmpegThreads,
                    /* numInputBuffers= */ 4,
                    /* numOutputBuffers= */ 4,
                )
            }
        }
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

/**
 * 兼容档的视频解码器选择器（A10 + REV-08）。
 *
 * 逐 Format 生效：平台有硬解就只留硬解（照旧硬解优先）；只有平台软解时按 REV-08 的
 * 能力快照决定——扩展确定可解才让平台不认领（把候选过滤空，落到 FFmpeg / MJPEG / legacy），
 * 扩展接不了则**保留平台软解**，绝不把唯一可用的解码器过滤掉。
 *
 * 决策规则唯一来源是 [PlatformDecoderSupport.decideVideoDecoderPolicy]，本选择器只负责
 * 取候选与落到 `MediaCodecInfo` 上；API 29 以下拿不到硬/软标记，原样返回（旧行为）。
 */
@OptIn(UnstableApi::class)
private object HardwareOnlyVideoCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        // API 29 以下拿不到 isHardwareAccelerated：保持旧行为，不判软解优先。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return infos
        // 能力查询异常时按 KEEP_UNKNOWN：保留全部平台候选（不判「不支持」、不返回空）。
        val capability = runCatching {
            PlatformDecoderSupport.videoDecoderCapability(mimeType)
        }.getOrElse {
            PlatformDecoderSupport.DecoderCapability(
                hardwareAvailable = false,
                softwareAvailable = false,
                extensionHandles = false,
                queryFailed = true,
            )
        }
        return npKeptCompatDecoderIndices(infos.map { it.hardwareAccelerated }, capability)
            .map { infos[it] }
    }
}

/**
 * 兼容档选择器的纯决策桥（REV-08）：能力快照 + 候选硬解标记 → 应保留的候选下标。
 *
 * 抽成顶层纯函数是为了让五组能力矩阵能在纯 JVM 里测（`MediaCodecInfo` 不可实例化）：
 * - 平台有硬解 → 只留硬解下标；
 * - 仅平台软解、扩展接不了 → 全留（软解保底，非空）；
 * - 仅扩展可解 → 过滤成空（平台不认领，交给扩展渲染器）；
 * - 全部不支持 → 空；
 * - 查询异常 → 全留（KEEP_UNKNOWN）。
 */
@OptIn(UnstableApi::class)
internal fun npKeptCompatDecoderIndices(
    hardwareFlags: List<Boolean>,
    capability: PlatformDecoderSupport.DecoderCapability,
): List<Int> {
    val policy = PlatformDecoderSupport.decideVideoDecoderPolicy(capability)
    return PlatformDecoderSupport.applyVideoDecoderPolicy(
        policy,
        hardwareFlags.indices.toList(),
    ) { index -> hardwareFlags[index] }
}

/**
 * A18：解码器回退过滤包装。
 *
 * `ExoVideoPlayer` 在 `onPlayerError` 里判定视频解码失败后，把「排除该 codec」或
 * 「该 mime 只走软解链」写进进程级 [decoderFallbackState]，然后重新 prepare 同一条媒体。
 * 服务进程里的播放器每次 prepare 都会重新查询解码器候选，所以这里能读到新状态——
 * 不需要重建 ExoPlayer，也不需要给服务端加自定义命令。
 *
 * 未武装时**零开销直通**（原样返回 delegate 的列表），平时行为与 A18 之前完全一致；
 * 只是在兼容档选择器外面多一次 `isArmed` 判断。
 */
@OptIn(UnstableApi::class)
internal class DecoderFallbackAwareSelector(
    private val delegate: MediaCodecSelector,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val state = decoderFallbackState
        if (!state.isArmed) return infos
        val keptNames = state.filterCandidates(mimeType, infos.map { it.name }).toSet()
        return if (keptNames.size == infos.size) infos else infos.filter { it.name in keptNames }
    }
}
