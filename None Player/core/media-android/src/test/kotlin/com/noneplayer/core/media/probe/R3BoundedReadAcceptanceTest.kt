package com.noneplayer.core.media.probe

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.extract.Mp4vAspRewritingExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 独立验收（REV-05「有界读」）——黑盒验收 FormatProbe 的输出壳与 mp4v 改写器：
 *
 * 1. 超大样本（64MB）**不得**产生 length 级 read 请求 / 大分配：单次 read 请求有界，
 *    但总消费必须等于 length、sampleData 返回值必须等于实际消费字节数；
 * 2. `format()` 之后决策头只固化前 HEAD_CAP 字节（前导字节上限 + 内容前缀正确）；
 * 3. 短输入 / 提前 EOF（allowEndOfInput 两种）与旧语义一致；
 * 4. 分段到达（1..N 字节）时 head 前缀与一次读完完全一致、消费不差一字节；
 * 5. `Mp4vAspRewritingExtractor` 大样本下游字节与输入完全等价（含 sampleMetadata
 *    size/offset），决策头只留 HEAD_CAP、剩余分块转发；短样本同样等价；
 * 6. 回归：Simple 保持平台 mp4v 链路、ASP 判读不回归。
 *
 * 说明：REV-05 实现落地前，`FormatProbe` 内部 `ProbeOutput` 是 private，本测试按任务约束
 * （不改生产代码）用反射拿到输出壳；待实现改为 internal 后本测试无需改动。
 */
@OptIn(UnstableApi::class)
class R3BoundedReadAcceptanceTest {

    // ---------------- REV-05 ①：超大样本有界读 + head 上限 ----------------

    @Test
    fun rev05HugeSampleReadIsBoundedAndFullyConsumed() {
        val length = 64 * MIB
        val reader = RecordingDataReader(availableBytes = length.toLong(), chunkLimit = 64 * KIB)
        val probe = ProbeOutputHarness.create()
        val track = probe.videoTrack()

        val returned = track.sampleData(reader, length, /* allowEndOfInput = */ true, /* sampleDataPart = */ 0)
        track.sampleMetadata(0L, 0, length, 0, null)

        assertEquals(
            "sampleData 必须返回实际消费字节数（=length）",
            length.toLong(),
            returned.toLong(),
        )
        assertEquals("必须完整消费 length 字节", length.toLong(), reader.consumedBytes)
        assertTrue(
            "单次 read 请求必须有界：实际最大 ${reader.maxRequested}B，" +
                "禁止 length（$length B）级请求（=一次性分配整样本）",
            reader.maxRequested <= MAX_BOUNDED_READ,
        )
        val head = probe.firstVideoSampleHead()
        assertNotNull("sampleMetadata 之后应已固化首个视频样本前导字节", head)
        assertEquals("前导字节只保留 HEAD_CAP", HEAD_CAP, head!!.size)
        assertArrayEquals(
            "前导字节内容必须等于原样本前 HEAD_CAP 字节",
            PatternBytes.slice(0, HEAD_CAP),
            head,
        )
    }

    // ---------------- REV-05 ②：短输入 / 提前 EOF 旧语义 ----------------

    @Test
    fun rev05ShortInputWithAllowEndOfInputKeepsLegacySemantics() {
        val requestedLength = 10_000
        val available = 3_000
        val reader = RecordingDataReader(availableBytes = available.toLong(), chunkLimit = 1_024)
        val probe = ProbeOutputHarness.create()
        val track = probe.videoTrack()

        val returned = track.sampleData(reader, requestedLength, /* allowEndOfInput = */ true, 0)
        track.sampleMetadata(0L, 0, requestedLength, 0, null)

        assertEquals(
            "提前 EOF 且 allowEndOfInput=true 时返回实际读到的字节数",
            available.toLong(),
            returned.toLong(),
        )
        assertEquals("必须消费到 EOF 为止", available.toLong(), reader.consumedBytes)
        val head = probe.firstVideoSampleHead()
        assertNotNull(head)
        assertEquals("短样本前导字节全部保留", available, head!!.size)
        assertArrayEquals(PatternBytes.slice(0, available), head)
    }

    @Test
    fun rev05EarlyEofWithoutAllowEndOfInputStillThrows() {
        val reader = RecordingDataReader(availableBytes = 100, chunkLimit = 64)
        val track = ProbeOutputHarness.create().videoTrack()

        assertThrows(
            "allowEndOfInput=false 时提前 EOF 必须仍抛 IllegalStateException（旧语义）",
            IllegalStateException::class.java,
        ) {
            track.sampleData(reader, 1_000, /* allowEndOfInput = */ false, 0)
        }
        assertEquals(100L, reader.consumedBytes)
    }

    // ---------------- REV-05 ③：分段到达 head 一致 ----------------

    @Test
    fun rev05SegmentedArrivalHeadMatchesSingleRead() {
        val length = 1 * MIB

        fun readHead(reader: RecordingDataReader): Pair<Int, ByteArray?> {
            val probe = ProbeOutputHarness.create()
            val track = probe.videoTrack()
            val returned = track.sampleData(reader, length, true, 0)
            track.sampleMetadata(0L, 0, length, 0, null)
            return returned to probe.firstVideoSampleHead()
        }

        val singleReader = RecordingDataReader(availableBytes = length.toLong(), chunkLimit = Int.MAX_VALUE)
        val chunkedReader = RecordingDataReader(
            availableBytes = length.toLong(),
            chunkSizes = intArrayOf(1, 2, 3, 5, 7, 11, 13, 17),
        )
        val (singleReturned, singleHead) = readHead(singleReader)
        val (chunkedReturned, chunkedHead) = readHead(chunkedReader)

        assertEquals(length.toLong(), singleReturned.toLong())
        assertEquals(length.toLong(), chunkedReturned.toLong())
        assertEquals("不能少消费一个字节（一次读完）", length.toLong(), singleReader.consumedBytes)
        assertEquals("不能少消费一个字节（分段到达）", length.toLong(), chunkedReader.consumedBytes)
        assertNotNull(singleHead)
        assertNotNull(chunkedHead)
        assertEquals(HEAD_CAP, singleHead!!.size)
        assertEquals(HEAD_CAP, chunkedHead!!.size)
        assertArrayEquals("分段到达的 head 前缀必须与一次读完完全一致", singleHead, chunkedHead)
        assertArrayEquals("head 前缀必须等于输入前 HEAD_CAP 字节", PatternBytes.slice(0, HEAD_CAP), chunkedHead)
        assertTrue(
            "分段读取时单次请求也应有界：实际最大 ${chunkedReader.maxRequested}B",
            chunkedReader.maxRequested <= MAX_BOUNDED_READ,
        )
    }

    // ---------------- REV-05 ④：mp4v 改写器大样本等价 + 分块转发 ----------------

    @Test
    fun rev05RewritingLargeSampleIsByteEquivalentAndChunked() {
        val length = 2 * MIB
        val reader = RecordingDataReader(availableBytes = length.toLong(), chunkLimit = 64 * KIB)
        val downstream = RecordingExtractorOutput()
        val inner = CapturingInnerExtractor()
        Mp4vAspRewritingExtractor(inner).init(downstream)
        val wrapper = inner.output
            ?: throw AssertionError("Mp4vAspRewritingExtractor.init 未把下游输出壳交给内层 Extractor")
        val track = wrapper.track(0, C.TRACK_TYPE_VIDEO)
        val recorded = downstream.lastVideoTrack
            ?: throw AssertionError("下游输出壳未收到视频轨")

        track.format(mp4vFormat())
        val returned = track.sampleData(reader, length, true, 0)
        track.sampleMetadata(1_000L, 0, length, 64, null)

        assertEquals("sampleData 必须返回实际消费字节数", length.toLong(), returned.toLong())
        assertEquals("必须完整消费 length 字节", length.toLong(), reader.consumedBytes)
        assertTrue(
            "改写器不得一次性读满整样本：实际最大 read 请求 ${reader.maxRequested}B",
            reader.maxRequested <= MAX_BOUNDED_READ,
        )
        assertEquals("下游只收到一份格式", 1, recorded.formats.size)
        assertEquals("Simple/未知 profile 不得改写成专用解码链", MIME_MP4V, recorded.formats[0].sampleMimeType)
        assertTrue(
            "下游必须分块接收：实际 ${recorded.sampleCalls.size} 块",
            recorded.sampleCalls.size >= 2,
        )
        assertEquals("决策头只保留 HEAD_CAP 再下发", HEAD_CAP, recorded.sampleCalls.first().requestedSize)
        assertTrue(
            "剩余部分必须分块转发，单块不得 length 级：实际最大 ${recorded.maxSampleRequestLength}B",
            recorded.maxSampleRequestLength <= MAX_BOUNDED_READ,
        )
        assertEquals("下游收到的总字节数必须等于输入 length", length.toLong(), recorded.forwardedBytes.size().toLong())
        assertArrayEquals(
            "大样本下游字节必须与输入完全等价",
            PatternBytes.slice(0, length),
            recorded.forwardedBytes.toByteArray(),
        )
        assertEquals("sampleMetadata 必须原样转发一次", 1, recorded.metadataCalls.size)
        val metadata = recorded.metadataCalls.single()
        assertEquals("sampleMetadata size 必须保留原样本大小", length.toLong(), metadata.size.toLong())
        assertEquals("sampleMetadata offset 必须原样转发", 64, metadata.offset)
        assertEquals("sampleMetadata timeUs 必须原样转发", 1_000L, metadata.timeUs)
    }

    @Test
    fun rev05RewritingShortSimpleSampleStaysPlatformMime() {
        val simpleHeader = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0x01, 0x00)
        val length = 1_000
        val sample = ByteArray(length) { index ->
            if (index < simpleHeader.size) simpleHeader[index] else PatternBytes.byteAt(index.toLong())
        }
        assertEquals(
            "回归：Simple Profile 头必须判读为 SIMPLE",
            Mpeg4ProfileParser.RequiredProfile.SIMPLE,
            Mpeg4ProfileParser.parse(sample.copyOf(6)).required,
        )

        val reader = RecordingDataReader(availableBytes = length.toLong(), chunkLimit = length, source = sample)
        val downstream = RecordingExtractorOutput()
        val inner = CapturingInnerExtractor()
        Mp4vAspRewritingExtractor(inner).init(downstream)
        val wrapper = inner.output
            ?: throw AssertionError("Mp4vAspRewritingExtractor.init 未把下游输出壳交给内层 Extractor")
        val track = wrapper.track(0, C.TRACK_TYPE_VIDEO)
        val recorded = downstream.lastVideoTrack
            ?: throw AssertionError("下游输出壳未收到视频轨")

        track.format(mp4vFormat())
        val returned = track.sampleData(reader, length, true, 0)
        track.sampleMetadata(0L, 0, length, 0, null)

        assertEquals(length.toLong(), returned.toLong())
        assertEquals("短样本也必须给下游发格式", 1, recorded.formats.size)
        assertEquals("Simple 样本必须保持平台 mp4v 链路（不得改写走专用解码）", MIME_MP4V, recorded.formats[0].sampleMimeType)
        assertEquals("短样本下游总字节数必须等于 length", length.toLong(), recorded.forwardedBytes.size().toLong())
        assertArrayEquals("短样本下游字节必须完全等价", sample, recorded.forwardedBytes.toByteArray())
        assertEquals(1, recorded.metadataCalls.size)
        assertEquals(length.toLong(), recorded.metadataCalls.single().size.toLong())
    }

    // ---------------- REV-05 ⑤：Simple / ASP 判读回归 ----------------

    @Test
    fun rev05SimpleAndAspProfileClassificationRegression() {
        val simple = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0x01, 0x00)
        val asp = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0xF5.toByte(), 0x00)

        assertEquals(
            "Simple Profile（pli=0x01）必须判 SIMPLE",
            Mpeg4ProfileParser.RequiredProfile.SIMPLE,
            Mpeg4ProfileParser.parse(simple).required,
        )
        assertEquals(
            "Advanced Simple Profile（pli=0xF5）必须判 ADVANCED_SIMPLE",
            Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE,
            Mpeg4ProfileParser.parse(asp).required,
        )
        assertFalse(
            "Simple 码流不得被判为需要专用解码链（必须继续走平台）",
            Mp4vAspGate.requiresDedicatedDecoder(simple),
        )
        // ASP 的专用链判定依赖设备 profile 查询：纯 JVM 下查询失败 → fail-open（空表→false），
        // 属环境限制而非规则。JVM 里只断言「不误判 Simple 走专用链」。
    }

    // ---------------- helpers ----------------

    private fun mp4vFormat(): Format = Format.Builder()
        .setSampleMimeType(MIME_MP4V)
        .setWidth(320)
        .setHeight(240)
        .build()

    private companion object {
        const val KIB = 1_024
        const val MIB = 1_024 * 1_024

        /** 与生产实现 HEAD_CAP 常量一致：决策头只保留前 4KB */
        const val HEAD_CAP = 4 * 1_024

        /** 有界读的上限（宽松取 128KB：生产 scratch 通常 ≤64KB，但绝不允许 length 级） */
        const val MAX_BOUNDED_READ = 128 * 1_024

        const val MIME_MP4V = "video/mp4v-es"
    }
}
