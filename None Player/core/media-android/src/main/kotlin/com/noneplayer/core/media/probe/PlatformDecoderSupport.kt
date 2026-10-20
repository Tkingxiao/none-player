package com.noneplayer.core.media.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.Format

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

    /** 平台是否存在能解该格式的解码器；查询异常按 true 放行（探测侧 fail-open）。 */
    fun hasPlatformDecoder(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return true
        return runCatching {
            val platform = MediaFormat.createVideoFormat(
                mime,
                maxOf(format.width, 1),
                maxOf(format.height, 1),
            )
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(platform) != null
        }.getOrDefault(true)
    }

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
}
