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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真实编码器载荷**的 OpenDML 夹具回归（E227）：`avi/np-odml-wmv2-pcm.avi` 由
 * `tools/gen-avi-odml-fixture.py` 生成——ffmpeg still-image WMV2 + PCM 8kHz 编码后，
 * 把容器重组为 OpenDML（strl 带 indx、movi 内组间交错 ix00/ix01、无 idx1），关键帧位
 * 从源 idx1 的 AVIIF_KEYFRAME 原样传播（视频 5/50 稀疏 I 帧、音频全关键帧）。
 *
 * 合成夹具（Wmv2AviExtractorIndexTest）钉的是解析语义；这份钉的是「真实 muxer 写出的
 * strh/strf 载荷 + 真实关键帧分布」在超索引路径上的全链行为。
 */
@OptIn(UnstableApi::class)
class Wmv2AviExtractorOdmlFixtureTest {

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

    private class RecordingTrack : TrackOutput {
        var totalData = 0L
        var metadataCount = 0
        var lastTimeUs = Long.MIN_VALUE
        val times = mutableListOf<Long>()
        val keyframeTimes = mutableListOf<Long>()

        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buf = ByteArray(65536)
            var read = 0
            while (read < length) {
                val n = input.read(buf, 0, minOf(buf.size, length - read))
                if (n == C.RESULT_END_OF_INPUT) error("EOF in sampleData")
                read += n
            }
            totalData += length
            return length
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalData += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            metadataCount++
            lastTimeUs = timeUs
            times.add(timeUs)
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) keyframeTimes.add(timeUs)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = mutableListOf<RecordingTrack>()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): RecordingTrack = RecordingTrack().also { tracks.add(it) }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun resourceBytes(): ByteArray =
        javaClass.getResourceAsStream("/avi/np-odml-wmv2-pcm.avi")?.use { it.readBytes() }
            ?: error("test resource avi/np-odml-wmv2-pcm.avi missing")

    /** 驱动到 END_OF_INPUT，跟随每一次 SEEK */
    private fun runToEnd(data: ByteArray): Pair<RecordingOutput, Wmv2AviExtractor> {
        val extractor = Wmv2AviExtractor()
        val output = RecordingOutput()
        extractor.init(output)
        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        while (guard++ < 100_000) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        return output to extractor
    }

    @Test
    fun `真实载荷 OpenDML 夹具可拖，关键帧位与源 idx1 一致`() {
        val data = resourceBytes()
        val (output, _) = runToEnd(data)

        assertEquals(2, output.tracks.size)
        val seekMap = assertNotNull(output.seekMap)
        assertTrue(seekMap.isSeekable)
        // strh dwLength=30、scale/rate=1/15（由源携带；movi 实际 50 块，时长以 dwLength 为准）
        assertEquals(30L * 1_000_000L / 15L, seekMap.getDurationUs())
        val video = output.tracks[0]
        val audio = output.tracks[1]
        assertEquals(50, video.metadataCount)
        assertEquals(16, audio.metadataCount)
        // 生成器从源 idx1 传播：视频 5 个 I 帧、音频全关键帧
        assertEquals(5, video.keyframeTimes.size)
        assertEquals(16, audio.keyframeTimes.size)
    }

    @Test
    fun `真实载荷 seek 落到目标前最近关键帧并从该样本继续`() {
        val data = resourceBytes()
        val (output, extractor) = runToEnd(data)
        val seekMap = assertNotNull(output.seekMap)
        val video = output.tracks[0]

        // 拖到 1.9s：应落在 5 个 I 帧里 ≤1.9s 的最后一个（GOP ≈ 0.67s）
        val sp = seekMap.getSeekPoints(1_900_000L).first
        assertTrue("sp=${sp.timeUs}", sp.timeUs > 1_000_000L)
        assertTrue("sp=${sp.timeUs}", sp.timeUs <= 1_900_000L)

        extractor.seek(sp.position, sp.timeUs)
        val holder = PositionHolder()
        val reader = ByteReader(data)
        var input: ExtractorInput = DefaultExtractorInput(reader, 0, data.size.toLong())
        var guard = 0
        val before = video.metadataCount
        while (guard++ < 1000 && video.metadataCount < before + 1) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                val pos = holder.position
                reader.pos = pos
                input = DefaultExtractorInput(reader, pos, (data.size - pos).coerceAtLeast(0))
            } else if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        assertEquals(1, video.metadataCount - before)
        assertEquals(sp.timeUs, video.times[before])
        // 续出的第一条必须带关键帧位（seek 落点就是 I 帧）
        assertTrue(video.keyframeTimes.contains(sp.timeUs))
    }

    private fun assertNotNull(value: SeekMap?): SeekMap {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
