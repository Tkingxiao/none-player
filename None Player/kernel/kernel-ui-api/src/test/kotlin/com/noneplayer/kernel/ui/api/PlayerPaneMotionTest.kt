package com.noneplayer.kernel.ui.api

import androidx.compose.ui.geometry.Rect
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
}
