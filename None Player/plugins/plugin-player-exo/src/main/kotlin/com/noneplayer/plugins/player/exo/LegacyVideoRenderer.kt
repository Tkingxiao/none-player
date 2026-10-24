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
import com.noneplayer.core.media.legacy.VideoCodec
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 只认领 [MediaNames.MIME_WMV2] 和 [MediaNames.MIME_MPEG4_ASP]。
 *
 * 不认 `video/mp4v-es` / `video/mjpeg`，所以硬件优先开或关时，Simple Profile 和设备已声明
 * ASP 的流仍由平台渲染器获得。构造函数不加载 native。
 *
 * E204 白名单批参数化：同一渲染器经 [codecNameFor]/[codecFactory] 可驱动任意
 * [VideoCodec] 实现（legacy 后端 = libnplegacy 静态最小 avcodec；npffmpeg 后端 =
 * NextLib 共享 libavcodec 的白名单解码器）。默认参数即 legacy 行为，零改动。
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
    private val rendererName: String = "LegacyVideoRenderer",
    /**
     * mime → 解码器名；null = 不认领（渲染器 supportsFormat 的唯一判据）。
     *
     * 默认（legacy 后端）必须把 [LegacyDecoderLibrary.isAvailable] 带进来：E206 参数化时
     * 只留了 `codecName()`（纯字符串映射），supportsFormat 于是会在 `libnplegacy` 尚未
     * 加载时认领 WMV2/ASP，createDecoder 里 `LegacyNative.nativeCreate` 直接
     * UnsatisfiedLinkError 崩溃（E208 真机：WMV1+WMA 播完自动连播到 WMV2 条目时复现）。
     */
    private val codecNameFor: (String?) -> String? = { mime ->
        legacyClaimableCodec(mime, LegacyDecoderLibrary.isAvailable())
    },
    private val codecFactory: (codecName: String, extradata: ByteArray?, width: Int, height: Int) -> VideoCodec =
        { name, extra, w, h -> LegacyCodec(name, extra, w, h) },
) : DecoderVideoRenderer(
    allowedJoiningTimeMs,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    private var decoder: LegacyVideoDecoder? = null
    private val surfaces = OwnedSurfaceCloner(rendererName)

    override fun getName(): String = rendererName

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType
        if (codecNameFor(mime) == null) return C.FORMAT_UNSUPPORTED_TYPE
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
        return LegacyVideoDecoder(
            format,
            onReleased = surfaces::release,
            rendererName = rendererName,
            codecNameFor = codecNameFor,
            codecFactory = codecFactory,
        ).also { decoder = it }
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
}

/**
 * legacy 后端的能力判据（纯函数，便于 JVM 钉住语义）：库不可用时**不得**认领
 * WMV2/ASP，否则 createDecoder 会直接打 JNI 并 UnsatisfiedLinkError（E208）。
 */
internal fun legacyClaimableCodec(mime: String?, libraryAvailable: Boolean): String? =
    LegacyDecoderLibrary.codecName(mime)?.takeIf { libraryAvailable }

@OptIn(UnstableApi::class)
private class LegacyVideoDecoder(
    format: Format,
    private val onReleased: () -> Unit,
    rendererName: String,
    codecNameFor: (String?) -> String?,
    codecFactory: (codecName: String, extradata: ByteArray?, width: Int, height: Int) -> VideoCodec,
) :
    Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private val codecName = codecNameFor(format.sampleMimeType)
        ?: throw DecoderException("no codec for ${format.sampleMimeType}", null)

    private val codec: VideoCodec = try {
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
        codecFactory(codecName, extra, format.width.takeIf { it > 0 } ?: 0, format.height.takeIf { it > 0 } ?: 0)
    } catch (e: Exception) {
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
    private val outputMatrices = java.util.WeakHashMap<VideoDecoderOutputBuffer, Int>()
    private var skipCarry = 0
    private var eosReceived = false
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

    override fun getName(): String = "npff-$codecName"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        if (dequeuedIn != null || availableIn.isEmpty()) return null
        // E204：readyOut 背压必须挡在**入口**——解码出帧快于上屏消费时不再吃输入，
        // 让 media3 把积压放进有界的 SampleQueue（LoadControl 60s）。挡在 pump 里
        // 不行：原生队列只有 4 帧，满了再 send 会撞 AVERROR(EAGAIN) 直接报错。
        if (readyOut.size >= MAX_READY_OUT) return null
        dequeuedIn = availableIn.removeFirst()
        return dequeuedIn
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        try {
            val eos = inputBuffer.isEndOfStream()
            val pts = inputBuffer.timeUs
            val data = inputBuffer.data
            // A1 批：direct buffer 直送 JNI（nativeSendDirect），不再每帧
            // `ByteArray(remaining())` 拷一份；非 direct 兜底由 LegacyCodec 的
            // direct scratch 复制处理，行为不变。
            val direct = if (!eos && data != null && data.remaining() > 0) data else null
            codec.send(direct, pts, eos)
            if (eos) eosReceived = true
            pump(fallbackPts = pts)
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
        eosReceived = false
        eosQueued = false
        outputMatrices.clear()
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
                matrix = outputMatrices.remove(buffer) ?: 0,
                surface = surface,
            )
        } catch (e: LegacyDecoderException) {
            throw DecoderException(e.message ?: "legacy decoder", e)
        }
    }

    private fun pump(fallbackPts: Long) {
        // 原生队列必须每次排空（≤4 帧，原生内存），否则 send 撞 EAGAIN；
        // 上屏消费的背压由 dequeueInputBuffer 在 readyOut 满时挡（见上）。
        while (true) {
            val frame = codec.receive() ?: break
            if (outputStartTimeUs != C.TIME_UNSET && frame.ptsUs != C.TIME_UNSET && frame.ptsUs < outputStartTimeUs) {
                skipCarry++
                continue
            }
            readyOut.add(fill(frame, fallbackPts))
        }
        // 原生队列上限只有 4 帧，EOS 后的尾帧要等解码器真正排空再发 EOS。
        if (eosReceived && !eosQueued && codec.isEof) {
            eosQueued = true
            enqueueEos()
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
        outputMatrices[output] = frame.matrix
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

        /** readyOut 积压上限：挡住"解码快于上屏"的稳态增长（E204 OOM） */
        const val MAX_READY_OUT = 8
    }
}
