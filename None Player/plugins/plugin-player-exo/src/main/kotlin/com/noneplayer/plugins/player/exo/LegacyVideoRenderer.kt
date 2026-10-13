package com.noneplayer.plugins.player.exo

import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.legacy.LegacyCodec
import com.noneplayer.core.media.legacy.LegacyDecoderException
import com.noneplayer.core.media.legacy.LegacyDecoderLibrary
import com.noneplayer.core.media.legacy.LegacyFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 只认领 [MediaNames.MIME_WMV2] 和 [MediaNames.MIME_MPEG4_ASP]。
 *
 * 不认 `video/mp4v-es` / `video/mjpeg`，所以硬件优先开或关时，Simple Profile 和设备已声明
 * ASP 的流仍由平台渲染器获得。构造函数不加载 native。
 *
 * CPU 渲染经 [OwnedSurfaceCloner] 走给定 Surface 的 Parcel 克隆，解码器释放时归还
 * CPU 连接——否则下一条平台流的 MediaCodec 会 `connect: already connected` 起不来
 * （完整缘由见 [OwnedSurfaceCloner] 类注释）。
 */
@OptIn(UnstableApi::class)
class LegacyVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(
    allowedJoiningTimeMs,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    private var decoder: LegacyVideoDecoder? = null
    private val surfaces = OwnedSurfaceCloner("LegacyVideoRenderer")

    override fun getName(): String = TAG

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType
        if (!LegacyDecoderLibrary.supports(mime)) return C.FORMAT_UNSUPPORTED_TYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {
        return LegacyVideoDecoder(format, onReleased = surfaces::release).also { decoder = it }
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        try {
            val active = decoder ?: throw DecoderException("legacy decoder released", null)
            active.render(outputBuffer, surfaces.surfaceFor(surface))
        } finally {
            outputBuffer.release()
        }
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        decoder?.outputMode = outputMode
    }

    /** 换集/换轨时解码器释放回调之外的兜底——有时 renderer 先 disable 再重建解码器 */
    override fun onDisabled() {
        surfaces.release()
        super.onDisabled()
    }

    override fun onRelease() {
        surfaces.release()
        super.onRelease()
    }

    private companion object {
        const val TAG = "LegacyVideoRenderer"
    }
}

@OptIn(UnstableApi::class)
private class LegacyVideoDecoder(
    format: Format,
    private val onReleased: () -> Unit,
) :
    Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private val codecName = LegacyDecoderLibrary.codecName(format.sampleMimeType)
        ?: throw DecoderException("no legacy codec for ${format.sampleMimeType}", null)

    private val codec: LegacyCodec = try {
        val extra = format.initializationData.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { parts ->
                val total = parts.sumOf { it.size }
                ByteArray(total).also { out ->
                    var at = 0
                    for (part in parts) {
                        part.copyInto(out, at)
                        at += part.size
                    }
                }
            }
        LegacyCodec(
            codecName,
            extra,
            format.width.takeIf { it > 0 } ?: 0,
            format.height.takeIf { it > 0 } ?: 0,
        )
    } catch (e: LegacyDecoderException) {
        throw DecoderException(e.message ?: "legacy decoder", e)
    }

    var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    private val availableIn = ArrayDeque<DecoderInputBuffer>()
    private var dequeuedIn: DecoderInputBuffer? = null
    private val readyOut = ArrayDeque<VideoDecoderOutputBuffer>()
    private val freeOut = ArrayDeque<VideoDecoderOutputBuffer>()
    private val owner = DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> { buffer ->
        buffer.clear()
        freeOut.add(buffer)
    }
    private var outputStartTimeUs = C.TIME_UNSET
    private var skipCarry = 0
    private var eosQueued = false
    private var released = false

    init {
        repeat(4) {
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
            buffer.ensureSpaceForWrite(DEFAULT_INPUT)
            availableIn.add(buffer)
        }
        repeat(4) { freeOut.add(VideoDecoderOutputBuffer(owner)) }
    }

    override fun getName(): String = "nplegacy-$codecName"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        if (dequeuedIn != null || availableIn.isEmpty()) return null
        dequeuedIn = availableIn.removeFirst()
        return dequeuedIn
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        try {
            val eos = inputBuffer.isEndOfStream()
            val pts = inputBuffer.timeUs
            val data = inputBuffer.data
            val bytes = if (!eos && data != null && data.remaining() > 0) {
                ByteArray(data.remaining()).also { data.get(it) }
            } else {
                ByteArray(0)
            }
            codec.send(bytes, pts, eos)
            pump(fallbackPts = pts)
            if (eos) {
                eosQueued = true
                enqueueEos()
            }
        } catch (e: LegacyDecoderException) {
            throw DecoderException(e.message ?: "legacy decoder", e)
        } finally {
            inputBuffer.clear()
            if (dequeuedIn === inputBuffer) dequeuedIn = null
            availableIn.add(inputBuffer)
        }
    }

    override fun dequeueOutputBuffer(): VideoDecoderOutputBuffer? =
        if (readyOut.isEmpty()) null else readyOut.removeFirst()

    override fun flush() {
        codec.flush()
        eosQueued = false
        skipCarry = 0
        dequeuedIn?.let {
            it.clear()
            availableIn.add(it)
            dequeuedIn = null
        }
        while (readyOut.isNotEmpty()) readyOut.removeFirst().release()
    }

    override fun release() {
        if (released) return
        released = true
        codec.close()
        // 顺带归还克隆 Surface 的 CPU 连接（见 LegacyVideoRenderer 类注释）
        onReleased()
    }

    fun render(buffer: VideoDecoderOutputBuffer, surface: Surface) {
        val planes = buffer.yuvPlanes ?: throw DecoderException("missing yuv planes", null)
        try {
            codec.render(
                y = planes[0],
                yStride = buffer.yuvStrides?.get(0) ?: buffer.yStride,
                u = planes[1],
                uvStride = buffer.yuvStrides?.get(1) ?: buffer.uvStride,
                v = planes[2],
                width = buffer.width,
                height = buffer.height,
                colorspace = buffer.colorspace,
                surface = surface,
            )
        } catch (e: LegacyDecoderException) {
            throw DecoderException(e.message ?: "legacy decoder", e)
        }
    }

    private fun pump(fallbackPts: Long) {
        while (true) {
            val frame = codec.receive() ?: break
            if (outputStartTimeUs != C.TIME_UNSET && frame.ptsUs != C.TIME_UNSET && frame.ptsUs < outputStartTimeUs) {
                skipCarry++
                continue
            }
            readyOut.add(fill(frame, fallbackPts))
        }
    }

    private fun fill(frame: LegacyFrame, fallbackPts: Long): VideoDecoderOutputBuffer {
        val output = if (freeOut.isEmpty()) VideoDecoderOutputBuffer(owner) else freeOut.removeFirst()
        val pts = if (frame.ptsUs == C.TIME_UNSET || frame.ptsUs == Long.MIN_VALUE) fallbackPts else frame.ptsUs
        output.init(pts, outputMode, null)
        if (!output.initForYuvFrame(frame.width, frame.height, frame.yStride, frame.uvStride, frame.colorspace)) {
            throw DecoderException("yuv buffer alloc failed ${frame.width}x${frame.height}", null)
        }
        copyPlane(frame.y, output.yuvPlanes!![0])
        copyPlane(frame.u, output.yuvPlanes!![1])
        copyPlane(frame.v, output.yuvPlanes!![2])
        output.skippedOutputBufferCount = skipCarry
        skipCarry = 0
        return output
    }

    private fun copyPlane(src: ByteBuffer, dst: ByteBuffer) {
        src.position(0)
        src.limit(src.capacity())
        dst.clear()
        val bytes = minOf(src.remaining(), dst.remaining())
        val previous = src.limit()
        src.limit(src.position() + bytes)
        dst.put(src)
        src.limit(previous)
    }

    private fun enqueueEos() {
        val output = if (freeOut.isEmpty()) VideoDecoderOutputBuffer(owner) else freeOut.removeFirst()
        output.init(0, outputMode, null)
        output.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        output.skippedOutputBufferCount = skipCarry
        skipCarry = 0
        readyOut.add(output)
    }

    private companion object {
        const val DEFAULT_INPUT = 256 * 1024
    }
}
