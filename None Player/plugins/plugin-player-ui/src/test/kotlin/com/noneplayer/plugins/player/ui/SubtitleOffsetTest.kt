package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOffsetTest {
    @Test
    fun `offset preserves supported values`() {
        listOf(-1_000L, -100L, 0L, 100L, 1_000L).forEach {
            assertEquals(it, clampSubtitleOffsetMs(it))
        }
    }

    @Test
    fun `offset clamps both out of range directions`() {
        assertEquals(-1_000L, clampSubtitleOffsetMs(Long.MIN_VALUE))
        assertEquals(1_000L, clampSubtitleOffsetMs(Long.MAX_VALUE))
    }
}
