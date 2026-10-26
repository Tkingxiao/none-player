package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E245：软解渲染链帧计数口径。
 *
 * 钉三件事：① 输入/解出/上屏/丢弃各记各的，互不串行；② 队列深度取"解出后积压"，
 * 平均值按样本算、最大值取峰值；③ disable 时 reset 之后摘要回到零，不会把上一段
 * 播放的读数带进下一段 A/B。
 */
class NpFrameStatsTest {

    @Test
    fun countsEachStageIndependently() {
        val stats = NpFrameStats("LegacyVideoRenderer")
        repeat(3) { stats.onInput() }
        stats.onDecoded(1)
        stats.onDecoded(3)
        stats.onRendered(0)
        stats.onRendered(2)

        val s = stats.snapshot()
        assertEquals(3L, s.inputFrames)
        assertEquals(2L, s.decodedFrames)
        assertEquals(2L, s.renderedFrames)
        assertEquals(2L, s.droppedFrames)
        assertEquals(0L, s.nativeErrors)
        assertFalse(s.isEmpty)
    }

    @Test
    fun queueDepthUsesPostDecodeBacklog() {
        val stats = NpFrameStats("npffmpeg")
        listOf(1, 2, 3, 8).forEach { stats.onDecoded(it) }

        val s = stats.snapshot()
        assertEquals(8, s.queueDepthMax)
        assertEquals(8, s.queueDepthLast)
        assertEquals(3.5, s.queueDepthAvg, 0.001)
    }

    @Test
    fun summaryLineIsStableForLogDiff() {
        val stats = NpFrameStats("LegacyVideoRenderer")
        stats.onInput()
        stats.onInput()
        stats.onDecoded(2)
        stats.onRendered(1)

        val line = stats.summaryLine()
        assertTrue(line, line.startsWith("renderer=LegacyVideoRenderer in=2 dec=1 rend=1 drop=1 qavg=2.00 qmax=2 err=0"))
    }

    @Test
    fun nativeErrorsAreCountedAndIsEmptyOnlyWithoutFrames() {
        val stats = NpFrameStats("LegacyVideoRenderer")
        assertTrue(stats.snapshot().isEmpty)
        stats.onNativeError()
        assertEquals(1L, stats.snapshot().nativeErrors)
        assertFalse(stats.snapshot().isEmpty)
    }

    @Test
    fun resetClearsCountersBetweenSessions() {
        val stats = NpFrameStats("LegacyVideoRenderer")
        stats.onInput()
        stats.onDecoded(4)
        stats.onRendered(3)
        stats.reset()

        val s = stats.snapshot()
        assertEquals(0L, s.inputFrames)
        assertEquals(0L, s.decodedFrames)
        assertEquals(0L, s.renderedFrames)
        assertEquals(0L, s.droppedFrames)
        assertEquals(0, s.queueDepthMax)
        assertEquals(0.0, s.queueDepthAvg, 0.0)
        assertTrue(s.isEmpty)
    }
}
