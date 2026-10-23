package com.noneplayer.plugins.player.exo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.noneplayer.core.media.MediaNames
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

    private var handle: Long = 0
    private var hasOutputFormat = false
    @Volatile
    private var channelCount = 0
    @Volatile
    private var sampleRate = 0

    init {
        val mime = format.sampleMimeType ?: throw NpFfmpegDecoderException("no mime")
        codecName = NpFfmpegCodecs.audioCodecName(mime, format.initializationData)
            ?: throw NpFfmpegDecoderException("no npffmpeg audio codec for $mime")
        extraData = ffmpegExtraData(mime, format.initializationData)
        android.util.Log.d(
            "NpFfmpegAudio",
            "create $codecName mime=$mime rate=${format.sampleRate} ch=${format.channelCount} " +
                "csd=${format.initializationData.map { it.size }}",
        )
        handle = NpFfmpegNative.npffAudioCreate(
            codecName,
            extraData,
            format.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
            format.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
            wavBlockAlign(mime, format.initializationData),
        )
        if (handle == 0L) {
            throw NpFfmpegDecoderException("初始化 $codecName 失败")
        }
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "npffAudio-$codecName"

    /**
     * FFmpeg 的 wma extradata 语义 = WAVEFORMATEX **之后**的尾部（FFmpeg avidec 同款）：
     * 我们的 csd 是整段 strf（tag 在 [0..1] 供路由读），所以 wma 族在这里切掉 18 字节
     * 头再交给解码器——整段直传会让 wmav2 的 open 读到错位的 flags 直接 EINVAL
     * （真机实录"初始化 wmav2 失败"）。
     */
    /** WAVEFORMATEX.nBlockAlign（[12..13]）；非 WMA / 无 csd 返回 0 */
    private fun wavBlockAlign(mime: String?, initializationData: List<ByteArray>): Int {
        if (mime != MediaNames.MIME_WMA && mime != MediaNames.MIME_WMAPRO) return 0
        val strf = initializationData.firstOrNull { it.size >= 14 } ?: return 0
        return (strf[12].toInt() and 0xff) or ((strf[13].toInt() and 0xff) shl 8)
    }

    private fun ffmpegExtraData(mime: String?, initializationData: List<ByteArray>): ByteArray? {
        val csd = initializationData.firstOrNull { it.isNotEmpty() } ?: return null
        if (mime == MediaNames.MIME_WMA && csd.size > WAVEFORMATEX_SIZE) {
            return csd.copyOfRange(WAVEFORMATEX_SIZE, csd.size)
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
        val result = NpFfmpegNative.npffAudioDecode(handle, inputData, inputData.limit())
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

    /** 供 native 回调：输出缓冲不够时扩容（契约见 np_ffmpeg_decoder.c 音频段注释） */
    fun growOutputBuffer(outputBuffer: SimpleDecoderOutputBuffer, requiredSize: Int): ByteBuffer =
        outputBuffer.grow(requiredSize)

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

        /** 与 NextLib 同值：µ-law 解码的最大输出，覆盖白名单音频族的单包输出 */
        const val INITIAL_OUTPUT_BUFFER_SIZE_16BIT = 65535
    }
}

private fun checkNotNull(value: ByteBuffer?): ByteBuffer =
    value ?: throw NpFfmpegDecoderException("output buffer null")
