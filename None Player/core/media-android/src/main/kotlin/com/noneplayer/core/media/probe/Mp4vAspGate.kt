package com.noneplayer.core.media.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * A17 探测侧：mp4v-es 码流是否需要改走专用（nplegacy）软解链。
 *
 * 判定原则（R3-14 / REV-04 / A17）：
 *  - 只有 [Mpeg4ProfileParser] 判为 Advanced Simple Profile 的码流才可能触发改写；
 *  - 先问「[MediaCodecList.findDecoderForFormat] 真正会选中的那一个解码器」是否声明 ASP：
 *    它声明了 ASP → 平台链可用，不改写；它没声明 → 走专用链；
 *  - 只有拿不到选中解码器信息（查询异常 / 无解码器）时才退回全设备并集兜底；
 *  - 选中与并集都拿不到 = 无法判定（null）：探测侧按 fail-open 放行且结论不进缓存，
 *    改写侧保持平台链，绝不凭缺失信息拒绝播放。
 */
internal object Mp4vAspGate {

    private const val MIME_MP4V = "video/mp4v-es"

    private val cachedProfiles: List<Int> by lazy { queryDeviceProfiles() }

    /** 设备上所有声明支持 mp4v 的解码器的 MPEG-4 profile 并集（查询失败为空表）。 */
    fun deviceProfiles(): List<Int> = cachedProfiles

    /** 改写/探测路径主入口：拿到头部与视频格式（可空）后判断是否需要专用 mime。无法判定时 false。 */
    fun requiresDedicatedDecoder(header: ByteArray, videoFormat: Format?): Boolean =
        needsDedicatedDecoder(header, videoFormat) ?: false

    /**
     * 兼容重载：**只服务既有测试**（task-13 的 R3BoundedReadAcceptanceTest 仍按单参调用），
     * 生产调用必须走 [requiresDedicatedDecoder] 的 (header, videoFormat) 版本，
     * 否则会退化成「仅 mime 的 mp4v Format」，拿不到真实宽高/初始化数据，可能错过选中解码器。
     * 内部仍与主重载同一口径（先查被选中的解码器、并集仅兜底），不留双份语义。
     */
    @Deprecated(
        message = "生产调用请传 Format（横向对齐探测/改写口径）；此重载只保留给既有测试",
        replaceWith = ReplaceWith("requiresDedicatedDecoder(header, format)"),
    )
    fun requiresDedicatedDecoder(header: ByteArray): Boolean =
        requiresDedicatedDecoder(header, defaultMp4vFormat())

    /** 三态判定：true=需要专用链，false=平台链可用，null=无法判定（调用方必须 fail-open）。 */
    internal fun needsDedicatedDecoder(header: ByteArray, videoFormat: Format?): Boolean? {
        val verdict = Mpeg4ProfileParser.parse(header)
        if (verdict.required != Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE) return false
        val probeFormat = videoFormat ?: defaultMp4vFormat()
        return decideRequiresDedicated(
            selectedSupportsAsp = selectedDecoderSupportsAsp(probeFormat),
            unionSupportsAsp = unionSupportsAsp(),
        )
    }

    /**
     * 纯决策规则（纯 JVM 单测唯一入口）：选中解码器信息优先，并集只在选中信息缺失时兜底。
     * 两者都缺失 → null（无法判定，不得冒充「支持」或「不支持」）。
     */
    internal fun decideRequiresDedicated(
        selectedSupportsAsp: Boolean?,
        unionSupportsAsp: Boolean?,
    ): Boolean? = when {
        selectedSupportsAsp != null -> !selectedSupportsAsp
        unionSupportsAsp != null -> !unionSupportsAsp
        else -> null
    }

    /** 被选中的那一个解码器是否声明 ASP；查不到（异常/无解码器）→ null。 */
    private fun selectedDecoderSupportsAsp(format: Format): Boolean? {
        val selected = PlatformDecoderSupport.selectedDecoder(format) ?: return null
        return runCatching {
            selected.getCapabilitiesForType(MIME_MP4V).profileLevels
                ?.any { it.profile == MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple }
        }.getOrNull()
    }

    /** 全设备并集是否声明 ASP；并集为空（查询失败/无解码器）→ null。 */
    private fun unionSupportsAsp(): Boolean? =
        cachedProfiles.takeIf { it.isNotEmpty() }
            ?.any { it == MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple }

    @OptIn(UnstableApi::class)
    private fun defaultMp4vFormat(): Format =
        Format.Builder().setSampleMimeType(MIME_MP4V).build()

    private fun queryDeviceProfiles(): List<Int> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { !it.isEncoder }
            .filter { info ->
                runCatching { info.supportedTypes.any { it.equals(MIME_MP4V, true) } }
                    .getOrDefault(false)
            }
            .flatMap { info ->
                runCatching {
                    info.getCapabilitiesForType(MIME_MP4V).profileLevels?.map { it.profile }
                        ?: emptyList()
                }.getOrDefault(emptyList()).asSequence()
            }
            .toList()
    }.getOrDefault(emptyList())
}
