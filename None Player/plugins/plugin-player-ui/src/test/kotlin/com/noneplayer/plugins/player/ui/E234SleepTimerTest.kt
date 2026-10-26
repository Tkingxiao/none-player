package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E234：睡眠定时的纯函数口径（四档 / 过期即停 / 标签）。
 *
 * 任务文档 §4 睡眠定时验收：四档 15/30/60/播完当前、到期时刻写会话+DataStore、
 * 回前台/进程起 now>=endsAt 立刻暂停、倒计时不在组合期读墙钟。这里钉住其中
 * 可纯函数化的部分；落盘/观察器/回前台收口在会话层（设备取证见 e234 证据）。
 */
class E234SleepTimerTest {

    @Test
    fun `计时档给出绝对到期时刻`() {
        assertEquals(1_000L + 15 * 60_000L, sleepDeadlineAtMs(nowMs = 1_000L, preset = SleepPreset.MIN15))
        assertEquals(1_000L + 30 * 60_000L, sleepDeadlineAtMs(1_000L, SleepPreset.MIN30))
        assertEquals(1_000L + 60 * 60_000L, sleepDeadlineAtMs(1_000L, SleepPreset.MIN60))
    }

    @Test
    fun `播完当前没有到期时刻`() {
        assertNull(sleepDeadlineAtMs(nowMs = 1_000L, preset = SleepPreset.TRACK_END))
    }

    @Test
    fun `到期判定含等号且未开启不算`() {
        assertFalse(sleepTimerExpired(endsAtMs = 0L, nowMs = 999_999L))
        assertFalse(sleepTimerExpired(endsAtMs = 2_000L, nowMs = 1_999L))
        // "now>=endsAt 立刻暂停"：等号即过期
        assertTrue(sleepTimerExpired(endsAtMs = 2_000L, nowMs = 2_000L))
        assertTrue(sleepTimerExpired(endsAtMs = 2_000L, nowMs = 2_001L))
    }

    @Test
    fun `标签按剩余分钟向上取整`() {
        val endsAt = 900_000L
        assertEquals("睡眠 15分", sleepTimerLabel(endsAt, nowMs = 0L, atTrackEnd = false))
        // 剩 10 秒也读 1 分，不读 0 分
        assertEquals("睡眠 1分", sleepTimerLabel(endsAt, nowMs = endsAt - 10_000L, atTrackEnd = false))
        assertEquals("睡眠 1分", sleepTimerLabel(endsAt, nowMs = endsAt - 60_000L, atTrackEnd = false))
    }

    @Test
    fun `未开启与已过期都回落睡眠定时`() {
        assertEquals("睡眠定时", sleepTimerLabel(0L, nowMs = 42L, atTrackEnd = false))
        assertEquals("睡眠定时", sleepTimerLabel(1_000L, nowMs = 1_000L, atTrackEnd = false))
        assertEquals("睡眠定时", sleepTimerLabel(1_000L, nowMs = 2_000L, atTrackEnd = false))
    }

    @Test
    fun `播完当前档给专属文案`() {
        assertEquals("播完当前暂停", sleepTimerLabel(0L, nowMs = 0L, atTrackEnd = true))
    }
}