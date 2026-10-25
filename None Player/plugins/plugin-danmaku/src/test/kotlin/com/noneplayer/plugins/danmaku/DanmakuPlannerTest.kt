package com.noneplayer.plugins.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPlannerTest {

    private val config = DanmakuPlanner.Config(
        areaFraction = 0.5f,
        speed = 1f,
        maxOnScreen = 0,
    )

    /** 画布 1000×1000、行高 70（字号 50）的量测桩：文字宽 = 字号 × 文本长度 */
    private fun measureStub(): (Danmaku, Float) -> Float = { d, px -> px * d.text.length }

    private fun danmaku(timeMs: Long, mode: DanmakuMode = DanmakuMode.Scroll, text: String = "abcd") =
        Danmaku(timeMs, mode, 1f, 0xFFFFFFFF.toInt(), text)

    @Test
    fun `scroll danmaku spawns at right edge and exits left`() {
        val placed = DanmakuPlanner.plan(
            listOf(danmaku(0L)),
            displayPosMs = 0L,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertEquals(1, placed.size)
        // 出生：左缘 = W - textW（右缘贴屏幕右缘）
        assertEquals(1000f - 200f, placed[0].x, 0.5f)
        // 半程：文字完全越过中线方向，出生点左移 (W + textW) / 2
        val half = DanmakuPlanner.plan(
            listOf(danmaku(0L)),
            displayPosMs = DanmakuPlanner.BASE_SCROLL_SPAN_MS / 2,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertEquals(1000f - 200f - (1000f + 200f) / 2, half[0].x, 1f)
        // 超时出屏
        val gone = DanmakuPlanner.plan(
            listOf(danmaku(0L)),
            displayPosMs = DanmakuPlanner.BASE_SCROLL_SPAN_MS + 1,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertTrue(gone.isEmpty())
    }

    @Test
    fun `top and bottom stack from their edges`() {
        val placed = DanmakuPlanner.plan(
            listOf(
                danmaku(0L, DanmakuMode.Top, "t1"),
                danmaku(0L, DanmakuMode.Top, "t2"),
                danmaku(0L, DanmakuMode.Bottom, "b1"),
            ),
            displayPosMs = 0L,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertEquals(3, placed.size)
        val tops = placed.filter { it.danmaku.mode == DanmakuMode.Top }
        val bottoms = placed.filter { it.danmaku.mode == DanmakuMode.Bottom }
        // 顶部自上而下：轨道 0、1
        assertEquals(0, tops[0].lane)
        assertEquals(1, tops[1].lane)
        assertTrue(tops[0].y < tops[1].y)
        // 底部自下而上：轨道 0 贴弹幕层下缘（层高 = 500）
        assertEquals(0, bottoms[0].lane)
        assertEquals(500f - 35f, bottoms[0].y, 0.5f)
    }

    @Test
    fun `density cap keeps the latest entries`() {
        val many = (0 until 10).map { danmaku(it * 100L) }
        val capped = DanmakuPlanner.plan(
            many,
            displayPosMs = 4_000L,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config.copy(maxOnScreen = 3),
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertEquals(3, capped.size)
        // 保留的是时间最近的（窗口尾）
        assertEquals(700L, capped[0].danmaku.timeMs)
    }

    @Test
    fun `hold span applies to top-bottom only`() {
        val top = DanmakuPlanner.plan(
            listOf(danmaku(0L, DanmakuMode.Top)),
            displayPosMs = DanmakuPlanner.HOLD_SPAN_MS + 1,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        // 4s 驻留已过 → 消失
        assertTrue(top.isEmpty())
        // 同刻的滚动弹幕（8s 寿命）仍在屏上
        val scroll = DanmakuPlanner.plan(
            listOf(danmaku(0L, DanmakuMode.Scroll)),
            displayPosMs = DanmakuPlanner.HOLD_SPAN_MS + 1,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            config = config,
            laneHeightPx = 70f,
            measure = measureStub(),
        )
        assertEquals(1, scroll.size)
    }

    @Test
    fun `binary window equals linear filter on large list`() {
        // 0..185s、每 37ms 一条：稠密大列表，二分窗口须与逐条过滤（旧语义）逐条一致
        val list = (0 until 5_000).map { danmaku(it * 37L) }
        val display = 100_000L
        val placed = DanmakuPlanner.plan(list, display, 1000f, 1000f, config, 70f, measureStub())
        val expected = list.filter {
            it.timeMs > display - DanmakuPlanner.BASE_SCROLL_SPAN_MS && it.timeMs <= display
        }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected.size, placed.size)
        assertEquals(expected.map { it.timeMs }, placed.map { it.danmaku.timeMs })
    }

    @Test
    fun `window boundary mirrors strict span filter`() {
        // 恰落在窗口起点的滚动弹幕：驻留判定是严格 `>`，应当已消失
        val atWindowStart = listOf(danmaku(10_000L - DanmakuPlanner.BASE_SCROLL_SPAN_MS))
        assertTrue(DanmakuPlanner.plan(atWindowStart, 10_000L, 1000f, 1000f, config, 70f, measureStub()).isEmpty())
        // 恰在 displayPosMs 时刻出生（如 seek 到该时刻）：必须可见
        val atDisplay = listOf(danmaku(10_000L))
        assertEquals(1, DanmakuPlanner.plan(atDisplay, 10_000L, 1000f, 1000f, config, 70f, measureStub()).size)
        // 顶/底的 4s 边界同样严格：出生 6000ms、显示 10000ms 恰满 4s → 已消失；6001ms 仍在
        assertTrue(
            DanmakuPlanner.plan(listOf(danmaku(6_000L, DanmakuMode.Top)), 10_000L, 1000f, 1000f, config, 70f, measureStub())
                .isEmpty(),
        )
        assertEquals(
            1,
            DanmakuPlanner.plan(listOf(danmaku(6_001L, DanmakuMode.Top)), 10_000L, 1000f, 1000f, config, 70f, measureStub())
                .size,
        )
    }

    @Test
    fun `degenerate inputs stay empty`() {
        // 空列表 / 画布尺寸未就绪（首帧前）
        assertTrue(DanmakuPlanner.plan(emptyList(), 0L, 1000f, 1000f, config, 70f, measureStub()).isEmpty())
        assertTrue(DanmakuPlanner.plan(listOf(danmaku(0L)), 0L, 0f, 0f, config, 70f, measureStub()).isEmpty())
        // 窗口内无内容：显示位置远早于第一条
        assertTrue(DanmakuPlanner.plan(listOf(danmaku(50_000L)), 0L, 1000f, 1000f, config, 70f, measureStub()).isEmpty())
    }

    // ---- E225：文件内稳定车道序号 ----

    /** 两条滚动弹幕：A(t=0) 与 B(t=1000)；A 先出窗后 B 的窗口内序号会前移 */
    private val two = listOf(danmaku(0L, text = "aaaa"), danmaku(1_000L, text = "bbbb"))

    @Test
    fun `stable ordinals count per mode`() {
        val ordinals = stableLaneOrdinals(
            listOf(
                danmaku(0L, DanmakuMode.Scroll),
                danmaku(1L, DanmakuMode.Top),
                danmaku(2L, DanmakuMode.Scroll),
                danmaku(3L, DanmakuMode.Bottom),
                danmaku(4L, DanmakuMode.Scroll),
            ),
        )
        // 各模式独立计数：滚动 0,1,2 / 顶 0 / 底 0
        assertEquals(listOf(0, 0, 1, 0, 2), ordinals)
    }

    @Test
    fun `lane stays stable as the window slides`() {
        // 车道数 = 1000×0.5 / 70 = 7。窗口滑动使 A（t=0）出窗后，旧实现里 B 的
        // 窗口内序号 1→0，车道 1→0 瞬移；稳定序号下车道不变。
        val ordinals = stableLaneOrdinals(two)
        val before = DanmakuPlanner.plan(
            two, displayPosMs = 6_000L, canvasWidth = 1000f, canvasHeight = 1000f,
            config = config, laneHeightPx = 70f, measure = measureStub(),
            laneOrdinals = ordinals,
        )
        val after = DanmakuPlanner.plan(
            two, displayPosMs = 8_500L, canvasWidth = 1000f, canvasHeight = 1000f,
            config = config, laneHeightPx = 70f, measure = measureStub(),
            laneOrdinals = ordinals,
        )
        // 6s 时两条都在窗内；8.5s 时 A（存活 8s）已出窗、只剩 B
        assertEquals(2, before.size)
        assertEquals(1, after.size)
        val bBefore = before.single { it.danmaku.timeMs == 1_000L }
        val bAfter = after.single()
        assertEquals("B 的车道在窗口滑动前后必须一致", bBefore.lane, bAfter.lane)
        assertEquals("稳定序号下车道 = 序号 mod 轨道数", 1 % 7, bAfter.lane)
    }

    @Test
    fun `fallback without ordinals keeps window-counter behavior`() {
        // 内嵌 spawned（无稳定文件序）走旧路径：行为与 E225 前一致
        val placed = DanmakuPlanner.plan(
            two, displayPosMs = 6_000L, canvasWidth = 1000f, canvasHeight = 1000f,
            config = config, laneHeightPx = 70f, measure = measureStub(),
        )
        assertEquals(2, placed.size)
        assertEquals(0, placed[0].lane)
        assertEquals(1, placed[1].lane)
    }

}

class DanmakuBlocklistTest {

    @Test
    fun `substring match is case insensitive`() {
        val words = listOf("广告", "AD")
        assertTrue(DanmakuBlocklist.isBlocked("承接广告联系V", words))
        assertTrue(DanmakuBlocklist.isBlocked("纯ad分享", words))
        assertTrue(!DanmakuBlocklist.isBlocked("正常弹幕", words))
    }

    @Test
    fun `empty wordlist never blocks`() {
        assertTrue(!DanmakuBlocklist.isBlocked("anything", emptyList()))
    }
}

