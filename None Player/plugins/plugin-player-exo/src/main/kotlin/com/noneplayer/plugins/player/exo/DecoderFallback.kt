package com.noneplayer.plugins.player.exo

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.noneplayer.core.media.probe.Mp4vAspGate

/**
 * A18：解码器初始化失败的一次性受控回退（客户端侧纯内核）。
 *
 * 背景：播放器在服务进程里，客户端只有 [androidx.media3.session.MediaController]，
 * 拿不到 `MediaCodecRenderer.DecoderInitializationException` 的类型信息——跨 binder 后
 * media3 只保留了 `PlaybackException.message`（见 `PlaybackException.toBundle`）。
 * 所以这里只能从 message 里**容错**解析失败的 mime 与平台 codec 名。
 *
 * 回退动作本身靠两条通道落地（均不需要改服务端契约）：
 * 1. [MP4V_VIDEO_MIME] 走软解链：过滤掉平台解码器，让 `NpRenderersFactory` 里挂的
 *    nextlib / WMV 专用 renderer 接管（README 记录过 mp4v 走专用 mime 的先例）；
 * 2. 其余视频 codec：按名字排除失败的平台 codec，交给 `setEnableDecoderFallback(true)`
 *    的候选列表里的下一个 codec。
 */

/** `"video/mp4v-es"`：专用 renderer 链路的触发 mime。 */
internal const val MP4V_VIDEO_MIME: String = MimeTypes.VIDEO_MP4V

/** 从 [PlaybackException.message] 解析出的失败轨信息；解析不出时为 null 字段。 */
internal data class ParsedDecoderFailure(
    val mime: String?,
    val codecName: String?,
    /** mime 缺失时，用 renderer 名补足音视频轨判定。 */
    val rendererTrack: DecoderRendererTrack = DecoderRendererTrack.UNKNOWN,
)

/** media3 错误消息里可确认的 renderer 轨道。 */
internal enum class DecoderRendererTrack {
    VIDEO,
    AUDIO,
    UNKNOWN,
}

/** 解码失败的归类：视频初始化、能力不足和运行期错误允许触发一次回退。 */
internal enum class DecoderFailureKind {
    VIDEO_DECODER_INIT,
    VIDEO_EXCEEDS_CAPABILITIES,
    VIDEO_DECODING_RUNTIME,
    AUDIO_DECODER_INIT,
    AUDIO_EXCEEDS_CAPABILITIES,
    AUDIO_DECODING_RUNTIME,
    OTHER,
}

/** 回退计划。 */
internal enum class DecoderFallbackAction {
    /** 排除失败的平台 codec 后重试同一条媒体。 */
    RETRY_WITHOUT_FAILED_CODEC,

    /** 强制走软解链（nextlib / 专用 renderer）后重试同一条媒体。 */
    RETRY_WITH_SOFT_ONLY,

    /** 不做回退，直接落错误面板。 */
    GIVE_UP,
}

/** 回退计划：携带过滤包装需要的 mime / codec 名。 */
internal data class DecoderFallbackPlan(
    val action: DecoderFallbackAction,
    val mime: String?,
    val codecName: String?,
)

// media3 的 message 形如：
//   "Decoder init failed: OMX.qcom.video.decoder.avc, Format(1, video/avc, null, -1, null)"
//   "Decoder init failed: [-10000], Format(1, video/mp4v-es, null, -1, null)"（无可用解码器）
private val DECODER_INIT_PREFIX = "Decoder init failed: "
private val TRACK_MIME_PATTERN = Regex("""\b(video|audio)/[A-Za-z0-9.+\-]+""")
private val VIDEO_RENDERER_MARKER = "MediaCodecVideoRenderer"
private val AUDIO_RENDERER_MARKER = "MediaCodecAudioRenderer"

/**
 * 从错误 message 里容错解析失败轨的 mime 与平台 codec 名。
 *
 * 任何输入（null / 空串 / 无关文本 / 被截断的 message）都必须正常返回，绝不抛异常；
 * 只有可确认的轨道才允许按运行期失败做回退，不做更激进的误判。
 */
internal fun parseDecoderFailureMessage(message: String?): ParsedDecoderFailure {
    if (message.isNullOrBlank()) return ParsedDecoderFailure(mime = null, codecName = null)
    val mime = TRACK_MIME_PATTERN.find(message)?.value
    val codecName = if (message.startsWith(DECODER_INIT_PREFIX)) {
        message.removePrefix(DECODER_INIT_PREFIX)
            .substringBefore(',')
            .trim()
            // 无可用解码器时这里是 "[<errorCode>"，不是 codec 名
            .takeIf { it.isNotEmpty() && !it.startsWith("[") }
    } else {
        null
    }
    val rendererTrack = when {
        mime?.startsWith("video/") == true || VIDEO_RENDERER_MARKER in message ->
            DecoderRendererTrack.VIDEO
        mime?.startsWith("audio/") == true || AUDIO_RENDERER_MARKER in message ->
            DecoderRendererTrack.AUDIO
        else -> DecoderRendererTrack.UNKNOWN
    }
    return ParsedDecoderFailure(mime = mime, codecName = codecName, rendererTrack = rendererTrack)
}

/** 是否属于 media3 的解码器家族错误（决定要不要走 A18 的错误面板归类）。 */
internal fun isDecoderFailureCode(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> true

    else -> false
}

/**
 * 归类解码失败。运行期 `DECODING_FAILED` 只有在 message/mime 能确认是视频轨时
 * 才进入 E241 回退；未知轨道保持 [DecoderFailureKind.OTHER]，避免把音频故障误送进视频链。
 *
 * 初始化错误解析不出 mime 时沿用旧的保守视频归类；运行期错误则必须有视频 mime
 * 或 `MediaCodecVideoRenderer` 标记，未知轨道不回退。
 */
internal fun classifyDecoderFailure(errorCode: Int, parsed: ParsedDecoderFailure): DecoderFailureKind {
    val audio = parsed.mime?.startsWith("audio/") == true ||
        parsed.rendererTrack == DecoderRendererTrack.AUDIO
    val video = parsed.mime?.startsWith("video/") == true ||
        parsed.rendererTrack == DecoderRendererTrack.VIDEO
    return when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            if (audio) DecoderFailureKind.AUDIO_DECODER_INIT else DecoderFailureKind.VIDEO_DECODER_INIT

        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            if (audio) {
                DecoderFailureKind.AUDIO_EXCEEDS_CAPABILITIES
            } else {
                DecoderFailureKind.VIDEO_EXCEEDS_CAPABILITIES
            }

        PlaybackException.ERROR_CODE_DECODING_FAILED -> when {
            audio -> DecoderFailureKind.AUDIO_DECODING_RUNTIME
            video -> DecoderFailureKind.VIDEO_DECODING_RUNTIME
            else -> DecoderFailureKind.OTHER
        }

        else -> DecoderFailureKind.OTHER
    }
}

/**
 * 错误面板是否应显示"音频不可解"。
 *
 * `onPlayerError` 里 `DECODER_INIT_FAILED` 既可能来自视频轨也可能来自音频轨：
 * 音频 track 的失败被映射成 `videoCodecUnsupported` 会给出错误的转码建议。
 */
internal fun isAudioDecoderFailure(kind: DecoderFailureKind, mime: String?): Boolean =
    kind == DecoderFailureKind.AUDIO_DECODER_INIT ||
        kind == DecoderFailureKind.AUDIO_EXCEEDS_CAPABILITIES ||
        kind == DecoderFailureKind.AUDIO_DECODING_RUNTIME ||
        mime?.startsWith("audio/") == true

/** 是否值得做一次回退：视频解码失败且本次媒体还没回退过。 */
internal fun shouldRetryDecoderFallback(
    kind: DecoderFailureKind,
    alreadyRetriedThisMedia: Boolean,
): Boolean {
    if (alreadyRetriedThisMedia) return false
    return kind == DecoderFailureKind.VIDEO_DECODER_INIT ||
        kind == DecoderFailureKind.VIDEO_EXCEEDS_CAPABILITIES ||
        kind == DecoderFailureKind.VIDEO_DECODING_RUNTIME
}

/** 根据归类决定回退动作；[MP4V_VIDEO_MIME] 一律走软解链。 */
internal fun fallbackPlan(kind: DecoderFailureKind, parsed: ParsedDecoderFailure): DecoderFallbackPlan {
    val retryable = shouldRetryDecoderFallback(kind, alreadyRetriedThisMedia = false)
    if (!retryable) {
        return DecoderFallbackPlan(DecoderFallbackAction.GIVE_UP, parsed.mime, parsed.codecName)
    }
    val action = if (parsed.mime == MP4V_VIDEO_MIME) {
        DecoderFallbackAction.RETRY_WITH_SOFT_ONLY
    } else {
        DecoderFallbackAction.RETRY_WITHOUT_FAILED_CODEC
    }
    return DecoderFallbackPlan(action, parsed.mime, parsed.codecName)
}

/**
 * "同一媒体只回退一次"的闸门。
 *
 * 媒体键参与校验：即使引擎忘了在切集时复位，换了媒体键也不会被上一条的
 * 回退记录挡住；引擎仍应在 prepare / setQueue / selectQueueIndex / clearQueue
 * 时调用 [onMediaChanged]，让"未回退过"成为显式状态而不是依赖键不相等。
 */
internal class DecoderFallbackGate {
    private var attempted: Boolean = false
    private var attemptedMediaKey: String? = null
    private val attemptedTrackKeys = mutableSetOf<String?>()

    val hasAttempted: Boolean get() = attempted

    fun canRetry(kind: DecoderFailureKind, mediaKey: String?, trackKey: String? = null): Boolean {
        if (!shouldRetryDecoderFallback(kind, alreadyRetriedThisMedia = false)) return false
        if (!attempted || attemptedMediaKey != mediaKey) return true
        // 同一媒体的不同视频轨可以各自尝试一次；同一轨第二次错误必须终止，
        // 否则运行期错误会在平台 codec 与扩展链之间无限来回重建。
        if (trackKey == null) return false
        return trackKey !in attemptedTrackKeys
    }

    fun markAttempted(mediaKey: String?, trackKey: String? = null) {
        if (attemptedMediaKey != mediaKey) attemptedTrackKeys.clear()
        attempted = true
        attemptedMediaKey = mediaKey
        attemptedTrackKeys += trackKey
    }

    fun onMediaChanged() {
        attempted = false
        attemptedMediaKey = null
        attemptedTrackKeys.clear()
    }
}

/**
 * 回退状态：当前媒体是否需要"排除某个 codec"或"只走软解链"。
 *
 * [NpRenderersFactory][com.noneplayer.plugins.player.exo.NpRenderersFactory] 里的
 * [androidx.media3.exoplayer.mediacodec.MediaCodecSelector] 包装在每次 prepare
 * 查询解码器列表时读取它；未武装时直接返回原列表（零开销直通）。
 *
 * 状态是进程级共享的（服务进程里只有一个播放器），由 `ExoVideoPlayer` 在
 * 媒体意图变化 / 回退成功 / 释放时清空。
 */
internal class DecoderFallbackState {
    private var armed: Boolean = false
    private var armedMediaKey: String? = null
    private var armedSelectorMediaId: String? = null
    private var currentSelectorMediaId: String? = null
    private var excludedMime: String? = null
    private var excludedCodecName: String? = null
    private var softOnlyMime: String? = null

    /** 是否有任何回退状态被武装；false 时过滤包装零开销直通。 */
    val isArmed: Boolean get() = armed

    /** 由服务侧 transition 提供当前 MediaItem.mediaId；空 id 由 transition 边界清理。 */
    fun onMediaItemChanged(mediaId: String?) {
        val normalized = mediaId?.takeUnless { it.isBlank() }
        // 单媒体 prepare 沿用 Media3 的空 mediaId，无法做身份比较；服务侧每次
        // transition 都会调用这里并清掉旧状态，避免把上一条单媒体的过滤带到下一条。
        if (normalized == null) {
            currentSelectorMediaId = null
            clear()
            return
        }
        if (normalized == currentSelectorMediaId) return
        currentSelectorMediaId = normalized
        clear()
    }

    /** 过滤前校验状态属于服务侧当前媒体；单媒体空 id 由 transition 清理保证边界。 */
    fun isArmedForCurrentMedia(): Boolean =
        armed && (
            currentSelectorMediaId == null && armedSelectorMediaId == null ||
                armedSelectorMediaId == currentSelectorMediaId
            )

    /** 状态是否属于这条媒体（媒体键校验，避免污染下一条）。 */
    fun isArmedFor(mediaKey: String?): Boolean = armed && armedMediaKey == mediaKey

    /** 该 mime 需要排除的 codec 名；无状态或不匹配时 null。 */
    fun excludedCodecNameFor(mime: String?): String? =
        if (armed && excludedMime != null && excludedMime == mime) excludedCodecName else null

    /** 该 mime 是否应强制只走软解链。 */
    fun isSoftOnlyVideo(mime: String?): Boolean =
        armed && softOnlyMime != null && softOnlyMime == mime

    /**
     * 过滤包装的纯决策：给定某个 mime 的平台候选 codec 名，返回应保留的候选。
     *
     * - 未武装 → 原样返回（调用方据此零开销直通）；
     * - 软解链 mime → 返回空，让平台不认领、落到 nextlib / MJPEG / legacy；
     * - 排除 codec → 去掉该名字（若全被去掉，扩展渲染器接手）。
     */
    fun filterCandidates(mimeType: String, candidateNames: List<String>): List<String> {
        if (!isArmedForCurrentMedia()) return candidateNames
        if (softOnlyMime != null && softOnlyMime == mimeType) return emptyList()
        val excluded = excludedCodecNameFor(mimeType) ?: return candidateNames
        return candidateNames.filterNot { it == excluded }
    }

    /** 武装：排除指定平台的 codec。 */
    fun armExcludedCodec(
        mediaKey: String?,
        mime: String?,
        codecName: String,
        selectorMediaId: String? = mediaKey,
    ) {
        Mp4vAspGate.clearForcedDedicatedDecoder()
        armed = true
        armedMediaKey = mediaKey
        armedSelectorMediaId = selectorMediaId
        excludedMime = mime
        excludedCodecName = codecName
        softOnlyMime = null
    }

    /** 武装：该 mime 只走软解链（mp4v → nextlib / 专用 renderer）。 */
    fun armSoftOnlyVideo(
        mediaKey: String?,
        mime: String,
        selectorMediaId: String? = mediaKey,
    ) {
        if (mime == MP4V_VIDEO_MIME) Mp4vAspGate.armForcedDedicatedDecoder()
        armed = true
        armedMediaKey = mediaKey
        armedSelectorMediaId = selectorMediaId
        excludedMime = null
        excludedCodecName = null
        softOnlyMime = mime
    }

    /** 清空全部回退状态（切媒体 / 回退成功 / 释放时调用）。 */
    fun clear() {
        Mp4vAspGate.clearForcedDedicatedDecoder()
        armed = false
        armedMediaKey = null
        armedSelectorMediaId = null
        excludedMime = null
        excludedCodecName = null
        softOnlyMime = null
    }
}

/**
 * 进程级回退状态。
 *
 * 播放器实例在服务进程里，`MediaCodecSelector` 的包装也在服务进程里，两者要看到
 * 同一份状态；媒体键 / 回退闸门仍在客户端 `ExoVideoPlayer` 侧维护。
 */
internal val decoderFallbackState: DecoderFallbackState = DecoderFallbackState()
