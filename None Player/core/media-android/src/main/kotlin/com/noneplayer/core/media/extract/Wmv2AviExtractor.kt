package com.noneplayer.core.media.extract

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.AviChunk
import com.noneplayer.core.media.MediaNames
import java.io.EOFException
import java.io.IOException
import kotlin.math.abs

/**
 * 只认「内层 AviExtractor 会丢掉的 WMV2 视频轨」。
 *
 * 非 WMV2 的 AVI 在 [sniff] 返回 false，仍由 [com.homesoft.exo.extractor.AviExtractorsFactory] 解。
 * 时间戳用 strh 的 dwScale/dwRate；有 idx1 时按关键帧做 SeekMap。
 */
@OptIn(UnstableApi::class)
class Wmv2AviExtractor : Extractor {

    private enum class Phase { HEADER, INDEX, VERIFY, BUILD, EMIT, SAMPLE }

    private class StreamInfo(
        val index: Int,
        val isWmv2: Boolean,
        val audioMime: String?,
        val scale: Long,
        val rate: Long,
        val length: Long,
        val width: Int,
        val height: Int,
        val extra: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    /**
     * 一条 chunk 索引项。[ckid] 存 fourcc 的**打包值**而不是字符串——扫描一条两小时
     * 视频是十几万次，每次 `String(b, o, 4)` 就是十几万个短命对象（A1）。
     */
    private class RawIdx(val ckid: Int, val flags: Long, val offset: Long, val size: Int)

    private class Sample(
        val stream: Int,
        val offset: Long,
        val size: Int,
        val timeUs: Long,
        val keyframe: Boolean,
    )

    private var output: ExtractorOutput? = null
    private var phase = Phase.HEADER
    private var riffChecked = false
    private val streams = mutableListOf<StreamInfo>()
    private var moviFccPos = -1L
    private var moviDataStart = -1L
    private var moviEnd = -1L
    private var inputLengthCache = AviChunk.UNKNOWN_LENGTH
    private val rawIndex = mutableListOf<RawIdx>()
    private var baseTry = 0
    private var scanCursor = -1L
    private val scanned = mutableListOf<RawIdx>()

    /** 扫描路径上"这条流见过没有"——用集合代替 `scanned.none { }` 的 O(n²) 线性回看。 */
    private val scannedStreams = mutableSetOf<Int>()
    private val samples = mutableListOf<Sample>()

    /** 关键帧信息是否可信：只有 idx1 的 flags 带真位；扫描路径的"首帧即关键帧"是猜的。 */
    private var keyframesReliable = false

    /** 样本按文件偏移是否单调（idx1 是解码顺序，不保证单调；不单调就不能二分）。 */
    private var samplesOffsetSorted = true
    private val tracks = mutableMapOf<Int, TrackOutput>()
    private var sampleCursor = 0
    private var emitted = false

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            peekIsWmv2(input)
        } catch (_: Throwable) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return when (phase) {
            Phase.HEADER -> readHeader(input, seekPosition)
            Phase.INDEX -> readIndex(input, seekPosition)
            Phase.VERIFY -> verifyBase(input, seekPosition)
            Phase.BUILD -> buildByScan(input, seekPosition)
            Phase.EMIT -> emit(seekPosition)
            Phase.SAMPLE -> readSample(input, seekPosition)
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (samples.isEmpty()) {
            resetParse()
            return
        }
        // 拖动是一次 seek 一拍，旧写法 `indexOfFirst` 在播放线程上对十几万条做线性扫。
        // idx1 是解码顺序、不保证偏移单调，所以只有确认单调时才二分。
        sampleCursor = if (samplesOffsetSorted) {
            val found = samples.binarySearch { it.offset.compareTo(position) }
            if (found >= 0) found else (-found - 1).coerceAtMost(samples.size)
        } else {
            val at = samples.indexOfFirst { it.offset >= position }
            if (at < 0) samples.size else at
        }
    }

    override fun release() = Unit

    private fun resetParse() {
        phase = Phase.HEADER
        riffChecked = false
        streams.clear()
        moviFccPos = -1L
        moviDataStart = -1L
        moviEnd = -1L
        inputLengthCache = AviChunk.UNKNOWN_LENGTH
        rawIndex.clear()
        baseTry = 0
        scanCursor = -1L
        scanned.clear()
        scannedStreams.clear()
        samples.clear()
        keyframesReliable = false
        samplesOffsetSorted = true
        tracks.clear()
        sampleCursor = 0
        emitted = false
    }

    private fun readHeader(input: ExtractorInput, holder: PositionHolder): Int {
        if (!riffChecked) {
            val head = ByteArray(12)
            readFully(input, head)
            if (fcc(head, 0) != "RIFF" || fcc(head, 8) != "AVI ") {
                return Extractor.RESULT_END_OF_INPUT
            }
            riffChecked = true
        }
        val hdr = ByteArray(8)
        readFully(input, hdr)
        val chunkStart = input.position - 8
        val id = fcc(hdr, 0)
        val size = u32(hdr, 4)
        if (id == "LIST") {
            // A1：LIST 是**容器**不是叶子，不照声明 size 拒绝。正片 `movi` 的 size 等于
            // 4 加整段媒体数据，OpenDML 更直接写 0xFFFFFFFF——旧写法在这里就抛，探测侧
            // 拿到 0 条 format 判 Unplayable，播放侧报"封装格式暂无解封装支持"。
            // 上限只留给真正按 size 分配内存的地方（hdrl payload、idx1、样本）。
            val type = ByteArray(4)
            readFully(input, type)
            val listType = fcc(type, 0)
            if (size < 4) throw IOException("AVI list too small")
            when (listType) {
                "hdrl" -> {
                    if (size > MAX_CHUNK) throw IOException("AVI hdrl too large: $size")
                    val payloadLen = (size - 4).toInt()
                    val payload = ByteArray(payloadLen)
                    if (payloadLen > 0) readFully(input, payload)
                    streams.clear()
                    streams.addAll(parseHdrl(payload))
                    skipPad(input, size)
                }
                "movi" -> {
                    moviFccPos = input.position - 4
                    moviDataStart = input.position
                    moviEnd = AviChunk.listEnd(chunkStart, size, inputLength(input))
                    if (streams.none { it.isWmv2 }) return Extractor.RESULT_END_OF_INPUT
                    phase = Phase.INDEX
                    holder.position = moviEnd
                    return Extractor.RESULT_SEEK
                }
                else -> {
                    // 已读过 4 字节的 list type：跳过**剩余** payload
                    // （E140 真机采出：先前写成 `size + (size - 4)`，seek 直接越过 movi，
                    //  整片零格式——WMV2 全灭。算术提进 AviChunk 并有单测钉着。）
                    val at = AviChunk.nextAfterUnknownList(input.position, size)
                    if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
                    holder.position = at
                    return Extractor.RESULT_SEEK
                }
            }
        } else {
            // 叶子 chunk 在这里只是**跳过**、不按 size 分配，所以同样不照声明值拒绝
            // （正片里一个巨大的 JUNK/对齐块是合法形态；idx1 走 readIndex，那里才要上限）
            val at = nextChunkPos(input.position, size)
            if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
            holder.position = at
            return Extractor.RESULT_SEEK
        }
        return Extractor.RESULT_CONTINUE
    }

    /** 跳过一段 payload 后的位置：夹到文件长度，未知长度时保持算术值。 */
    private fun nextChunkPos(position: Long, payloadSize: Long): Long {
        val length = inputLengthCache
        return (position + payloadSize + pad(payloadSize)).let { raw ->
            if (length == AviChunk.UNKNOWN_LENGTH || length <= 0L) raw else raw.coerceAtMost(length)
        }
    }

    private fun inputLength(input: ExtractorInput): Long {
        val length = runCatching { input.length.toLong() }.getOrDefault(C.LENGTH_UNSET.toLong())
        inputLengthCache = if (length == C.LENGTH_UNSET.toLong()) AviChunk.UNKNOWN_LENGTH else length
        return inputLengthCache
    }

    private fun readIndex(input: ExtractorInput, holder: PositionHolder): Int {
        val hdr = try {
            val bytes = ByteArray(8)
            readFully(input, bytes)
            bytes
        } catch (_: EOFException) {
            return startScan(holder)
        }
        val id = fcc(hdr, 0)
        val size = u32(hdr, 4)
        if (id == "idx1" && size in 16 until MAX_CHUNK) {
            val payload = ByteArray(size.toInt())
            readFully(input, payload)
            rawIndex.clear()
            var p = 0
            while (p + 16 <= payload.size) {
                rawIndex.add(
                    RawIdx(
                        ckid = AviChunk.fourcc(payload, p),
                        flags = u32(payload, p + 4),
                        offset = u32(payload, p + 8),
                        size = u32(payload, p + 12).toInt(),
                    ),
                )
                p += 16
            }
            if (rawIndex.isEmpty()) return startScan(holder)
            baseTry = 0
            phase = Phase.VERIFY
            return Extractor.RESULT_CONTINUE
        }
        return startScan(holder)
    }

    private fun startScan(holder: PositionHolder): Int {
        scanned.clear()
        scannedStreams.clear()
        scanCursor = moviDataStart
        phase = Phase.BUILD
        holder.position = moviDataStart
        return Extractor.RESULT_SEEK
    }

    private fun verifyBase(input: ExtractorInput, holder: PositionHolder): Int {
        val bases = longArrayOf(moviFccPos, moviDataStart, 0L)
        if (baseTry >= bases.size) return startScan(holder)
        val at = bases[baseTry] + rawIndex.first().offset
        if (at < 0) {
            baseTry++
            return Extractor.RESULT_CONTINUE
        }
        if (input.position != at) {
            holder.position = at
            return Extractor.RESULT_SEEK
        }
        val id = ByteArray(4)
        readFully(input, id)
        if (AviChunk.fourcc(id, 0) == rawIndex.first().ckid) {
            // idx1 的 flags 带真关键帧位，这条路径上 SeekMap 才敢说"可拖"
            materialize(bases[baseTry], rawIndex, fromIndex = true)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        baseTry++
        return Extractor.RESULT_CONTINUE
    }

    private fun buildByScan(input: ExtractorInput, holder: PositionHolder): Int {
        if (scanCursor < 0) scanCursor = moviDataStart
        if (input.position != scanCursor) {
            holder.position = scanCursor
            return Extractor.RESULT_SEEK
        }
        // 每块回一次 SEEK 是 media3 的形状：ExtractorInput 没有 skip/seek，不返回 SEEK
        // 就只能把 payload 真读出来丢掉（两小时正片是几 GB 白读）。**往返的成本在探测侧
        // 每次 SEEK 都 close()+open()**，那半在 FormatProbe 收（A1）。这里改的是每块
        // 都要付的三样东西：String 分配、O(n²) 的"这条流见过没有"、以及一遇异常就
        // 让整片出局的抛法。
        if (scanCursor + 8 > moviEnd) {
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val hdr = ByteArray(8)
        try {
            readFully(input, hdr)
        } catch (_: EOFException) {
            // 文件截断，或流式输入报不出长度：用手上已认出的部分收尾，不把整片判死
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val ckid = AviChunk.fourcc(hdr, 0)
        val size = u32(hdr, 4)
        if (size > MAX_CHUNK) {
            // 单个样本不会这么大：走到这里说明解析已经跑偏。停住收尾（旧写法直接抛
            // IOException，整片出局）。
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val stream = AviChunk.streamIndex(ckid)
        // "这条流的第一块"当关键帧是**猜的**，所以 SeekMap 不据此宣称可拖（见 isSeekable）。
        // 见过没有用集合判，旧写法 `scanned.none { }` 是 O(n²) 次比较 + 每块一个 String。
        val key = if (stream != null && scannedStreams.add(stream)) KEYFRAME else 0L
        scanned.add(RawIdx(ckid, key, scanCursor, size.toInt()))
        val next = scanCursor + 8 + size + AviChunk.pad(size)
        scanCursor = next
        holder.position = next
        return Extractor.RESULT_SEEK
    }

    private fun materialize(base: Long, entries: List<RawIdx>, fromIndex: Boolean) {
        samples.clear()
        keyframesReliable = fromIndex
        samplesOffsetSorted = true
        val ordinal = HashMap<Int, Int>()
        var videoSeen = false
        var previousOffset = Long.MIN_VALUE
        for (entry in entries) {
            val streamIndex = AviChunk.streamIndex(entry.ckid) ?: continue
            val stream = streams.getOrNull(streamIndex) ?: continue
            if (!stream.isWmv2 && stream.audioMime == null) continue
            val kind = AviChunk.chunkKind(entry.ckid)
            val videoChunk = kind == AviChunk.Kind.VIDEO
            val audioChunk = kind == AviChunk.Kind.AUDIO
            if (stream.isWmv2 && !videoChunk) continue
            if (!stream.isWmv2 && !audioChunk) continue
            val n = ordinal.getOrDefault(streamIndex, 0)
            ordinal[streamIndex] = n + 1
            val scale = stream.scale.coerceAtLeast(1L)
            val rate = stream.rate.coerceAtLeast(1L)
            val timeUs = n * 1_000_000L * scale / rate
            val key = entry.flags and KEYFRAME != 0L || (stream.isWmv2 && !videoSeen)
            if (stream.isWmv2) videoSeen = true
            val offset = if (base == 0L && !fromIndex) entry.offset else base + entry.offset
            // idx1 是**解码顺序**，偏移不保证单调；不单调就不能二分（见 seek）
            if (offset < previousOffset) samplesOffsetSorted = false
            previousOffset = offset
            samples.add(Sample(streamIndex, offset, entry.size.coerceAtLeast(0), timeUs, key))
        }
        // 扫描路径的中间列表与 samples 同量级（十几万条），建完就放手，别两份一起驻留
        if (!fromIndex) scanned.clear()
        Log.d(TAG, "wmv2 samples=${samples.size} base=$base streams=${streams.size} idx1=$fromIndex")
    }

    private fun emit(holder: PositionHolder): Int {
        val sink = output ?: return Extractor.RESULT_END_OF_INPUT
        if (!emitted) {
            val video = streams.firstOrNull { it.isWmv2 } ?: return Extractor.RESULT_END_OF_INPUT
            for (stream in streams) {
                if (stream.isWmv2) {
                    val maxIn = samples.filter { it.stream == stream.index }.maxOfOrNull { it.size } ?: 64 * 1024
                    val format = Format.Builder()
                        .setSampleMimeType(MediaNames.MIME_WMV2)
                        .setWidth(abs(stream.width).coerceAtLeast(1))
                        .setHeight(abs(stream.height).coerceAtLeast(1))
                        .setMaxInputSize(maxIn.coerceAtLeast(1))
                        .apply {
                            if (stream.extra.isNotEmpty()) setInitializationData(listOf(stream.extra))
                            if (stream.rate > 0 && stream.scale > 0) {
                                setFrameRate(stream.rate.toFloat() / stream.scale.toFloat())
                            }
                        }
                        .build()
                    val track = sink.track(stream.index, C.TRACK_TYPE_VIDEO)
                    track.format(format)
                    tracks[stream.index] = track
                } else if (stream.audioMime != null) {
                    val maxIn = samples.filter { it.stream == stream.index }.maxOfOrNull { it.size } ?: 16 * 1024
                    val format = Format.Builder()
                        .setSampleMimeType(stream.audioMime)
                        .setMaxInputSize(maxIn.coerceAtLeast(1))
                        .apply {
                            if (stream.sampleRate > 0) setSampleRate(stream.sampleRate)
                            if (stream.channels > 0) setChannelCount(stream.channels)
                        }
                        .build()
                    val track = sink.track(stream.index, C.TRACK_TYPE_AUDIO)
                    track.format(format)
                    tracks[stream.index] = track
                }
            }
            val duration = streams.maxOfOrNull { streamDurationUs(it) } ?: 0L
            sink.seekMap(object : SeekMap {
                override fun isSeekable(): Boolean =
                    keyframesReliable && samples.any { it.keyframe }
                override fun getDurationUs(): Long = duration
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val videoSamples = samples.filter { streams.getOrNull(it.stream)?.isWmv2 == true }
                    val pool = videoSamples.ifEmpty { samples }
                    var pick = pool.firstOrNull() ?: return SeekMap.SeekPoints(SeekPoint(0, 0))
                    for (sample in pool) {
                        if (!sample.keyframe) continue
                        if (sample.timeUs <= timeUs) pick = sample else break
                    }
                    return SeekMap.SeekPoints(SeekPoint(pick.timeUs, pick.offset))
                }
            })
            sink.endTracks()
            emitted = true
            Log.d(TAG, "emit ${video.width}x${video.height} extra=${video.extra.size} durationUs=$duration")
        }
        if (samples.isEmpty()) return Extractor.RESULT_END_OF_INPUT
        sampleCursor = 0
        phase = Phase.SAMPLE
        holder.position = samples.first().offset
        return Extractor.RESULT_SEEK
    }

    private fun streamDurationUs(stream: StreamInfo): Long {
        val rate = stream.rate
        if (rate <= 0) return 0L
        val count = if (stream.length > 0) stream.length else samples.count { it.stream == stream.index }.toLong()
        return count * 1_000_000L * stream.scale.coerceAtLeast(1L) / rate
    }

    private fun readSample(input: ExtractorInput, holder: PositionHolder): Int {
        if (sampleCursor >= samples.size) return Extractor.RESULT_END_OF_INPUT
        val sample = samples[sampleCursor]
        if (input.position != sample.offset) {
            holder.position = sample.offset
            return Extractor.RESULT_SEEK
        }
        val hdr = ByteArray(8)
        readFully(input, hdr)
        val size = u32(hdr, 4).toInt()
        if (size < 0 || size > MAX_CHUNK) throw IOException("sample too large: $size")
        val data = ByteArray(size)
        if (size > 0) readFully(input, data)
        if (size % 2 == 1) skipOne(input)
        val track = tracks[sample.stream]
        if (track != null) {
            val bytes = ParsableByteArray(data)
            track.sampleData(bytes, data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(
                sample.timeUs,
                if (sample.keyframe) C.BUFFER_FLAG_KEY_FRAME else 0,
                data.size,
                0,
                null,
            )
        }
        sampleCursor++
        return Extractor.RESULT_CONTINUE
    }

    private fun peekIsWmv2(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        input.peekFully(head, 0, head.size)
        if (fcc(head, 0) != "RIFF" || fcc(head, 8) != "AVI ") return false
        var guard = 0
        while (guard++ < 64 && input.peekPosition < PEEK_LIMIT) {
            val hdr = ByteArray(8)
            input.peekFully(hdr, 0, hdr.size)
            val id = fcc(hdr, 0)
            val size = u32(hdr, 4)
            if (size > MAX_CHUNK || size > Int.MAX_VALUE) return false
            if (id == "LIST") {
                if (size < 4) return false
                val type = ByteArray(4)
                input.peekFully(type, 0, 4)
                if (fcc(type, 0) == "hdrl") {
                    val payload = ByteArray((size - 4).toInt())
                    if (payload.isNotEmpty()) input.peekFully(payload, 0, payload.size)
                    return parseHdrl(payload).any { it.isWmv2 }
                } else {
                    input.advancePeekPosition((size - 4).toInt())
                }
            } else if (size > 0) {
                input.advancePeekPosition(size.toInt())
            }
            if (size % 2L == 1L) input.advancePeekPosition(1)
        }
        return false
    }

    private fun parseHdrl(payload: ByteArray): List<StreamInfo> {
        val out = mutableListOf<StreamInfo>()
        var p = 0
        while (p + 8 <= payload.size) {
            val id = fcc(payload, p)
            val size = u32(payload, p + 4).toInt()
            val data = p + 8
            if (size < 0 || data + size > payload.size) break
            if (id == "LIST" && size >= 4 && fcc(payload, data) == "strl") {
                out.add(parseStrl(payload, data + 4, data + size, out.size))
            }
            val step = 8 + size + (size and 1)
            if (step <= 0) break
            p += step
        }
        return out
    }

    private fun parseStrl(buf: ByteArray, start: Int, end: Int, index: Int): StreamInfo {
        var p = start
        var fccType = ""
        var fccHandler = ""
        var scale = 1L
        var rate = 1L
        var length = 0L
        var strf: ByteArray? = null
        while (p + 8 <= end) {
            val id = fcc(buf, p)
            val size = u32(buf, p + 4).toInt()
            val data = p + 8
            if (size < 0 || data > end || data + size > end) break
            if (id == "strh" && size >= 36) {
                fccType = fcc(buf, data)
                fccHandler = fcc(buf, data + 4)
                scale = u32(buf, data + 20)
                rate = u32(buf, data + 24)
                length = u32(buf, data + 32)
            } else if (id == "strf") {
                strf = buf.copyOfRange(data, data + size)
            }
            val step = 8 + size + (size and 1)
            if (step <= 0) break
            p += step
        }
        val video = fccType == "vids"
        val compression = if (video && strf != null && strf.size >= 20) fcc(strf, 16) else fccHandler
        val width = if (video && strf != null && strf.size >= 8) u32(strf, 4).toInt() else 0
        val height = if (video && strf != null && strf.size >= 12) u32(strf, 8).toInt() else 0
        // extradata 的切分对齐 FFmpeg 的 avidec：BITMAPINFOHEADER 之后的全部字节
        // （size - 40）。WMV2 解码器的 decode_ext_header 要读这 4 字节决定
        // j_type/abt/mbrl 等标志位；若按 biSize 判断，biSize==strf.size 的编码器
        // （本样本 biSize=44）会把 extradata 吞掉，解码器标志位全零 → 花屏。
        val extra = if (video && strf != null && strf.size > BITMAPINFOHEADER) {
            val biSize = u32(strf, 0).toInt()
            val extraSize = if (biSize == strf.size - 1 && (biSize and 1) == 1) {
                biSize - BITMAPINFOHEADER
            } else {
                strf.size - BITMAPINFOHEADER
            }
            if (extraSize in 1..(strf.size - BITMAPINFOHEADER)) {
                strf.copyOfRange(BITMAPINFOHEADER, BITMAPINFOHEADER + extraSize)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        val tag = if (!video && strf != null && strf.size >= 16) u16(strf, 0) else -1
        val audioMime = when (tag) {
            0x0055, 0x0050 -> "audio/mpeg"
            else -> null
        }
        return StreamInfo(
            index = index,
            isWmv2 = video && (compression == "WMV2" || fccHandler == "WMV2"),
            audioMime = audioMime,
            scale = scale,
            rate = rate,
            length = length,
            width = width,
            height = height,
            extra = extra,
            sampleRate = if (strf != null && strf.size >= 8 && !video) u32(strf, 4).toInt() else 0,
            channels = if (strf != null && strf.size >= 4 && !video) u16(strf, 2) else 0,
        )
    }

    private fun skipPad(input: ExtractorInput, size: Long) {
        if (size % 2L == 1L) skipOne(input)
    }

    private fun skipOne(input: ExtractorInput) {
        val one = ByteArray(1)
        val n = input.read(one, 0, 1)
        if (n == C.RESULT_END_OF_INPUT) throw EOFException("pad")
    }

    private fun readFully(input: ExtractorInput, dest: ByteArray) {
        var read = 0
        while (read < dest.size) {
            val n = input.read(dest, read, dest.size - read)
            if (n == C.RESULT_END_OF_INPUT) throw EOFException("need ${dest.size} got $read")
            read += n
        }
    }

    private fun pad(size: Long): Long = if (size % 2L == 0L) 0L else 1L

    private fun fcc(b: ByteArray, offset: Int): String = String(b, offset, 4, Charsets.ISO_8859_1)

    private fun u32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xff) or
            ((b[offset + 1].toLong() and 0xff) shl 8) or
            ((b[offset + 2].toLong() and 0xff) shl 16) or
            ((b[offset + 3].toLong() and 0xff) shl 24)

    private fun u16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val TAG = "Wmv2Avi"
        const val PEEK_LIMIT = 1_048_576L
        const val MAX_CHUNK = 32L * 1024 * 1024
        const val KEYFRAME = 0x10L

        /** 标准 BITMAPINFOHEADER 长度；extradata 从它之后开始（与 FFmpeg avidec 一致） */
        const val BITMAPINFOHEADER = 40
    }
}
