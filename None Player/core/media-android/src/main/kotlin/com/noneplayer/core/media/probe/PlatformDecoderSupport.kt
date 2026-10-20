package com.noneplayer.core.media.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.noneplayer.core.media.legacy.LegacyDecoderLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

/**
 * 平台解码器判定的唯一来源（A10）。
 *
 * 探测（[FormatProbe]）与播放侧的「兼容档」选择器必须用同一套规则：
 * - [hasPlatformDecoder]：平台有没有该编码的解码器（探测可播性用，异常 fail-open）；
 * - [hasHardwareDecoder]：其中有没有**硬件加速**的（兼容档据此决定是否让 FFmpeg /
 *   legacy 排到平台前面；API 29 以下无法判断，按 true 处理，不改变旧行为）。
 *
 * 「只有软解可用」不再看默认选中的那一个解码器——`findDecoderForFormat` 可能先返回
 * 软件解码器，而同一台设备另有硬解；播放侧的选择器按 `isHardwareAccelerated` 过滤，
 * 探测侧也必须按同一集合判断，否则会出现「探测说硬解、兼容档却走软解」的分叉。
 */
object PlatformDecoderSupport {

    /** AVI 内嵌 MJPEG：无平台解码器、FFmpeg 也不含该编码，只有 Media3Avi 的位图路径能解。 */
    private const val MIME_MJPEG = "video/mjpeg"

    /**
     * 平台解码器查询三态（REV-04/A17）：true=有解码器，false=明确没有，null=查询异常/无法判定。
     *
     * 探测侧不能再把「查询异常」当成「有解码器」：那会把一次系统抖动固化成永久结论并写进缓存。
     * 调用方拿到 null 必须按 fail-open 放行，且结论不得冒充确定性支持/不支持、不得进缓存。
     * 音频 mime 走 `createAudioFormat`（采样率/声道缺失时按常见档位查询），其余走视频查询。
     */
    fun platformDecoderQuery(format: Format): Boolean? {
        val mime = format.sampleMimeType ?: return true
        return runCatching {
            val platform = if (mime.startsWith("audio/", ignoreCase = true)) {
                MediaFormat.createAudioFormat(
                    mime,
                    if (format.sampleRate > 0) format.sampleRate else 48_000,
                    if (format.channelCount > 0) format.channelCount else 2,
                )
            } else {
                MediaFormat.createVideoFormat(
                    mime,
                    maxOf(format.width, 1),
                    maxOf(format.height, 1),
                )
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(platform) != null
        }.getOrNull()
    }

    /** 平台是否存在能解该格式的解码器；查询异常按 true 放行（探测侧 fail-open，语义不变）。 */
    fun hasPlatformDecoder(format: Format): Boolean = platformDecoderQuery(format) ?: true

    /**
     * 平台是否存在**硬件加速**的该格式解码器。
     *
     * API 29 以下 `isHardwareAccelerated` 不可用，返回 true（保持「不判软解优先」的旧语义）。
     * 查询异常也返回 true：宁可硬解优先，也不要因为一次查询失败把整类格式推给软解。
     */
    fun hasHardwareDecoder(format: Format): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val mime = format.sampleMimeType ?: return true
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(true)
    }

    /** 选中的平台解码器信息（测试与排查用；播放侧选择器不依赖它）。 */
    fun selectedDecoder(format: Format): MediaCodecInfo? {
        val mime = format.sampleMimeType ?: return null
        return runCatching {
            val platform = MediaFormat.createVideoFormat(
                mime,
                maxOf(format.width, 1),
                maxOf(format.height, 1),
            )
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = list.findDecoderForFormat(platform) ?: return@runCatching null
            list.codecInfos.firstOrNull { it.name == name }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // REV-08：能力快照 → 决策（探测与播放侧「兼容档」选择器的唯一规则来源）
    // ------------------------------------------------------------------

    /**
     * 解码能力快照（REV-08）。
     *
     * 探测侧与播放侧兼容档选择器必须落在同一份快照上，否则会出现
     * 「探测说平台软解可播、兼容档却把软解也过滤掉 → 一个解码器都选不出来」。
     * 纯数据、不依赖 Android，可直接在 JVM 单测里构造。
     *
     * @property hardwareAvailable 平台存在硬件加速解码器
     * @property softwareAvailable 平台存在非硬件加速（软件）解码器
     * @property extensionHandles 扩展软解链（FFmpeg / MJPEG / libnplegacy）能解该格式
     * @property queryFailed 平台能力查询异常、无法判定
     */
    data class DecoderCapability(
        val hardwareAvailable: Boolean,
        val softwareAvailable: Boolean,
        val extensionHandles: Boolean,
        val queryFailed: Boolean = false,
    )

    /** 兼容档选择器的决策结果（见 [decideVideoDecoderPolicy]）。 */
    enum class DecoderPolicy {
        /** 平台有硬解：只保留 hardwareAccelerated 的候选（硬解优先，保持现状）。 */
        HARDWARE_ONLY,

        /** 保留全部平台候选：仅平台软解且扩展接不了时的软解保底；「全部不支持」时全留即空。 */
        PLATFORM_ALL,

        /** 仅平台软解、扩展确定可解：允许把平台候选过滤空（do not claim），让扩展渲染器接手。 */
        FILTER_TO_EXTENSION,

        /** 查询异常/无法判定：保留全部候选，不判「不支持」、不返回空。 */
        KEEP_UNKNOWN,
    }

    /**
     * 能力快照 → 决策（REV-08 规则唯一来源）。
     *
     * 顺序即优先级：
     * 1. 查询异常最高——无法判定就不判死，保留全部候选；
     * 2. 有硬解 → 只留硬解；
     * 3. 只有平台软解：扩展能接手就让平台不认领（[DecoderPolicy.FILTER_TO_EXTENSION]），
     *    接不了则软解保底（[DecoderPolicy.PLATFORM_ALL]）——这正是旧实现过滤成空的回归点；
     * 4. 平台无任何解码器 → 同样 [DecoderPolicy.PLATFORM_ALL]（全留即空列表），
     *    播放链条自然落到扩展渲染器，不额外发明「不支持」常量。
     */
    fun decideVideoDecoderPolicy(capability: DecoderCapability): DecoderPolicy = when {
        capability.queryFailed -> DecoderPolicy.KEEP_UNKNOWN
        capability.hardwareAvailable -> DecoderPolicy.HARDWARE_ONLY
        capability.softwareAvailable && !capability.extensionHandles -> DecoderPolicy.PLATFORM_ALL
        capability.extensionHandles -> DecoderPolicy.FILTER_TO_EXTENSION
        else -> DecoderPolicy.PLATFORM_ALL
    }

    /**
     * 把决策落到候选列表上（纯函数；泛型以避免把 media3 的 `MediaCodecInfo` 类型带进本模块）。
     *
     * - [DecoderPolicy.HARDWARE_ONLY] / [DecoderPolicy.FILTER_TO_EXTENSION]：只留
     *   [isHardwareAccelerated] 为 true 的候选；
     * - [DecoderPolicy.PLATFORM_ALL] / [DecoderPolicy.KEEP_UNKNOWN]：原样返回（含平台软解）。
     */
    fun <T> applyVideoDecoderPolicy(
        policy: DecoderPolicy,
        candidates: List<T>,
        isHardwareAccelerated: (T) -> Boolean,
    ): List<T> = when (policy) {
        DecoderPolicy.HARDWARE_ONLY, DecoderPolicy.FILTER_TO_EXTENSION ->
            candidates.filter(isHardwareAccelerated)

        DecoderPolicy.PLATFORM_ALL, DecoderPolicy.KEEP_UNKNOWN -> candidates
    }

    /**
     * 扩展软解链能否解该 mime：FFmpeg（NextLib）/ MJPEG 位图 / libnplegacy。
     *
     * 与 [FormatProbe] 的私有 helper（`ffmpegSupports` / `bitmapRendererSupports` / `legacySupports`）
     * 同口径。探测侧暂未改为调用本方法（REV-08 白名单只放开播放侧），后续批次应合并为一处。
     * 任何查询异常都按 false：不能把「查不到」当「能解」，否则兼容档会错误地让平台不认领、
     * 结果连仅有的平台软解也放弃。
     */
    @OptIn(UnstableApi::class)
    fun extensionHandlesVideo(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        if (mime == MIME_MJPEG) return true
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }
            .getOrDefault(false)
        if (ffmpeg) return true
        return runCatching { LegacyDecoderLibrary.supports(mime) }.getOrDefault(false)
    }

    /**
     * 播放侧兼容档用的能力快照（会查询 `MediaCodecList`，只在生产路径调用；
     * 纯 JVM 测试请直接构造 [DecoderCapability]）。
     *
     * API 29 以下拿不到 `isHardwareAccelerated`：返回 `queryFailed = true`，决策落到
     * [DecoderPolicy.KEEP_UNKNOWN]（保留全部候选），等价于旧行为——不判软解优先。
     * 查询异常同样按 queryFailed 处理，绝不判「不支持」、绝不返回空。
     */
    fun videoDecoderCapability(mimeType: String?): DecoderCapability {
        val mime = mimeType ?: return DecoderCapability(
            hardwareAvailable = false,
            softwareAvailable = false,
            extensionHandles = false,
            queryFailed = true,
        )
        val extensionHandles = extensionHandlesVideo(mime)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // 硬/软标记不可用：等价 KEEP_UNKNOWN（保留全部候选，保持旧行为）
            return DecoderCapability(
                hardwareAvailable = false,
                softwareAvailable = false,
                extensionHandles = extensionHandles,
                queryFailed = true,
            )
        }
        return runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            DecoderCapability(
                hardwareAvailable = infos.any { it.isHardwareAccelerated },
                softwareAvailable = infos.any { !it.isHardwareAccelerated },
                extensionHandles = extensionHandles,
            )
        }.getOrElse {
            DecoderCapability(
                hardwareAvailable = false,
                softwareAvailable = false,
                extensionHandles = extensionHandles,
                queryFailed = true,
            )
        }
    }
}
