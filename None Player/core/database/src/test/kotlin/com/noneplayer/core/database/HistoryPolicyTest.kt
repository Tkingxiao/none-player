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
        // 15 秒短片看到 14.9 秒 → ratio 0.993 ≥ 0.99 → 看完，落盘
        assertTrue(HistoryRepository.shouldPersist(14_900, 15_000))
    }

    @Test
    fun `看到底了不留断点`() {
        // 播完（位置 = 时长）与 99% 以上都算到底——装载链据此从头播（L5）
        assertTrue(HistoryRepository.isWatchedToEnd(180_000, 180_000))
        assertTrue(HistoryRepository.isWatchedToEnd(178_500, 180_000))
        assertFalse(HistoryRepository.isWatchedToEnd(170_000, 180_000))
        // 时长未知时不能判"看完"——否则任何一条脏断点都会被静默丢弃
        assertFalse(HistoryRepository.isWatchedToEnd(9_999, 0))
    }

    @Test
    fun `media_key 按来源分命名空间`() {
        val item = MediaItem(id = "ms-1", sourceId = "local", title = "t", uri = "content://x")
        assertEquals("local:ms-1", HistoryEntry.keyOf(item))
    }

    /**
     * 审查批次 A3：不同来源出现**同一个媒体 ID** 时，展示层若用裸 `mediaId`
     * 建进度/时间 map，`associate` 会让后一条覆盖前一条，进度串到另一个来源去。
     * 这条断言钉住"跨来源同 ID 必须落在不同键上"。
     */
    @Test
    fun `跨来源同媒体 id 落在不同的进度键上`() {
        val a = MediaItem(id = "ms-1", sourceId = "local", title = "本地.mp4", uri = "file:///a")
        val b = MediaItem(id = "ms-1", sourceId = "saf", title = "网盘.mp4", uri = "content://b")
        val keys = listOf(HistoryEntry.keyOf(a), HistoryEntry.keyOf(b)).toSet()
        // 两个键都必须保留：若这里退化成裸 mediaId，集合只剩 1 个元素
        assertEquals(2, keys.size)
        assertEquals(setOf("local:ms-1", "saf:ms-1"), keys)
    }

    @Test
    fun `mediaId 是 mediaKey 去命名空间后的剩余部分`() {
        val entry = HistoryEntry(
            mediaKey = "local:ms-1",
            sourceId = "local",
            mediaId = "local:ms-1".substringAfter(':'),
            title = "t",
            uri = "u",
            mimeType = "video/mp4",
            coverUri = null,
            folder = null,
            durationMs = 0,
            positionMs = 0,
            watchedRatio = 0f,
            playCount = 1,
            lastPlayedAt = 0,
            width = 0,
            height = 0,
        )
        assertEquals("ms-1", entry.mediaId)
        // 建表要用完整 mediaKey，不是裸 mediaId
        assertEquals(entry.mediaKey, "${entry.sourceId}:${entry.mediaId}")
    }

    @Test
    fun `孤儿键是库里存在但扫描结果里没有的那些`() {
        // L16：重扫后只删"不在本次结果集"的行——存在的键一个都不能误删
        val existing = listOf("local:ms-1", "local:ms-2", "local:saf-abc")
        val valid = setOf("local:ms-1", "local:saf-abc")
        assertEquals(listOf("local:ms-2"), HistoryRepository.orphanKeys(existing, valid))
        assertTrue(HistoryRepository.orphanKeys(emptyList(), valid).isEmpty())
        // valid 为空 = 库被清空，整表都是孤儿
        assertEquals(existing, HistoryRepository.orphanKeys(existing, emptySet()))
    }

    // ---- A1-3 断点失效校验 ----

    @Test
    fun `local scan cannot prune another sources history or favorites`() {
        val keys = listOf("local:1", "remote:1", "local-extra:2", "local:3")
        assertEquals(listOf("local:3"), HistoryRepository.orphanKeys(keys, setOf("local:1"), "local"))
        assertEquals(
            listOf("local:1", "local:3"),
            HistoryRepository.orphanKeys(keys, emptySet(), "local"),
        )
    }

    @Test
    fun `source names with sql wildcard characters are matched literally`() {
        val keys = listOf("disk_1:4", "diskX1:4", "disk_1:5")
        assertEquals(
            listOf("disk_1:5"),
            HistoryRepository.orphanKeys(keys, setOf("disk_1:4"), "disk_1"),
        )
    }

    @Test
    fun `指纹一致时断点可信`() {
        assertTrue(HistoryRepository.breakpointIntact(100L, 20L, 100L, 20L))
    }

    @Test
    fun `文件被替换后断点失效`() {
        // 大小变了 = 换了内容；大小没变但 mtime 变了 = 重录/覆写
        assertFalse(HistoryRepository.breakpointIntact(100L, 20L, 101L, 20L))
        assertFalse(HistoryRepository.breakpointIntact(100L, 20L, 100L, 21L))
    }

    @Test
    fun `任一指纹缺一侧就不参与比对`() {
        // v1 老行两列都是 0：升级不能顺手把老用户的续播清掉
        assertTrue(HistoryRepository.breakpointIntact(0L, 0L, 100L, 20L))
        // 来源拿不到 size（部分 SAF 文档 / 云盘）：同一道理，不否决
        assertTrue(HistoryRepository.breakpointIntact(100L, 20L, 0L, 0L))
        // 一轴可比且相等、另一轴不可比 → 仍然可信
        assertTrue(HistoryRepository.breakpointIntact(100L, 0L, 100L, 20L))
        // 一轴可比且不等 → 直接失效，另一轴不可比也不能救
        assertFalse(HistoryRepository.breakpointIntact(100L, 0L, 99L, 20L))
    }
}
