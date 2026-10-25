package com.noneplayer.plugins.library

import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库筛选（设计稿 FILTERS 六档）的纯函数语义：
 * - 类型两档按 mimeType 判定的 [MediaKind] 分；
 * - 「未看完 / 已看完」共用 HistoryRepository 的「看完」线（WATCHED_DONE_RATIO），
 *   未看完下限 0.01——「看过一帧」和「没看过」要分开；
 * - 「新增」按 addedAtMs 48 小时判定。
 */
class FilterListTest {

    private fun item(
        id: String,
        durationMs: Long = 100_000L,
        mime: String = "video/mp4",
        addedAtMs: Long = 0L,
    ) = MediaItem(
        id = id,
        sourceId = "test",
        title = id,
        uri = "content://test/$id",
        mimeType = mime,
        durationMs = durationMs,
        addedAtMs = addedAtMs,
    )

    /** positions[item] = positionMs（历史断点） */
    private fun progress(item: MediaItem, positionMs: Long) =
        mapOf(HistoryEntry.keyOf(item) to positionMs)

    @Test
    fun `video and audio split by mime kind`() {
        val list = listOf(item("v"), item("a", mime = "audio/mpeg"))
        val videos = filterList(list, FilterKey.VIDEO.key, emptyMap())
        val audios = filterList(list, FilterKey.AUDIO.key, emptyMap())
        assertEquals(listOf(MediaKind.VIDEO), videos.map { it.kind })
        assertEquals(listOf(MediaKind.AUDIO), audios.map { it.kind })
    }

    @Test
    fun `unfinished keeps 1 percent to below watched line`() {
        val none = item("none")
        val barely = item("barely") // 0.9%：低于 0.01 下限，不算"未看完"
        val middle = item("middle")
        val almost = item("almost") // 98.5%：仍在未看完
        val atLine = item("atLine") // 99%：恰好到线 → 已看完
        val positions = progress(barely, 900L) +
            progress(middle, 50_000L) +
            progress(almost, 98_500L) +
            progress(atLine, 99_000L)
        val result = filterList(listOf(none, barely, middle, almost, atLine), FilterKey.UNFINISHED.key, positions)
        assertEquals(listOf("middle", "almost"), result.map { it.id })
        assertFalse(result.any { it.id == "none" })
    }

    @Test
    fun `done keeps at or above watched line`() {
        val under = item("under") // 98.9%：未到线
        val atLine = item("atLine") // 99%
        val full = item("full") // 100%（seek 到片尾）
        val positions = progress(under, 98_900L) +
            progress(atLine, 99_000L) +
            progress(full, 100_000L)
        val result = filterList(listOf(under, atLine, full), FilterKey.DONE.key, positions)
        assertEquals(listOf("atLine", "full"), result.map { it.id })
        assertTrue(result.none { it.id == "under" })
    }

    @Test
    fun `new keeps items added within 48 hours`() {
        val now = System.currentTimeMillis()
        val fresh = item("fresh", addedAtMs = now - 47 * 60 * 60 * 1000L)
        val stale = item("stale", addedAtMs = now - 49 * 60 * 60 * 1000L)
        val result = filterList(listOf(fresh, stale), FilterKey.NEW.key, emptyMap())
        assertEquals(listOf("fresh"), result.map { it.id })
    }

    @Test
    fun `unknown filter key falls back to full list`() {
        val list = listOf(item("v"))
        assertEquals(list, filterList(list, "whatever", emptyMap()))
        assertEquals(list, filterList(list, FilterKey.ALL.key, emptyMap()))
    }
}
