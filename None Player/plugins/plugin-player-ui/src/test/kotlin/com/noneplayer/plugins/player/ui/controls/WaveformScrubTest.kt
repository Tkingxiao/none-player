package com.noneplayer.plugins.player.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformScrubTest {
    @Test
    fun `drag left advances time and translates the waveform by the same distance`() {
        val start = WaveformScrub(0.5)
        val end = start.dragBy(-80f, 800f)
        assertEquals(0.6, end.fraction, 0.000001)
        assertEquals(60_000L, end.positionMs(100_000L))
        assertEquals(
            -80f,
            waveformOriginPx(400f, 800f, end.fraction) -
                waveformOriginPx(400f, 800f, start.fraction),
            0.0001f,
        )
    }

    @Test
    fun `drag right rewinds regardless of where the finger starts`() {
        assertEquals(0.25, WaveformScrub(0.5).dragBy(200f, 800f).fraction, 0.0)
    }

    @Test
    fun `center reference aligns with selected time at start middle and end`() {
        for (fraction in listOf(0.0, 0.25, 0.5, 1.0)) {
            val origin = waveformOriginPx(600f, 1200f, fraction)
            assertEquals(300f, origin + (fraction * 1200f).toFloat(), 0.0f)
        }
    }

    @Test
    fun `dragging past either boundary clamps and immediately reverses`() {
        val beginning = WaveformScrub(0.1).dragBy(1000f, 800f)
        assertEquals(0L, beginning.positionMs(100_000L))
        assertEquals(0.1, beginning.dragBy(-80f, 800f).fraction, 0.000001)
        val end = WaveformScrub(0.9).dragBy(-1000f, 800f)
        assertEquals(100_000L, end.positionMs(100_000L))
        assertEquals(0.9, end.dragBy(80f, 800f).fraction, 0.000001)
    }

    @Test
    fun `unusable sizes and unknown durations never produce an invalid seek`() {
        val scrub = WaveformScrub(0.5)
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(scrub, scrub.dragBy(-100f, width))
        }
        assertEquals(scrub, scrub.dragBy(Float.NaN, 800f))
        assertEquals(0L, scrub.positionMs(0L))
        assertEquals(0L, scrub.positionMs(-1L))
    }

    @Test
    fun `preview is immutable and repeated drag steps agree with one drag`() {
        val start = WaveformScrub(0.2)
        val steps = start.dragBy(-40f, 800f).dragBy(-40f, 800f)
        assertEquals(start.dragBy(-80f, 800f).fraction, steps.fraction, 0.000001)
        assertEquals(0.2, start.fraction, 0.0)
    }
}
