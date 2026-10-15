package com.noneplayer.kernel.ui.api

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整屏 morph 的位姿数学（E133）。
 *
 * 参考实现 `player-orientation-sim.html` 里每个 actor 做三件事：中心从旧矩形插值到新矩形、
 * 尺寸同插值、始终正立。这里钉的就是这三条，外加"跨窗口搬运"那一步——转屏前后两套窗口
 * 坐标系不能直接插值，否则中间帧会飞出屏幕。
 */
class NpMorphTest {
    private val fullscreen = Rect(0f, 0f, 1800f, 1350f)
    private val pane = Rect(1057f, 259f, 2830f, 1589f)

    // ---- 跨窗口搬运 ----

    /** 同一窗口内（只是换版式，没转屏）：原样返回，不做映射。 */
    @Test
    fun `same window source rectangle is passed through`() {
        val source = Rect(20f, 30f, 200f, 130f)
        assertEquals(source, mapPictureRect(source, IntSize.Zero, fullscreen))
    }

    /**
     * 跨窗口（转屏）：中心按窗口归一化位置搬，尺寸按窗口比例**统一**缩放。
     * 逐轴缩放会把 16:9 的中间帧挤成方形，所以断言长宽乘的是同一个比例。
     */
    @Test
    fun `cross window source keeps its place without stretching`() {
        val source = Rect(20f, 120f, 380f, 323f)
        val destination = Rect(0f, 0f, 800f, 400f)
        val mapped = mapPictureRect(source, IntSize(400, 800), destination)
        assertEquals(180f, mapped.width, 0.01f)
        assertEquals(101.5f, mapped.height, 0.01f)
        assertEquals(400f, mapped.center.x, 0.01f)
        assertEquals(110.75f, mapped.center.y, 0.01f)
    }

    /** 源矩形贴边时收敛回窗口内：开局就缺一角比多飞一点更难看。 */
    @Test
    fun `cross window source is pulled back inside the window`() {
        val source = Rect(900f, -40f, 1_100f, 160f)
        val destination = Rect(0f, 0f, 1_000f, 600f)
        val mapped = mapPictureRect(source, IntSize(1_000, 400), destination)
        assertEquals(800f, mapped.left, 0.01f)
        assertEquals(0f, mapped.top, 0.01f)
        assertEquals(200f, mapped.width, 0.01f)
    }

    // ---- 位姿插值 ----

    /** e=0 必须严丝合缝压在旧矩形上：起飞的同一帧看不到任何"已经落位"的内容。 */
    @Test
    fun `at zero the node sits exactly on its previous rectangle`() {
        val pose = npMorphTransform(fullscreen, pane, progress = 0f, displacement = true, scale = true)
        assertEquals(fullscreen.center.x, pane.center.x + pose.translationX, 0.01f)
        assertEquals(fullscreen.center.y, pane.center.y + pose.translationY, 0.01f)
        assertEquals(fullscreen.width, pane.width * pose.scaleX, 0.01f)
        assertEquals(fullscreen.height, pane.height * pose.scaleY, 0.01f)
    }

    /** e=1 必须回到恒等：落点就是版式本来的位置，不留一丝残差。 */
    @Test
    fun `at one the transform is identity`() {
        val pose = npMorphTransform(fullscreen, pane, progress = 1f, displacement = true, scale = true)
        assertEquals(0f, pose.translationX, 0.01f)
        assertEquals(0f, pose.translationY, 0.01f)
        assertEquals(1f, pose.scaleX, 0.01f)
        assertEquals(1f, pose.scaleY, 0.01f)
        assertEquals(1f, pose.alpha, 0.01f)
    }

    /** 中心与四条边都单调逼近落点：中途不回头、不过冲（曲线由 Silk 负责，不在这里）。 */
    @Test
    fun `travel is monotonic toward the landing rect`() {
        var previousDistance = Float.MAX_VALUE
        for (step in 0..100) {
            val pose = npMorphTransform(fullscreen, pane, step / 100f, true, scale = true)
            val cx = pane.center.x + pose.translationX
            val cy = pane.center.y + pose.translationY
            val dx = cx - pane.center.x
            val dy = cy - pane.center.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            assertTrue("第 $step 步离落点更远了", distance <= previousDistance + 0.01f)
            previousDistance = distance
            assertEquals(pane.width * pose.scaleX, pane.width + (fullscreen.width - pane.width) * (1f - step / 100f), 0.5f)
        }
    }

    /**
     * 越接近落位，对"落点矩形本身的变化"越不敏感。
     *
     * 这是旧实现"实测落点晚到 → 端点被换 → 单帧瞬移"的反证：morph 每帧从**自己当前的
     * 版式矩形**现算位姿，e→1 时平移量趋于 0，所以右栏宽度即使后来才稳定也顶不跳画面。
     */
    @Test
    fun `late landing rect changes cannot shove a nearly landed node`() {
        val shifted = Rect(1057f, 259f, 2830f, 1620f)
        val early = npMorphTransform(fullscreen, pane, 0.2f, true, scale = true)
        val earlyShifted = npMorphTransform(fullscreen, shifted, 0.2f, true, scale = true)
        val late = npMorphTransform(fullscreen, pane, 0.98f, true, scale = true)
        val lateShifted = npMorphTransform(fullscreen, shifted, 0.98f, true, scale = true)
        val earlyJump = kotlin.math.abs(earlyShifted.translationY - early.translationY)
        val lateJump = kotlin.math.abs(lateShifted.translationY - late.translationY)
        assertTrue("落位附近的跳变必须比开局小一个量级：early=$earlyJump late=$lateJump", lateJump < earlyJump / 5f)
    }

    /** 文字与按钮不缩放：画布缩放会拉糊字形，只跟着平移。 */
    @Test
    fun `non scaling parts translate without stretching`() {
        val pose = npMorphTransform(fullscreen, pane, 0.5f, displacement = true, scale = false)
        assertEquals(1f, pose.scaleX, 0.01f)
        assertEquals(1f, pose.scaleY, 0.01f)
        assertTrue(pose.translationY != 0f)
    }

    /** 只在新形态存在的部件（没有旧矩形）：不位移，只淡入。 */
    @Test
    fun `parts without a predecessor only fade in`() {
        val pose = npMorphTransform(null, pane, 0.5f, displacement = true, scale = true)
        assertEquals(0f, pose.translationX, 0.01f)
        assertEquals(0f, pose.translationY, 0.01f)
        assertEquals(0.5f, pose.alpha, 0.01f)
    }

    /** Reduce Motion / 轻量档：不演位移，直接落位（旧矩形存在时连淡入都不做）。 */
    @Test
    fun `displacement off lands immediately`() {
        val moved = npMorphTransform(fullscreen, pane, 0.1f, displacement = false, scale = true)
        assertEquals(0f, moved.translationX, 0.01f)
        assertEquals(1f, moved.scaleX, 0.01f)
        assertEquals(1f, moved.alpha, 0.01f)
        val entering = npMorphTransform(null, pane, 0.1f, displacement = false, scale = true)
        assertEquals(0.1f, entering.alpha, 0.01f)
    }

    /** 越界进度不得越过两端（补间曲线由上层负责，这里只保证夹住）。 */
    @Test
    fun `out of range progress cannot overshoot the endpoints`() {
        assertEquals(fullscreen.center.x, pane.center.x + npMorphTransform(fullscreen, pane, -1f, true, true).translationX, 0.01f)
        val atTwo = npMorphTransform(fullscreen, pane, 2f, true, true)
        assertEquals(0f, atTwo.translationX, 0.01f)
        assertEquals(1f, atTwo.scaleX, 0.01f)
    }

    // ---- 搬进新窗口（E133 起点） ----

    /** 同一窗口换版式（没转屏）：中心与尺寸都不动。 */
    @Test
    fun `same window carries nothing`() {
        val source = Rect(20f, 30f, 200f, 130f)
        assertEquals(source, carryIntoWindow(source, IntSize(1000, 1000), IntSize(1000, 1000)))
    }

    /**
     * 竖屏顶部的画面搬进横屏窗口：**尺寸一点不动**，方位照旧留在中上。
     *
     * 尺寸不动是这个函数的重点：开局把它缩一下（或按刚体旋转把 16:9 变成 9:16 再逐轴
     * 缩放）都会在飞行开始处出现一次假缩放——用户实报的"在固定位置直接展开"。
     */
    @Test
    fun `carry keeps the size and the relative place`() {
        // 竖屏 1800x2880，画面满宽、顶在 270..1450
        val video = Rect(0f, 270f, 1800f, 1450f)
        val carried = carryIntoWindow(video, IntSize(1800, 2880), IntSize(2880, 1800))
        assertEquals(video.width, carried.width, 0.5f)
        assertEquals(video.height, carried.height, 0.5f)
        assertEquals(2880f * 0.5f, carried.center.x, 1f)
        // 竖屏在中上 → 横屏也留在上半。落到垂直正中就是"全屏"读感，是这条要挡掉的回归。
        assertTrue("必须留在上半：y=" + carried.center.y, carried.center.y <= 1800f * 0.40f)
    }

    /** 搬过去之后整块矩形仍在窗口内（贴边可以，飞出去不行）。 */
    @Test
    fun `carry clamps the rect inside the window`() {
        // 这条画面紧贴竖屏顶端，搬进横屏后按归一化位置会探出上边
        val nearTop = Rect(0f, 0f, 1800f, 1180f)
        val carried = carryIntoWindow(nearTop, IntSize(1800, 2880), IntSize(2880, 1800))
        assertTrue("上边不许探出窗口：top=" + carried.top, carried.top >= -0.5f)
        assertTrue("下边不许探出窗口：bottom=" + carried.bottom, carried.bottom <= 1800.5f)
    }
}

