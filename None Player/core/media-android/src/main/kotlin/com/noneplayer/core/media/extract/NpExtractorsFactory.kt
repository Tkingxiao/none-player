package com.noneplayer.core.media.extract

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.homesoft.exo.extractor.AviExtractorsFactory

/**
 * 探测和播放共用的 Extractor 工厂。
 *
 * WMV2 由 [Wmv2AviExtractor] 在 sniff 阶段认领；其它文件原样走 [AviExtractorsFactory]，
 * 只是把其中的 AviExtractor 包一层，以便在 Format 出手之前改写确需软解的 ASP。
 */
@OptIn(UnstableApi::class)
class NpExtractorsFactory : ExtractorsFactory {

    private val delegate = AviExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = patch(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = patch(delegate.createExtractors(uri, responseHeaders))

    private fun patch(extractors: Array<Extractor>): Array<Extractor> {
        val wrapped = extractors.map { extractor ->
            if (extractor.javaClass.name == AVI_EXTRACTOR) {
                Mp4vAspRewritingExtractor(extractor)
            } else {
                extractor
            }
        }
        // E207：avformat 解封装（asf/rm/flv/mpegps/nut）插在 media3 defaults 之前——
        // 这些容器里 media3 没有解封装器（ASF/RM）或实现不覆盖白名单编码（FLV 的 VP6）
        return arrayOf(Wmv2AviExtractor(), NpFfmpegExtractor("asf", "WMV (ASF)"),
            NpFfmpegExtractor("rm", "RealMedia (RM)"), NpFfmpegExtractor("flv", "FLV"),
            NpFfmpegExtractor("mpegps", "MPEG-PS"), NpFfmpegExtractor("nut", "NUT"),
            *wrapped.toTypedArray())
    }

    private companion object {
        const val AVI_EXTRACTOR = "com.homesoft.exo.extractor.avi.AviExtractor"
    }
}
