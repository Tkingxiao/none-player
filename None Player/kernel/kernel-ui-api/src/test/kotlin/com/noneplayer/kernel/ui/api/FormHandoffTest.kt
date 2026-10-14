package com.noneplayer.kernel.ui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormHandoffTest {
    @Test
    fun `tablet landscape is desktop-like and portrait is not`() {
        assertTrue(isDesktopLikeWindow(1152, 720))
        assertFalse(isDesktopLikeWindow(720, 1112))
    }

    @Test
    fun `phone landscape is not desktop-like`() {
        assertFalse(isDesktopLikeWindow(952, 427))
    }

    @Test
    fun `aspect at the boundary is not desktop-like`() {
        assertFalse(isDesktopLikeWindow(840, 600))
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
