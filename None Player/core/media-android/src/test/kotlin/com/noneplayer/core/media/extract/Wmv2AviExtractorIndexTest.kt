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

    /** 两个关键帧的 WMV2 视频（无音轨），idx1 偏移按 movi 数据起点 */
    private fun buildAvi(): ByteArray {
        val strh = ByteArray(56)
        System.arraycopy(fourcc("vids"), 0, strh, 0, 4)
        System.arraycopy(fourcc("WMV2"), 0, strh, 4, 4)
        // scale @20 = 1001, rate @24 = 30000 (29.97fps), length @32 = 2
        System.arraycopy(u32(1001), 0, strh, 20, 4)
        System.arraycopy(u32(30000), 0, strh, 24, 4)
        System.arraycopy(u32(2), 0, strh, 32, 4)
        val strf = ByteArray(40) // BITMAPINFOHEADER：biSize=40、64x48
        System.arraycopy(u32(40), 0, strf, 0, 4)
        System.arraycopy(u32(64), 0, strf, 4, 4)
        System.arraycopy(u32(48), 0, strf, 8, 4)

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

    /** 驱动到 SAMPLE 阶段全部样本交出为止 */
    private fun runToSamples(data: ByteArray): Pair<RecordingOutput, Wmv2AviExtractor> {
        val extractor = Wmv2AviExtractor()
        val output = RecordingOutput()
        extractor.init(output)
        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        while (guard++ < 200) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) {
                break
            }
            if (output.tracks.isNotEmpty() && output.tracks.sumOf { it.metadataCount } >= 2) break
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

    private fun assertNotNull(value: SeekMap?): SeekMap {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
