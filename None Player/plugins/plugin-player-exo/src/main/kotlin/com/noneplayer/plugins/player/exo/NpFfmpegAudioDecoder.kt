package com.noneplayer.plugins.player.exo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.noneplayer.core.media.ffmpeg.NpFfmpegCodecs
import com.noneplayer.core.media.ffmpeg.NpFfmpegDecoderException
import com.noneplayer.core.media.ffmpeg.NpFfmpegNative
import java.nio.ByteBuffer

/**
 * 白名单音频解码器（移植 NextLib FfmpegAudioDecoder，E204 白名单批）。
 *
 * 与蓝本的差异只有两处：解码器名来自 [NpFfmpegCodecs.audioCodecName]（wma 族按
 * extradata 的 WAVE tag 细分），JNI 后端是 `libnpffmpeg.so`（NextLib 共享 libavcodec）。
 * s16 输出（float 输出路径未引入——AudioSink 对 16-bit PCM 直通支持最广，白名单音频
 * 的码率下 float 无收益）。
 */
@UnstableApi
class NpFfmpegAudioDecoder(
    format: Format,
    numInputBuffers: Int,
    numOutputBuffers: Int,
    initialInputBufferSize: Int,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, NpFfmpegDecoderException>(
    arrayOfNulls(numInputBuffers),
    arrayOfNulls(numOutputBuffers),
) {

    private val codecName: String
    private val extraData: ByteArray?

    /** WMA 族的完整 WAVEFORMATEX 解析结果；非 WMA 族或只有 codec data 时为 null */
    private val wave: NpFfmpegCodecs.WaveFormatEx?

    private var handle: Long = 0
    private var inputScratch: ByteBuffer? = null
    private var hasOutputFormat = false
    @Volatile
    private var channelCount = 0
    @Volatile
    private var sampleRate = 0

    init {
        val mime = format.sampleMimeType ?: throw NpFfmpegDecoderException("no mime")
        codecName = NpFfmpegCodecs.audioCodecName(mime, format.initializationData)
            ?: throw NpFfmpegDecoderException("no npffmpeg audio codec for $mime")
        wave = if (NpFfmpegCodecs.isWmaFamily(mime)) {
            NpFfmpegCodecs.waveFormatExOf(format.initializationData)
        } else {
            null
        }
        if (NpFfmpegCodecs.isWmaFamily(mime) && wave == null) {
            // 没有整段 WAVEFORMATEX 就拿不到 block_align（wmav*/wmapro open 硬性要求）。
            // 渲染器 supportsFormat 已按同一判据拒绝，能走到这里说明上游漏了闸。
            throw NpFfmpegDecoderException(
                "WMA csd 不是完整 WAVEFORMATEX（无法取 blockAlign）：$mime",
            )
        }
        extraData = ffmpegExtraData(mime, format.initializationData, wave)
        android.util.Log.d(
            "NpFfmpegAudio",
            "create $codecName mime=$mime rate=${format.sampleRate} ch=${format.channelCount} " +
                "csd=${format.initializationData.map { it.size }} blockAlign=${wave?.blockAlign ?: 0}",
        )
        handle = NpFfmpegNative.npffAudioCreate(
            codecName,
            extraData,
            format.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
            format.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
            wave?.blockAlign ?: 0,
        )
        if (handle == 0L) {
            throw NpFfmpegDecoderException("初始化 $codecName 失败")
        }
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "npffAudio-$codecName"

    /**
     * WMA 三兄弟（wmav1/2/wmapro/wmalossless）统一剥掉 18B WAVEFORMATEX 头，只留
     * codec-specific data；其它 mime 原样直传。Media3Avi 的 codec-data-only csd
     * 不会走到这一步（渲染器按 [NpFfmpegCodecs.hasWaveFormatEx] 拒认）。
     */
    private fun ffmpegExtraData(
        mime: String?,
        initializationData: List<ByteArray>,
        wave: NpFfmpegCodecs.WaveFormatEx?,
    ): ByteArray? {
        val csd = initializationData.firstOrNull { it.isNotEmpty() } ?: return null
        if (NpFfmpegCodecs.isWmaFamily(mime) && wave != null) {
            return if (csd.size > WAVEFORMATEX_SIZE) {
                csd.copyOfRange(WAVEFORMATEX_SIZE, csd.size)
            } else {
                null
            }
        }
        return csd
    }

    val encoding: Int get() = C.ENCODING_PCM_16BIT

    val outputChannelCount: Int get() = channelCount

    val outputSampleRate: Int get() = sampleRate

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer(this::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable): NpFfmpegDecoderException =
        NpFfmpegDecoderException("Unexpected decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): NpFfmpegDecoderException? {
        if (reset) {
            handle = NpFfmpegNative.npffAudioReset(handle, extraData)
            if (handle == 0L) return NpFfmpegDecoderException("reset 失败")
        }
        val inputData = inputBuffer.data
        if (inputData == null) {
            // SimpleDecoder 不会带空 data 调 decode（EOS 在其内部处理），防御性标记无输出
            outputBuffer.shouldBeSkipped = true
            return null
        }
        // native 侧用 GetDirectBufferAddress + size，不看 Java position（E205 P3 余项）。
        val size = inputData.remaining()
        val packet = if (inputData.position() == 0) {
            inputData
        } else {
            val scratch = inputScratch?.takeIf { it.capacity() >= size }
                ?: ByteBuffer.allocateDirect(maxOf(size, 16 * 1024)).also { inputScratch = it }
            scratch.clear()
            scratch.put(inputData.duplicate())
            scratch.flip()
            scratch
        }
        val result = NpFfmpegNative.npffAudioDecode(handle, packet, size)
        when (result) {
            ERROR_OTHER -> return NpFfmpegDecoderException("decode 失败（见 logcat）")
            ERROR_INVALID_DATA -> {
                // 与 MediaCodec 行为对齐：坏帧非致命，标记 skip 让 sink 重置位置
                outputBuffer.shouldBeSkipped = true
                return null
            }
        }
        if (result == 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }
        if (!hasOutputFormat) {
            channelCount = NpFfmpegNative.npffAudioGetChannelCount(handle)
            sampleRate = NpFfmpegNative.npffAudioGetSampleRate(handle)
            hasOutputFormat = true
        }
        // 二段式：C 侧已按 result 字节备好 PCM，输出缓冲按需分配后 drain 拷出
        val outputData = outputBuffer.init(inputBuffer.timeUs, result)
        NpFfmpegNative.npffAudioDrain(handle, outputData, result)
        val data = checkNotNull(outputBuffer.data)
        data.position(0)
        data.limit(result)
        return null
    }

    override fun release() {
        super.release()
        if (handle != 0L) {
            NpFfmpegNative.npffAudioRelease(handle)
            handle = 0L
        }
    }

    private companion object {
        const val WAVEFORMATEX_SIZE = 18
        const val ERROR_INVALID_DATA = -1
        const val ERROR_OTHER = -2
    }
}

private fun checkNotNull(value: ByteBuffer?): ByteBuffer =
    value ?: throw NpFfmpegDecoderException("output buffer null")
