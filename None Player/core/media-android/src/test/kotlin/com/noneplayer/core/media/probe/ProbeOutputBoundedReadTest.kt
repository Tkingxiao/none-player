package com.noneplayer.core.media.probe

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-11/REV-05：探测截留首个视频样本前导字节时，读取与内存分配必须固定有界
 * （固定 scratch 分块，而不是 `ByteArray(length)` 整段分配），
 * 同时 ExtractorInput 的消费语义（读满 length、EOF 语义、返回实际字节数）与旧实现一致。
 *
 * 纯 JVM 用例：只触碰 media3 的 ExtractorInput/TrackOutput 契约，
 * 不调用 `hasEnoughForProfileCheck()`（它依赖 Android `LruCache` 的 FormatProbe 外层状态）。
 */
@OptIn(UnstableApi::class)
class ProbeOutputBoundedReadTest {

    private companion object {
        /** FormatProbe 的截留上限：只保留 4KB 头部 */
        const val HEAD_CAP = 4 * 1024

        /** REV-05 规定单次读取分配上限（64KB 固定 scratch） */
        const val MAX_READ_REQUEST = 64 * 1024
    }

    /** 记录最大单次请求、可注入 EOF 的 DataReader。 */
    private class RecordingDataReader(
        private val data: ByteArray,
        private val eofAt: Int = data.size,
    ) : DataReader {
        var maxRequested = 0
            private set
        var readCalls = 0
            private set
        private var position = 0

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            maxRequested = maxOf(maxRequested, length)
            readCalls++
            if (position >= eofAt) return C.RESULT_END_OF_INPUT
            val count = minOf(length, eofAt - position)
            System.arraycopy(data, position, target, offset, count)
            position += count
            return count
        }
    }

    private fun sampleBytes(length: Int): ByteArray = ByteArray(length) { (it % 251).toByte() }

    @Test
    fun `large sample is consumed fully with bounded read requests`() {
        // 旧实现：ByteArray(1MiB) 一次性分配且首轮 read 请求整段 1MiB
        val length = 1 shl 20
        val source = sampleBytes(length)
        val input = RecordingDataReader(source)
        val track = FormatProbe.ProbeOutput().track(0, C.TRACK_TYPE_VIDEO)

        val consumed = track.sampleData(input, length, true, TrackOutput.SAMPLE_DATA_PART_MAIN)

        assertEquals(length, consumed)
        assertTrue(
            "单次 read 不得超过 ${MAX_READ_REQUEST}B（实际 ${input.maxRequested}B）",
            input.maxRequested <= MAX_READ_REQUEST,
        )
        assertTrue("必须分块读取（实际 ${input.readCalls} 次）", input.readCalls > 1)
    }

    @Test
    fun `head is capped at head cap and keeps sample prefix bytes`() {
        val length = 64 * 1024
        val source = sampleBytes(length)
        val output = FormatProbe.ProbeOutput()
        val track = output.track(0, C.TRACK_TYPE_VIDEO)

        val consumed = track.sampleData(
            RecordingDataReader(source),
            length,
            true,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        assertEquals(length, consumed)
        assertNull("样本边界到达前不应固化头部", output.firstVideoSampleHead)

        track.sampleMetadata(0L, 0, length, 0, null)

        val head = requireNotNull(output.firstVideoSampleHead) { "sampleMetadata 后应固化头部" }
        assertEquals(HEAD_CAP, head.size)
        assertArrayEquals(source.copyOfRange(0, HEAD_CAP), head)
    }

    @Test
    fun `short sample is captured fully at metadata boundary`() {
        val length = 1_000
        val source = sampleBytes(length)
        val output = FormatProbe.ProbeOutput()
        val track = output.track(0, C.TRACK_TYPE_VIDEO)

        val consumed = track.sampleData(
            RecordingDataReader(source),
            length,
            true,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        assertEquals(length, consumed)
        assertNull(output.firstVideoSampleHead)

        track.sampleMetadata(0L, 0, length, 0, null)

        val head = requireNotNull(output.firstVideoSampleHead) { "sampleMetadata 后应固化头部" }
        assertEquals(length, head.size)
        assertArrayEquals(source, head)
    }

    @Test
    fun `end of input with allowEndOfInput returns consumed bytes`() {
        val source = sampleBytes(500)
        val output = FormatProbe.ProbeOutput()
        val track = output.track(0, C.TRACK_TYPE_VIDEO)

        val consumed = track.sampleData(
            RecordingDataReader(source),
            10_000,
            true,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        assertEquals("EOF 允许时返回实际读取字节数", 500, consumed)

        track.sampleMetadata(0L, 0, consumed, 0, null)
        assertArrayEquals(source, requireNotNull(output.firstVideoSampleHead))
    }

    @Test
    fun `end of input without allowEndOfInput fails like previous implementation`() {
        val source = sampleBytes(500)
        val track = FormatProbe.ProbeOutput().track(0, C.TRACK_TYPE_VIDEO)

        val error = runCatching {
            track.sampleData(
                RecordingDataReader(source),
                10_000,
                false,
                TrackOutput.SAMPLE_DATA_PART_MAIN,
            )
        }.exceptionOrNull()

        assertTrue("旧实现抛 IllegalStateException，实际 $error", error is IllegalStateException)
    }

    @Test
    fun `non video track keeps discarding without capturing head`() {
        val input = RecordingDataReader(sampleBytes(10_000))
        val output = FormatProbe.ProbeOutput()
        val track = output.track(0, C.TRACK_TYPE_AUDIO)

        // 非视频轨维持 DiscardingTrackOutput 语义（其内部自带 4KB 有界读取）
        val consumed = track.sampleData(input, 10_000, true, TrackOutput.SAMPLE_DATA_PART_MAIN)

        assertTrue(consumed in 1..HEAD_CAP)
        track.sampleMetadata(0L, 0, 10_000, 0, null)
        assertNull(output.firstVideoSampleHead)
    }
}
