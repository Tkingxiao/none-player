package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏占用判据：**未播放态（只装载不起播）不占右栏**。
 *
 * 2026-10-15 用户实报"竖屏未播放状态切换横屏会直接打开右侧栏"——判据此前只看
 * "有没有当前项"，而竖屏播放页的未播放态同样有当前项。反面同样要钉住：播放过
 * 再暂停/播完的会话必须继续占右栏（右栏的常驻语义，E101）。
 */
class PaneOccupancyTest {
    private fun item(id: String) =
        MediaItem(id = id, sourceId = "local", title = id, uri = "file:///$id.mkv")

    @Test
    fun `loaded but never played session does not occupy the pane`() {
        val queue = listOf(item("a"), item("b"))
        assertFalse(isPaneOccupied(queue, 0, playbackStarted = false))
    }

    @Test
    fun `playing or paused session keeps occupying the pane`() {
        val queue = listOf(item("a"))
        assertTrue(isPaneOccupied(queue, 0, playbackStarted = true))
    }

    @Test
    fun `empty or out of range session never occupies`() {
        assertFalse(isPaneOccupied(emptyList(), -1, playbackStarted = true))
        assertFalse(isPaneOccupied(listOf(item("a")), 1, playbackStarted = true))
    }
}
