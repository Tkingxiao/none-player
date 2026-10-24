package com.noneplayer.core.media.extract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E208 审查修复的纯逻辑回归（不需要 native）：
 * 包缓冲扩容、未知时长 seek 钳位、Vorbis Xiph lacing 拆头。
 */
class NpFfmpegExtractorLogicTest {

    @Test
    fun `包缓冲按需增长且不缩容`() {
        assertEquals(
            NpFfmpegExtractor.INITIAL_PACKET_CAPACITY,
            packetCapacityFor(0, 1000),
        )
        assertEquals(
            NpFfmpegExtractor.INITIAL_PACKET_CAPACITY,
            packetCapacityFor(NpFfmpegExtractor.INITIAL_PACKET_CAPACITY, 1000),
        )
        assertEquals(128 * 1024, packetCapacityFor(64 * 1024, 128 * 1024))
        // 超大包由调用方丢弃；纯函数本身也夹在上限内
        assertEquals(
            NpFfmpegExtractor.MAX_PACKET_BYTES,
            packetCapacityFor(0, NpFfmpegExtractor.MAX_PACKET_BYTES + 1),
        )
    }

    @Test
    fun `未知时长 seek 不再把目标夹到 0`() {
        val target90s = 90_000_000L
        // RM/NUT 估不出时长时 duration=0：旧写法会把所有 seek 变成 0
        assertEquals(target90s, seekPointTimeUs(0, target90s))
        // 时长 ≤ 0 是未知（负值同样按未知处理），目标保持原值
        assertEquals(target90s, seekPointTimeUs(-1, target90s))
        // 已知时长照常夹取
        assertEquals(10_000_000L, seekPointTimeUs(10_000_000L, target90s))
        assertEquals(0L, seekPointTimeUs(10_000_000L, -5))
    }

    @Test
    fun `Xiph lacing 拆出三段 Vorbis 头`() {
        // [包数-1=2][len0=3][len1=2] 然后才是三段头：[h0][h1][h2 剩余 4 字节]
        val csd = byteArrayOf(2, 3, 2, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val parts = splitXiphExtradata(csd)
        assertEquals(3, parts?.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), parts!![0])
        assertArrayEquals(byteArrayOf(4, 5), parts[1])
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), parts[2])
    }

    @Test
    fun `Xiph lacing 支持多字节长度且拒绝损坏数据`() {
        // len0 = 200 -> 0xC8 0x01（7bit 小端续位）；len1=1；长度全在前，头全在后
        val header0 = ByteArray(200) { 0x42 }
        val csd = byteArrayOf(2, 0xC8.toByte(), 0x01, 1) + header0 +
            byteArrayOf(0x33) + byteArrayOf(0x44, 0x55)
        val parts = splitXiphExtradata(csd)
        assertEquals(3, parts?.size)
        assertEquals(200, parts!![0].size)
        assertEquals(1, parts[1].size)
        assertArrayEquals(byteArrayOf(0x44, 0x55), parts[2])

        assertNull(splitXiphExtradata(null))
        assertNull(splitXiphExtradata(byteArrayOf(1, 0, 0))) // 只有 2 个包，不够 Vorbis 三段
        assertNull(splitXiphExtradata(byteArrayOf(2, 5, 1, 2))) // 声称 5 字节但数据不足
        assertNull(splitXiphExtradata(byteArrayOf(2, 1, 9))) // 最后一段为空
    }
}
