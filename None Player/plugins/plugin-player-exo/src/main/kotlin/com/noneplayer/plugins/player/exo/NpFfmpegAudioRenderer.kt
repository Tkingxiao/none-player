package com.noneplayer.plugins.player.exo

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.noneplayer.core.media.ffmpeg.NpFfmpegCodecs
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 白名单音频渲染器（移植 NextLib FfmpegAudioRenderer，E204 白名单批）。
 *
 * 认领范围 = [NpFfmpegCodecs] 音频表 ∩ 共享 libavcodec 实际编译的解码器，
 * s16 PCM 输出交给 [AudioSink]（platform/NextLib 渲染器不认领这些 mime，不冲突）。
 */
@UnstableApi
class NpFfmpegAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioProcessors: Array<out AudioProcessor>,
) : DecoderAudioRenderer<NpFfmpegAudioDecoder>(eventHandler, eventListener, *audioProcessors) {

    constructor(eventHandler: Handler?, eventListener: AudioRendererEventListener?) : this(
        eventHandler,
        eventListener,
        emptyArray(),
    )

    private var decoder: NpFfmpegAudioDecoder? = null

    override fun getName(): String = "NpFfmpegAudio"

    override fun supportsFormatInternal(format: Format): Int {
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!NpFfmpegCodecs.inTable(mime) || !NpFfmpegCodecs.isAvailable()) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM
        }
        if (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return C.FORMAT_HANDLED
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = ADAPTIVE_NOT_SEAMLESS

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): NpFfmpegAudioDecoder {
        val initialInputBufferSize =
            if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_BUFFER_SIZE
        return NpFfmpegAudioDecoder(format, NUM_BUFFERS, NUM_BUFFERS, initialInputBufferSize)
            .also { decoder = it }
    }

    override fun getOutputFormat(decoder: NpFfmpegAudioDecoder): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.outputChannelCount)
            .setSampleRate(decoder.outputSampleRate)
            .setPcmEncoding(decoder.encoding)
            .build()

    private fun sinkSupportsFormat(inputFormat: Format, pcmEncoding: Int): Boolean =
        sinkSupportsFormat(
            Util.getPcmFormat(pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate),
        )

    private companion object {
        const val NUM_BUFFERS = 16
        const val DEFAULT_INPUT_BUFFER_SIZE = 960 * 6
    }
}
