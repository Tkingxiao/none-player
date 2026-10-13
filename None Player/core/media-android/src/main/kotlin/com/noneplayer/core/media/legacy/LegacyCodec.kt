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

    fun receive(): LegacyFrame? {
        if (handle == 0L || LegacyNative.nativePending(handle) <= 0) return null
        val info = IntArray(5)
        val pts = LongArray(1)
        if (!LegacyNative.nativePeek(handle, info, pts)) return null
        val width = info[0]
        val height = info[1]
        val yStride = info[2]
        val uvStride = info[3]
        val colorspace = info[4]
        if (width <= 0 || height <= 0 || yStride <= 0 || uvStride <= 0) {
            throw LegacyDecoderException("帧尺寸无效 ${width}x$height")
        }
        val uvHeight = (height + 1) / 2
        val y = ByteBuffer.allocateDirect(yStride * height)
        val u = ByteBuffer.allocateDirect(uvStride * uvHeight)
        val v = ByteBuffer.allocateDirect(uvStride * uvHeight)
        LegacyNative.nativeCopy(handle, y, u, v)
        return LegacyFrame(width, height, y, u, v, yStride, uvStride, colorspace, pts[0])
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
        surface: Surface,
    ) {
        val rc = LegacyNative.nativeRender(y, yStride, u, uvStride, v, width, height, colorspace, surface)
        if (rc < 0) throw LegacyDecoderException("画到 Surface 失败 ($rc)")
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
    val ptsUs: Long,
)

class LegacyDecoderException(message: String, cause: Throwable? = null) : Exception(message, cause)
