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
        return arrayOf(Wmv2AviExtractor(), *wrapped.toTypedArray())
    }

    private companion object {
        const val AVI_EXTRACTOR = "com.homesoft.exo.extractor.avi.AviExtractor"
    }
}
