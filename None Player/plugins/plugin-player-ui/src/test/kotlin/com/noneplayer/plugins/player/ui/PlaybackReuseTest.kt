package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReuseTest {
    private fun item(id: String, uri: String = "file:///episode.mkv") =
        MediaItem(id = id, sourceId = "local", title = id, uri = uri)

    @Test
    fun `failed media must be reloaded even when its uri is unchanged`() {
        val media = item("1")
        assertFalse(canReusePlayback(media.uri, media, media, PlaybackState.Error("unsupported")))
    }

    @Test
    fun `paused session is reused without rewinding`() {
        val media = item("1")
        assertTrue(canReusePlayback(media.uri, media, media, PlaybackState.Paused))
    }

    @Test
    fun `attached pane reuses pending queue during probing`() {
        val media = item("2", "file:///next.mkv")
        assertTrue(canReusePlayback("file:///previous.mkv", media, media, PlaybackState.Buffering))
    }

    @Test
    fun `different tracks sharing a uri are not the same media`() {
        assertFalse(canReusePlayback("file:///episode.mkv", item("1"), item("2"), PlaybackState.Playing))
    }

    @Test
    fun `cleared session cannot reuse stale engine uri`() {
        val media = item("1")
        assertFalse(canReusePlayback(media.uri, null, media, PlaybackState.Paused))
    }
}
