package com.noneplayer.plugins.player.exo

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.exoplayer.Renderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A19 余项：验证 [NpFfmpegVideoRenderer] 只在 `MSG_SET_VIDEO_OUTPUT` 上做 Surface
 * 克隆，其余消息原样转发；disable 后旧克隆归还，下一次重新克隆。
 */
@RunWith(AndroidJUnit4::class)
class NpFfmpegVideoRendererTest {

    private val sources = mutableListOf<Surface>()
    private val textures = mutableListOf<SurfaceTexture>()
    private val forwarded = mutableListOf<Any?>()
    private val calls = mutableListOf<String>()

    private val delegate: Renderer = Proxy.newProxyInstance(
        Renderer::class.java.classLoader,
        arrayOf(Renderer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "handleMessage" -> {
                calls += method.name
                forwarded += args?.getOrNull(1)
                Unit
            }

            "disable", "reset", "release" -> {
                calls += method.name
                Unit
            }

            "getName" -> "fake-renderer"
            "getTrackType" -> 2
            "getState" -> 0
            "getReadingPositionUs" -> 0L
            "getDurationToProgressUs" -> Renderer.DEFAULT_DURATION_TO_PROGRESS_US
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
    } as Renderer

    @After
    fun tearDown() {
        sources.forEach { it.release() }
        textures.forEach { it.release() }
    }

    private fun newSurface(): Surface {
        val texture = SurfaceTexture(0)
        texture.setDefaultBufferSize(16, 16)
        textures += texture
        return Surface(texture).also { sources += it }
    }

    @Test
    fun clonesEachSourceOnce() {
        val renderer = NpFfmpegVideoRenderer(delegate)
        val source = newSurface()

        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, source)
        val first = forwarded.last() as Surface
        assertNotSame("必须换成克隆 Surface", source, first)

        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, source)
        assertSame("同一 source 必须复用同一克隆", first, forwarded.last())

        val other = newSurface()
        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, other)
        val second = forwarded.last() as Surface
        assertNotSame("换 source 必须换克隆", source, second)
        assertNotSame(first, second)

        renderer.release()
    }

    @Test
    fun disableReleasesCloneSoNextSurfaceIsFresh() {
        val renderer = NpFfmpegVideoRenderer(delegate)
        val source = newSurface()

        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, source)
        val first = forwarded.last() as Surface
        renderer.disable()
        assertTrue("disable 必须转发", "disable" in calls)

        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, source)
        val second = forwarded.last() as Surface
        assertNotSame("disable 归还后必须重建克隆", first, second)

        renderer.release()
    }

    @Test
    fun nullSurfaceIsForwardedAsNull() {
        val renderer = NpFfmpegVideoRenderer(delegate)
        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null)
        assertNull(forwarded.last())
        assertEquals(1, forwarded.size)
        renderer.release()
    }
}
