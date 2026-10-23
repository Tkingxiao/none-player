package com.noneplayer.plugins.player.ui

import com.noneplayer.plugins.player.ui.gesture.STAGE_SEEK_FULL_SPAN_MS
import com.noneplayer.plugins.player.ui.gesture.StageDragKind
import com.noneplayer.plugins.player.ui.gesture.StageGestureZonePx
import com.noneplayer.plugins.player.ui.gesture.StageTapZone
import com.noneplayer.plugins.player.ui.gesture.StageVerticalMode
import com.noneplayer.plugins.player.ui.gesture.clampSeekTargetMs
import com.noneplayer.plugins.player.ui.gesture.decideDragKind
import com.noneplayer.plugins.player.ui.gesture.doubleTapSeekTargetMs
import com.noneplayer.plugins.player.ui.gesture.isDragDeadZone
import com.noneplayer.plugins.player.ui.gesture.isSecondTapValid
import com.noneplayer.plugins.player.ui.gesture.isTapDeadZone
import com.noneplayer.plugins.player.ui.gesture.movementCancelsLongPress
import com.noneplayer.plugins.player.ui.gesture.seekDeltaMs
import com.noneplayer.plugins.player.ui.gesture.seekSpanForDurationMs
import com.noneplayer.plugins.player.ui.gesture.stageDpAtLeast
import com.noneplayer.plugins.player.ui.gesture.tapZoneForX
import com.noneplayer.plugins.player.ui.gesture.verticalDeltaPercent
import com.noneplayer.plugins.player.ui.gesture.verticalModeForX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 手势策略的纯 JVM 判据：死区/分轴/分区/双击窗口/长按守卫/增量换算/时长夹取。
 *
 * 设备侧行为（系统手势不被抢、进度条拖动不触发舞台手势）仍需真机验收；
 * 这里钉住的是识别器决策函数的语义，防止回归。
 */
class StageGesturePolicyTest {

    private val zones = StageGestureZonePx(left = 24f, right = 32f, top = 48f, bottom = 56f)

    @Test
    fun `edge width takes larger of system inset and minimum`() {
        assertEquals(24f, stageDpAtLeast(systemInsetDp = 0f, minDp = 24f), 0f)
        assertEquals(36f, stageDpAtLeast(systemInsetDp = 36f, minDp = 24f), 0f)
        assertEquals(48f, stageDpAtLeast(systemInsetDp = 16f, minDp = 48f), 0f)
        assertEquals(64f, stageDpAtLeast(systemInsetDp = 64f, minDp = 48f), 0f)
    }

    @Test
    fun `tap dead zone covers left right and top edges`() {
        val width = 1000f
        assertTrue(isTapDeadZone(-10f, 500f, width, zones))
        assertTrue(isTapDeadZone(0f, 500f, width, zones))
        assertTrue(isTapDeadZone(23.9f, 500f, width, zones))
        assertFalse(isTapDeadZone(24f, 500f, width, zones))
        assertTrue(isTapDeadZone(969f, 500f, width, zones))
        assertFalse(isTapDeadZone(968f, 500f, width, zones))
        assertTrue(isTapDeadZone(500f, 0f, width, zones))
        assertTrue(isTapDeadZone(500f, 47.9f, width, zones))
        assertFalse(isTapDeadZone(500f, 48f, width, zones))
        // 底部不是点按死区：解锁钮/控制层唤出需要点到底部。
        assertFalse(isTapDeadZone(500f, 999f, width, zones))
    }

    @Test
    fun `drag dead zone covers all four edges`() {
        val width = 1000f
        val height = 800f
        assertTrue(isDragDeadZone(10f, 400f, width, height, zones))
        assertTrue(isDragDeadZone(990f, 400f, width, height, zones))
        assertTrue(isDragDeadZone(500f, 10f, width, height, zones))
        assertTrue(isDragDeadZone(500f, 745f, width, height, zones))
        assertFalse(isDragDeadZone(500f, 744f, width, height, zones))
        assertFalse(isDragDeadZone(500f, 400f, width, height, zones))
    }

    @Test
    fun `drag dead zone is checked before axis decision`() {
        val width = 1000f
        val height = 800f
        // 从底部导航条起手的横滑：死区先于分轴，不能再变成一次 seek。
        assertNull(
            decideDragKind(
                totalX = 300f,
                totalY = 5f,
                startX = 500f,
                startY = 760f,
                width = width,
                height = height,
                zones = zones,
            ),
        )
        // 从顶部状态栏起手的竖滑同样不接管。
        assertNull(
            decideDragKind(
                totalX = 5f,
                totalY = 300f,
                startX = 500f,
                startY = 10f,
                width = width,
                height = height,
                zones = zones,
            ),
        )
        assertNull(
            decideDragKind(
                totalX = 300f,
                totalY = 5f,
                startX = 10f,
                startY = 400f,
                width = width,
                height = height,
                zones = zones,
            ),
        )
    }

    @Test
    fun `horizontal movement wins and becomes seek`() {
        val width = 1000f
        val height = 800f
        assertEquals(
            StageDragKind.Seek,
            decideDragKind(100f, 20f, 500f, 400f, width, height, zones),
        )
        assertEquals(
            StageDragKind.Seek,
            decideDragKind(-100f, 20f, 500f, 400f, width, height, zones),
        )
        // 等量位移按横向优先。
        assertEquals(
            StageDragKind.Seek,
            decideDragKind(50f, 50f, 500f, 400f, width, height, zones),
        )
    }

    @Test
    fun `vertical movement on left third is brightness`() {
        assertEquals(
            StageDragKind.Brightness,
            decideDragKind(5f, 120f, 100f, 400f, 1000f, 800f, zones),
        )
    }

    @Test
    fun `vertical movement on right third is volume`() {
        assertEquals(
            StageDragKind.Volume,
            decideDragKind(5f, 120f, 900f, 400f, 1000f, 800f, zones),
        )
    }

    @Test
    fun `vertical movement on middle third is not taken`() {
        assertNull(decideDragKind(5f, 120f, 500f, 400f, 1000f, 800f, zones))
    }

    @Test
    fun `tap zones split screen into thirds`() {
        val width = 900f
        assertEquals(StageTapZone.Back, tapZoneForX(0f, width))
        assertEquals(StageTapZone.Back, tapZoneForX(299f, width))
        assertEquals(StageTapZone.Center, tapZoneForX(300f, width))
        assertEquals(StageTapZone.Center, tapZoneForX(450f, width))
        assertEquals(StageTapZone.Center, tapZoneForX(600f, width))
        assertEquals(StageTapZone.Forward, tapZoneForX(601f, width))
        assertEquals(StageTapZone.Forward, tapZoneForX(899f, width))
    }

    @Test
    fun `vertical modes split screen into thirds with middle ignored`() {
        val width = 900f
        assertEquals(StageVerticalMode.Brightness, verticalModeForX(299f, width))
        assertNull(verticalModeForX(300f, width))
        assertNull(verticalModeForX(600f, width))
        assertEquals(StageVerticalMode.Volume, verticalModeForX(601f, width))
    }

    @Test
    fun `second tap must fall inside double tap window`() {
        val timeout = 300L
        val minTime = 40L
        // 太早（双击最小间隔之前）
        assertFalse(isSecondTapValid(1000L, 1039L, timeout, minTime))
        // 边界：正好等于最小间隔，有效
        assertTrue(isSecondTapValid(1000L, 1040L, timeout, minTime))
        assertTrue(isSecondTapValid(1000L, 1150L, timeout, minTime))
        assertTrue(isSecondTapValid(1000L, 1299L, timeout, minTime))
        // 边界：正好等于超时，无效
        assertFalse(isSecondTapValid(1000L, 1300L, timeout, minTime))
        assertFalse(isSecondTapValid(1000L, 1600L, timeout, minTime))
    }

    @Test
    fun `long press requires still finger`() {
        assertFalse(movementCancelsLongPress(0f, 20f))
        assertFalse(movementCancelsLongPress(20f, 20f))
        assertTrue(movementCancelsLongPress(20.1f, 20f))
    }

    @Test
    fun `seek delta scales horizontally with full span`() {
        assertEquals(STAGE_SEEK_FULL_SPAN_MS, seekDeltaMs(1000f, 1000f), 0f)
        assertEquals(STAGE_SEEK_FULL_SPAN_MS / 2f, seekDeltaMs(500f, 1000f), 0f)
        assertEquals(-STAGE_SEEK_FULL_SPAN_MS, seekDeltaMs(-1000f, 1000f), 0f)
        assertEquals(0f, seekDeltaMs(500f, 0f), 0f)
    }

    @Test
    fun `seek full span follows duration`() {
        // 时长未知：退回 90s 默认值。
        assertEquals(90_000f, seekSpanForDurationMs(0L), 0f)
        assertEquals(90_000f, seekSpanForDurationMs(-1L), 0f)
        // ≤30s：满幅就是整片，一滑到底。
        assertEquals(20_000f, seekSpanForDurationMs(20_000L), 0f)
        assertEquals(30_000f, seekSpanForDurationMs(30_000L), 0f)
        // >30s 且 <180s：按片长 1:1。
        assertEquals(31_000f, seekSpanForDurationMs(31_000L), 0f)
        assertEquals(90_000f, seekSpanForDurationMs(90_000L), 0f)
        assertEquals(180_000f, seekSpanForDurationMs(180_000L), 0f)
        // 超过 3 分钟：封顶 180s。
        assertEquals(180_000f, seekSpanForDurationMs(600_000L), 0f)
        assertEquals(180_000f, seekSpanForDurationMs(7_200_000L), 0f)
    }

    @Test
    fun `seek delta uses duration derived span`() {
        assertEquals(20_000f, seekDeltaMs(1000f, 1000f, seekSpanForDurationMs(20_000L)), 0f)
        assertEquals(90_000f, seekDeltaMs(1000f, 1000f, seekSpanForDurationMs(90_000L)), 0f)
        assertEquals(180_000f, seekDeltaMs(1000f, 1000f, seekSpanForDurationMs(7_200_000L)), 0f)
        assertEquals(-90_000f, seekDeltaMs(-500f, 1000f, seekSpanForDurationMs(7_200_000L)), 0f)
    }

    @Test
    fun `double tap does not seek when duration unknown`() {
        assertNull(doubleTapSeekTargetMs(30_000L, 10_000L, forward = true, durationMs = 0L))
        assertNull(doubleTapSeekTargetMs(30_000L, 10_000L, forward = false, durationMs = -1L))
    }

    @Test
    fun `double tap target clamps to known duration`() {
        assertEquals(40_000L, doubleTapSeekTargetMs(30_000L, 10_000L, true, 60_000L))
        assertEquals(20_000L, doubleTapSeekTargetMs(30_000L, 10_000L, false, 60_000L))
        assertEquals(0L, doubleTapSeekTargetMs(5_000L, 10_000L, false, 60_000L))
        assertEquals(60_000L, doubleTapSeekTargetMs(55_000L, 10_000L, true, 60_000L))
    }

    @Test
    fun `vertical delta scales vertically with full span`() {
        assertEquals(100f, verticalDeltaPercent(800f, 800f), 0f)
        assertEquals(50f, verticalDeltaPercent(400f, 800f), 0f)
        assertEquals(0f, verticalDeltaPercent(400f, 0f), 0f)
    }

    @Test
    fun `clamp seek target uses callback time duration`() {
        assertEquals(60_000f, clampSeekTargetMs(90_000f, 60_000L), 0f)
        assertEquals(0f, clampSeekTargetMs(-10f, 60_000L), 0f)
        assertEquals(5_000f, clampSeekTargetMs(5_000f, 60_000L), 0f)
    }

    @Test
    fun `clamp seek target keeps raw value when duration unknown`() {
        // AVI 时长回填前 / 切集瞬间 duration=0：不能把目标夹成 0（否则滑到哪都回开头）。
        assertEquals(1_234f, clampSeekTargetMs(1_234f, 0L), 0f)
        assertEquals(1_234f, clampSeekTargetMs(1_234f, -1L), 0f)
        assertEquals(0f, clampSeekTargetMs(-5f, 0L), 0f)
    }
}
