package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPolicyTest {
    @Test
    fun `all speed menus use the requested six presets`() {
        assertEquals(listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f), SPEED_PRESETS)
    }

    @Test
    fun `portrait speed chips also reach 3x`() {
        assertEquals(SPEED_PRESETS, SPEED_PORTRAIT_PRESETS)
        assertEquals(3f, SPEED_PORTRAIT_PRESETS.last(), 0f)
    }

    @Test
    fun `ordinary portrait and landscape ratios are preserved`() {
        assertEquals(16f / 9f, pipAspectRatio(16f / 9f), 0f)
        assertEquals(9f / 16f, pipAspectRatio(9f / 16f), 0f)
    }

    @Test
    fun `ultrawide and tall videos stay within platform limits after rounding`() {
        assertEquals(2.39f, pipAspectRatio(10f), 0f)
        val rounded = kotlin.math.round(pipAspectRatio(0.1f) * 1000) / 1000
        assertTrue(rounded >= 1f / 2.39f)
    }

    @Test
    fun `unknown and invalid dimensions use landscape fallback`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(16f / 9f, pipAspectRatio(it), 0f)
        }
    }

    @Test
    fun `pip source size letterboxes landscape into a square view`() {
        val (w, h) = pipContentSize(viewWidth = 1000, viewHeight = 1000, aspectRatio = 16f / 9f, fit = true)
        assertEquals(1000, w)
        assertEquals(563, h)
    }

    @Test
    fun `pip source size pillarboxes portrait into a square view`() {
        val (w, h) = pipContentSize(viewWidth = 1000, viewHeight = 1000, aspectRatio = 9f / 16f, fit = true)
        assertEquals(563, w)
        assertEquals(1000, h)
    }

    @Test
    fun `pip source size fills the view for crop or invalid input`() {
        assertEquals(1000 to 1000, pipContentSize(1000, 1000, 16f / 9f, fit = false))
        assertEquals(1000 to 1000, pipContentSize(1000, 1000, 0f, fit = true))
        assertEquals(0 to 0, pipContentSize(0, 1000, 16f / 9f, fit = true))
    }
}
