package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A24：周期落点走窄更新，行不存在时回退整行 upsert。
 *
 * 纯 JVM（[FakeHistoryDao] 内存表），覆盖：
 *  - 行存在时只发一条 `updateProgress`，不读改写整行、不动 playCount；
 *  - 行不存在时仍能插入（首次落点早于 markPlayed 的窗口）；
 *  - E12 门槛内不写；
 *  - 窄更新失败返回 false、不误报成功、不回退成第二次写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryNarrowProgressTest {

    @Test
    fun `已有行时只做窄更新且保留播放次数`() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = row(playCount = 3)
        }
        val repository = repository(dao)

        val ok = repository.persistProgress(item(), positionMs = 50_000, durationMs = 120_000)

        assertTrue(ok)
        assertEquals(listOf("updateProgress:local:ms-1"), dao.writeCalls)
        val updated = dao.history.getValue("local:ms-1")
        assertEquals(50_000, updated.positionMs)
        assertEquals(50_000f / 120_000f, updated.watchedRatio, 0.0001f)
        assertEquals(3, updated.playCount)
        assertTrue(updated.lastPlayedAt > 100L)
    }

    @Test
    fun `行不存在时回退整行插入`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)

        val ok = repository.persistProgress(item(), positionMs = 50_000, durationMs = 120_000)

        assertTrue(ok)
        // 先尝试窄更新（0 行），再回退整行插入。
        assertEquals(listOf("updateProgress:local:ms-1", "insert:local:ms-1"), dao.writeCalls)
        val inserted = dao.history.getValue("local:ms-1")
        assertEquals(50_000, inserted.positionMs)
        assertEquals(0, inserted.playCount)
    }

    @Test
    fun `门槛内不写`() = runTest {
        val dao = FakeHistoryDao().apply { history["local:ms-1"] = row() }
        val repository = repository(dao)

        assertTrue(repository.persistProgress(item(), positionMs = 5_000, durationMs = 120_000))
        assertTrue(repository.persistProgress(item(), positionMs = 0, durationMs = 120_000))
        assertTrue(dao.writeCalls.isEmpty())
    }

    @Test
    fun `窄更新失败不误报且不回退写第二次`() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = row()
            progressUpdateError = IOException("模拟：窄更新 IO 失败")
        }
        val repository = repository(dao)

        val ok = repository.persistProgress(item(), positionMs = 50_000, durationMs = 120_000)

        assertFalse(ok)
        assertEquals(listOf("updateProgress:local:ms-1"), dao.writeCalls)
        assertEquals(12_000L, dao.history.getValue("local:ms-1").positionMs)
        assertFalse(repository.storageStatus.value.healthy)
        assertEquals("persistProgress", repository.storageStatus.value.operation)
    }

    private fun TestScope.repository(dao: HistoryDao): HistoryRepository = HistoryRepository(
        dao = dao,
        scope = backgroundScope,
    )

    private fun item(): MediaItem = MediaItem(
        id = "ms-1",
        sourceId = "local",
        title = "视频",
        uri = "file:///videos/ms-1.mp4",
        mimeType = "video/mp4",
        durationMs = 120_000L,
    )

    private fun row(playCount: Int = 1): PlaybackHistoryEntity = PlaybackHistoryEntity(
        mediaKey = "local:ms-1",
        sourceId = "local",
        title = "视频",
        uri = "file:///videos/ms-1.mp4",
        mime = "video/mp4",
        coverUri = null,
        folder = null,
        durationMs = 120_000L,
        positionMs = 12_000L,
        watchedRatio = 0.1f,
        playCount = playCount,
        lastPlayedAt = 100L,
        width = 0,
        height = 0,
    )
}
