package com.noneplayer.core.media.extract

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.MediaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 平铺样本表 + 二分 seek + 关键帧寻点表的 JVM 回归（A1 批）：
 * 合成一条最小 WMV2 AVI（hdrl + movi 两块 + idx1），驱动提取器走完
 * HEADER → INDEX → VERIFY → EMIT → SAMPLE 全链，钉住：
 * ① idx1 路径 isSeekable=true、时长按 strh scale/rate；
 * ② getSeekPoints 二分取「≤ timeUs 的最后一个关键帧」，t 在首关键帧之前回落到第一条；
 * ③ seek(offset) 后 SAMPLE 从目标样本继续，关键帧位随样本交出。
 *
 * 超级索引批（E227）：OpenDML 合成夹具（strl 带 indx、movi 里插 ix##、无 idx1）钉住
 * ④ indx→ix## 两级解析出真关键帧 → 可拖；⑤ ix 基址四种口径（写对/绝对/错写/全坏）
 * 的对账与回退；⑥ DELTAFRAME 位语义；⑦ 双流按偏移归并与音频字节折块时间戳。
 */
@OptIn(UnstableApi::class)
class Wmv2AviExtractorIndexTest {

    // ---- 合成 AVI ----

    private fun fourcc(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun u32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 24) and 0xff).toByte(),
    )

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc(id))
        out.write(u32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    /**
     * 两个关键帧的视频（无音轨），idx1 偏移按 movi 数据起点。[codec] 写进 strh 的
     * fccHandler **和** strf 的 biCompression（strf[16..20]）两处：提取器认领时对
     * compression 与 handler 双查，缺一处就认不到。
     */
    private fun buildAvi(codec: String = "WMV2"): ByteArray {
        val strh = ByteArray(56)
        System.arraycopy(fourcc("vids"), 0, strh, 0, 4)
        System.arraycopy(fourcc(codec), 0, strh, 4, 4)
        // scale @20 = 1001, rate @24 = 30000 (29.97fps), length @32 = 2
        System.arraycopy(u32(1001), 0, strh, 20, 4)
        System.arraycopy(u32(30000), 0, strh, 24, 4)
        System.arraycopy(u32(2), 0, strh, 32, 4)
        val strf = ByteArray(40) // BITMAPINFOHEADER：biSize=40、64x48
        System.arraycopy(u32(40), 0, strf, 0, 4)
        System.arraycopy(u32(64), 0, strf, 4, 4)
        System.arraycopy(u32(48), 0, strf, 8, 4)
        System.arraycopy(fourcc(codec), 0, strf, 16, 4) // biCompression

        val strl = chunk("LIST", fourcc("strl") + chunk("strh", strh) + chunk("strf", strf))
        val hdrl = chunk("LIST", fourcc("hdrl") + strl)

        val payload1 = ByteArray(100) { (it + 1).toByte() }
        val payload2 = ByteArray(90) { (it + 7).toByte() }
        val c1 = chunk("00dc", payload1)
        val c2 = chunk("00dc", payload2)
        val movi = chunk("LIST", fourcc("movi") + c1 + c2)

        val idxEntry1 = fourcc("00dc") + u32(0x10) + u32(0) + u32(100)
        val idxEntry2 = fourcc("00dc") + u32(0x10) + u32(c1.size) + u32(90)
        val idx1 = chunk("idx1", idxEntry1 + idxEntry2)

        val riffBody = fourcc("AVI ") + hdrl + movi + idx1
        return fourcc("RIFF") + u32(riffBody.size) + riffBody
    }

    // ---- 测试替身 ----

    private class ByteReader(private val data: ByteArray) : DataReader {
        var pos = 0L

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, (data.size - pos).toInt())
            System.arraycopy(data, pos.toInt(), target, offset, n)
            pos += n
            return n
        }
    }

    private class RecordingTrack(private val formats: MutableList<Format>) : TrackOutput {
        var totalData = 0
        var lastTimeUs = Long.MIN_VALUE
        var lastFlags = 0
        var metadataCount = 0
        val times = mutableListOf<Long>()
        val flags = mutableListOf<Int>()

        override fun format(format: Format) {
            formats.add(format)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val buf = ByteArray(minOf(length, 4096))
            var read = 0
            while (read < length) {
                val n = input.read(buf, 0, minOf(buf.size, length - read))
                if (n == C.RESULT_END_OF_INPUT) error("unexpected EOF in sampleData")
                read += n
            }
            totalData += length
            return length
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalData += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            lastTimeUs = timeUs
            lastFlags = flags
            metadataCount++
            times.add(timeUs)
            this.flags.add(flags)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val formats = mutableListOf<Format>()
        val tracks = mutableListOf<RecordingTrack>()
        var seekMap: SeekMap? = null
        var ended = false

        override fun track(id: Int, type: Int): TrackOutput =
            RecordingTrack(formats).also { tracks.add(it) }

        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            seekMap ?: return
            this.seekMap = seekMap
        }
    }

    /** 驱动到 SAMPLE 阶段；[stopAtSamples] 控制要收满多少条样本（默认 2 条即断，全量收用 Int.MAX_VALUE） */
    private fun runToSamples(
        data: ByteArray,
        stopAtSamples: Int = 2,
    ): Pair<RecordingOutput, Wmv2AviExtractor> {
        val extractor = Wmv2AviExtractor()
        val output = RecordingOutput()
        extractor.init(output)
        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        while (guard++ < 500) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) {
                break
            }
            if (output.tracks.isNotEmpty() && output.tracks.sumOf { it.metadataCount } >= stopAtSamples) break
        }
        return output to extractor
    }

    // ---- 断言 ----

    @Test
    fun `idx1 路径建出可拖的 SeekMap，时长按 strh 折算`() {
        val (output, _) = runToSamples(buildAvi())

        val format = output.formats.single()
        assertEquals(MediaNames.MIME_WMV2, format.sampleMimeType)
        assertEquals(64, format.width)
        assertEquals(48, format.height)

        val seekMap = assertNotNull(output.seekMap)
        assertTrue(seekMap.isSeekable)
        // 2 帧 × 1e6 × 1001 / 30000
        assertEquals(2L * 1_000_000L * 1001L / 30000L, seekMap.durationUs)
    }

    @Test
    fun `WMV3 fourcc 认领为 WMV3 家族 mime`() {
        val (output, _) = runToSamples(buildAvi("WMV3"))
        assertEquals(MediaNames.MIME_WMV3, output.formats.single().sampleMimeType)
    }

    @Test
    fun `WVC1 fourcc 认领为 VC1 家族 mime`() {
        val (output, _) = runToSamples(buildAvi("WVC1"))
        assertEquals(MediaNames.MIME_VC1, output.formats.single().sampleMimeType)
    }

    @Test
    fun `getSeekPoints 二分取不超过 timeUs 的最后一个关键帧，提前回落首帧`() {
        val (output, _) = runToSamples(buildAvi())
        val seekMap = assertNotNull(output.seekMap)

        // t=0 → 首关键帧（timeUs=0）
        val first = seekMap.getSeekPoints(0)
        assertEquals(0L, first.first.timeUs)

        // 第二帧 timeUs = 33366：t=40000 落在第二帧，t=33366 精确命中
        val secondTime = 1L * 1_000_000L * 1001L / 30000L
        assertEquals(secondTime, seekMap.getSeekPoints(40000).first.timeUs)
        assertEquals(secondTime, seekMap.getSeekPoints(secondTime).first.timeUs)

        // t 在首关键帧之前（负方向）回落到第一条，不越界
        assertEquals(0L, seekMap.getSeekPoints(-1).first.timeUs)
    }

    @Test
    fun `seek 到关键帧偏移后 SAMPLE 从目标样本继续，关键帧位随样本交出`() {
        val data = buildAvi()
        val (output, extractor) = runToSamples(data)
        val seekMap = assertNotNull(output.seekMap)
        val track = output.tracks.single()

        // 跳过第一帧，直接定位到第二帧的偏移
        val second = seekMap.getSeekPoints(40000).first
        assertTrue(second.timeUs > 0)
        extractor.seek(second.position, second.timeUs)

        val holder = PositionHolder()
        val reader = ByteReader(data)
        // seek 后下一次 read 从目标样本起：reader 需先落在 extractor 认定的位置。
        // extractor.seek 设定内部游标，readSample 首轮会对不上位置并返回 SEEK，用 holder 跟随。
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        val before = track.metadataCount
        while (guard++ < 50 && track.metadataCount < before + 1) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) {
                break
            }
        }
        val secondTime = 1L * 1_000_000L * 1001L / 30000L
        assertEquals(secondTime, track.lastTimeUs)
        assertTrue(track.lastFlags and C.BUFFER_FLAG_KEY_FRAME != 0)
    }

    @Test
    fun `样本载荷全量交出（scratch 复用不改数据量）`() {
        val (output, _) = runToSamples(buildAvi())
        val track = output.tracks.single()
        // 两块 payload 100 + 90 字节都要原样过 TrackOutput
        assertEquals(190, track.totalData)
    }

    @Test
    fun `无 idx1 走扫描路径：相邻块就地跳读，样本表与 idx1 路径等价`() {
        // 把 idx1 从夹具里去掉（模拟 A1 的"无 idx1 正片"），逼出 BUILD 扫描路径
        val full = buildAvi()
        val idx1Start = findIdx1Start(full)
        val noIdx1 = full.copyOf(idx1Start)

        val (output, _) = runToSamples(noIdx1)

        val format = output.formats.single()
        assertEquals(MediaNames.MIME_WMV2, format.sampleMimeType)
        val seekMap = assertNotNull(output.seekMap)
        // 扫描路径的"首帧即关键帧"是猜的 → 不可拖（与既有口径一致）
        assertEquals(false, seekMap.isSeekable)
        assertEquals(2L * 1_000_000L * 1001L / 30000L, seekMap.durationUs)
        // 扫描路径同样把两块 payload 全量认出
        assertEquals(190, output.tracks.single().totalData)
        assertEquals(2, output.tracks.single().metadataCount)
    }

    /** RIFF 顶层找 idx1 chunk 的起点（测试夹具里它总在文件尾） */
    private fun findIdx1Start(data: ByteArray): Int {
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.ISO_8859_1)
            val size = (data[pos + 4].toInt() and 0xff) or
                ((data[pos + 5].toInt() and 0xff) shl 8) or
                ((data[pos + 6].toInt() and 0xff) shl 16) or
                ((data[pos + 7].toInt() and 0xff) shl 24)
            if (id == "idx1") return pos
            pos += 8 + size + (size and 1)
        }
        error("idx1 not found")
    }

    // ---- OpenDML 超级索引夹具（E227）----

    /**
     * ix 条目基址的写法口径（qwBaseOffset 与条目相对量的组合）。真实 muxer 之间不统一，
     * 提取器靠「读首条 entry 指向的 chunk fourcc 对账」+ 候选基址序列消化；这里每种
     * 口径造一份夹具钉住对账行为。
     */
    private enum class SuperBaseMode {
        /** 写对：qwBaseOffset=movi fourcc，条目相对它（候选 0 命中） */
        BASE_OK,
        /** 谎写：qwBaseOffset=movi fourcc，条目是绝对偏移（候选 3 才命中） */
        BASE_ZERO_ABS,
        /** 谎写：qwBaseOffset=0，条目相对 movi 数据起点（候选 2 才命中） */
        BASE_WRONG_DATA_START,
        /** 全坏：条目相对 moviFccPos-8，四个候选全灭 → 回落扫描 */
        BASE_BROKEN,
    }

    private fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xff).toByte()
        dst[off + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun putU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xff).toByte()
        dst[off + 1] = ((v ushr 8) and 0xff).toByte()
        dst[off + 2] = ((v ushr 16) and 0xff).toByte()
        dst[off + 3] = ((v ushr 24) and 0xff).toByte()
    }

    private fun putI64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (i * 8)) and 0xff).toByte()
    }

    /**
     * `ix##` 块：20 字节 ix 头 + n 条 8 字节 entry（相对偏移 + 大小|DELTAFRAME 位）。
     * [offsets] 传绝对文件偏移，条目里减 [entryBase]。
     */
    private fun buildIxChunk(
        streamNo: Int,
        kind: String,
        base: Long,
        entryBase: Long,
        offsets: List<Long>,
        sizes: List<Int>,
        deltas: List<Boolean>,
    ): ByteArray {
        val n = offsets.size
        val payload = ByteArray(20 + n * 8)
        putU16(payload, 0, 2) // wLongsPerEntry
        payload[2] = 0 // bIndexSubType
        payload[3] = 1 // bIndexType = AVI_INDEX_OF_CHUNKS
        putU32(payload, 4, n.toLong())
        System.arraycopy(fourcc("%02d$kind".format(streamNo)), 0, payload, 8, 4)
        putU32(payload, 12, base)
        putU32(payload, 16, 0)
        for (i in 0 until n) {
            val p = 20 + i * 8
            putU32(payload, p, offsets[i] - entryBase)
            putU32(payload, p + 4, (sizes[i] or (if (deltas[i]) Int.MIN_VALUE else 0)).toLong() and 0xFFFFFFFFL)
        }
        return chunk("%02dix".format(streamNo), payload)
    }

    /** strl 里的 `indx` 超级索引：20 字节头 + n 条 16 字节（qwOffset=ix 块绝对偏移） */
    private fun buildIndxChunk(
        streamNo: Int,
        kind: String,
        ixOffsets: List<Long>,
        ixSizes: List<Int>,
        durations: List<Int>,
    ): ByteArray {
        val n = ixOffsets.size
        val payload = ByteArray(20 + n * 16)
        putU16(payload, 0, 4) // wLongsPerEntry
        payload[2] = 0
        payload[3] = 0 // bIndexType = AVI_INDEX_OF_INDEXES
        putU32(payload, 4, n.toLong())
        System.arraycopy(fourcc("%02d$kind".format(streamNo)), 0, payload, 8, 4)
        putU32(payload, 12, 0) // dwBase（超级索引条目是绝对偏移，不参与）
        putU32(payload, 16, 0)
        for (i in 0 until n) {
            val p = 20 + i * 16
            putI64(payload, p, ixOffsets[i])
            putU32(payload, p + 8, ixSizes[i].toLong())
            putU32(payload, p + 12, durations[i].toLong())
        }
        return chunk("indx", payload)
    }

    /**
     * OpenDML 合成 AVI：视频 6 帧（0/2/4 关键帧、1/3/5 差分，ixEvery=2 → 3 个 ix00）、
     * 可选音频 3 块 PCM（1024B/块 → 2 个 ix01），movi 里组间交错插 ix 块，**无 idx1**。
     * 两遍布局：先用空 indx 占位定 hdrl 尺寸算出全部绝对偏移，再回填 indx/ix 条目。
     */
    private fun buildOdmlAvi(
        codec: String = "WMV2",
        baseMode: SuperBaseMode = SuperBaseMode.BASE_OK,
        withAudio: Boolean = false,
        allDelta: Boolean = false,
    ): ByteArray {
        val frameSizes = intArrayOf(100, 90, 80, 70, 60, 50)
        val audioChunkSize = 1024
        val audioChunkCount = if (withAudio) 3 else 0
        val ixEvery = 2
        val videoGroups = frameSizes.toList().chunked(ixEvery)
        val audioGroups = if (withAudio) List(audioChunkCount) { audioChunkSize }.chunked(ixEvery) else emptyList()

        val strh = ByteArray(56)
        System.arraycopy(fourcc("vids"), 0, strh, 0, 4)
        System.arraycopy(fourcc(codec), 0, strh, 4, 4)
        System.arraycopy(u32(1001), 0, strh, 20, 4)
        System.arraycopy(u32(30000), 0, strh, 24, 4)
        System.arraycopy(u32(frameSizes.size), 0, strh, 32, 4)
        val strf = ByteArray(40)
        System.arraycopy(u32(40), 0, strf, 0, 4)
        System.arraycopy(u32(64), 0, strf, 4, 4)
        System.arraycopy(u32(48), 0, strf, 8, 4)
        System.arraycopy(fourcc(codec), 0, strf, 16, 4)
        val strhA = ByteArray(56)
        System.arraycopy(fourcc("auds"), 0, strhA, 0, 4)
        System.arraycopy(u32(1), 0, strhA, 20, 4) // dwScale
        System.arraycopy(u32(8000), 0, strhA, 24, 4) // dwRate（采样时钟）
        System.arraycopy(u32(audioChunkCount * audioChunkSize / 2), 0, strhA, 32, 4) // dwLength（采样数）
        val strfA = ByteArray(16) // WAVEFORMATEX：8kHz 单声道 16bit，blockAlign=2
        putU16(strfA, 0, 1)
        putU16(strfA, 2, 1)
        putU32(strfA, 4, 8000)
        putU32(strfA, 8, 16000)
        putU16(strfA, 12, 2)
        putU16(strfA, 14, 16)

        fun buildHdrl(videoIndx: ByteArray, audioIndx: ByteArray): ByteArray {
            var body = fourcc("hdrl") +
                chunk("LIST", fourcc("strl") + chunk("strh", strh) + chunk("strf", strf) + videoIndx)
            if (withAudio) {
                body += chunk("LIST", fourcc("strl") + chunk("strh", strhA) + chunk("strf", strfA) + audioIndx)
            }
            body += chunk("LIST", fourcc("odml") + chunk("dmlh", ByteArray(56)))
            return chunk("LIST", body)
        }

        // pass 1：空 indx 占位（与真实 indx 同尺寸）定布局
        val hdrlProbe = buildHdrl(
            chunk("indx", ByteArray(20 + videoGroups.size * 16)),
            chunk("indx", ByteArray(20 + audioGroups.size * 16)),
        )
        val moviFccPos = 12L + hdrlProbe.size
        val moviDataStart = moviFccPos + 12

        val (writtenBase, entryBase) = when (baseMode) {
            SuperBaseMode.BASE_OK -> moviFccPos to moviFccPos
            SuperBaseMode.BASE_ZERO_ABS -> moviFccPos to 0L
            SuperBaseMode.BASE_WRONG_DATA_START -> 0L to moviDataStart
            SuperBaseMode.BASE_BROKEN -> 12_345L to (moviFccPos - 8)
        }

        // 绝对偏移：组间交错（视频组 g → 音频组 g → 视频组 g+1 …）
        val frameOffsets = LongArray(frameSizes.size)
        val audioOffsets = LongArray(audioChunkCount)
        val videoIxOffsets = LongArray(videoGroups.size)
        val audioIxOffsets = LongArray(audioGroups.size)
        var pos = moviDataStart
        val maxGroups = maxOf(videoGroups.size, audioGroups.size)
        for (g in 0 until maxGroups) {
            if (g < videoGroups.size) {
                videoIxOffsets[g] = pos
                pos += 8 + 20 + videoGroups[g].size * 8
                for (j in videoGroups[g].indices) {
                    frameOffsets[g * ixEvery + j] = pos
                    pos += 8 + videoGroups[g][j] + (videoGroups[g][j] and 1)
                }
            }
            if (g < audioGroups.size) {
                audioIxOffsets[g] = pos
                pos += 8 + 20 + audioGroups[g].size * 8
                for (j in audioGroups[g].indices) {
                    audioOffsets[g * ixEvery + j] = pos
                    pos += 8 + audioGroups[g][j] + (audioGroups[g][j] and 1)
                }
            }
        }

        fun mediaPayload(size: Int): ByteArray = ByteArray(size) { i -> ((i % 16) + 1).toByte() }

        val movi = ByteArrayOutputStream()
        movi.write(fourcc("LIST"))
        movi.write(u32((4 + (pos - moviDataStart)).toInt()))
        movi.write(fourcc("movi"))
        for (g in 0 until maxGroups) {
            if (g < videoGroups.size) {
                val offs = videoGroups[g].mapIndexed { j, _ -> frameOffsets[g * ixEvery + j] }
                val deltas = videoGroups[g].mapIndexed { j, _ -> allDelta || (g * ixEvery + j) % 2 == 1 }
                movi.write(buildIxChunk(0, "dc", writtenBase, entryBase, offs, videoGroups[g], deltas))
                for (j in videoGroups[g].indices) {
                    movi.write(fourcc("00dc"))
                    movi.write(u32(videoGroups[g][j]))
                    movi.write(mediaPayload(videoGroups[g][j]))
                }
            }
            if (g < audioGroups.size) {
                val offs = audioGroups[g].mapIndexed { j, _ -> audioOffsets[g * ixEvery + j] }
                movi.write(
                    buildIxChunk(1, "wb", writtenBase, entryBase, offs, audioGroups[g], List(offs.size) { false }),
                )
                for (j in audioGroups[g].indices) {
                    movi.write(fourcc("01wb"))
                    movi.write(u32(audioGroups[g][j]))
                    movi.write(mediaPayload(audioGroups[g][j]))
                }
            }
        }

        val hdrl = buildHdrl(
            buildIndxChunk(0, "dc", videoIxOffsets.toList(), videoGroups.map { 8 + 20 + it.size * 8 }, videoGroups.map { it.size }),
            if (withAudio) {
                buildIndxChunk(1, "wb", audioIxOffsets.toList(), audioGroups.map { 8 + 20 + it.size * 8 }, audioGroups.map { it.size })
            } else {
                ByteArray(0)
            },
        )
        val body = fourcc("AVI ") + hdrl + movi.toByteArray()
        return fourcc("RIFF") + u32(body.size) + body
    }

    // ---- 超级索引断言（E227）----

    @Test
    fun `超索引路径：indx+ix## 无 idx1 出真关键帧，可拖`() {
        val (output, _) = runToSamples(buildOdmlAvi(), stopAtSamples = Int.MAX_VALUE)
        assertEquals(MediaNames.MIME_WMV2, output.formats.single().sampleMimeType)
        val seekMap = assertNotNull(output.seekMap)
        assertTrue(seekMap.isSeekable)
        assertEquals(6L * 1_000_000L * 1001L / 30000L, seekMap.durationUs)
        // 关键帧 f0/f2/f4：落在关键帧之间的时刻取「≤ 的最后一个」
        val t2 = 2L * 1_000_000L * 1001L / 30000L
        val t4 = 4L * 1_000_000L * 1001L / 30000L
        assertEquals(t2, seekMap.getSeekPoints(t2 + 1).first.timeUs)
        assertEquals(t4, seekMap.getSeekPoints(1_000_000L).first.timeUs)
    }

    @Test
    fun `seek 到超索引关键帧后从目标样本继续，关键帧位随样本交出`() {
        val data = buildOdmlAvi()
        val (output, extractor) = runToSamples(data)
        val seekMap = assertNotNull(output.seekMap)
        val track = output.tracks.single()

        val t2 = 2L * 1_000_000L * 1001L / 30000L
        val sp = seekMap.getSeekPoints(t2).first
        assertEquals(t2, sp.timeUs)
        extractor.seek(sp.position, sp.timeUs)

        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        val before = track.metadataCount
        while (guard++ < 100 && track.metadataCount < before + 2) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        assertEquals(2, track.metadataCount - before)
        assertEquals(t2, track.times[before])
        assertTrue(track.flags[before] and C.BUFFER_FLAG_KEY_FRAME != 0)
        val t3 = 3L * 1_000_000L * 1001L / 30000L
        assertEquals(t3, track.times[before + 1])
        assertEquals(0, track.flags[before + 1] and C.BUFFER_FLAG_KEY_FRAME)
    }

    @Test
    fun `ix 基址口径：绝对偏移与错写基址都对得上账`() {
        for (mode in listOf(SuperBaseMode.BASE_ZERO_ABS, SuperBaseMode.BASE_WRONG_DATA_START)) {
            val (output, _) = runToSamples(buildOdmlAvi(baseMode = mode), stopAtSamples = Int.MAX_VALUE)
            val seekMap = assertNotNull(output.seekMap)
            assertTrue("mode=$mode", seekMap.isSeekable)
            assertEquals("mode=$mode", 6, output.tracks.single().metadataCount)
            assertEquals("mode=$mode", 450, output.tracks.single().totalData)
        }
    }

    @Test
    fun `基址全坏回落扫描路径：可播不可拖`() {
        val (output, _) = runToSamples(buildOdmlAvi(baseMode = SuperBaseMode.BASE_BROKEN), stopAtSamples = Int.MAX_VALUE)
        val seekMap = assertNotNull(output.seekMap)
        assertEquals(false, seekMap.isSeekable)
        assertEquals(6, output.tracks.single().metadataCount)
        assertEquals(450, output.tracks.single().totalData)
    }

    @Test
    fun `DELTAFRAME 位全置：没有可信关键帧，不宣称可拖`() {
        val (output, _) = runToSamples(buildOdmlAvi(allDelta = true), stopAtSamples = Int.MAX_VALUE)
        assertEquals(false, assertNotNull(output.seekMap).isSeekable)
        assertEquals(6, output.tracks.single().metadataCount)
        assertEquals(450, output.tracks.single().totalData)
    }

    @Test
    fun `双流超索引按偏移归并，音频时间戳按字节折块`() {
        val (output, _) = runToSamples(buildOdmlAvi(withAudio = true), stopAtSamples = Int.MAX_VALUE)
        assertEquals(2, output.formats.size)
        assertEquals(MediaNames.MIME_WMV2, output.formats[0].sampleMimeType)
        assertEquals("audio/raw", output.formats[1].sampleMimeType)
        assertEquals(8000, output.formats[1].sampleRate)
        val video = output.tracks[0]
        val audio = output.tracks[1]
        assertEquals(6, video.metadataCount)
        assertEquals(3, audio.metadataCount)
        assertEquals(450, video.totalData)
        assertEquals(3072, audio.totalData)
        // 512 采样/块 × 1e6/8000 = 64000µs 递进
        assertEquals(listOf(64000L, 128000L, 192000L), audio.times)
        assertEquals(
            listOf(true, false, true, false, true, false),
            video.flags.map { it and C.BUFFER_FLAG_KEY_FRAME != 0 },
        )
        assertTrue(assertNotNull(output.seekMap).isSeekable)
    }

    @Test
    fun `双流合并表 seek 后两流都从目标附近继续`() {
        val data = buildOdmlAvi(withAudio = true)
        val (output, extractor) = runToSamples(data, stopAtSamples = Int.MAX_VALUE)
        val seekMap = assertNotNull(output.seekMap)
        val video = output.tracks[0]
        val audio = output.tracks[1]

        val t2 = 2L * 1_000_000L * 1001L / 30000L
        val sp = seekMap.getSeekPoints(t2).first
        extractor.seek(sp.position, sp.timeUs)

        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        val videoBefore = video.metadataCount
        val audioBefore = audio.metadataCount
        while (guard++ < 200) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        // 合并表文件序：… a1, f2, f3, a2, f4, f5——seek 到 f2 后视频续 4 帧、音频只续 a2
        assertEquals(4, video.metadataCount - videoBefore)
        assertEquals(t2, video.times[videoBefore])
        assertTrue(video.flags[videoBefore] and C.BUFFER_FLAG_KEY_FRAME != 0)
        assertEquals(1, audio.metadataCount - audioBefore)
        assertEquals(192000L, audio.times[audioBefore])
    }

    private fun assertNotNull(value: SeekMap?): SeekMap {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
