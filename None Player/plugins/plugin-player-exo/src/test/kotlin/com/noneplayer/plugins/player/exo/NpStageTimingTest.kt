package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E245：分段耗时口径——每段只累计自己的窗口，首帧延迟只认第一次上屏。 */
class NpStageTimingTest {

    private fun ms(v: Long) = v * 1_000_000

    @Test
    fun stagesAccumulateIndependentlyAndCountFramesOnSend() {
        val t = NpStageTiming("LegacyVideoRenderer")
        t.onFirstInput(1_000_000)
        t.onSend(ms(2))
        t.onRecv(ms(3))
        t.onCopy(ms(1))
        t.onRender(11_000_000, ms(4))
        t.onSend(ms(2))
        t.onRecv(ms(1))
        t.onCopy(ms(1))
        t.onRender(12_000_000, ms(2))

        val s = t.snapshot()
        assertEquals(2L, s.frames)
        assertEquals(4_000L, s.sendUs)
        assertEquals(4_000L, s.recvUs)
        assertEquals(2_000L, s.copyUs)
        assertEquals(6_000L, s.renderUs)
    }

    @Test
    fun firstFrameSpansFirstSendToFirstRenderOnly() {
        val t = NpStageTiming("r")
        t.onFirstInput(5_000_000)
        t.onSend(ms(1))
        t.onRender(305_000_000, ms(6))
        // 第二次上屏不得改写首帧延迟
        t.onRender(905_000_000, ms(6))
        assertEquals(300_000L, t.snapshot().firstFrameUs)
    }

    @Test
    fun summaryWithoutRenderKeepsFirstDash() {
        val t = NpStageTiming("LegacyVideoRenderer")
        t.onFirstInput(0)
        t.onSend(ms(1))
        val line = t.summaryLine()
        assertTrue(line, line.startsWith("renderer=LegacyVideoRenderer n=1 "))
        assertTrue(line, line.contains(" send=1.00ms "))
        assertTrue(line, line.endsWith(" first=-"))
    }

    @Test
    fun summaryUsesPerFrameAverages() {
        val t = NpStageTiming("r")
        t.onFirstInput(0)
        repeat(4) { t.onSend(ms(2)) }
        val line = t.summaryLine()
        assertTrue(line, line.contains(" n=4 "))
        assertTrue(line, line.contains(" send=2.00ms "))
    }

    @Test
    fun resetClearsAccumulatorsAndFirstFrame() {
        val t = NpStageTiming("r")
        t.onFirstInput(0)
        t.onSend(ms(3))
        t.onRender(10_000_000, ms(1))
        t.reset()
        val s = t.snapshot()
        assertEquals(0L, s.frames)
        assertEquals(-1L, s.firstFrameUs)
        assertTrue(s.isEmpty)
        assertTrue(t.summaryLine().endsWith(" first=-"))
    }
}
