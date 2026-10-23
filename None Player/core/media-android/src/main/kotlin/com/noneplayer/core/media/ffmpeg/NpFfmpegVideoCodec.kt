package com.noneplayer.core.media.ffmpeg

import android.view.Surface
import com.noneplayer.core.media.legacy.LegacyFrame
import com.noneplayer.core.media.legacy.VideoCodec
import java.nio.ByteBuffer

/**
 * 白名单视频解码器（镜像 [com.noneplayer.core.media.legacy.LegacyCodec]，E204 白名单批）。
 *
 * 与 legacy 的唯一差异是 native 后端：链 NextLib 随包的共享 libavcodec，按名字打开
 * wmv1/wmv3/vc1/msmpeg4 等白名单解码器。状态语义（参考帧常驻、direct buffer 送入、
 * 帧池复用）与 legacy 完全一致。
 */
class NpFfmpegVideoCodec(
    codecName: String,
    extradata: ByteArray?,
    width: Int,
    height: Int,
) : VideoCodec {

    private var handle: Long = NpFfmpegNative.nativeCreate(
        codecName,
        extradata ?: ByteArray(0),
        width,
        height,
    )

    private var sendScratch: ByteBuffer? = null

    init {
        if (handle == 0L) {
            throw NpFfmpegDecoderException("打开 $codecName 失败")
        }
    }

    override fun send(data: ByteBuffer?, ptsUs: Long, eos: Boolean) {
        check(handle != 0L) { "decoder released" }
        // E204：size=0 的非 EOS 包是无效输入（avcodec_send_packet 直接 EINVAL）——
        // 跳过发送按成功处理（队列里没有可解的内容，drain 交给后续调用）。
        if (eos || data == null || data.remaining() <= 0) {
            val rc = NpFfmpegNative.nativeSendDirect(handle, null, 0, ptsUs, eos)
            if (rc < 0) throw NpFfmpegDecoderException("送入 $rc")
            return
        }
        val direct = if (data.isDirect) {
            data
        } else {
            val n = data.remaining()
            val scratch = sendScratch?.takeIf { it.capacity() >= n }
                ?: ByteBuffer.allocateDirect(maxOf(n, 64 * 1024)).also { sendScratch = it }
            scratch.clear()
            scratch.put(data)
            scratch.flip()
            scratch
        }
        val rc = NpFfmpegNative.nativeSendDirect(handle, direct, direct.remaining(), ptsUs, eos)
        if (rc < 0) throw NpFfmpegDecoderException("送入 $rc")
    }

    private var yBuffer: ByteBuffer? = null
    private var uBuffer: ByteBuffer? = null
    private var vBuffer: ByteBuffer? = null

    private fun reuse(buffer: ByteBuffer?, size: Int): ByteBuffer =
        if (buffer != null && buffer.capacity() >= size) {
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocateDirect(size)
        }

    override fun receive(): LegacyFrame? {
        if (handle == 0L || NpFfmpegNative.nativePending(handle) <= 0) return null
        val info = IntArray(6)
        val pts = LongArray(1)
        if (!NpFfmpegNative.nativePeek(handle, info, pts)) return null
        val width = info[0]
        val height = info[1]
        val yStride = info[2]
        val uvStride = info[3]
        if (width <= 0 || height <= 0 || yStride <= 0 || uvStride <= 0) {
            throw NpFfmpegDecoderException("帧尺寸无效 ${width}x$height")
        }
        val uvHeight = (height + 1) / 2
        val y = reuse(yBuffer, yStride * height).also { yBuffer = it }
        val u = reuse(uBuffer, uvStride * uvHeight).also { uBuffer = it }
        val v = reuse(vBuffer, uvStride * uvHeight).also { vBuffer = it }
        if (!NpFfmpegNative.nativeCopy(handle, y, u, v)) {
            throw NpFfmpegDecoderException("拷贝解码帧失败")
        }
        return LegacyFrame(width, height, y, u, v, yStride, uvStride, info[4], info[5], pts[0])
    }

    override val isEof: Boolean
        get() = handle != 0L && NpFfmpegNative.nativeEof(handle)

    override fun render(
        y: ByteBuffer,
        yStride: Int,
        u: ByteBuffer,
        uvStride: Int,
        v: ByteBuffer,
        width: Int,
        height: Int,
        colorspace: Int,
        matrix: Int,
        surface: Surface,
    ) {
        val rc = NpFfmpegNative.nativeRender(
            handle,
            y,
            yStride,
            u,
            uvStride,
            v,
            width,
            height,
            colorspace,
            matrix,
            surface,
        )
        if (rc < 0) throw NpFfmpegDecoderException("画到 Surface 失败 ($rc)")
    }

    override fun flush() {
        if (handle != 0L) NpFfmpegNative.nativeFlush(handle)
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        NpFfmpegNative.nativeRelease(current)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NpFfmpegDecoderException(message: String, cause: Throwable? = null) :
    androidx.media3.decoder.DecoderException(message, cause)
