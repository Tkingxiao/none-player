package com.noneplayer.kernel

import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerEngineProvider
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.VideoPlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E88 留尾的守护测试：无引擎时的错误信息必须携带格式名，
 * `PlaybackSession.acquire` 捕获它后写入 `engineError` 供 UI 渲染体面失败面板。
 */
class VideoPlayerFactoryTest {

    /** `context` 由 UI 层解释（Android 上是 Context），纯 JVM 测试里任意对象即可 */
    private val ctx = Object()

    private class StubEngine(private val accepted: (String) -> Boolean) : PlayerEngineProvider {
        override val engineId = "stub"
        override val priority = 0
        var created = 0
            private set
        override fun create(context: Any): VideoPlayer {
            created++
            return StubPlayer
        }

        override fun supports(mimeType: String) = accepted(mimeType)
    }

    private object StubPlayer : VideoPlayer {
        override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override fun prepare(uri: String, mimeType: String, title: String, artworkUri: String?) {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
    }

    @Test
    fun `默认 StateFlow getter 必须稳定复用同一个实例`() {
        assertSame(StubPlayer.bufferedPositionMs, StubPlayer.bufferedPositionMs)
        assertSame(StubPlayer.videoAspectRatio, StubPlayer.videoAspectRatio)
        assertSame(StubPlayer.speed, StubPlayer.speed)
        assertSame(StubPlayer.cues, StubPlayer.cues)
        assertSame(StubPlayer.connection, StubPlayer.connection)
    }

    @Test
    fun `无任何引擎时抛出携带格式名的错误`() {
        val factory = VideoPlayerFactory(DefaultExtensionRegistry())
        val e = assertThrows(IllegalStateException::class.java) {
            factory.create(ctx, "video/x-fake")
        }
        assertTrue("错误信息应包含格式名，实际：${e.message}", e.message!!.contains("video/x-fake"))
    }

    @Test
    fun `注册的引擎不支持该格式时不创建引擎`() {
        val registry = DefaultExtensionRegistry()
        val engine = StubEngine { it == "video/mp4" }
        registry.register(PlayerEngineProvider::class.java, engine)
        val factory = VideoPlayerFactory(registry)

        assertThrows(IllegalStateException::class.java) {
            factory.create(ctx, "audio/x-fake")
        }
        assertEquals("不支持格式的引擎不应被创建", 0, engine.created)
    }

    @Test
    fun `支持该格式的引擎被选中并创建`() {
        val registry = DefaultExtensionRegistry()
        val engine = StubEngine { it == "video/mp4" }
        registry.register(PlayerEngineProvider::class.java, engine)
        val factory = VideoPlayerFactory(registry)

        val player = factory.create(ctx, "video/mp4")
        assertEquals(StubPlayer, player)
        assertEquals(1, engine.created)
    }
}
