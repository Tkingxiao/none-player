package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-09：章节归属判定。
 *
 * 钉的是"切到没有章节的媒体后上一集章节不许残留"与"旧媒体的迟到回调不许盖回
 * 新媒体的章节"这两条；同媒体的空回调（章节元数据晚到）不清已有效章节。
 */
class ChapterIdentityTest {

    @Test
    fun keyOfPrefersMediaKeyThenUri() {
        assertEquals("source:id", ChapterIdentity.keyOf("source:id", "content://x"))
        // 单媒体 prepare / 无 id 条目：退回 uri，至少能区分两条媒体
        assertEquals("content://x", ChapterIdentity.keyOf(null, "content://x"))
        assertEquals("content://x", ChapterIdentity.keyOf("  ", "content://x"))
        assertNull(ChapterIdentity.keyOf(null, null))
        assertNull(ChapterIdentity.keyOf("", ""))
    }

    @Test
    fun shouldApplyChaptersRejectsOwnerlessAndStaleCallbacks() {
        // 没有归属键：无从判断归属，丢弃
        assertFalse(ChapterIdentity.shouldApplyChapters("k1", null, incomingCount = 5, existingCount = 0))
        assertFalse(ChapterIdentity.shouldApplyChapters("k1", "  ", incomingCount = 5, existingCount = 0))
        // 旧媒体的迟到回调：键不同，不许覆盖新媒体的章节
        assertFalse(ChapterIdentity.shouldApplyChapters("k2", "k1", incomingCount = 5, existingCount = 0))
    }

    @Test
    fun shouldApplyChaptersAcceptsSameMediaEntries() {
        // 首次拿到章节（当前还没有键）
        assertTrue(ChapterIdentity.shouldApplyChapters(null, "k1", incomingCount = 3, existingCount = 0))
        // 同媒体更新章节
        assertTrue(ChapterIdentity.shouldApplyChapters("k1", "k1", incomingCount = 4, existingCount = 3))
    }

    @Test
    fun shouldApplyChaptersKeepsExistingWhenSameMediaCallbackIsEmpty() {
        // 章节元数据有时晚于 tracks 到达：同媒体的空回调不代表"没有章节"
        assertFalse(ChapterIdentity.shouldApplyChapters("k1", "k1", incomingCount = 0, existingCount = 3))
        // 当前本来就没有章节时，空回调落成空集也无害
        assertTrue(ChapterIdentity.shouldApplyChapters("k1", "k1", incomingCount = 0, existingCount = 0))
        assertTrue(ChapterIdentity.shouldApplyChapters(null, "k1", incomingCount = 0, existingCount = 0))
    }
}
