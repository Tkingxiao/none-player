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

    private class RawIdx(val ckid: String, val flags: Long, val offset: Long, val size: Int)

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
    private val rawIndex = mutableListOf<RawIdx>()
    private var baseTry = 0
    private var scanCursor = -1L
    private val scanned = mutableListOf<RawIdx>()
    private val samples = mutableListOf<Sample>()
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
        val at = samples.indexOfFirst { it.offset >= position }
        sampleCursor = if (at < 0) samples.size else at
    }

    override fun release() = Unit

    private fun resetParse() {
        phase = Phase.HEADER
        riffChecked = false
        streams.clear()
        moviFccPos = -1L
        moviDataStart = -1L
        moviEnd = -1L
        rawIndex.clear()
        baseTry = 0
        scanCursor = -1L
        scanned.clear()
        samples.clear()
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
        val size = u32(hdr, 0 + 4)
        if (size > MAX_CHUNK) throw IOException("AVI chunk $id too large: $size")
        if (id == "LIST") {
            val type = ByteArray(4)
            readFully(input, type)
            val listType = fcc(type, 0)
            if (size < 4) throw IOException("AVI list too small")
            when (listType) {
                "hdrl" -> {
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
                    moviEnd = chunkStart + 8 + size + pad(size)
                    if (streams.none { it.isWmv2 }) return Extractor.RESULT_END_OF_INPUT
                    phase = Phase.INDEX
                    holder.position = moviEnd
                    return Extractor.RESULT_SEEK
                }
                else -> {
                    val at = input.position + (size - 4) + pad(size)
                    if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
                    holder.position = at
                    return Extractor.RESULT_SEEK
                }
            }
        } else {
            val at = input.position + size + pad(size)
            if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
            holder.position = at
            return Extractor.RESULT_SEEK
        }
        return Extractor.RESULT_CONTINUE
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
                        ckid = fcc(payload, p),
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
        if (fcc(id, 0) == rawIndex.first().ckid) {
            materialize(bases[baseTry], rawIndex)
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
        if (scanCursor + 8 > moviEnd) {
            materialize(0L, scanned.map { RawIdx(it.ckid, it.flags, it.offset, it.size) })
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val hdr = ByteArray(8)
        readFully(input, hdr)
        val id = fcc(hdr, 0)
        val size = u32(hdr, 4)
        if (size > MAX_CHUNK) throw IOException("movi chunk $id too large")
        scanned.add(RawIdx(id, if (scanned.none { streamOf(it.ckid) == streamOf(id) }) KEYFRAME else 0L, scanCursor, size.toInt()))
        val next = scanCursor + 8 + size + pad(size)
        scanCursor = next
        holder.position = next
        return Extractor.RESULT_SEEK
    }

    private fun materialize(base: Long, entries: List<RawIdx>) {
        samples.clear()
        val ordinal = HashMap<Int, Int>()
        var videoSeen = false
        for (entry in entries) {
            val streamIndex = streamOf(entry.ckid) ?: continue
            val stream = streams.getOrNull(streamIndex) ?: continue
            if (!stream.isWmv2 && stream.audioMime == null) continue
            val kind = entry.ckid.drop(2)
            val videoChunk = kind == "dc" || kind == "db"
            val audioChunk = kind == "wb"
            if (stream.isWmv2 && !videoChunk) continue
            if (!stream.isWmv2 && !audioChunk) continue
            val n = ordinal.getOrDefault(streamIndex, 0)
            ordinal[streamIndex] = n + 1
            val scale = stream.scale.coerceAtLeast(1L)
            val rate = stream.rate.coerceAtLeast(1L)
            val timeUs = n * 1_000_000L * scale / rate
            val key = entry.flags and KEYFRAME != 0L || (stream.isWmv2 && !videoSeen)
            if (stream.isWmv2) videoSeen = true
            val offset = if (base == 0L && entries === scanned) entry.offset else base + entry.offset
            samples.add(Sample(streamIndex, offset, entry.size.coerceAtLeast(0), timeUs, key))
        }
        Log.d(TAG, "wmv2 samples=${samples.size} base=$base streams=${streams.size}")
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
                override fun isSeekable(): Boolean = samples.any { it.keyframe }
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

    private fun streamOf(ckid: String): Int? {
        if (ckid.length < 4) return null
        return ckid.substring(0, 2).toIntOrNull(16)
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
