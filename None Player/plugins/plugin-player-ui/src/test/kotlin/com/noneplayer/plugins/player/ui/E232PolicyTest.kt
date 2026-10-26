package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E232 三条设备面策略的纯函数钉桩：
 * ① 全屏方向按视频比例选择（竖屏视频进竖屏全屏）；
 * ② 锁定徽标随窗口短边缩放（平板/大屏不再固定 32dp）；
 * ③ 删除事件只命中"当前正在播的那一条"（空 id/uri 不参与匹配）。
 */
class E232PolicyTest {

    // ① 全屏方向

    @Test
    fun `portrait video enters portrait fullscreen`() {
        assertEquals(
            FullscreenOrientation.PORTRAIT,
            resolveFullscreenOrientation(videoAspectRatio = 9f / 16f, width = 0, height = 0),
        )
    }

    @Test
    fun `landscape video enters landscape fullscreen`() {
        assertEquals(
            FullscreenOrientation.LANDSCAPE,
            resolveFullscreenOrientation(videoAspectRatio = 16f / 9f, width = 0, height = 0),
        )
    }

    @Test
    fun `ratio falls back to item pixels when player has not measured yet`() {
        assertEquals(
            FullscreenOrientation.PORTRAIT,
            resolveFullscreenOrientation(videoAspectRatio = 0f, width = 1080, height = 1920),
        )
        assertEquals(
            FullscreenOrientation.LANDSCAPE,
            resolveFullscreenOrientation(videoAspectRatio = Float.NaN, width = 1920, height = 1080),
        )
    }

    @Test
    fun `unknown direction defaults to landscape`() {
        assertEquals(
            FullscreenOrientation.LANDSCAPE,
            resolveFullscreenOrientation(videoAspectRatio = 0f, width = 0, height = 0),
        )
        assertEquals(
            FullscreenOrientation.LANDSCAPE,
            resolveFullscreenOrientation(videoAspectRatio = 1f, width = 0, height = 0),
        )
    }

    // ② 锁定徽标缩放

    @Test
    fun `lock badge keeps phone size at reference width`() {
        assertEquals(1f, lockBadgeScale(shortestWindowDp = 360), 0.001f)
        assertEquals(1f, lockBadgeScale(shortestWindowDp = 320), 0.001f)
        assertEquals(1f, lockBadgeScale(shortestWindowDp = 0), 0.001f)
    }

    @Test
    fun `lock badge grows on tablets and caps at 18x`() {
        assertEquals(600f / 360f, lockBadgeScale(shortestWindowDp = 600), 0.001f)
        assertEquals(1.8f, lockBadgeScale(shortestWindowDp = 800), 0.001f)
        assertEquals(1.8f, lockBadgeScale(shortestWindowDp = 1440), 0.001f)
    }

    // ③ 删除命中当前项

    private fun item(id: String, uri: String) = MediaItem(
        id = id,
        sourceId = "local",
        title = "x.mp4",
        uri = uri,
    )

    @Test
    fun `deleted id or uri stops the matching current item`() {
        val current = item("ms-1", "content://media/external/video/media/1")
        assertTrue(isPlayingItemDeleted(current, deletedMediaId = "ms-1", deletedUri = ""))
        assertTrue(
            isPlayingItemDeleted(
                current,
                deletedMediaId = "",
                deletedUri = "content://media/external/video/media/1",
            ),
        )
        assertTrue(
            isPlayingItemDeleted(
                current,
                deletedMediaId = "ms-1",
                deletedUri = "content://media/external/video/media/1",
            ),
        )
    }

    @Test
    fun `deleting other media or empty identities does not stop playback`() {
        val current = item("ms-1", "content://media/external/video/media/1")
        assertFalse(isPlayingItemDeleted(current, deletedMediaId = "ms-2", deletedUri = ""))
        assertFalse(isPlayingItemDeleted(current, deletedMediaId = "", deletedUri = ""))
        assertFalse(
            isPlayingItemDeleted(
                item("", ""),
                deletedMediaId = "",
                deletedUri = "",
            ),
        )
        assertFalse(isPlayingItemDeleted(null, deletedMediaId = "ms-1", deletedUri = ""))
    }
}
