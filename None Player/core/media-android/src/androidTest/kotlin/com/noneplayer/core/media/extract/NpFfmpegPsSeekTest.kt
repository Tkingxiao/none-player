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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E208 审查的真机回归：MPEG-PS「回起点 + 丢包」seek。
 *
 * 45s 合成 PS（mpeg2video + mp2）经 [NpFfmpegExtractor] 播到轨道就绪后，
 * `seek(0, 30s)` 必须：
 * - 走 `avformat_flush + avio_seek(0)`（旧实现漏了 avio_seek，AVIO 旧缓冲会把
 *   0 位置的数据重复吐出）；
 * - 丢弃 30s 之前的包，视频等到下一个关键帧，音频照常放行；
 * - 首个视频样本是 keyframe，且 pts 落在 [30s, 32s)；音频 pts ≥ 30s。
 *
 * 设备侧直接驱动 Extractor（不经过 media3 的 SampleQueue），所以不受「60s 已缓冲」
 * 影响，能确定性覆盖 C 侧 seek 路径。
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NpFfmpegPsSeekTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun psSeekTo30sWaitsForKeyframeAndStartsAtTarget() {
        val asset = "np-ps-seek.mpg"
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        try {
            val reader = ChannelReader(channel)
            val size = channel.size()
            var input: ExtractorInput = DefaultExtractorInput(reader, 0, size)
            val holder = PositionHolder()
            val output = RecordingOutput()
            val extractor = NpFfmpegExtractor("mpegps", "MPEG-PS")
            extractor.init(output)

            var guard = 0
            while (guard++ < 2_000 && !output.tracksEnded) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_SEEK -> {
                        channel.position(holder.position)
                        input = DefaultExtractorInput(reader, holder.position, size - holder.position)
                    }
                    Extractor.RESULT_END_OF_INPUT -> break
                }
            }
            assertTrue("MPEG-PS 应完成轨道表", output.tracksEnded)
            val video = output.tracks.firstOrNull { it.format?.sampleMimeType?.startsWith("video/") == true }
            val audio = output.tracks.firstOrNull { it.format?.sampleMimeType?.startsWith("audio/") == true }
            assertNotNull("缺视频轨：${output.tracks.map { it.format?.sampleMimeType }}", video)
            assertNotNull("缺音频轨：${output.tracks.map { it.format?.sampleMimeType }}", audio)
            assertEquals("video/x-np-mpeg2", video!!.format?.sampleMimeType)
            assertEquals("audio/mpeg-L2", audio!!.format?.sampleMimeType)

            video.samples.clear()
            audio.samples.clear()
            extractor.seek(/* position= */ 0, /* timeUs= */ 30_000_000L)

            guard = 0
            while (guard++ < 2_000) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_SEEK -> {
                        channel.position(holder.position)
                        input = DefaultExtractorInput(reader, holder.position, size - holder.position)
                    }
                    Extractor.RESULT_END_OF_INPUT -> break
                }
                if (video.samples.isNotEmpty() && audio.samples.isNotEmpty()) break
            }
            extractor.release()

            val firstVideo = video.samples.firstOrNull()
            val firstAudio = audio.samples.firstOrNull()
            assertNotNull("seek 后没有视频样本", firstVideo)
            assertNotNull("seek 后没有音频样本", firstAudio)
            assertTrue("首个视频样本必须是关键帧", (firstVideo!!.second and C.BUFFER_FLAG_KEY_FRAME) != 0)
            assertTrue(
                "视频首样本 pts=${firstVideo.first} 应在 [30s, 32s)（关键帧等待）",
                firstVideo.first >= 30_000_000L && firstVideo.first < 32_000_000L,
            )
            assertTrue(
                "音频首样本 pts=${firstAudio!!.first} 不应早于 30s",
                firstAudio.first >= 30_000_000L,
            )
        } finally {
            channel.close()
            file.delete()
        }
    }

    private class ChannelReader(private val channel: FileChannel) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val buffer = ByteBuffer.wrap(target, offset, length)
            val n = channel.read(buffer)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
    }

    private class RecordingTrack : TrackOutput {
        var format: Format? = null
        val samples = ArrayList<Pair<Long, Int>>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val scratch = ByteArray(minOf(length, 4096))
            var read = 0
            while (read < length) {
                val n = input.read(scratch, 0, minOf(scratch.size, length - read))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return read
                    error("unexpected EOF in sampleData")
                }
                read += n
            }
            return length
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            samples.add(timeUs to flags)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = mutableListOf<RecordingTrack>()
        var tracksEnded = false

        override fun track(id: Int, type: Int): TrackOutput =
            RecordingTrack().also { tracks.add(it) }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }
}
