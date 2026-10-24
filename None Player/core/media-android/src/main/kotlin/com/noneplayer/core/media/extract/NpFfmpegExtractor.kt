package com.noneplayer.core.media.extract

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
    private val packetBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
    private val packetCopy = ByteArray(2 * 1024 * 1024)

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

        // 上一个包还没交给 TrackOutput（先 drain 再读新包）
        if (pendingStream >= 0) {
            dispatchPending()
            return Extractor.RESULT_CONTINUE
        }

        val rc = NpFfmpegNative.npffExtRead(handle)
        return when (rc) {
            0 -> {
                pendingStream = NpFfmpegNative.npffExtPacketStream(handle)
                pendingPtsUs = NpFfmpegNative.npffExtPacketPtsUs(handle)
                pendingKeyframe = NpFfmpegNative.npffExtPacketFlags(handle) == 1
                pendingSize = NpFfmpegNative.npffExtPacketSize(handle)
                val track = trackRefFor(pendingStream)
                if (track == null || pendingSize <= 0) {
                    // 非认领流（字幕等）：丢包继续
                    NpFfmpegNative.npffExtPacketData(handle, packetBuffer, packetBuffer.capacity())
                    pendingStream = -1
                    return read(input, seekPosition)
                }
                dispatchPending()
                Extractor.RESULT_CONTINUE
            }
            1 -> {
                val pos = NpFfmpegNative.npffExtPendingSeekPos(handle)
                if (pos < 0) return Extractor.RESULT_END_OF_INPUT
                seekPosition.position = pos
                Extractor.RESULT_SEEK
            }
            -1 -> Extractor.RESULT_END_OF_INPUT
            else -> throw IOException("npff demux read failed")
        }
    }

    private fun trackRefFor(streamIndex: Int): TrackRef? =
        trackOutputs.firstOrNull { it.streamIndex == streamIndex }

    private fun dispatchPending() {
        val track = trackRefFor(pendingStream)
        pendingStream = -1
        track ?: run {
            NpFfmpegNative.npffExtPacketData(handle, packetBuffer, packetBuffer.capacity())
            return
        }
        packetBuffer.clear()
        val n = NpFfmpegNative.npffExtPacketData(handle, packetBuffer, pendingSize)
        if (n <= 0) return
        packetBuffer.get(packetCopy, 0, n)
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
                    if (mime == MediaNames.MIME_WMV3 || mime == MediaNames.MIME_VC1 ||
                        mime == MediaNames.MIME_WMV1 || mime == MediaNames.MIME_WMV2
                    ) {
                        setMaxInputSize(maxOf(info[4], 1))
                    }
                }
                .build()
            val trackOutput = sink.track(trackId++, if (type == 1) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO)
            if (type == 1) {
                val csd = NpFfmpegNative.npffExtStreamExtraData(handle, index)
                if (csd != null) {
                    trackOutput.format(format.buildUpon().setInitializationData(listOf(csd)).build())
                } else {
                    trackOutput.format(format)
                }
            } else {
                // csd 只给真正消费它的路径。WMA 族吃"整段 WAVEFORMATEX"（tag@0 供路由
                // 细分，解码器侧切掉 18B 头）；其余 npff 表内 mime（cook/sipr/atrac3/mp2…）
                // 的 avformat extradata 已是 FFmpeg 原生形态，原样直传；AAC 平台解码要
                // AudioSpecificConfig 原样作 csd-0；mp3 等平台轨自解析——**绝不能**把合成
                // WAVE 头塞给平台解码器（E207 真机：MP3 带 WAVE csd-0 → c2 首帧 Error 0xe）
                if (mime == MediaNames.MIME_WMA) {
                    val wav = NpFfmpegNative.npffExtStreamWavHeader(handle, index)
                    if (wav != null) {
                        trackOutput.format(format.buildUpon().setInitializationData(listOf(wav)).build())
                    } else {
                        trackOutput.format(format)
                    }
                } else if (NpFfmpegCodecs.inTable(mime)) {
                    val csd = NpFfmpegNative.npffExtStreamExtraData(handle, index)
                    if (csd != null) {
                        trackOutput.format(format.buildUpon().setInitializationData(listOf(csd)).build())
                    } else {
                        trackOutput.format(format)
                    }
                } else if (mime == MimeTypes.AUDIO_AAC) {
                    val csd = NpFfmpegNative.npffExtStreamExtraData(handle, index)
                    if (csd != null) {
                        trackOutput.format(format.buildUpon().setInitializationData(listOf(csd)).build())
                    } else {
                        trackOutput.format(format)
                    }
                } else {
                    trackOutput.format(format)
                }
            }
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
                SeekMap.SeekPoints(SeekPoint(timeUs.coerceIn(0, duration.coerceAtLeast(0)), 0))
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
