package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeightedPartition]：按权重大小做 LPT 分车道，替代 `chunked(n)` 轮转。
 *
 * 关键不变量：返回恰好 lanes 条车道、不丢项、不重复；大件先开工；同权重稳定保序。
 */
class WeightedPartitionTest {

    @Test
    fun `契约用例_10到5分3车道_负载15_15_15`() {
        val items = listOf(10L, 9L, 8L, 7L, 6L, 5L)
        val lanes = WeightedPartition.partition(items, 3) { it }

        assertEquals(3, lanes.size)
        val loads = lanes.map { it.sum() }
        assertEquals(listOf(15L, 15L, 15L), loads)
        assertEquals(15L, WeightedPartition.maxLaneLoad(items, 3) { it })
        // 不丢项、不重复
        assertEquals(items.sorted(), lanes.flatten().sorted())
    }

    @Test
    fun `空输入_返回lanes个空车道`() {
        val lanes = WeightedPartition.partition(emptyList<Int>(), 4) { it.toLong() }
        assertEquals(4, lanes.size)
        assertTrue(lanes.all { it.isEmpty() })
        assertEquals(0L, WeightedPartition.maxLaneLoad(emptyList<Int>(), 4) { it.toLong() })
    }

    @Test
    fun `lanes大于项数_车道数不变_空车道为空表`() {
        val lanes = WeightedPartition.partition(listOf("a", "b"), 5) { 1L }
        assertEquals(5, lanes.size)
        assertEquals(2, lanes.count { it.isNotEmpty() })
        assertEquals(3, lanes.count { it.isEmpty() })
        assertEquals(listOf("a", "b"), lanes.flatten().sorted())
    }

    @Test
    fun `全0权重_一条不丢且maxLaneLoad为0`() {
        val items = listOf("a", "b", "c", "d", "e", "f")
        val lanes = WeightedPartition.partition(items, 3) { 0L }
        // 车道分布不保证均匀：全 0 负载时 PriorityQueue 每次都取「当前最小负载」的车道，
        // 而所有车道负载恒为 0，同负载之间没有稳定顺序，完全可能出现 6/0/0。
        // 契约只保证：恰好 3 条车道、一条不丢、最大负载为 0。
        assertEquals(3, lanes.size)
        assertEquals(items.sorted(), lanes.flatten().sorted())
        assertEquals(0L, WeightedPartition.maxLaneLoad(items, 3) { 0L })
    }

    @Test
    fun `lanes为0或负数_抛IllegalArgumentException`() {
        for (bad in listOf(0, -1, -99)) {
            try {
                WeightedPartition.partition(listOf(1L, 2L), bad) { it }
                throw AssertionError("lanes=$bad 应抛 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // 期望路径
            }
            try {
                WeightedPartition.maxLaneLoad(listOf(1L, 2L), bad) { it }
                throw AssertionError("maxLaneLoad lanes=$bad 应抛 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // 期望路径
            }
        }
    }

    @Test
    fun `lanes为1_保持输入原序`() {
        val items = listOf("c", "a", "b")
        val lanes = WeightedPartition.partition(items, 1) { it.length.toLong() }
        assertEquals(1, lanes.size)
        assertEquals(items, lanes[0])
    }

    @Test
    fun `同权重_保持输入相对顺序`() {
        val items = listOf("a", "b", "c", "d", "e", "f")
        val lanes = WeightedPartition.partition(items, 2) { 5L }
        assertEquals(2, lanes.size)
        // 轮转落车道：偶数下标进 0 号车道、奇数下标进 1 号车道，各自保序
        assertEquals(listOf("a", "c", "e"), lanes[0])
        assertEquals(listOf("b", "d", "f"), lanes[1])
    }

    @Test
    fun `大件先开工_巨型文件排在车道首位`() {
        val weights = mapOf("tiny1" to 1L, "giant" to 100L, "tiny2" to 2L, "tiny3" to 3L)
        val items = listOf("tiny1", "giant", "tiny2", "tiny3")
        val lanes = WeightedPartition.partition(items, 2) { weights.getValue(it) }
        val giantLane = lanes.single { "giant" in it }
        assertEquals("giant", giantLane.first())
        assertEquals(items.sorted(), lanes.flatten().sorted())
    }

    @Test
    fun `maxLaneLoad_不劣于总负载除以车道数`() {
        // LPT 的下界性质：maxLaneLoad <= 总负载/lanes + 最大单项（保守上界）
        val weights = listOf(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L)
        val lanes = 3
        val total = weights.sum()
        val maxLoad = WeightedPartition.maxLaneLoad(weights, lanes) { it }
        assertTrue("maxLoad=$maxLoad 应不超过总负载", maxLoad <= total)
        assertTrue(
            "maxLoad=$maxLoad 应接近下界 ${total / lanes}",
            maxLoad >= total / lanes && maxLoad <= (total + lanes - 1) / lanes + weights.max(),
        )
    }

    @Test
    fun `负权重按0处理_不出现负负载_也不丢项`() {
        val items = listOf(-100L, 5L, 7L)
        // 单车道：-100 被钳成 0，负载 = 5 + 7
        assertEquals(12L, WeightedPartition.maxLaneLoad(items, 1) { it })
        val lanes = WeightedPartition.partition(items, 2) { it }
        assertEquals(2, lanes.size)
        assertEquals(items, lanes.flatten().sorted())
        assertTrue("负载不可能为负", WeightedPartition.maxLaneLoad(items, 3) { it } >= 0L)
    }

    @Test
    fun `每条车道内部元素总数等于输入总数`() {
        val items = (1..37).map { it.toLong() }
        val lanes = WeightedPartition.partition(items, 6) { it * it }
        assertEquals(6, lanes.size)
        assertEquals(items.size, lanes.sumOf { it.size })
        assertEquals(items.sorted(), lanes.flatten().sorted())
    }
}
