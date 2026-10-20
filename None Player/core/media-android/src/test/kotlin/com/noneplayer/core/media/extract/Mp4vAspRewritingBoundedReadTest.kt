package com.noneplayer.core.media.extract

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-11/REV-05：mp4v-es 改写器只保留 HEAD_CAP 决策头，其余样本字节用固定 scratch
 * 边读边转发（不再 ByteArray(length)+ByteArrayOutputStream 累积整样本），
 * 而字节顺序、返回值与 sampleMetadata 的 size/offset 语义必须与旧实现一致。
 *
 * 纯 JVM：用脚本化 inner Extractor 驱动样本流；JVM 上 Mp4vAspGate 设备 profile 为空，
 * 因此这里只验证"有界读取 + 透明转发"，mime 实际改写留给 instrumentation 资产用例。
 */
@OptIn(UnstableApi::class)
class Mp4vAspRewritingBoundedReadTest {

    private companion object {
        /** 改写器决策头上限 */
        const val HEAD_CAP = 4 * 1024

        /** REV-05 规定单次读取分配上限（64KB 固定 scratch） */
        const val MAX_READ_REQUEST = 64 * 1024

        const val MIME_MP4V = "video/mp4v-es"
    }

    /** 脚本化 inner Extractor：完全忽略 input，按脚本驱动 output。 */
    private class ScriptedExtractor(
        private val script: (ExtractorOutput) -> Int,
    ) : Extractor {
        private lateinit var output: ExtractorOutput

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = script(output)

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit
    }

    /** 脚本化 inner Extractor 不使用 input，这里只需不会用到的占位实现。 */
    private object UnusedExtractorInput : ExtractorInput {
        override fun read(target: ByteArray, offset: Int, length: Int): Int = unsupported()
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean = unsupported()
        override fun readFully(target: ByteArray, offset: Int, length: Int): Unit = unsupported()
        override fun skip(length: Int): Int = unsupported()
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = unsupported()
        override fun skipFully(length: Int): Unit = unsupported()
        override fun peek(target: ByteArray, offset: Int, length: Int): Int = unsupported()
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean = unsupported()
        override fun peekFully(target: ByteArray, offset: Int, length: Int): Unit = unsupported()
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean = unsupported()
        override fun advancePeekPosition(length: Int): Unit = unsupported()
        override fun resetPeekPosition(): Unit = unsupported()
        override fun getPeekPosition(): Long = unsupported()
        override fun getPosition(): Long = unsupported()
        override fun getLength(): Long = unsupported()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("脚本化测试不消费 ExtractorInput")
    }

    /** 记录最大单次请求的 DataReader。 */
    private class ByteArrayDataReader(private val data: ByteArray) : DataReader {
        var maxRequested = 0
            private set
        var readCalls = 0
            private set
        private var position = 0

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            maxRequested = maxOf(maxRequested, length)
            readCalls++
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, target, offset, count)
            position += count
            return count
        }
    }

    /** 记录 format/转发字节/sampleMetadata 的下游 TrackOutput。 */
    private class RecordingTrackOutput : TrackOutput {
        val formats = mutableListOf<Format>()
        val forwarded = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        var metadataSize = -1
            private set
        var metadataOffset = -1
            private set
        var metadataCount = 0
            private set

        override fun format(format: Format) {
            formats += format
            events += "format"
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val buffer = ByteArray(MAX_READ_REQUEST)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, 0, minOf(buffer.size, length - read))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) break else throw EOFException("recording downstream")
                }
                forwarded.write(buffer, 0, n)
                read += n
            }
            events += "data:$read"
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            forwarded.write(data.data, data.position, length)
            data.skipBytes(length)
            events += "data:$length"
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            metadataSize = size
            metadataOffset = offset
            metadataCount++
            events += "metadata:$size:$offset"
        }
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        val video = RecordingTrackOutput()
        var tracksEnded = false
            private set

        override fun track(id: Int, type: Int): TrackOutput =
            if (type == C.TRACK_TYPE_VIDEO) video else RecordingTrackOutput()

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun sampleBytes(length: Int): ByteArray = ByteArray(length) { (it % 251).toByte() }

    private fun mp4vFormat(): Format = Format.Builder().setSampleMimeType(MIME_MP4V).build()

    private fun runExtractor(script: (ExtractorOutput) -> Int): RecordingExtractorOutput {
        val output = RecordingExtractorOutput()
        val extractor = Mp4vAspRewritingExtractor(ScriptedExtractor(script))
        extractor.init(output)
        assertEquals(
            Extractor.RESULT_END_OF_INPUT,
            extractor.read(UnusedExtractorInput, PositionHolder()),
        )
        return output
    }

    @Test
    fun `large sample is forwarded in order with bounded reads and unchanged metadata`() {
        val source = sampleBytes(200_000)
        val reader = ByteArrayDataReader(source)
        val output = runExtractor { root ->
            val track = root.track(0, C.TRACK_TYPE_VIDEO)
            track.format(mp4vFormat())
            val consumed = track.sampleData(
                reader,
                source.size,
                true,
                TrackOutput.SAMPLE_DATA_PART_MAIN,
            )
            assertEquals("必须消费完整 length", source.size, consumed)
            track.sampleMetadata(10_000L, 0, source.size, 0, null)
            Extractor.RESULT_END_OF_INPUT
        }

        val video = output.video
        assertEquals(1, video.formats.size)
        assertEquals(MIME_MP4V, video.formats[0].sampleMimeType)
        assertEquals("format 必须先于样本数据", "format", video.events.first())
        assertArrayEquals(source, video.forwarded.toByteArray())
        assertEquals(source.size, video.metadataSize)
        assertEquals(0, video.metadataOffset)
        assertTrue(
            "单次 read 不得超过 ${MAX_READ_REQUEST}B（实际 ${reader.maxRequested}B）",
            reader.maxRequested <= MAX_READ_REQUEST,
        )
        assertTrue("样本必须分块读取（实际 ${reader.readCalls} 次）", reader.readCalls > 1)
    }

    @Test
    fun `short sample below head cap is buffered until metadata`() {
        val source = sampleBytes(1_000)
        val output = RecordingExtractorOutput()
        val extractor = Mp4vAspRewritingExtractor(
            ScriptedExtractor { root ->
                val track = root.track(0, C.TRACK_TYPE_VIDEO)
                track.format(mp4vFormat())
                val consumed = track.sampleData(
                    ByteArrayDataReader(source),
                    source.size,
                    true,
                    TrackOutput.SAMPLE_DATA_PART_MAIN,
                )
                assertEquals(source.size, consumed)
                assertEquals("metadata 前不应转发数据", 0, output.video.forwarded.size())
                assertTrue("metadata 前不应发布格式", output.video.formats.isEmpty())
                track.sampleMetadata(1_000L, 0, source.size, 0, null)
                Extractor.RESULT_END_OF_INPUT
            },
        )
        extractor.init(output)
        extractor.read(UnusedExtractorInput, PositionHolder())

        assertEquals(1, output.video.formats.size)
        assertEquals(MIME_MP4V, output.video.formats[0].sampleMimeType)
        assertArrayEquals(source, output.video.forwarded.toByteArray())
        assertEquals(source.size, output.video.metadataSize)
    }

    @Test
    fun `end of input with allowEndOfInput returns partial bytes and keeps metadata size`() {
        val source = sampleBytes(3_000)
        val output = runExtractor { root ->
            val track = root.track(0, C.TRACK_TYPE_VIDEO)
            track.format(mp4vFormat())
            val consumed = track.sampleData(
                ByteArrayDataReader(source),
                5_000,
                true,
                TrackOutput.SAMPLE_DATA_PART_MAIN,
            )
            assertEquals(3_000, consumed)
            track.sampleMetadata(0L, 0, 5_000, 0, null)
            Extractor.RESULT_END_OF_INPUT
        }

        assertArrayEquals(source, output.video.forwarded.toByteArray())
        assertEquals("metadata size 必须原样转发", 5_000, output.video.metadataSize)
    }

    @Test
    fun `end of input without allowEndOfInput throws eof`() {
        val error = runCatching {
            runExtractor { root ->
                val track = root.track(0, C.TRACK_TYPE_VIDEO)
                track.format(mp4vFormat())
                track.sampleData(
                    ByteArrayDataReader(sampleBytes(1_000)),
                    5_000,
                    false,
                    TrackOutput.SAMPLE_DATA_PART_MAIN,
                )
                Extractor.RESULT_END_OF_INPUT
            }
        }.exceptionOrNull()

        assertTrue("旧实现抛 EOFException，实际 $error", error is EOFException)
    }

    @Test
    fun `sample after decision is forwarded directly`() {
        val first = sampleBytes(200_000)
        val second = sampleBytes(5_000).map { (it + 1).toByte() }.toByteArray()
        val secondReader = ByteArrayDataReader(second)
        val output = runExtractor { root ->
            val track = root.track(0, C.TRACK_TYPE_VIDEO)
            track.format(mp4vFormat())
            track.sampleData(
                ByteArrayDataReader(first),
                first.size,
                true,
                TrackOutput.SAMPLE_DATA_PART_MAIN,
            )
            track.sampleMetadata(0L, 0, first.size, 0, null)
            val consumed = track.sampleData(
                secondReader,
                second.size,
                true,
                TrackOutput.SAMPLE_DATA_PART_MAIN,
            )
            assertEquals("决策后样本应直接转发", second.size, consumed)
            track.sampleMetadata(10L, 0, second.size, 0, null)
            Extractor.RESULT_END_OF_INPUT
        }

        assertArrayEquals(first + second, output.video.forwarded.toByteArray())
        assertEquals(2, output.video.metadataCount)
        assertEquals(second.size, output.video.metadataSize)
    }
}
