package com.noneplayer.core.media.legacy

import android.view.Surface
import java.nio.ByteBuffer

/**
 * 有状态的 wmv2 / mpeg4 解码器。参考帧和 extradata 留在同一次 [nativeCreate] 里，
 * 不是 `(codec, extradata, packet) -> frame`。
 */
class LegacyCodec(
    codecName: String,
    extradata: ByteArray?,
    width: Int,
    height: Int,
) : AutoCloseable {

    private var yBuffer: ByteBuffer? = null
    private var uBuffer: ByteBuffer? = null
    private var vBuffer: ByteBuffer? = null

    private var handle: Long = LegacyNative.nativeCreate(
        codecName,
        extradata ?: ByteArray(0),
        width,
        height,
    )

    init {
        if (handle == 0L) {
            throw LegacyDecoderException("打开 $codecName 失败")
        }
    }

    fun send(data: ByteArray, ptsUs: Long, eos: Boolean) {
        check(handle != 0L) { "decoder released" }
        val rc = LegacyNative.nativeSend(handle, data, data.size, ptsUs, eos)
        if (rc < 0) throw LegacyDecoderException("送入 $rc")
    }

    /** 解码器已把 EOS 前的所有帧交出来（队列也因此排空）。 */
    val isEof: Boolean
        get() = handle != 0L && LegacyNative.nativeEof(handle)

    fun receive(): LegacyFrame? {
        if (handle == 0L || LegacyNative.nativePending(handle) <= 0) return null
        val info = IntArray(6)
        val pts = LongArray(1)
        if (!LegacyNative.nativePeek(handle, info, pts)) return null
        val width = info[0]
        val height = info[1]
        val yStride = info[2]
        val uvStride = info[3]
        val colorspace = info[4]
        val matrix = info[5]
        if (width <= 0 || height <= 0 || yStride <= 0 || uvStride <= 0) {
            throw LegacyDecoderException("帧尺寸无效 ${width}x$height")
        }
        val uvHeight = (height + 1) / 2
        // A19：direct buffer 按最大尺寸复用；调用方（pump）在下一帧 receive 前已消费完，
        // 不允许长期持有 LegacyFrame 的 ByteBuffer。
        val y = reuse(yBuffer, yStride * height).also { yBuffer = it }
        val u = reuse(uBuffer, uvStride * uvHeight).also { uBuffer = it }
        val v = reuse(vBuffer, uvStride * uvHeight).also { vBuffer = it }
        if (!LegacyNative.nativeCopy(handle, y, u, v)) {
            throw LegacyDecoderException("拷贝解码帧失败")
        }
        return LegacyFrame(width, height, y, u, v, yStride, uvStride, colorspace, matrix, pts[0])
    }

    fun render(
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
        val rc = LegacyNative.nativeRender(handle, y, yStride, u, uvStride, v, width, height, colorspace, matrix, surface)
        if (rc < 0) throw LegacyDecoderException("画到 Surface 失败 ($rc)")
    }

    private fun reuse(buffer: ByteBuffer?, size: Int): ByteBuffer =
        if (buffer != null && buffer.capacity() >= size) {
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocateDirect(size)
        }

    fun flush() {
        if (handle != 0L) LegacyNative.nativeFlush(handle)
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        LegacyNative.nativeRelease(current)
    }
}

class LegacyFrame(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val yStride: Int,
    val uvStride: Int,
    val colorspace: Int,
    /** A19: 0 = BT.601, 1 = BT.709 */
    val matrix: Int,
    val ptsUs: Long,
)

class LegacyDecoderException(message: String, cause: Throwable? = null) : Exception(message, cause)
