package com.noneplayer.kernel.ui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位翻转与静音窗的**纯函数**回归（E132）。
 *
 * 这一层不碰 Android/Compose，钉的是两条真机路径的判据本身；真实 recomposition 与
 * `SideEffect` 顺序的行为级取证在 `NpFormHandoffBehaviorTest`。
 */
class NpFormDecisionTest {

    // ---- 类 PC 档判据：两条上报路径共用同一个函数，不再各写一套阈值 ----

    @Test
    fun `desktop bit is derived from the config, not from the caller`() {
        // 同一份尺寸，Activity 路径与外壳路径必须给出同一个答案（旋转角不参与判档）。
        val fromCallback = npIsDesktopLike(NpFormConfig(1152, 720, displayRotation = 1))
        val fromShell = npIsDesktopLike(NpFormConfig(1152, 720, displayRotation = 3))
        assertEquals(fromCallback, fromShell)
        assertTrue(fromCallback)
    }

    // ---- 翻转归约 ----

    @Test
    fun `same desktop bit never counts as a flip`() {
        val (state, changed) = npReduceFormFlip(NpFormFlipState(desktop = true), NpFormConfig(1152, 720, 1))
        assertFalse(changed)
        assertEquals(0, state.generation)
    }

    /**
     * E132 的主案发现场：两条上报路径给出的配置**只差 1dp**，却跨过 840 阈值判出相反的档位
     * （旧写法一边 WindowSizeClass、一边硬编码 dp，必然出现这种自相矛盾）。
     * 归约靠"上一次被接受的翻转就在同一份配置上"把第二次拒掉——同一次旋转只交接一次。
     */
    @Test
    fun `second report of the same window config cannot flip back`() {
        val portrait = NpFormConfig(720, 1112, 0)
        val landscape = NpFormConfig(1152, 720, 1)
        val (afterFirst, firstChanged) = npReduceFormFlip(NpFormFlipState(desktop = false), landscape)
        assertTrue("进入类 PC 应算一次翻转", firstChanged)
        assertTrue(afterFirst.desktop)

        // 1151 与 1152 落在容差内 = 同一份窗口配置；判出的布尔却是 false（没过 840 的兄弟阈值）。
        val diverged = NpFormConfig(1151, 719, 1)
        val (afterSecond, secondChanged) = npReduceFormFlip(afterFirst, diverged)
        assertFalse("同一份配置的第二翻必须被拒", secondChanged)
        assertEquals("被拒的上报不得改写状态", afterFirst, afterSecond)
    }

    /** 真的又转了一次（新配置）：归约必须放行，不能被容差守卫关死。 */
    @Test
    fun `a real second rotation still flips`() {
        val landscape = NpFormConfig(1152, 720, 1)
        val portrait = NpFormConfig(720, 1152, 0)
        val (afterFirst, first) = npReduceFormFlip(NpFormFlipState(desktop = false), landscape)
        assertTrue(first)
        val (afterSecond, second) = npReduceFormFlip(afterFirst, portrait)
        assertTrue("转回竖屏是另一次交接", second)
        assertFalse(afterSecond.desktop)
        assertEquals(2, afterSecond.generation)
    }

    @Test
    fun `freeform resize without rotation still flips`() {
        val wide = NpFormConfig(1200, 800, 1)
        val narrow = NpFormConfig(700, 800, 1)
        val (afterFirst, first) = npReduceFormFlip(NpFormFlipState(desktop = false), wide)
        assertTrue(first)
        val (afterSecond, second) = npReduceFormFlip(afterFirst, narrow)
        assertTrue("自由窗口改尺寸同样换档：旋转角相同但配置不同，守卫不该挡", second)
        assertFalse(afterSecond.desktop)
    }

    @Test
    fun `config comparison tolerates one dp noise but not another rotation`() {
        assertTrue(npSameFormConfig(NpFormConfig(1152, 720, 1), NpFormConfig(1151, 721, 1)))
        assertFalse(npSameFormConfig(NpFormConfig(1152, 720, 1), NpFormConfig(1152, 720, 3)))
        assertFalse(npSameFormConfig(NpFormConfig(1152, 720, 1), NpFormConfig(720, 1152, 1)))
    }

    // ---- 静音窗：以飞行快照为准，秒表只兜没有画面可飞的形态变化 ----

    private fun gate(
        flightActive: Boolean,
        flightAgeMs: Long = 0L,
        silentRemainingMs: Long = 0L,
        bound: Boolean = false,
    ) = NpHandoffGate(
        flightActive = flightActive,
        flightAgeMs = if (flightActive) flightAgeMs else Long.MAX_VALUE,
        silentRemainingMs = silentRemainingMs,
        silentBoundToFlight = bound,
        ceilingMs = 2_500L,
    )

    @Test
    fun `flying keeps the page transition silent`() {
        assertTrue(npHandoffSilent(gate(flightActive = true, flightAgeMs = 900L, bound = true)))
    }

    /** 异常滞留的飞行不能把整站转场永久静音。 */
    @Test
    fun `ceiling releases a stuck flight`() {
        assertFalse(npHandoffSilent(gate(flightActive = true, flightAgeMs = 2_500L, bound = true)))
    }

    /**
     * 用户点名的误触发路径：**秒表还没过期，但这一轮的飞行已经结束**。
     * 绑定过飞行的 mark 到此必须放开，否则把手工翻页的转场也一起吞掉。
     */
    @Test
    fun `expired flight voids its own stopwatch`() {
        assertFalse(npHandoffSilent(gate(flightActive = false, silentRemainingMs = 400L, bound = true)))
    }

    /** 抓不到画面的形态变化没有飞行可绑，这时仍要压掉交叉淡入（旧 mark 的本职）。 */
    @Test
    fun `unbound stopwatch still silences a flightless form change`() {
        assertTrue(npHandoffSilent(gate(flightActive = false, silentRemainingMs = 400L, bound = false)))
        assertFalse(npHandoffSilent(gate(flightActive = false, silentRemainingMs = 0L, bound = false)))
    }

    private fun Boolean.not() = !this
}
