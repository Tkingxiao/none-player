package com.noneplayer.core.database

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E220：播放历史单条移除 + 撤销。
 *
 * 语义钉四件事：
 * - remove 把整行从表里摘走（行不存在返回 false，不报错）；
 * - undo 把**原样**的行插回（断点/时间戳逐字段一致）；
 * - 撤销窗口外（没有留底）undo 返回 false，不凭空造行；
 * - 留底只在内存：一次 remove 只有一次有效 undo（第二次 undo 返回 false）。
 *
 * 断言口径：表的真值看 [FakeHistoryDao.history]（内存表即真值）；`recent` 是
 * StateFlow（初值空表，普通 first() 直接读当前值、不触发上游收集），行级断言
 * 一律用 `first { 谓词 }` 挂上游等真实发射。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRemoveUndoTest {

    private fun TestScope.repository(dao: HistoryDao): HistoryRepository = HistoryRepository(
        dao = dao,
        scope = backgroundScope,
        retryPolicy = HistoryRetryPolicy(retryDelaysMs = listOf(1_000L)),
    )

    private fun historyRow(
        mediaKey: String,
        positionMs: Long = 12_000L,
        lastPlayedAt: Long = 100L,
    ): PlaybackHistoryEntity = PlaybackHistoryEntity(
        mediaKey = mediaKey,
        sourceId = "local",
        title = "视频",
        uri = "file:///videos/${mediaKey.substringAfter(':')}.mp4",
        mime = "video/mp4",
        coverUri = null,
        folder = null,
        durationMs = 120_000L,
        positionMs = positionMs,
        watchedRatio = 0.1f,
        playCount = 1,
        lastPlayedAt = lastPlayedAt,
        width = 0,
        height = 0,
    )

    @Test
    fun `remove takes the row out of the table`() = runTest {
        val dao = FakeHistoryDao().apply { history["local:ms-1"] = historyRow("local:ms-1") }
        val repository = repository(dao)

        assertTrue(repository.removeHistory("local:ms-1"))
        advanceUntilIdle()
        assertTrue(dao.history.isEmpty())
        // recent 流与表一致（谓词式：空表发射匹配谓词）
        assertTrue(repository.recent.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `remove of absent key returns false`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        assertFalse(repository.removeHistory("local:none"))
    }

    @Test
    fun `undo restores the exact row once`() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1", positionMs = 34_567L, lastPlayedAt = 9_999L)
        }
        val repository = repository(dao)

        assertTrue(repository.removeHistory("local:ms-1"))
        assertTrue(repository.undoRemoveHistory("local:ms-1"))
        advanceUntilIdle()

        // 表真值：原样整行（断点/时间戳逐字段）
        val table = dao.history.values.single()
        assertEquals("local:ms-1", table.mediaKey)
        assertEquals(34_567L, table.positionMs)
        assertEquals(9_999L, table.lastPlayedAt)
        // recent 流发射同一行（谓词式挂上游）
        val row = repository.recent.first { it.isNotEmpty() }.single()
        assertEquals("local:ms-1", row.mediaKey)
        assertEquals(34_567L, row.positionMs)
        // 第二次撤销没有留底：返回 false 且不报错
        assertFalse(repository.undoRemoveHistory("local:ms-1"))
    }

    @Test
    fun `remove and undo cycle repeatedly`() = runTest {
        val dao = FakeHistoryDao().apply { history["local:ms-1"] = historyRow("local:ms-1") }
        val repository = repository(dao)

        repeat(2) {
            assertTrue(repository.removeHistory("local:ms-1"))
            assertTrue(repository.undoRemoveHistory("local:ms-1"))
            advanceUntilIdle()
            assertTrue(dao.history.containsKey("local:ms-1"))
        }
        // 第三轮：留底被新一轮删除覆盖，undo 依旧可用
        assertTrue(repository.removeHistory("local:ms-1"))
        assertTrue(repository.undoRemoveHistory("local:ms-1"))
        advanceUntilIdle()
        assertTrue(dao.history.containsKey("local:ms-1"))
    }
}
