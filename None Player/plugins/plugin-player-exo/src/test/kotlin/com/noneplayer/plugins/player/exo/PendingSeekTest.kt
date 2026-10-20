package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-11：挂起 seek 的媒体身份与消费/清除判据。
 *
 * 钉的是三件事：① seek 只对"同一条媒体"生效（切集不许把旧媒体的目标位置带过去）；
 * ② 同一媒体上后写覆盖先写（用户最后一次拖动才是意图）；③ 轮询只在"身份一致且
 * 位置已收敛"时才清——只判位置会在切集后永远清不掉，只判身份会在 seek 未落实时
 * 就清掉。
 */
class PendingSeekTest {

    // ---- mediaKeyOf：必须与会话侧 PlaybackProgress.keyOf 逐字一致 ----

    @Test
    fun mediaKeyOfKeepsBlankSourceIdVerbatim() {
        // 深链/路由临时条目 sourceId 为空：会话侧算 ":id"，引擎侧也必须算 ":id"。
        // 任何 "local" 回退都会让两侧身份永不相等、进度持久化被静默跳过（REV-04）。
        assertEquals(":id1", mediaKeyOf("", "id1"))
        assertEquals("local:id1", mediaKeyOf("local", "id1"))
        assertEquals("source:media", mediaKeyOf("source", "media"))
    }

    @Test
    fun mediaKeyOfIsNullWithoutMediaId() {
        assertNull(mediaKeyOf("source", ""))
        assertNull(mediaKeyOf("source", "   "))
        assertNull(mediaKeyOf("", ""))
    }

    // ---- nextPendingSeek：后写覆盖先写 ----

    @Test
    fun nextPendingSeekBumpsGenerationAndOverwritesSameMedia() {
        val first = nextPendingSeek(null, 10_000L, "s:1")
        assertEquals(10_000L, first.positionMs)
        assertEquals("s:1", first.mediaKey)
        assertEquals(1L, first.generation)

        val second = nextPendingSeek(first, 20_000L, "s:1")
        assertEquals(20_000L, second.positionMs)
        assertEquals(2L, second.generation)
    }

    @Test
    fun nextPendingSeekClampsNegativePosition() {
        assertEquals(0L, nextPendingSeek(null, -500L, "s:1").positionMs)
    }

    // ---- resolveStartPosition：装载落地时的起播位置 ----

    @Test
    fun resolveStartPositionUsesPendingOnlyForSameMedia() {
        val pending = PendingSeek(positionMs = 30 * 60_000L, mediaKey = "s:1", generation = 1L)
        assertEquals(30 * 60_000L, resolveStartPosition(pending, targetMediaKey = "s:1", fallbackMs = 0L))
        // 切到另一条媒体：旧媒体的 seek 目标不许落进新媒体
        assertEquals(5_000L, resolveStartPosition(pending, targetMediaKey = "s:2", fallbackMs = 5_000L))
        // 没有身份的装载（单媒体 prepare）：身份 null==null，按旧行为采信
        assertEquals(1_000L, resolveStartPosition(PendingSeek(1_000L, null, 1L), null, 0L))
    }

    @Test
    fun resolveStartPositionFallsBackWhenPendingIsAbsentOrZero() {
        assertEquals(7_000L, resolveStartPosition(null, "s:1", 7_000L))
        // 目标 0 等于"从头播"，不能覆盖调用方给的断点续播位置
        assertEquals(7_000L, resolveStartPosition(PendingSeek(0L, "s:1", 1L), "s:1", 7_000L))
        // fallback 负数兜底到 0
        assertEquals(0L, resolveStartPosition(null, "s:1", -1L))
    }

    // ---- shouldConsumePendingSeek：这次装载是否吃掉了挂起 seek ----

    @Test
    fun shouldConsumePendingSeekOnlyWhenMediaMatches() {
        val pending = PendingSeek(1_000L, "s:1", 1L)
        assertTrue(shouldConsumePendingSeek(pending, "s:1"))
        assertFalse(shouldConsumePendingSeek(pending, "s:2"))
        assertFalse(shouldConsumePendingSeek(null, "s:1"))
    }

    // ---- shouldClearPendingSeekAfterPoll：轮询清空判据 ----

    @Test
    fun shouldClearPendingSeekAfterPollRequiresIdentityAndSettledPosition() {
        val pending = PendingSeek(60_000L, "s:1", 1L)
        assertTrue(shouldClearPendingSeekAfterPoll(pending, "s:1", 60_000L, 1_500L))
        assertTrue(shouldClearPendingSeekAfterPoll(pending, "s:1", 58_600L, 1_500L)) // 边界内
        assertTrue(shouldClearPendingSeekAfterPoll(pending, "s:1", 61_500L, 1_500L)) // 恰好容差
        assertFalse(shouldClearPendingSeekAfterPoll(pending, "s:1", 50_000L, 1_500L)) // 还没到
        // 身份不一致：旧媒体/新媒体的位置都不算"seek 已落实"（旧实现清不掉的根因）
        assertFalse(shouldClearPendingSeekAfterPoll(pending, "s:2", 60_000L, 1_500L))
        assertFalse(shouldClearPendingSeekAfterPoll(pending, null, 60_000L, 1_500L))
        assertFalse(shouldClearPendingSeekAfterPoll(null, "s:1", 60_000L, 1_500L))
    }

    @Test
    fun shouldClearPendingSeekAfterPollHandlesNullIdentityPair() {
        // 单媒体路径（无身份）仍可清理：null == null 视为同一条媒体
        val pending = PendingSeek(10_000L, null, 1L)
        assertTrue(shouldClearPendingSeekAfterPoll(pending, null, 10_200L, 1_500L))
        // 位置为负时按 0 处理，不会因控制器异常值误清
        assertFalse(shouldClearPendingSeekAfterPoll(PendingSeek(10_000L, null, 1L), null, -1L, 1_500L))
    }
}
