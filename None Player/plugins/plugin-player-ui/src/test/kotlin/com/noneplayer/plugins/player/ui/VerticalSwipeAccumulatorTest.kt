package com.noneplayer.plugins.player.ui

import com.noneplayer.plugins.player.ui.gesture.VerticalSwipeAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E144-2：竖滑增量累加器的纯 JVM 判据。
 *
 * 旧实现每帧 `(current - deltaPercent).roundToInt()`：慢滑单帧增量 <0.5% 被取整抹平，
 * `current` 又每帧读同一个已生效状态，整段滑动数值不动。这里钉住：
 * ① 亚半百分点的单帧增量要能累加；② 取整只发生在回写目标值；③ 端点夹取与一次性
 * edgeHit 报告；④ 播种值来自平台现值。真机手感仍需设备验收。
 */
class VerticalSwipeAccumulatorTest {
    @Test
    fun `start seeds from platform value`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(50)
        assertEquals(50f, acc.current(), 0.0001f)
    }

    @Test
    fun `sub half percent deltas accumulate instead of rounding away`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(50)
        // 每帧 0.3%：单帧取整都是 0，十帧应累计出 3%
        repeat(10) {
            acc.onDelta(0.3f, floor = 1, ceil = 100)
        }
        assertEquals(47f, acc.current(), 0.0001f)
        // 回写目标值取整只发生在最后：47.0 -> 47
        assertEquals(47, acc.onDelta(0f, floor = 1, ceil = 100).percent)
    }

    @Test
    fun `intermediate write back is rounded but does not reset the accumulator`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(50)
        // 三帧 0.3% -> 49.1 -> 回写 49；累计值仍是 49.1，不是 49
        val step = repeatAndLast(3) { acc.onDelta(0.3f, floor = 1, ceil = 100) }
        assertEquals(49, step.percent)
        assertEquals(49.1f, acc.current(), 0.0001f)
    }

    @Test
    fun `upward swipe increases because pointer y grows downward`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(50)
        val step = acc.onDelta(-2.4f, floor = 1, ceil = 100)
        assertEquals(52, step.percent)
        assertFalse(step.edgeHit)
    }

    @Test
    fun `clamp at ceil reports edge hit and saturates`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(99)
        val step = acc.onDelta(-5f, floor = 1, ceil = 100)
        assertEquals(100, step.percent)
        assertTrue(step.edgeHit)
        assertEquals(100f, acc.current(), 0.0001f)
    }

    @Test
    fun `clamp at brightness floor reports edge hit and saturates`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(2)
        val step = acc.onDelta(5f, floor = 1, ceil = 100)
        assertEquals(1, step.percent)
        assertTrue(step.edgeHit)
    }

    @Test
    fun `volume floor is zero`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(1)
        val step = acc.onDelta(3f, floor = 0, ceil = 100)
        assertEquals(0, step.percent)
        assertTrue(step.edgeHit)
    }

    @Test
    fun `edge hit rearms after moving back inside the range`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(100)
        assertTrue(acc.onDelta(-1f, floor = 1, ceil = 100).edgeHit)
        val back = acc.onDelta(1f, floor = 1, ceil = 100)
        assertFalse(back.edgeHit)
        assertEquals(99, back.percent)
    }

    @Test
    fun `start reseeds after a previous gesture`() {
        val acc = VerticalSwipeAccumulator()
        acc.start(20)
        repeat(5) { acc.onDelta(-1f, floor = 1, ceil = 100) }
        acc.start(80)
        assertEquals(80f, acc.current(), 0.0001f)
        assertEquals(82, acc.onDelta(-2f, floor = 1, ceil = 100).percent)
    }

    private inline fun <T> repeatAndLast(times: Int, block: () -> T): T {
        var last = block()
        repeat(times - 1) { last = block() }
        return last
    }
}
