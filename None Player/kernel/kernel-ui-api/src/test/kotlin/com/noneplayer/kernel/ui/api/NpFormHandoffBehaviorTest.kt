package com.noneplayer.kernel.ui.api

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 转屏交接的**行为级**回归（E132 / E133）。
 *
 * 与纯函数层的分工：`NpFormDecisionTest` 与 `NpMorphTest` 钉判据与位姿数学，这里钉
 * "生产代码在真实 recomposition 与 `SideEffect` 时序下做了什么"。档位翻转在外壳那一侧
 * 就是一句 `SideEffect { NpFormHandoff.noteDesktop(...) }`（见 `KernelTabScaffold`），它的
 * 执行时机（组合之后、第一帧之前）正是旧实现翻车的位置；静音窗同理——只有让组合真的去读
 * `isSilent`，才能证明它跟着 **morph 状态**走，而不是跟着一个没人观察的秒表走。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NpFormHandoffBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private var clockNow = 1_000L
    private var navigated = 0
    private var retires = 0
    private var lastRoute: String? = null

    /** 重组触发器：秒表与 morph 进度不是"到点自己响"的，生产里靠下一次组合去采样。 */
    private val tick = mutableIntStateOf(0)

    @Before
    fun setUp() {
        NpFormHandoff.resetForTests()
        NpMorph.resetForTests()
        NpDevicePose.resetForTests()
        clockNow = 1_000L
        tick.intValue = 0
        navigated = 0
        retires = 0
        lastRoute = null
        NpHandoffTime.clock = NpHandoffClock { clockNow }
    }

    @After
    fun tearDown() {
        NpHandoffTime.clock = NpSystemHandoffClock
        NpFormHandoff.resetForTests()
        NpMorph.resetForTests()
        NpDevicePose.resetForTests()
    }

    private fun advance(ms: Long) {
        clockNow += ms
        tick.intValue++
    }

    private fun silenceHarness() {
        compose.setContent {
            tick.intValue
            Text(if (NpFormHandoff.isSilent) "silent" else "loud")
        }
    }

    /** 登记一个"独占播放页"：路线来自右栏，退页动作来自本页。 */
    private fun installPlayerPage(token: Any) {
        NpFormHandoff.notePlayerPage(true)
        NpFormHandoff.bindStageRetire(token) { retires++ }
    }

    /**
     * 一次物理旋转只交接一次，并且**导航真的发生了**。
     *
     * 复刻真机现场（E133 的两个症状）：竖屏独占播放页 → 转横屏 → 外壳的 `SideEffect`
     * 带着同一份窗口配置（1dp 舍入差跨过阈值）再报一次。旧实现让第二翻通过，于是反向
     * 空交接、退页走两遍；而导航出口那一槽如果被"后离开的挂载者"置空，横转竖就打不开
     * 播放页——用户读到的是"直接掉回首页"。
     */
    @Test
    fun `one rotation hands over once and navigation is available`() {
        val token = Any()
        installPlayerPage(token)
        NpMorph.recordWindow(IntSizeOf(1800, 2880))
        NpMorph.record(NpMorphKey.Video, Rect(0f, 220f, 1800f, 1570f))

        var config by mutableStateOf(NpFormConfig(720, 1152, 0))
        compose.setContent {
            SideEffect { NpFormHandoff.noteDesktop(config) }
            tick.intValue
            Text(if (NpFormHandoff.isSilent) "shell silent" else "shell loud")
        }
        compose.onNodeWithText("shell loud").assertExists()
        assertEquals("竖屏首帧不是交接", 0, retires)

        // 旋转：先角度、后档位（与 MainActivity.onConfigurationChanged 的顺序一致）
        NpDevicePose.noteDisplayRotation(0)
        NpDevicePose.noteDisplayRotation(1)
        NpFormHandoff.handoffForWindowSize(840, 480)
        // 外壳那侧带同一份窗口配置，1dp 舍入差跨过阈值、判出"还不是类 PC"
        config = NpFormConfig(839, 479, 1)
        compose.onNodeWithText("shell silent").assertExists()

        assertEquals("退页只许一次", 1, retires)
        assertTrue("morph 必须起飞", NpMorph.isFlying)
        assertEquals(1, NpMorph.generation)
        assertNotNull("旧矩形要被冻住", NpMorph.sourceRect(NpMorphKey.Video))
    }

    /**
     * 横转竖：路线在、出口在，就必须把播放页打开（而不是停在首页）。
     *
     * 这条直接钉用户实报的回归——`navigator=false` 时交接只剩路线没有出口，
     * 于是"横屏右栏在播 → 转竖屏"什么也不做，屏幕上躺着的是媒体库首页。
     */
    @Test
    fun `leaving desktop navigates to the player page exactly once`() {
        val token = Any()
        NpFormHandoff.bindNavigator(token) { route ->
            navigated++
            lastRoute = route
        }
        NpFormHandoff.notePlayerPage(false)
        NpFormHandoff.offerPaneRoute("player?uri=media%3A%2F%2Fone")
        NpMorph.recordWindow(IntSizeOf(2880, 1800))
        NpMorph.record(NpMorphKey.Video, Rect(1057f, 259f, 2830f, 1589f))

        // 先进类 PC 档（横屏），再离开（竖屏）——离开那一次才该导航
        NpFormHandoff.handoffForWindowSize(1152, 680)
        assertEquals("进入类 PC 不该导航", 0, navigated)
        NpFormHandoff.handoffForWindowSize(720, 1112)
        assertEquals(1, navigated)
        assertEquals("player?uri=media%3A%2F%2Fone", lastRoute)
        assertTrue(NpMorph.isFlying)

        // 同一份配置的第二次上报（外壳 SideEffect）不得再导航一次
        NpFormHandoff.noteDesktop(NpFormConfig(721, 1111, 0))
        assertEquals("同配置的第二翻必须被拒", 1, navigated)
    }

    /** 出口没绑上时不得半途导航，也不得把 morph 留在"静音"里骗人。 */
    @Test
    fun `missing navigator leaves the handoff silent-free`() {
        NpFormHandoff.notePlayerPage(false)
        NpFormHandoff.offerPaneRoute("player?uri=media%3A%2F%2Fone")
        NpMorph.recordWindow(IntSizeOf(2880, 1800))
        NpMorph.record(NpMorphKey.Video, Rect(1057f, 259f, 2830f, 1589f))
        NpFormHandoff.handoffForWindowSize(1152, 680)
        NpFormHandoff.handoffForWindowSize(720, 1112)
        assertEquals(0, navigated)
        assertNull(lastRoute)
    }

    /**
     * 静音窗跟着 morph 走，不跟秒表走。
     *
     * morph 落位后秒表还剩 500ms：这时必须放开，否则用户手工翻页的整页转场会被一起吞掉。
     */
    @Test
    fun `silence follows the morph and not the stopwatch`() {
        silenceHarness()
        compose.onNodeWithText("loud").assertExists()

        NpMorph.recordWindow(IntSizeOf(1800, 2880))
        NpMorph.record(NpMorphKey.Video, Rect(0f, 0f, 720f, 405f))
        NpMorph.begin()
        NpFormHandoff.mark(boundToMorph = true)
        compose.onNodeWithText("silent").assertExists()

        NpMorph.advance(1f)
        NpMorph.end()
        advance(100L)  // 仍远在 WINDOW_MS(600) 之内
        compose.onNodeWithText("loud").assertExists()
    }

    /** morph 被异常留住（驱动者没跑完）时，天花板必须放手。 */
    @Test
    fun `ceiling releases a morph that never finished`() {
        silenceHarness()
        NpMorph.recordWindow(IntSizeOf(1800, 2880))
        NpMorph.record(NpMorphKey.Video, Rect(0f, 0f, 720f, 405f))
        NpMorph.begin()
        NpFormHandoff.mark(boundToMorph = true)
        compose.onNodeWithText("silent").assertExists()

        NpMorph.advance(0.5f)
        advance(1_500L)
        compose.onNodeWithText("loud").assertExists()
    }

    /**
     * 没有版式可飞的形态变化仍然要压掉交叉淡入——这是 `mark()` 的本职
     * （`LibraryScreen` 的 stage 转场靠它），不能因为"以 morph 为准"一起废掉。
     */
    @Test
    fun `mark without a morph still silences until the stopwatch expires`() {
        silenceHarness()
        compose.onNodeWithText("loud").assertExists()

        NpFormHandoff.mark()
        advance(0L)
        compose.onNodeWithText("silent").assertExists()

        advance(700L)
        compose.onNodeWithText("loud").assertExists()
    }

    /** mark 之后才起飞的调用点（PlayerScreen 的「交还浏览态」）：补认这轮 morph，随它结束而放开。 */
    @Test
    fun `mark before the morph starts still binds to it`() {
        silenceHarness()
        NpFormHandoff.mark()
        NpMorph.recordWindow(IntSizeOf(1800, 2880))
        NpMorph.record(NpMorphKey.Video, Rect(0f, 0f, 720f, 405f))
        NpMorph.begin()
        compose.onNodeWithText("silent").assertExists()

        NpMorph.advance(1f)
        NpMorph.end()
        advance(200L)
        compose.onNodeWithText("loud").assertExists()
    }

    private fun IntSizeOf(w: Int, h: Int) = androidx.compose.ui.unit.IntSize(w, h)
}
