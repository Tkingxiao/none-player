package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanParallelism]：按核数自主分配车道的纯函数，边界必须钉死。
 *
 * 这两条夹逼区间的意义：
 * - io 车道再多也只是堆在 MediaProvider 的 binder 队列上（1 核机型给 4 是下限，
 *   64 核机型封顶 12，避免把 /proc 压满反而变慢）；
 * - decode 车道受解码器数量限制，半核数且封顶 6，8 核给 4（与历史硬编码
 *   THUMB_PARALLELISM = 4 持平，不倒退）。
 */
class ScanParallelismTest {

    @Test
    fun `ioWorkers_1核_钳到下限4`() {
        assertEquals(4, ScanParallelism.ioWorkers(1))
    }

    @Test
    fun `ioWorkers_2核_按下限4`() {
        // 2 * 2 = 4，正好落上下限
        assertEquals(4, ScanParallelism.ioWorkers(2))
    }

    @Test
    fun `ioWorkers_4核_8条`() {
        assertEquals(8, ScanParallelism.ioWorkers(4))
    }

    @Test
    fun `ioWorkers_6核_封顶12`() {
        // 6 * 2 = 12，正好封顶
        assertEquals(12, ScanParallelism.ioWorkers(6))
    }

    @Test
    fun `ioWorkers_8核_封顶12不越界`() {
        // 8 * 2 = 16 → 钳到 12
        assertEquals(12, ScanParallelism.ioWorkers(8))
    }

    @Test
    fun `ioWorkers_64核_封顶12`() {
        assertEquals(12, ScanParallelism.ioWorkers(64))
    }

    @Test
    fun `decodeWorkers_1核_钳到下限2`() {
        // 1 / 2 = 0 → 钳到 2
        assertEquals(2, ScanParallelism.decodeWorkers(1))
    }

    @Test
    fun `decodeWorkers_2核_下限2`() {
        // 2 / 2 = 1 → 钳到 2
        assertEquals(2, ScanParallelism.decodeWorkers(2))
    }

    @Test
    fun `decodeWorkers_8核_4条与历史持平`() {
        assertEquals(4, ScanParallelism.decodeWorkers(8))
    }

    @Test
    fun `decodeWorkers_12核_封顶6`() {
        // 12 / 2 = 6
        assertEquals(6, ScanParallelism.decodeWorkers(12))
    }

    @Test
    fun `decodeWorkers_64核_封顶6`() {
        // 64 / 2 = 32 → 钳到 6
        assertEquals(6, ScanParallelism.decodeWorkers(64))
    }

    @Test
    fun `ioWorkers_非法核数0与负数_按1核处理`() {
        assertEquals(4, ScanParallelism.ioWorkers(0))
        assertEquals(4, ScanParallelism.ioWorkers(-1))
        assertEquals(2, ScanParallelism.decodeWorkers(0))
        assertEquals(2, ScanParallelism.decodeWorkers(-1))
    }

    @Test
    fun `describe_8核_格式与数值都正确`() {
        assertEquals("cores=8 io=12 decode=4", ScanParallelism.describe(8))
    }

    @Test
    fun `describe_1核`() {
        assertEquals("cores=1 io=4 decode=2", ScanParallelism.describe(1))
    }

    @Test
    fun `默认参数_按本机核数计算`() {
        // 默认值必须真的取 availableProcessors，否则真机上永远按写死的核数跑
        val cores = Runtime.getRuntime().availableProcessors()
        val expectedIo = (cores.coerceAtLeast(1) * 2).coerceIn(4, 12)
        val expectedDecode = (cores.coerceAtLeast(1) / 2).coerceIn(2, 6)
        assertEquals(
            "cores=$cores io=$expectedIo decode=$expectedDecode",
            ScanParallelism.describe(),
        )
        assertEquals(expectedIo, ScanParallelism.ioWorkers())
        assertEquals(expectedDecode, ScanParallelism.decodeWorkers())
    }

    @Test
    fun `所有核数区间_io恒不小于decode_且都在合法范围`() {
        for (cores in 1..128) {
            val io = ScanParallelism.ioWorkers(cores)
            val decode = ScanParallelism.decodeWorkers(cores)
            assertTrue("io=$io 应在 [4,12]", io in 4..12)
            assertTrue("decode=$decode 应在 [2,6]", decode in 2..6)
            assertTrue("io=$io 应不小于 decode=$decode", io >= decode)
        }
    }
}
