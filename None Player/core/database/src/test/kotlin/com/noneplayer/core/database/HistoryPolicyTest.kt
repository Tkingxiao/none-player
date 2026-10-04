package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史写入策略（E12 / §8.3 的纯函数部分）。 */
class HistoryPolicyTest {

    @Test
    fun `watchedRatio 夹在 0 到 1`() {
        assertEquals(0.5f, HistoryRepository.watchedRatio(50, 100), 0.001f)
        assertEquals(1f, HistoryRepository.watchedRatio(150, 100), 0.001f)
        assertEquals(0f, HistoryRepository.watchedRatio(-5, 100), 0.001f)
    }

    @Test
    fun `时长未知时比例按 0`() {
        assertEquals(0f, HistoryRepository.watchedRatio(50, 0), 0.001f)
    }

    @Test
    fun `低于十秒且未完看不写`() {
        assertFalse(HistoryRepository.shouldPersist(3_000, 100_000))
        assertFalse(HistoryRepository.shouldPersist(0, 100_000))
    }

    @Test
    fun `过了十秒就写`() {
        assertTrue(HistoryRepository.shouldPersist(10_000, 100_000))
        assertTrue(HistoryRepository.shouldPersist(60_000, 100_000))
    }

    @Test
    fun `未完看但时长未知仍按十秒门槛`() {
        assertFalse(HistoryRepository.shouldPersist(3_000, 0))
        assertTrue(HistoryRepository.shouldPersist(12_000, 0))
    }

    @Test
    fun `短片的看完位置要写（不足十秒但比例过阈值）`() {
        // 15 秒短片看到 14.5 秒 → ratio 0.966 ≥ 0.95 → 看完，落盘
        assertTrue(HistoryRepository.shouldPersist(14_500, 15_000))
    }

    @Test
    fun `media_key 按来源分命名空间`() {
        val item = MediaItem(id = "ms-1", sourceId = "local", title = "t", uri = "content://x")
        assertEquals("local:ms-1", HistoryEntry.keyOf(item))
    }
}
