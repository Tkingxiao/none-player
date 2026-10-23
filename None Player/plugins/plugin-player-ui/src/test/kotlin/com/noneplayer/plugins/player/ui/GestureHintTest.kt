package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GestureHintTest {

    @Test
    fun `same text retrigger produces a new timer key`() {
        val first = nextGestureHint(null, "快进 10 秒")
        val second = nextGestureHint(first, "快进 10 秒")

        assertEquals(first.text, second.text)
        assertEquals(first.seq + 1L, second.seq)
        // Compose 的 LaunchedEffect 以相等性判断 key：同文案同 seq 不会重新计时。
        assertNotEquals(first, second)
    }

    @Test
    fun `different text also advances the sequence`() {
        val mute = nextGestureHint(null, "已静音")
        val unmute = nextGestureHint(mute, "已取消静音")

        assertEquals("已取消静音", unmute.text)
        assertEquals(mute.seq + 1L, unmute.seq)
    }
}
