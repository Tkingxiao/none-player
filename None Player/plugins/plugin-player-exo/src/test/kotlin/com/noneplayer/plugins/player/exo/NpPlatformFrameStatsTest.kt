package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E245：平台/NextLib 路径的帧读数——回调累加、首帧只认一次、收尾用库的权威值覆盖。 */
class NpPlatformFrameStatsTest {

    @Test
    fun callbacksAccumulateFramesAndDrops() {
        val s = NpPlatformFrameStats()
        s.onEnabled(1_000)
        s.onDecoder("c2.qti.avc.decoder")
        s.onFrames(24)
        s.onFrames(24)
        s.onDropped(2)

        val snap = s.snapshot()
        assertEquals(48L, snap.frames)
        assertEquals(2L, snap.dropped)
        assertEquals("c2.qti.avc.decoder", snap.decoder)
    }

    @Test
    fun firstFrameAnchoredToSessionStartOnce() {
        val s = NpPlatformFrameStats()
        s.onEnabled(5_000)
        s.onFirstFrame(5_180)
        s.onFirstFrame(9_000)
        assertEquals(180L, s.snapshot().firstFrameMs)
    }

    @Test
    fun firstFrameIgnoredBeforeSessionStart() {
        val s = NpPlatformFrameStats()
        s.onEnabled(5_000)
        s.onFirstFrame(4_000)
        assertEquals(-1L, s.snapshot().firstFrameMs)
        assertTrue(s.summaryLine().endsWith(" first=-"))
    }

    @Test
    fun countersOverrideEstimatedFrames() {
        val s = NpPlatformFrameStats()
        s.onEnabled(1_000)
        s.onFrames(30)
        s.onCounters(inputs = 120, rendered = 118, dropped = 5, skipped = 3)

        val snap = s.snapshot()
        assertEquals(120L, snap.inputs)
        assertEquals(118L, snap.rendered)
        assertEquals(5L, snap.dropped)
        assertEquals(3L, snap.skipped)
        // 回调只估到 30 帧，库说上屏 118 —— 取大的，不能把真实读数改小
        assertEquals(118L, snap.frames)
    }

    @Test
    fun firstFrameRecordedAtFirstRenderedPoll() {
        val s = NpPlatformFrameStats()
        s.onEnabled(1_000)
        s.onCounters(0, 0, 0, 0, nowMs = 1_200)
        assertEquals(-1L, s.snapshot().firstFrameMs)
        s.onCounters(8, 1, 0, 0, nowMs = 1_450)
        assertEquals(450L, s.snapshot().firstFrameMs)
        // 之后的轮询不再改写首帧
        s.onCounters(80, 40, 0, 0, nowMs = 3_000)
        assertEquals(450L, s.snapshot().firstFrameMs)
    }

    @Test
    fun countersPollingUpdatesFieldsAndFrames() {
        val s = NpPlatformFrameStats()
        s.onEnabled(0)
        s.onCounters(inputs = 90, rendered = 88, dropped = 1, skipped = 2)

        val snap = s.snapshot()
        assertEquals(90L, snap.inputs)
        assertEquals(88L, snap.rendered)
        assertEquals(88L, snap.frames)
        assertEquals(1L, snap.dropped)
        assertEquals(2L, snap.skipped)
    }

    @Test
    fun summaryLineFormatAndReset() {
        val s = NpPlatformFrameStats()
        assertTrue(s.isEmpty)
        s.onEnabled(0)
        s.onDecoder("ffmpeg")
        s.onFrames(10)
        val line = s.summaryLine()
        assertTrue(line, line.startsWith("renderer=Platform:ffmpeg n=10 drop=0 in=0 rend=0 skip=0"))
        assertFalse(s.isEmpty)

        s.reset()
        assertTrue(s.isEmpty)
        // 名字跨轮保留：渲染器复用时不会再报一次
        assertEquals("ffmpeg", s.snapshot().decoder)
        assertEquals(-1L, s.snapshot().firstFrameMs)
    }
}
