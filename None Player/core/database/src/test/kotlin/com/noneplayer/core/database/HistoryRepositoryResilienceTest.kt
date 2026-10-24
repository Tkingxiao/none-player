package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-1 / REV-06：持久化 IO 失败可观察恢复。
 *
 * 全部是纯 JVM 用例（无 Robolectric）：[FakeHistoryDao] 用内存表 + 失败开关注入
 * 第 N 次读失败 / 指定写失败，`runTest` 的虚拟时间把 1s/2s 的重试退避跑成瞬间，
 * 从而既能断言「重试后恢复」，也能断言「结构性错误不重试、流结束保留最后值」。
 *
 * 这里构造 [HistoryRepository] 用的是**无 context 的 internal 注入构造器**：
 * 契约里的 `internal constructor(context, dao, scope, retryPolicy)` 首个参数是
 * `android.content.Context`，纯 JVM 单测无法安全实例化（android.jar 构造器是桩），
 * 故另开一个参数等价的注入构造器；两个构造器共用同一份实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRepositoryResilienceTest {

    // ---- 读流：可重试失败 → 有界退避重试 → 成功 emit 并恢复 healthy ----------

    @Test
    fun retryableReadFailureIsRetriedWithBoundedBackoffAndStatusRecovers() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1")
            recentFailuresRemaining = 2
        }
        val repository = repository(dao, retryDelays = listOf(1_000L, 2_000L))
        val statuses = mutableListOf<HistoryStorageStatus>()
        backgroundScope.launch { repository.storageStatus.collect { statuses += it } }

        val entries = repository.recent.first { it.isNotEmpty() }
        advanceUntilIdle()

        assertEquals(1, entries.size)
        assertEquals("local:ms-1", entries.single().mediaKey)
        // 2 次失败 + 1 次成功 = 3 次订阅
        assertEquals(3, dao.recentReadCount)
        // 失败期间 degraded 可观察，成功后恢复 healthy 且连续失败计数清零
        assertTrue(statuses.any { !it.healthy && it.operation == "recent" && it.failures > 0 })
        assertTrue(statuses.last().healthy)
        assertEquals(0, repository.storageStatus.value.failures)
    }

    @Test
    fun structuralReadErrorEndsFlowWithoutRetryAndKeepsLastKnownValue() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1")
            recentStructuralError = IllegalStateException("模拟：查询结构损坏")
        }
        val repository = repository(dao)
        val values = mutableListOf<List<HistoryEntry>>()
        backgroundScope.launch { repository.recent.collect { values += it } }
        // runCurrent 会排空当前虚拟时间点的任务（包括 backgroundScope 的任务）；
        // 普通 advanceUntilIdle 只保证前台任务，会在这里提前返回导致断言看到空值。
        runCurrent()

        // 不重试：结构性错误只订阅一次
        assertEquals(1, dao.recentReadCount)
        // 已经读到一次的真实数据被保留；没有因为失败被换成新的空表
        assertTrue(values.any { it.isNotEmpty() })
        assertEquals("local:ms-1", repository.recent.value.single().mediaKey)
        val status = repository.storageStatus.value
        assertFalse(status.healthy)
        assertEquals("recent", status.operation)
        assertNotNull(status.message)
    }

    @Test
    fun countStreamRetriesThenEmits() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1")
            countFailuresRemaining = 1
        }
        val repository = repository(dao, retryDelays = listOf(1_000L))

        val count = repository.count.first { it > 0 }

        assertEquals(1, count)
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun favoriteKeysStreamRetriesThenEmits() = runTest {
        val dao = FakeHistoryDao().apply {
            favorites["local:ms-1"] = FavoriteEntity("local:ms-1", 10L)
            favoriteKeysFailuresRemaining = 1
        }
        val repository = repository(dao, retryDelays = listOf(1_000L))

        val keys = repository.favoriteKeys.first { it.isNotEmpty() }

        assertEquals(setOf("local:ms-1"), keys)
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun sharedFlowRestartsAndEmitsFreshDataAfterSubscribersLeave() = runTest {
        val dao = FakeHistoryDao().apply { history["local:ms-1"] = historyRow("local:ms-1") }
        val repository = repository(dao)
        val job = backgroundScope.launch { repository.recent.collect {} }
        runCurrent()
        assertEquals(listOf("local:ms-1"), repository.recent.value.map { it.mediaKey })

        // 收集者离开 → 超过 WhileSubscribed 的 5s 停止窗口 → 共享停止
        job.cancel()
        advanceUntilIdle()
        delay(6_000L)
        dao.history["local:ms-2"] = historyRow("local:ms-2", lastPlayedAt = 200L)

        // 重新订阅：必须重新订阅 DAO 流并拿到最新数据，而不是永远停在旧快照
        val refreshed = repository.recent.first { it.size == 2 }
        assertEquals(setOf("local:ms-1", "local:ms-2"), refreshed.map { it.mediaKey }.toSet())
        assertTrue(repository.storageStatus.value.healthy)
    }

    // ---- 写操作：失败不抛、返回 false/null，随后成功可恢复 -------------------

    @Test
    fun recordFailureReturnsFalseThenRecoveryReturnsTrueAndHealthy() = runTest {
        val dao = FakeHistoryDao().apply { insertError = IOException("模拟：磁盘写入失败") }
        val repository = repository(dao)

        assertFalse(repository.record(item(), positionMs = 20_000L))
        val degraded = repository.storageStatus.value
        assertFalse(degraded.healthy)
        assertEquals("record", degraded.operation)
        assertEquals(1, degraded.failures)

        dao.insertError = null
        assertTrue(repository.record(item(), positionMs = 30_000L))
        assertEquals(30_000L, dao.history["local:ms-1"]?.positionMs)
        val recovered = repository.storageStatus.value
        assertTrue(recovered.healthy)
        assertEquals(0, recovered.failures)
    }

    @Test
    fun markPlayedFailureReturnsFalseThenRecoverySucceeds() = runTest {
        val dao = FakeHistoryDao().apply { bumpPlayError = IOException("模拟：播放次数写入失败") }
        val repository = repository(dao)

        assertFalse(repository.markPlayed(item()))
        assertEquals("markPlayed", repository.storageStatus.value.operation)

        dao.bumpPlayError = null
        assertTrue(repository.markPlayed(item()))
        assertTrue(repository.storageStatus.value.healthy)
        assertEquals(1, dao.history["local:ms-1"]?.playCount)
    }

    @Test
    fun toggleFavoriteFailureReturnsNullThenSuccessTogglesState() = runTest {
        val dao = FakeHistoryDao().apply { addFavoriteError = IOException("模拟：收藏写入失败") }
        val repository = repository(dao)

        assertNull(repository.toggleFavorite(item()))
        val degraded = repository.storageStatus.value
        assertFalse(degraded.healthy)
        assertEquals("toggleFavorite", degraded.operation)

        dao.addFavoriteError = null
        assertEquals(true, repository.toggleFavorite(item()))
        assertTrue("local:ms-1" in dao.favorites)
        assertEquals(false, repository.toggleFavorite(item()))
        assertFalse("local:ms-1" in dao.favorites)
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun toggleFavoriteReadFailureAlsoReturnsNull() = runTest {
        val dao = FakeHistoryDao().apply { isFavoriteNowError = IOException("模拟：收藏状态读取失败") }
        val repository = repository(dao)

        assertNull(repository.toggleFavorite(item()))
        assertEquals("toggleFavorite", repository.storageStatus.value.operation)
    }

    @Test
    fun clearHistoryFailureReturnsFalseThenRecovers() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1")
            clearError = IOException("模拟：清库失败")
        }
        val repository = repository(dao)

        assertFalse(repository.clearHistory())
        assertEquals("clearHistory", repository.storageStatus.value.operation)
        assertEquals(1, dao.history.size)

        dao.clearError = null
        assertTrue(repository.clearHistory())
        assertTrue(dao.history.isEmpty())
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun pruneOrphansFailureReturnsZeroInsteadOfThrowing() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:alive"] = historyRow("local:alive")
            history["local:gone"] = historyRow("local:gone")
            allHistoryKeysError = IOException("模拟：孤儿清理读失败")
        }
        val repository = repository(dao)

        assertEquals(0, repository.pruneOrphans(validKeys = setOf("local:alive"), sourceId = "local"))

        dao.allHistoryKeysError = null
        assertEquals(1, repository.pruneOrphans(validKeys = setOf("local:alive"), sourceId = "local"))
        assertFalse("local:gone" in dao.history)
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun protectedKeysFailureReturnsNullSoCallerSkipsPrune() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:audio-1"] = historyRow("local:audio-1", mime = "audio/mp4")
            mimePatternError = IOException("模拟：受保护键查询失败")
        }
        val repository = repository(dao)

        assertNull(repository.protectedKeysForMimePrefixes(listOf("audio/")))
        assertFalse(repository.storageStatus.value.healthy)

        dao.mimePatternError = null
        assertEquals(setOf("local:audio-1"), repository.protectedKeysForMimePrefixes(listOf("audio/")))
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun migrateFailureReturnsNullSoScannerRetriesLater() = runTest {
        val dao = FakeHistoryDao().apply { existsError = IOException("模拟：迁移读失败") }
        val repository = repository(dao)
        val items = listOf(item("ms-1"))
        val positions = mapOf("ms-1" to 20_000L)

        assertNull(repository.migrateFromDataStore(items, positions, emptyMap()))
        assertFalse(repository.storageStatus.value.healthy)

        dao.existsError = null
        assertEquals(1, repository.migrateFromDataStore(items, positions, emptyMap()))
        assertEquals(20_000L, dao.history["local:ms-1"]?.positionMs)
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun positionReadsReturnNullOnFailure() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1", positionMs = 42_000L)
            positionOfError = IOException("模拟：位置读取失败")
            breakpointOfError = IOException("模拟：断点读取失败")
        }
        val repository = repository(dao)

        assertNull(repository.positionOf("local:ms-1"))
        assertNull(repository.positionOfIfIntact(item()))
        assertFalse(repository.storageStatus.value.healthy)

        dao.positionOfError = null
        dao.breakpointOfError = null
        assertEquals(42_000L, repository.positionOf("local:ms-1"))
        assertEquals(42_000L, repository.positionOfIfIntact(item()))
        assertTrue(repository.storageStatus.value.healthy)
    }

    @Test
    fun primeFailureDoesNotThrowAndBecomesObservable() = runTest {
        val dao = FakeHistoryDao().apply { countError = IOException("模拟：库打不开") }
        val repository = repository(dao)

        repository.prime() // 预读失败不能影响启动，只记录状态

        assertFalse(repository.storageStatus.value.healthy)
        assertEquals("prime", repository.storageStatus.value.operation)
    }

    @Test
    fun cancellationFromWriteIsRethrownAndNotReportedAsFailure() = runTest {
        val dao = FakeHistoryDao().apply {
            insertError = CancellationException("调用方取消了写入")
        }
        val repository = repository(dao)

        val caught = try {
            repository.record(item(), positionMs = 20_000L)
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }

        assertNotNull("CancellationException 必须原样重抛，不能被当成写失败吞掉", caught)
        assertTrue("取消不是存储故障，不应把状态标成 degraded", repository.storageStatus.value.healthy)
    }

    // ---- E209：系统播放恢复的冷查询 ----------------------------------------

    @Test
    fun mostRecentForResumptionReturnsLatestRowWithoutCollectingRecentFlow() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:a"] = historyRow("local:a", lastPlayedAt = 10L)
            history["local:b"] = historyRow("local:b", lastPlayedAt = 20L, positionMs = 34_000L)
        }
        val repository = repository(dao)

        val latest = repository.mostRecentForResumption()

        assertEquals("local:b", latest?.mediaKey)
        assertEquals(34_000L, latest?.positionMs)
        // 关键是走冷查询，而不是 recent StateFlow 的首帧（无订阅者时为空表）
        assertEquals(0, dao.recentReadCount)
    }

    // ---- 辅助 --------------------------------------------------------------

    private fun TestScope.repository(
        dao: HistoryDao,
        retryDelays: List<Long> = listOf(1_000L, 2_000L),
    ): HistoryRepository = HistoryRepository(
        dao = dao,
        // backgroundScope：stateIn 的共享协程与进程同寿（生产语义），放进测试作用域会
        // 让 runTest 判定协程泄漏；backgroundScope 由 runTest 在用例结束时统一取消。
        scope = backgroundScope,
        retryPolicy = HistoryRetryPolicy(retryDelaysMs = retryDelays),
    )

    private fun item(id: String = "ms-1", durationMs: Long = 120_000L): MediaItem = MediaItem(
        id = id,
        sourceId = "local",
        title = "视频 $id",
        uri = "file:///videos/$id.mp4",
        mimeType = "video/mp4",
        durationMs = durationMs,
    )

    private fun historyRow(
        mediaKey: String,
        positionMs: Long = 12_000L,
        mime: String = "video/mp4",
        lastPlayedAt: Long = 100L,
    ): PlaybackHistoryEntity = PlaybackHistoryEntity(
        mediaKey = mediaKey,
        sourceId = "local",
        title = "视频",
        uri = "file:///videos/${mediaKey.substringAfter(':')}.mp4",
        mime = mime,
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
}
