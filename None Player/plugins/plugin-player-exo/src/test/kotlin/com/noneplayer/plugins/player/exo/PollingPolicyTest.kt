package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Test

class PollingPolicyTest {

    @Test
    fun `播放与缓冲保持 250ms`() {
        assertEquals(POLL_ACTIVE_MS, pollIntervalMs(true, playing = true, buffering = false, subtitleOffsetMs = 0))
        assertEquals(POLL_ACTIVE_MS, pollIntervalMs(true, playing = false, buffering = true, subtitleOffsetMs = 0))
    }

    @Test
    fun `暂停且无字幕偏移降到 1s`() {
        assertEquals(POLL_IDLE_MS, pollIntervalMs(true, playing = false, buffering = false, subtitleOffsetMs = 0))
    }

    @Test
    fun `暂停但字幕偏移非零仍保持 250ms`() {
        assertEquals(POLL_ACTIVE_MS, pollIntervalMs(true, playing = false, buffering = false, subtitleOffsetMs = 500))
        assertEquals(POLL_ACTIVE_MS, pollIntervalMs(true, playing = false, buffering = false, subtitleOffsetMs = -500))
    }

    @Test
    fun `空队列只隔 1s 检查一次`() {
        assertEquals(POLL_IDLE_MS, pollIntervalMs(false, playing = false, buffering = false, subtitleOffsetMs = 0))
        assertEquals(POLL_IDLE_MS, pollIntervalMs(false, playing = true, buffering = true, subtitleOffsetMs = 1_000))
    }
}
