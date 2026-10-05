package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E18：PlaybackSession 队列/连播逻辑的单测——覆盖 [resolveQueueStart] 的
 * 三条路径（id 命中 / uri 命中 / 全不命中退化为单项队列）。
 * 这正是批次 1 修过的"深链/重扫后续播静默失效"一族的判定核心。
 */
class PlaybackQueueResolutionTest {

    private fun item(id: String, uri: String) = MediaItem(
        id = id,
        sourceId = "local",
        title = id,
        uri = uri,
    )

    private val queue = listOf(
        item("ms-1", "file:///a1.mkv"),
        item("ms-2", "file:///a2.mkv"),
        item("ms-3", "file:///a3.mkv"),
    )

    @Test
    fun `id 命中时整队返回并定位到该项`() {
        val (list, index) = resolveQueueStart(queue, item("ms-2", "file:///changed-2.mkv"))
        assertEquals(queue, list)
        assertEquals(1, index)
    }

    @Test
    fun `id 不中 uri 命中时仍定位到该项（重扫换号场景）`() {
        val (list, index) = resolveQueueStart(queue, item("ms-999", "file:///a3.mkv"))
        assertEquals(queue, list)
        assertEquals(2, index)
    }

    @Test
    fun `全不命中退化为单项队列且下标 0（不硬指队首）`() {
        val outsider = item("ms-9", "file:///other.mkv")
        val (list, index) = resolveQueueStart(queue, outsider)
        assertEquals(listOf(outsider), list)
        assertEquals(0, index)
    }

    @Test
    fun `空队列同样退化为单项队列`() {
        val outsider = item("ms-9", "file:///other.mkv")
        val (list, index) = resolveQueueStart(emptyList(), outsider)
        assertEquals(listOf(outsider), list)
        assertEquals(0, index)
    }
}
