package com.noneplayer.kernel.ui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormHandoffTest {
    @Test
    fun `tablet landscape is desktop-like and portrait is not`() {
        assertTrue(npDesktopLikeFromSize(1152, 720))
        assertFalse(npDesktopLikeFromSize(720, 1112))
    }

    @Test
    fun `phone landscape is not desktop-like`() {
        assertFalse(npDesktopLikeFromSize(952, 427))
    }

    @Test
    fun `aspect at the boundary is not desktop-like`() {
        assertFalse(npDesktopLikeFromSize(840, 600))
    }

    /**
     * 档位判据只有一条真相。旧写法让转屏回调与外壳各算一套（一边硬编码 dp、一边用
     * WindowSizeClass 的 Expanded/heightClass），两者不等价时一次物理旋转会被判成**两次**
     * 档位变化：Activity 先翻过去，外壳在第一帧又翻回来。第二次是反向交接、两侧矩形登记
     * 已被起飞清空，于是抓不到帧、连 `mark()` 都不打，整页淡移与右栏宽度动画被放回画面
     * 正在飞的那几百毫秒里——用户读到的就是"转横屏直接全屏、退出时瞬间跳回右栏"。
     *
     * 这张表钉住两条上报路径共用的判据本体：`onConfigurationChanged` 的
     * [isDesktopLikeWindow] 与 [buildNpWindowInfo] 的 `isDesktopLike`（后者已改为直接调它）。
     * 手机横屏（952×427）那行是真机踩过的坑：只看宽度 Expanded 会把它误判成类 PC。
     */
    @Test
    fun `form judgment table for both report paths`() {
        val desktopLike = listOf(
            1152 to 720,  // 平板横屏
            1280 to 800,  // 大屏横屏
            1600 to 900,  // 桌面宽窗
            840 to 480,   // 两条下限同时踩线：算类 PC
        )
        val notDesktopLike = listOf(
            720 to 1112,  // 平板竖屏
            952 to 427,   // 手机横屏：高不够
            840 to 600,   // 宽高比正好卡在 1.4
            1600 to 1200, // 宽但不成比例
            0 to 0,       // 还没量到
        )
        desktopLike.forEach { (width, height) ->
            assertTrue("${width}x$height 应为类 PC", npDesktopLikeFromSize(width, height))
        }
        notDesktopLike.forEach { (width, height) ->
            assertFalse("${width}x$height 不应为类 PC", npDesktopLikeFromSize(width, height))
        }
    }

    @Test
    fun `handoff only when leaving desktop with a route`() {
        assertEquals("player", route(leaving = true, pane = "player"))
        assertNull(route(leaving = true, pane = null))
        assertNull(route(leaving = true, pane = "  "))
        assertNull(route(leaving = false, pane = "player"))
    }

    @Test
    fun `playing pane wins over the open detail page`() {
        assertEquals("pane", route(leaving = true, pane = "pane", detail = "detail"))
    }

    /** 详情页开着、右栏没在播：落点是详情页那一条，而不是留在收成首页的媒体库。 */
    @Test
    fun `detail page is the fallback landing`() {
        assertEquals("detail", route(leaving = true, pane = null, detail = "detail"))
    }

    /** 播放页已经在栈上：形态变化由它自己适配，交接不许再推第二个播放页。 */
    @Test
    fun `no handoff while the player page is alive`() {
        assertNull(
            formHandoffRoute(
                leavingDesktop = true,
                playerPageAlive = true,
                paneRoute = "pane",
                detailRoute = "detail",
            ),
        )
    }

    private fun route(leaving: Boolean, pane: String?, detail: String? = null) =
        formHandoffRoute(
            leavingDesktop = leaving,
            playerPageAlive = false,
            paneRoute = pane,
            detailRoute = detail,
        )
}
