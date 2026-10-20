package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 验收：引擎换代顺序（REV-11 相关）。
 *
 * 契约：不同 mime 需要换引擎时，必须**先创建新引擎、再释放旧引擎**——否则旧引擎
 * release 后、新引擎 create 前的窗口里 `player` 为 null，进度持久化/连接状态会丢；
 * 同一引擎能接的容器（mp4/mkv）换集时不得重建引擎。
 */
internal class R1EngineLifecycleTest : SessionRobolectricTest() {

    @Test
    fun `不同 mime 换代时新引擎 create 先于旧引擎 release`() {
        val mp4 = testItem("mp4", mimeType = "video/mp4")
        val avi = testItem("avi", mimeType = "video/avi")

        session.open(mp4, listOf(mp4))
        assertEquals("前置条件：mp4 应使用 exo 引擎", 1, exoProvider.createCount)
        val exoPlayer = fakePlayer
        assertEquals("exo", exoPlayer.engineId)

        session.open(avi, listOf(avi))
        assertEquals("前置条件：avi 应换到 ffmpeg 引擎", 1, ffmpegProvider.createCount)

        val createIndex = engineLog.indexOf("create:ffmpeg")
        val releaseIndex = engineLog.indexOf("release:exo")
        assertTrue(
            "引擎换代必须同时出现 create:ffmpeg 与 release:exo，实际=$engineLog",
            createIndex >= 0 && releaseIndex >= 0,
        )
        assertTrue(
            "引擎换代必须新引擎 create 先于旧引擎 release（否则存在 player 为 null 的窗口），实际=$engineLog",
            createIndex < releaseIndex,
        )
        assertEquals("旧引擎应被 release 一次", 1, exoPlayer.releaseCount)
    }

    @Test
    fun `同 mime 换集复用同一引擎不重建`() {
        val a = testItem("a", mimeType = "video/mp4")
        val b = testItem("b", mimeType = "video/mp4")

        session.open(a, listOf(a))
        val first = fakePlayer
        session.open(b, listOf(b))

        assertSame("同 mime 换集必须复用同一引擎实例", first, session.player)
        assertEquals("同 mime 换集不得重建引擎", 1, exoProvider.createCount)
        assertEquals("同 mime 换集不得 release 旧引擎", 0, first.releaseCount)
        assertEquals("复用引擎应按序收到两次 setQueue", 2, first.setQueueCalls().size)
    }

    @Test
    fun `同一引擎支持的不同容器换集不重建`() {
        val mp4 = testItem("mp4", mimeType = "video/mp4")
        val mkv = testItem("mkv", mimeType = "video/x-matroska")

        session.open(mp4, listOf(mp4))
        val first = fakePlayer
        session.open(mkv, listOf(mkv))

        assertSame("mp4→mkv 由同一引擎承接，必须复用实例", first, session.player)
        assertEquals("同一引擎能接的容器不得重建", 1, exoProvider.createCount)
        assertEquals("同一引擎能接的容器不得 release", 0, first.releaseCount)
    }
}
