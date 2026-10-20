package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-3 独立验收：**存储失败可观察恢复**（纯 JVM，无 Robolectric）。
 *
 * 与 task-8 的 `HistoryRepositoryResilienceTest` 的分工：那边是**实现者自测**（逐个失败
 * 开关验证每个 API 的降级返回值），这里是**验收测试**——只按对外可观察契约断言：
 * 1. 读流失败 → 有界退避重试 → 恢复后照常 emit，且 `storageStatus` 的降级/恢复可被
 *    外部观察（healthy/operation/failures 三个字段是 UI 连接横幅的输入）；
 * 2. 写失败 → `toggleFavorite` 返回 null、状态降级；恢复后返回新状态并回到 healthy；
 * 3. 结构性错误（`IllegalStateException`，非 IO 类）→ 不重试、不崩、不挂住收集者，
 *    且不会把库永久判死（后续读写恢复后仍可用）。
 *
 * 用 [FakeHistoryDao] 注入故障；[HistoryRepository] 走**无 context 的 internal 注入
 * 构造器**（纯 JVM 测试无法实例化 `android.content.Context`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryStorageAcceptanceTest {

    // ---- ⑤ 读流：失败 2 次 → 重试成功 emit，状态最终 healthy 且失败可观察 ------

    @Test
    fun retryableReadFailureRecoversAndStatusIsObservable() = runTest {
        val dao = FakeHistoryDao().apply {
            history["local:ms-1"] = historyRow("local:ms-1")
            recentFailuresRemaining = 2
        }
        val repository = repository(dao, retryDelays = listOf(1_000L, 2_000L))
        val statuses = mutableListOf<HistoryStorageStatus>()
        backgroundScope.launch { repository.storageStatus.collect { statuses += it } }

        val entries = repository.recent.first { it.isNotEmpty() }
        advanceUntilIdle()

        // 数据最终到位：2 次失败 + 1 次成功 = 3 次订阅
        assertEquals(listOf("local:ms-1"), entries.map { it.mediaKey })
        assertEquals(3, dao.recentReadCount)
        // 降级状态对 UI 可观察：至少一次 healthy=false 且记录了 operation/failures
        assertTrue(
            "失败期间必须能观察到 degraded 状态，实际=$statuses",
            statuses.any { !it.healthy && it.operation == "recent" && it.failures > 0 },
        )
        // 恢复后回到 healthy，连续失败计数清零
        assertTrue("恢复后必须回到 healthy，实际=${repository.storageStatus.value}", statuses.last().healthy)
        assertTrue(repository.storageStatus.value.healthy)
        assertEquals(0, repository.storageStatus.value.failures)
    }

    // ---- ⑥ 写失败：toggleFavorite 返回 null 并降级；恢复后返回新状态且 healthy ---

    @Test
    fun writeFailureReturnsNullAndDegradesThenRecovers() = runTest {
        val dao = FakeHistoryDao().apply { addFavoriteError = IOException("模拟：收藏写入失败") }
        val repository = repository(dao)

        assertNull("写失败必须返回 null（调用方据此提示），不能抛异常", repository.toggleFavorite(item()))
        val degraded = repository.storageStatus.value
        assertFalse(degraded.healthy)
        assertEquals("toggleFavorite", degraded.operation)
        assertTrue(degraded.failures > 0)
        assertTrue("失败的写入不得留下半成品", dao.favorites.isEmpty())

        dao.addFavoriteError = null
        assertEquals(true, repository.toggleFavorite(item()))
        assertTrue("local:ms-1" in dao.favorites)
        assertTrue(repository.storageStatus.value.healthy)
        assertEquals(0, repository.storageStatus.value.failures)

        // 恢复后状态可继续翻转（没有把库判死）
        assertEquals(false, repository.toggleFavorite(item()))
        assertFalse("local:ms-1" in dao.favorites)
    }

    // ---- ⑦ 结构性错误：不重试、不挂住、不崩，且不会永久判死 -------------------

    @Test
    fun structuralErrorEndsFlowWithoutRetryAndRecoversAfterwards() = runTest {
        val dao = FakeHistoryDao().apply {
            isFavoriteError = IllegalStateException("模拟：收藏表结构损坏")
        }
        val repository = repository(dao)
        val key = "local:ms-1"

        // 结构错误不可重试：collect 必须自行结束（若实现误当可重试，虚拟时间 5s 后
        // withTimeout 会超时失败，而不是无限重试挂住）
        withTimeout(5_000L) {
            repository.isFavorite(key).collect { /* 无成功值可断言，只验证流会结束 */ }
        }
        val degraded = repository.storageStatus.value
        assertFalse(degraded.healthy)
        assertEquals("isFavorite", degraded.operation)
        assertEquals("结构性错误只允许尝试一次，不得重试", 1, degraded.failures)

        // 再推进大量虚拟时间也不会冒出重试任务
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, repository.storageStatus.value.failures)

        // 结构恢复后读写正常：失败状态不是永久的
        dao.isFavoriteError = null
        dao.history["local:ms-1"] = historyRow("local:ms-1")
        assertFalse(repository.isFavorite(key).first())
        assertTrue(repository.storageStatus.value.healthy)
        assertEquals(0, repository.storageStatus.value.failures)
    }

    // ---- 辅助 --------------------------------------------------------------

    private fun TestScope.repository(
        dao: HistoryDao,
        retryDelays: List<Long> = listOf(1_000L, 2_000L),
    ): HistoryRepository = HistoryRepository(
        dao = dao,
        // 与生产同寿的共享 scope 放进 backgroundScope，由 runTest 在结束时统一取消，
        // 避免 stateIn 的共享协程被判定为泄漏。
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
