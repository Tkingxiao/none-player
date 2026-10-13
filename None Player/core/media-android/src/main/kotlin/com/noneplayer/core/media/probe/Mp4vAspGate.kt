package com.noneplayer.core.media.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * MPEG-4 ASP 是否必须改走专用软解 mime。
 *
 * 规则与 [FormatProbe] 的 profile 拦截是同一套，不另写一份：
 * 码流不是 Advanced Simple、设备声明了 ASP、或设备 profile 列表为空（fail-open）时都不改写。
 * 只有「确需 ASP 且设备明确没有 ASP」才返回 true；调用方还要再看软解是否真的加载成功。
 */
internal object Mp4vAspGate {

    private const val MIME_MP4V = "video/mp4v-es"

    private val cachedProfiles: List<Int> by lazy { queryDeviceProfiles() }

    fun deviceProfiles(): List<Int> = cachedProfiles

    fun requiresDedicatedDecoder(header: ByteArray): Boolean {
        val verdict = Mpeg4ProfileParser.parse(header)
        if (verdict.required != Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE) return false
        val profiles = deviceProfiles()
        val supportsAsp = profiles.any {
            it == MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple
        }
        if (supportsAsp) return false
        if (profiles.isEmpty()) return false
        return true
    }

    private fun queryDeviceProfiles(): List<Int> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
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
