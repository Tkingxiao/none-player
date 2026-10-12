package com.noneplayer.plugins.player.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsMenuGeometryTest {
    private fun geometry(window: IntSize, anchor: IntRect, above: Boolean, limit: Int? = null) =
        fsMenuGeometry(window, anchor, limit, above, 284, 48, 10, 12)

    @Test
    fun `narrow menu never exceeds window width`() {
        assertEquals(96, geometry(IntSize(120, 240), IntRect(50, 180, 100, 220), true).width)
    }

    @Test
    fun `anchor near top flips an upward menu downward`() {
        val result = geometry(IntSize(320, 200), IntRect(200, 15, 250, 55), true)
        assertFalse(result.preferAbove)
        assertEquals(123, result.maxHeight)
    }

    @Test
    fun `anchor near bottom flips a downward menu upward`() {
        val result = geometry(IntSize(320, 200), IntRect(200, 140, 250, 180), false)
        assertTrue(result.preferAbove)
        assertEquals(118, result.maxHeight)
    }

    @Test
    fun `upward menu respects seek bar boundary`() {
        assertEquals(
            88,
            geometry(IntSize(320, 240), IntRect(200, 180, 250, 220), true, limit = 110).maxHeight,
        )
    }

    @Test
    fun `height is not enlarged past remaining space`() {
        assertEquals(18, geometry(IntSize(320, 100), IntRect(200, 40, 250, 65), true).maxHeight)
    }

    @Test
    fun `tiny windows still produce valid dimensions`() {
        val result = geometry(IntSize(1, 1), IntRect(0, 0, 1, 1), true)
        assertEquals(1, result.width)
        assertEquals(1, result.maxHeight)
    }

    @Test
    fun `position clamps even when content fills the window`() {
        assertEquals(
            IntOffset.Zero,
            fsMenuPosition(
                IntSize(120, 100), IntSize(120, 100), IntRect(80, 80, 120, 100),
                null, true, true, 10, 12,
            ),
        )
    }

    @Test
    fun `position fits resized window and stale offscreen anchor`() {
        val result = fsMenuPosition(
            IntSize(200, 120), IntSize(176, 60), IntRect(250, 200, 290, 240),
            null, true, false, 10, 12,
        )
        assertEquals(IntOffset(12, 48), result)
    }
}
