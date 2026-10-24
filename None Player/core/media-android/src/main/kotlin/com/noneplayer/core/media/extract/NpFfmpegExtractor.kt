package com.noneplayer.core.media.extract

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.ffmpeg.NpExtractorReaderImpl
import com.noneplayer.core.media.ffmpeg.NpFfmpegCodecs
import com.noneplayer.core.media.ffmpeg.NpFfmpegNative
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * avformat 解封装（E207 avformat 批）：asf / rm / flv / mpegps / nut。
 *
 * 这些容器 media3 没有解封装器（ASF/RM）或实现不覆盖白名单编码（FLV 的 VP6/FLV1、
 * PS 的全轨），由本提取器统一接 avformat 的对应 demuxer。sniff 只认容器 magic——
 * FLV 全量走 avformat（首 tag 常是 onMetaData 脚本 tag，按"首视频 tag codec"分流
 * 不可靠，且其 H.264-FLV 一样能解，E207 真机已删该启发式）；AVI/MP4/MKV 不在此认领。
 *
 * 结构：Kotlin 实现 media3 Extractor；demux 本体在 C（np_ffmpeg_extractor.c）。
 * 读桥 [Bridge] 实现 [NpExtractorReader]（方法名受 proguard keep 保护）；seek 走
 * "AVIO 挂起 → RESULT_SEEK(holder=目标偏移) → media3 重建输入" 的标准循环。
 * 包传递二段式（C 侧缓存一包 → Kotlin 查询并取走），无 JNI 回调到 TrackOutput。
 */
@OptIn(UnstableApi::class)
class NpFfmpegExtractor(private val iformatName: String, private val containerName: String) : Extractor {

    /** sniff 魔数（顺序无关）：name → magic */
    companion object {
        private const val TAG = "NpFfmpegExtractor"

        /** 单包上限：超过按损坏包丢弃。64MB 远高于 4K 关键帧，同时挡住伪造头 OOM。 */
        internal const val MAX_PACKET_BYTES = 64 * 1024 * 1024

        /** 包缓冲起步值：按需增长；不为未被嗅探认领的实例预分配（工厂一次造 5 个）。 */
        internal const val INITIAL_PACKET_CAPACITY = 64 * 1024

        private val CONTAINERS = listOf(
            Triple("asf", byteArrayOf(0x30.toByte(), 0x26.toByte(), 0xB2.toByte(), 0x75.toByte()), "WMV (ASF)"),
            Triple("rm", byteArrayOf(0x2E, 0x52, 0x4D, 0x46), "RealMedia (RM)"),
            // FLV 第 4 字节是版本号（0x01），只比 "FLV" 三字节
            Triple("flv", byteArrayOf(0x46, 0x4C, 0x56), "FLV"),
            Triple("mpegps", byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte()), "MPEG-PS"),
            Triple("nut", "NUTM".toByteArray(Charsets.ISO_8859_1), "NUT"),
        )
    }

    private var output: ExtractorOutput? = null
    private var handle = 0L
    private var streamsEmitted = false
    private var pendingStream = -1
    private var pendingPtsUs = 0L
    private var pendingKeyframe = false
    private var pendingSize = 0
    private var durationUs = 0L
    private val bridge = NpExtractorReaderImpl()
    private var packetBuffer: ByteBuffer? = null
    private var packetCopy = ByteArray(0)
    private var discardBuffer: ByteBuffer? = null

    private data class TrackRef(val streamIndex: Int, val output: TrackOutput, val mime: String?)

    private val trackOutputs = mutableListOf<TrackRef>()
    private var tracksEnded = false

    val containerDisplayName: String get() = containerName

    override fun sniff(input: ExtractorInput): Boolean {
        // 只验 4 字节容器 magic（E207 真机：FLV 首个 tag 常是 onMetaData 脚本 tag，
        // 按"首 tag 类型/codec"启发式分流会让媒体3 的 FlvExtractor 抢走整条流、
        // 只解出音频——FLV 全量走 avformat，其 H.264-FLV 一样能解）
        val head = ByteArray(4)
        if (!input.peekFully(head, 0, 4, true)) return false
        input.resetPeekPosition()
        val magic = CONTAINERS.firstOrNull { it.first == iformatName }?.second ?: return false
        for (i in magic.indices) {
            if (head[i] != magic[i]) return false
        }
        return true
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // attach 必须先于 open：avformat_open_input 里的首个读回调就发生在
        // npffExtOpen 的调用栈内（E207 真机实录：先 open 后 attach 会拿到 NULL 输入）
        bridge.attachInput(input)
        if (handle == 0L) {
            // native 前置闸：libnpffmpeg 未加载时这里调 npffExtOpen 会直接
            // UnsatisfiedLinkError（"No implementation found"）——加载失败按
            // "本提取器不认领"放行给链上后续 extractor。
            if (!NpFfmpegCodecs.isAvailable()) return Extractor.RESULT_END_OF_INPUT
            handle = NpFfmpegNative.npffExtOpen(bridge, iformatName)
            if (handle == 0L) return Extractor.RESULT_END_OF_INPUT
        }

        if (!streamsEmitted) {
            val n = NpFfmpegNative.npffExtFindStreams(handle)
            if (n <= 0) return Extractor.RESULT_END_OF_INPUT
            durationUs = NpFfmpegNative.npffExtDurationUs(handle)
            emitStreams(n)
            streamsEmitted = true
        }

        // 丢包用循环而不是递归：不支持编码的长片可以让数万包连续落到
        // "非认领流" 分支，递归会直接爆栈。
        while (true) {
            if (pendingStream >= 0) {
                dispatchPending()
                return Extractor.RESULT_CONTINUE
            }

            when (val rc = NpFfmpegNative.npffExtRead(handle)) {
                0 -> {
                    val stream = NpFfmpegNative.npffExtPacketStream(handle)
                    val size = NpFfmpegNative.npffExtPacketSize(handle)
                    val track = trackRefFor(stream)
                    if (track == null || size <= 0 || size > MAX_PACKET_BYTES) {
                        if (size > MAX_PACKET_BYTES) {
                            Log.w(TAG, "丢包：声称 $size 字节，超过上限 $MAX_PACKET_BYTES")
                        }
                        discardPacket()
                        continue
                    }
                    pendingStream = stream
                    pendingPtsUs = NpFfmpegNative.npffExtPacketPtsUs(handle)
                    pendingKeyframe = NpFfmpegNative.npffExtPacketFlags(handle) == 1
                    pendingSize = size
                    dispatchPending()
                    return Extractor.RESULT_CONTINUE
                }
                1 -> {
                    val pos = NpFfmpegNative.npffExtPendingSeekPos(handle)
                    if (pos < 0) return Extractor.RESULT_END_OF_INPUT
                    seekPosition.position = pos
                    return Extractor.RESULT_SEEK
                }
                -1 -> return Extractor.RESULT_END_OF_INPUT
                else -> throw IOException("npff demux read failed")
            }
        }
    }

    private fun trackRefFor(streamIndex: Int): TrackRef? =
        trackOutputs.firstOrNull { it.streamIndex == streamIndex }

    private fun dispatchPending() {
        val stream = pendingStream
        val size = pendingSize
        pendingStream = -1
        val track = trackRefFor(stream)
        if (track == null) {
            discardPacket()
            return
        }
        val buffer = ensurePacketBuffer(size)
        val n = NpFfmpegNative.npffExtPacketData(handle, buffer, size)
        if (n != size) {
            // C 侧按 direct buffer 真实 capacity 再夹一次；不一致说明容量契约坏了，
            // 已消费的包只能丢（绝不把截断样本交给解码器）。
            Log.w(TAG, "包拷贝不完整 expected=$size got=$n（该包丢弃）")
            return
        }
        buffer.clear()
        buffer.get(packetCopy, 0, n)
        val parsable = androidx.media3.common.util.ParsableByteArray(packetCopy, n)
        track.output.sampleData(parsable, n, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.output.sampleMetadata(
            pendingPtsUs,
            if (pendingKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0,
            n,
            0,
            null,
        )
    }

    /** 按需扩容（不被嗅探认领的实例零分配）；容量夹在 [MAX_PACKET_BYTES]。 */
    private fun ensurePacketBuffer(size: Int): ByteBuffer {
        val current = packetBuffer
        val target = packetCapacityFor(current?.capacity() ?: 0, size)
        if (current != null && current.capacity() == target) return current
        return ByteBuffer.allocateDirect(target).also {
            packetBuffer = it
            packetCopy = ByteArray(target)
        }
    }

    /** 丢弃当前 C 侧缓存包；1 字节 direct 只是 JNI 取地址的载体。 */
    private fun discardPacket() {
        val buffer = discardBuffer
            ?: ByteBuffer.allocateDirect(1).also { discardBuffer = it }
        NpFfmpegNative.npffExtPacketData(handle, buffer, 1)
    }

    private fun emitStreams(streamCount: Int) {
        val sink = output ?: return
        var trackId = 0
        for (index in 0 until streamCount) {
            val type = NpFfmpegNative.npffExtStreamType(handle, index)
            if (type != 1 && type != 2) continue
            val codecName = NpFfmpegNative.npffExtStreamCodecName(handle, index) ?: continue
            val mime = mimeForCodecName(codecName)
            if (mime == null) continue // 不认识的编码：轨丢弃（诚实边界）
            val info = NpFfmpegNative.npffExtStreamInfo(handle, index)

            // csd 只给真正消费它的路径：
            // - WMA 三兄弟（wmav1/2/pro/lossless）吃"整段 WAVEFORMATEX"（tag@0 供路由
            //   细分、blockAlign@12 供解码器 open，解码器侧切掉 18B 头）；
            // - 其余 npff 表内 mime（cook/sipr/atrac3/mp2…）的 avformat extradata 已是
            //   FFmpeg 原生形态，原样直传；
            // - Vorbis 的平台/NextLib 解码器要 Xiph lacing 拆出的三段头，缺了会在
            //   NextLib FfmpegAudioDecoder 里越界（E208 审查发现）；
            // - AAC 平台解码要 AudioSpecificConfig 原样作 csd-0；
            // - mp3 等平台轨自解析——**绝不能**把合成 WAVE 头塞给平台解码器
            //   （E207 真机：MP3 带 WAVE csd-0 → c2 首帧 Error 0xe）。
            val initData: List<ByteArray>? = if (type == 1) {
                NpFfmpegNative.npffExtStreamExtraData(handle, index)?.let { listOf(it) }
            } else {
                when {
                    NpFfmpegCodecs.isWmaFamily(mime) ->
                        NpFfmpegNative.npffExtStreamWavHeader(handle, index)?.let { listOf(it) }
                    mime == MimeTypes.AUDIO_VORBIS ->
                        splitXiphExtradata(NpFfmpegNative.npffExtStreamExtraData(handle, index))
                    NpFfmpegCodecs.inTable(mime) || mime == MimeTypes.AUDIO_AAC ->
                        NpFfmpegNative.npffExtStreamExtraData(handle, index)?.let { listOf(it) }
                    else -> null
                }
            }
            if (mime == MimeTypes.AUDIO_VORBIS && initData == null) {
                // 拿不到三段头就不能把轨交给 NextLib（其 getVorbisExtraData 按 index 取）。
                Log.w(TAG, "vorbis csd 不是 Xiph lacing，丢轨")
                continue
            }

            val format = androidx.media3.common.Format.Builder()
                .setSampleMimeType(mime)
                .apply {
                    if (type == 1) {
                        if (info[0] > 0) setWidth(info[0])
                        if (info[1] > 0) setHeight(info[1])
                    } else {
                        if (info[2] > 0) setSampleRate(info[2])
                        if (info[3] > 0) setChannelCount(info[3])
                        if (mime == MimeTypes.AUDIO_RAW) {
                            setPcmEncoding(C.ENCODING_PCM_16BIT)
                        }
                    }
                    if (initData != null) setInitializationData(initData)
                }
                .build()
            val trackOutput = sink.track(trackId++, if (type == 1) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO)
            trackOutput.format(format)
            trackOutputs.add(TrackRef(index, trackOutput, mime))
        }
        val duration = durationUs
        sink.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            // 时长未知（流模式下估不出，如 RM 无索引）给 TIME_UNSET，别冒充 0 时长
            override fun getDurationUs(): Long = duration.takeIf { it > 0 } ?: C.TIME_UNSET
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                // 位置哨兵 0：seek(position=0, timeUs) 的时间参数才是真值，
                // avformat_seek_file 选点后经 AVIO 挂起 → RESULT_SEEK 落到实际偏移
                SeekMap.SeekPoints(SeekPoint(seekPointTimeUs(duration, timeUs), 0))
        })
        sink.endTracks()
        tracksEnded = true
    }

    override fun seek(position: Long, timeUs: Long) {
        if (handle == 0L) return
        pendingStream = -1
        NpFfmpegNative.npffExtSeekTimeUs(handle, timeUs)
    }

    override fun release() {
        if (handle != 0L) {
            NpFfmpegNative.npffExtClose(handle)
            handle = 0L
        }
    }

    /**
     * 流内 FFmpeg 编码名 → mime。与 [NpFfmpegCodecs] 的路由表严格互逆：
     * 表内编码"一个解码器一个 mime"（vp6/rv/atrac3 各变体分开），保证渲染器按表
     * 实例化的解码器与流内编码一致；表外但有 mime 的（atrac3al/pal）让探测给出
     * A11"音频不支持"提示而不是静默丢轨；完全没映射的编码轨丢弃（诚实边界）。
     */
    private fun mimeForCodecName(name: String): String? = when (name) {
        "wmv3" -> MediaNames.MIME_WMV3
        "wmv2" -> MediaNames.MIME_WMV2
        "wmv1" -> MediaNames.MIME_WMV1
        "vc1" -> MediaNames.MIME_VC1
        "msmpeg4v2" -> "video/mp42"
        "msmpeg4", "msmpeg4v3" -> "video/mp43"
        "rv10" -> "video/x-pn-realvideo"
        "rv20" -> "video/x-pn-realvideo2"
        "rv30" -> "video/x-pn-realvideo3"
        "rv40" -> "video/x-pn-realvideo4"
        "vp6" -> "video/x-vp6"
        "vp6f" -> "video/x-vp6f"
        "vp6a" -> "video/x-vp6a"
        "flv1" -> "video/x-flv1"
        "ffv1" -> "video/x-ffv1"
        // mpeg1/2 视频走 npff 专属 mime（不走平台 video/mpeg2）：SD860/MIUI 的
        // OMX.qcom mpeg2 硬解实测坏——10s 只渲染 1 帧后停滞 → "Unexpected runtime error"
        // （E207 真机）；npff 软解 mpeg2 稳定，SD 内容 CPU 成本可忽略。TS 容器由
        // media3 TsExtractor 产出标准 video/mpeg2，不经过这里，平台行为不变。
        "mpeg1video" -> "video/x-np-mpeg1"
        "mpeg2video" -> "video/x-np-mpeg2"
        "h264" -> MimeTypes.VIDEO_H264
        "mjpeg" -> MimeTypes.VIDEO_MJPEG
        "theora" -> "video/theora"
        "wmav1", "wmav2" -> MediaNames.MIME_WMA
        "wmapro" -> MediaNames.MIME_WMAPRO
        "wmalossless" -> MediaNames.MIME_WMALOSSLESS
        "cook" -> "audio/x-cook"
        "sipr" -> "audio/x-sipr"
        "atrac3" -> "audio/x-atrac3"
        "atrac3al" -> "audio/x-atrac3al"
        "atrac3pal" -> "audio/x-atrac3pal"
        "wavpack" -> "audio/x-wavpack"
        "tta" -> "audio/x-tta"
        "mp1" -> MimeTypes.AUDIO_MPEG_L1
        "mp2" -> MimeTypes.AUDIO_MPEG_L2
        "mp3" -> MimeTypes.AUDIO_MPEG
        "aac" -> MimeTypes.AUDIO_AAC
        "ac3" -> MimeTypes.AUDIO_AC3
        "eac3" -> MimeTypes.AUDIO_E_AC3
        "vorbis" -> MimeTypes.AUDIO_VORBIS
        "pcm_s16le" -> MimeTypes.AUDIO_RAW
        else -> null
    }
}

/**
 * 包缓冲扩容策略（纯函数便于 JVM 测试）：不缩容；需要时一步长到目标或 64KB 起步，
 * 上限 [NpFfmpegExtractor.MAX_PACKET_BYTES]。调用方负责先拒绝超过上限的包。
 */
internal fun packetCapacityFor(currentCapacity: Int, packetSize: Int): Int {
    require(packetSize > 0) { "packetSize=$packetSize" }
    if (packetSize <= currentCapacity) return currentCapacity
    return max(packetSize, NpFfmpegExtractor.INITIAL_PACKET_CAPACITY)
        .coerceAtMost(NpFfmpegExtractor.MAX_PACKET_BYTES)
}

/**
 * SeekMap 的时间钳位：**时长未知（≤0）时不能把目标夹到 0**。
 * 旧写法 `coerceIn(0, duration.coerceAtLeast(0))` 在 RM/NUT 这类估不出时长的容器上
 * 把每次 seek 都变成 0，E208 审查修复。
 */
internal fun seekPointTimeUs(durationUs: Long, requestedUs: Long): Long {
    val requested = requestedUs.coerceAtLeast(0L)
    return if (durationUs > 0L) requested.coerceAtMost(durationUs) else requested
}

/**
 * Vorbis 的 AVCodecParameters.extradata = Xiph lacing 的三段头：
 * `[包数-1][len0 7bit-varlen][h0][len1][h1][h2]`。Media3/NextLib 要的是拆开的
 * `initializationData[0..2]`，整段直传会让 NextLib 的 getVorbisExtraData 越界。
 * 解析失败返回 null（调用方丢轨，不把不完整头交给解码器）。
 */
internal fun splitXiphExtradata(csd: ByteArray?): List<ByteArray>? {
    if (csd == null || csd.size < 4) return null
    val packetCount = (csd[0].toInt() and 0xff) + 1
    if (packetCount < 3) return null
    var pos = 1
    val lengths = IntArray(packetCount - 1)
    for (i in lengths.indices) {
        var len = 0
        var shift = 0
        while (true) {
            if (pos >= csd.size || shift > 28) return null
            val b = csd[pos++].toInt() and 0xff
            len = len or ((b and 0x7f) shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        lengths[i] = len
    }
    val parts = ArrayList<ByteArray>(packetCount)
    for (len in lengths) {
        if (len <= 0 || pos + len > csd.size) return null
        parts.add(csd.copyOfRange(pos, pos + len))
        pos += len
    }
    if (pos >= csd.size) return null
    parts.add(csd.copyOfRange(pos, csd.size))
    return parts
}

/** 工厂：每个容器一个实例（sniff 各认各的 magic）。 */
@OptIn(UnstableApi::class)
class NpFfmpegExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(
        NpFfmpegExtractor("asf", "WMV (ASF)"),
        NpFfmpegExtractor("rm", "RealMedia (RM)"),
        NpFfmpegExtractor("flv", "FLV"),
        NpFfmpegExtractor("mpegps", "MPEG-PS"),
        NpFfmpegExtractor("nut", "NUT"),
    )
}
