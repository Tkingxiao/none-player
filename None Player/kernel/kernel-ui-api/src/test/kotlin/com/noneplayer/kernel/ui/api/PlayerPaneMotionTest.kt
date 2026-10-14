package com.noneplayer.kernel.ui.api

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPaneMotionTest {
    private val fullscreen = Rect(0f, 0f, 1600f, 1000f)
    private val pane = Rect(600f, 100f, 1540f, 960f)

    @Test
    fun `return starts at the fullscreen bounds and ends at the original pane`() {
        assertEquals(fullscreen, playerPaneFrame(fullscreen, pane, 0f))
        assertEquals(pane, playerPaneFrame(fullscreen, pane, 1f))
    }

    @Test
    fun `midpoint interpolates position and size together`() {
        assertEquals(Rect(300f, 50f, 1570f, 980f), playerPaneFrame(fullscreen, pane, 0.5f))
    }

    @Test
    fun `every edge moves monotonically without overshoot`() {
        var previous = fullscreen
        for (step in 1..100) {
            val current = playerPaneFrame(fullscreen, pane, step / 100f)
            check(current.left >= previous.left && current.top >= previous.top)
            check(current.right <= previous.right && current.bottom <= previous.bottom)
            previous = current
        }
        assertEquals(pane, previous)
    }

    @Test
    fun `out of range progress cannot move beyond either endpoint`() {
        assertEquals(fullscreen, playerPaneFrame(fullscreen, pane, -1f))
        assertEquals(pane, playerPaneFrame(fullscreen, pane, 2f))
    }

    /** 同一窗口内起飞（用户点返回）：源与落点同处一个坐标系，不做映射。 */
    @Test
    fun `same window source rectangle is passed through`() {
        val source = Rect(20f, 30f, 200f, 130f)
        assertEquals(source, mapPictureRect(source, IntSize.Zero, fullscreen))
    }

    /**
     * 跨窗口（转屏）：源矩形按**窗口归一化位置**搬进新窗口，尺寸按窗口比例**统一**缩放。
     * 逐轴缩放会把 16:9 的中间帧挤成方形，所以这里断言长宽乘的是同一个比例。
     */
    @Test
    fun `cross window source keeps its place without stretching`() {
        // 竖屏 400x800 里画面 360x203，中心在 (200, 221.5)
        val source = Rect(20f, 120f, 380f, 323f)
        val destination = Rect(0f, 0f, 800f, 400f)
        val mapped = mapPictureRect(source, IntSize(400, 800), destination)
        // scale = min(800/400, 400/800) = 0.5 → 长短边同倍缩，比例不变
        assertEquals(180f, mapped.width, 0.01f)
        assertEquals(101.5f, mapped.height, 0.01f)
        // 中心按窗口归一化位置搬：0.5 / 0.2769 → (400, 110.75)
        assertEquals(400f, mapped.center.x, 0.01f)
        assertEquals(110.75f, mapped.center.y, 0.01f)
    }

    /** 源矩形（或换算结果）越出本窗口时收敛回来：开局就缺一角比多飞一点更难看。 */
    @Test
    fun `cross window source is pulled back inside the window`() {
        val source = Rect(900f, -40f, 1_100f, 160f)
        val destination = Rect(0f, 0f, 1_000f, 600f)
        val mapped = mapPictureRect(source, IntSize(1_000, 400), destination)
        assertEquals(800f, mapped.left, 0.01f)
        assertEquals(0f, mapped.top, 0.01f)
        assertEquals(200f, mapped.width, 0.01f)
    }
}
