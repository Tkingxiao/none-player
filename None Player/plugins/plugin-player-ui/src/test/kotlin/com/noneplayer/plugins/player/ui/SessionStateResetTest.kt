package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.NpLoopMode
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionStateResetTest : SessionRobolectricTest() {
    @Test
    fun `stop 复位循环擦洗与裁剪`() {
        val item = testItem("a")
        session.open(item, listOf(item))
        session.setLoopMode(NpLoopMode.SHUFFLE)
        fakePlayer.setScrubbing(true)
        fakePlayer.setVideoCrop(true)

        session.stop()

        assertTrue(
            "stop 必须下发循环 OFF，实际=${fakePlayer.labels}",
            fakePlayer.calls.any { it is PlayerCall.LoopMode && it.mode == NpLoopMode.OFF },
        )
        assertTrue(
            "stop 必须清擦洗态，实际=${fakePlayer.labels}",
            fakePlayer.calls.any { it is PlayerCall.Scrubbing && !it.active },
        )
        assertTrue(
            "stop 必须清裁剪态，实际=${fakePlayer.labels}",
            fakePlayer.calls.any { it is PlayerCall.VideoCrop && !it.crop },
        )
    }

    @Test
    fun `open 新队列先复位循环擦洗与裁剪再装载`() {
        val a = testItem("a")
        val b = testItem("b")
        session.open(a, listOf(a))
        session.setLoopMode(NpLoopMode.SHUFFLE)
        fakePlayer.setScrubbing(true)
        fakePlayer.setVideoCrop(true)

        val start = fakePlayer.calls.size
        session.open(b, listOf(b))
        val tail = fakePlayer.calls.drop(start)

        val setQueue = tail.indexOfFirst { it is PlayerCall.SetQueue }
        val resetLoop = tail.indexOfFirst { it is PlayerCall.LoopMode && it.mode == NpLoopMode.OFF }
        val resetScrub = tail.indexOfFirst { it is PlayerCall.Scrubbing && !it.active }
        val resetCrop = tail.indexOfFirst { it is PlayerCall.VideoCrop && !it.crop }

        assertTrue("新队列必须先下发循环 OFF，实际=${tail.map { it.describe() }}", resetLoop in 0 until setQueue)
        assertTrue("新队列必须先清擦洗态，实际=${tail.map { it.describe() }}", resetScrub in 0 until setQueue)
        assertTrue("新队列必须先清裁剪态，实际=${tail.map { it.describe() }}", resetCrop in 0 until setQueue)
    }
}
