package com.noneplayer.plugins.library

import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFramePolicyTest {

    @Test
    fun `只有本地无缓存封面的视频才现场抽帧`() {
        assertTrue(shouldExtractFrame(LOCAL_MEDIA_SOURCE_ID, null, isAudio = false, recentlyFailed = false))
        assertFalse(shouldExtractFrame(LOCAL_MEDIA_SOURCE_ID, null, isAudio = false, recentlyFailed = true))
        assertFalse(shouldExtractFrame(LOCAL_MEDIA_SOURCE_ID, null, isAudio = true, recentlyFailed = false))
        assertFalse(shouldExtractFrame(LOCAL_MEDIA_SOURCE_ID, "file:///cover.jpg", isAudio = false, recentlyFailed = false))
        assertFalse(shouldExtractFrame("remote", null, isAudio = false, recentlyFailed = false))
    }

    @Test
    fun `失败负缓存短 TTL 到期后允许重试`() {
        val memo = CoverFrameFailureMemo
        memo.clearForTest()
        memo.markFailed("file:///v1.mp4", nowMs = 1_000L)

        assertTrue(memo.hasFailed("file:///v1.mp4", nowMs = 1_001L))
        assertTrue(memo.hasFailed("file:///v1.mp4", nowMs = 1_000L + COVER_FRAME_FAILURE_TTL_MS))
        assertFalse(memo.hasFailed("file:///v1.mp4", nowMs = 1_000L + COVER_FRAME_FAILURE_TTL_MS + 1))
        assertFalse(memo.hasFailed("file:///v2.mp4", nowMs = 1_001L))

        memo.clearForTest()
    }

    @Test
    fun `失败负缓存有 LRU 上限`() {
        val memo = CoverFrameFailureMemo
        memo.clearForTest()
        repeat(COVER_FRAME_FAILURE_MAX_ENTRIES + 20) { i ->
            memo.markFailed("file:///v$i.mp4", nowMs = 1_000L + i)
        }

        assertTrue(memo.sizeForTest() <= COVER_FRAME_FAILURE_MAX_ENTRIES)
        // 最旧的条目已被淘汰。
        assertFalse(memo.hasFailed("file:///v0.mp4", nowMs = 1_001L))

        memo.clearForTest()
    }
}
