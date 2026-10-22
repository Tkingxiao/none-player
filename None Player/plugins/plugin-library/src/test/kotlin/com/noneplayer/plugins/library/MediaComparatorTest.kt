package com.noneplayer.plugins.library

import com.noneplayer.core.data.SortMode
import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E18/E20：媒体库四档排序比较器的单测——排序口径收口到 [mediaComparator]
 * 后，四档行为（含 E42 自然序、升降序、时间/时长/大小档）在这里锁定。
 */
class MediaComparatorTest {

    private fun item(
        title: String,
        addedAtMs: Long = 0,
        durationMs: Long = 0,
        sizeBytes: Long = 0,
    ) = MediaItem(
        id = title,
        sourceId = "local",
        title = title,
        uri = "file:///$title",
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        addedAtMs = addedAtMs,
    )

    @Test
    fun `名称档走自然序 E2 在 E10 前`() {
        val sorted = listOf(item("E10"), item("E2")).sortedWith(mediaComparator(SortMode.NAME, ascending = true))
        assertEquals(listOf("E2", "E10"), sorted.map { it.title })
    }

    @Test
    fun `名称档降序为自然序反排`() {
        val sorted = listOf(item("E2"), item("E10")).sortedWith(mediaComparator(SortMode.NAME, ascending = false))
        assertEquals(listOf("E10", "E2"), sorted.map { it.title })
    }

    @Test
    fun `加入时间档按时间戳排`() {
        val a = item("a", addedAtMs = 1)
        val b = item("b", addedAtMs = 2)
        assertEquals(listOf("a", "b"), listOf(a, b).sortedWith(mediaComparator(SortMode.ADDED, ascending = true)).map { it.title })
        assertEquals(listOf("b", "a"), listOf(a, b).sortedWith(mediaComparator(SortMode.ADDED, ascending = false)).map { it.title })
    }

    @Test
    fun `时长与大小档按数值排`() {
        val short = item("short", durationMs = 1_000, sizeBytes = 100)
        val long = item("long", durationMs = 90_000, sizeBytes = 9_000_000)
        assertEquals(
            listOf("short", "long"),
            listOf(short, long).sortedWith(mediaComparator(SortMode.DURATION, ascending = true)).map { it.title },
        )
        assertEquals(
            listOf("long", "short"),
            listOf(short, long).sortedWith(mediaComparator(SortMode.SIZE, ascending = false)).map { it.title },
        )
    }

    @Test
    fun `最近播放按 lastPlayedAt 排，未播放条目落到后面`() {
        val a = item("a", addedAtMs = 1)
        val b = item("b", addedAtMs = 2)
        val c = item("c", addedAtMs = 3)
        val playback = PlaybackSortContext(
            lastPlayedAt = mapOf("local:a" to 100L, "local:b" to 300L),
        )
        assertEquals(
            listOf("b", "a", "c"),
            listOf(a, b, c)
                .sortedWith(mediaComparator(SortMode.RECENT, ascending = false, playback))
                .map { it.title },
        )
        assertEquals(
            listOf("c", "a", "b"),
            listOf(a, b, c)
                .sortedWith(mediaComparator(SortMode.RECENT, ascending = true, playback))
                .map { it.title },
        )
    }

    @Test
    fun `最常看按 playCount 排，非历史档不受上下文影响`() {
        val a = item("a", addedAtMs = 1)
        val b = item("b", addedAtMs = 2)
        val c = item("c", addedAtMs = 3)
        val playback = PlaybackSortContext(
            playCounts = mapOf("local:a" to 3, "local:c" to 5),
        )
        assertEquals(
            listOf("c", "a", "b"),
            listOf(a, b, c)
                .sortedWith(mediaComparator(SortMode.MOST_PLAYED, ascending = false, playback))
                .map { it.title },
        )
        assertEquals(
            listOf("a", "b", "c"),
            listOf(a, b, c)
                .sortedWith(mediaComparator(SortMode.ADDED, ascending = true, playback))
                .map { it.title },
        )
    }

    @Test
    fun `搜索排序不提供历史档，历史档回落为最近添加`() {
        assertEquals(
            listOf(SortMode.NAME, SortMode.ADDED, SortMode.DURATION, SortMode.SIZE),
            SEARCH_SORT_MODES,
        )
        assertEquals(SortMode.ADDED, SortMode.RECENT.forSearch())
        assertEquals(SortMode.ADDED, SortMode.MOST_PLAYED.forSearch())
        assertEquals(SortMode.NAME, SortMode.NAME.forSearch())
    }
}
