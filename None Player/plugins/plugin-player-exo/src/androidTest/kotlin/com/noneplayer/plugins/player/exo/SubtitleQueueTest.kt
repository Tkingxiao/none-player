package com.noneplayer.plugins.player.exo

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

class SubtitleTestActivity : Activity()

@RunWith(AndroidJUnit4::class)
class SubtitleQueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var player: ExoVideoPlayer
    private lateinit var observer: MediaController
    private lateinit var video: File
    private lateinit var subtitle: File

    @Before fun setup() = runBlocking {
        instrumentation.context.startActivity(
            Intent().setClass(instrumentation.context, SubtitleTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        delay(500)
        video = File(context.cacheDir, "subtitle-queue.mp4")
        instrumentation.context.assets.open("subtitle-queue.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        subtitle = File(context.cacheDir, "subtitle-queue.srt").apply {
            writeText("1\n00:00:00,000 --> 00:02:50,000\nQUEUE_SUBTITLE_OK\n\n")
        }
        withContext(Dispatchers.Main) {
            player = ExoVideoPlayer(context)
            player.awaitReady()
            val future = MediaController.Builder(
                context, SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync()
            waitUntil { future.isDone }
            observer = future.get()
            player.clearQueue()
        }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            if (::player.isInitialized) {
                player.clearQueue()
                player.release()
            }
            if (::observer.isInitialized) observer.release()
        }
    }

    private fun item(id: String, startUs: Long = 0L, endUs: Long = 0L) = MediaItem(
        id = id, sourceId = "subtitle-test", title = "Title-$id",
        uri = video.toURI().toString(), mimeType = "video/mp4",
        clipStartUs = startUs, clipEndUs = endUs,
    )

    private suspend fun waitUntil(condition: () -> Boolean) {
        waitUntil("condition", condition)
    }

    private suspend fun waitUntil(label: String, condition: () -> Boolean) {
        try {
            withTimeout(20_000L) {
                while (!condition()) delay(100)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val tracks = runCatching { player.tracks.value.joinToString { it.type.name } }.getOrDefault("?")
            val cues = runCatching { player.cues.value.joinToString { it.text.orEmpty() } }.getOrDefault("?")
            throw AssertionError(
                "$label timed out; state=${observer.playbackState} index=${observer.currentMediaItemIndex} " +
                    "id=${observer.currentMediaItem?.mediaId} pos=${observer.currentPosition} " +
                    "playWhenReady=${observer.playWhenReady} cues=[$cues] tracks=[$tracks]",
                timeout,
            )
        }
    }

    private suspend fun ready(label: String) = waitUntil(label) { observer.playbackState == Player.STATE_READY }
    private suspend fun hasCue(label: String) = waitUntil(label) {
        player.cues.value.any { it.text?.contains("QUEUE_SUBTITLE_OK") == true }
    }

    @Test fun lateMountPreservesQueueIdentityTitlePositionAndPause() = runBlocking {
        withContext(Dispatchers.Main) {
            player.setQueue(listOf(item("a"), item("b")), 1, 12_000L)
            ready("late initial ready")
            player.pause()
            waitUntil { !observer.playWhenReady }
            player.setSubtitle(subtitle.toURI().toString())
            ready("late subtitle ready")
            hasCue("late subtitle cue")
            assertEquals(2, observer.mediaItemCount)
            assertEquals(1, observer.currentMediaItemIndex)
            assertEquals("b", observer.currentMediaItem?.mediaId)
            assertEquals("Title-b", observer.currentMediaItem?.mediaMetadata?.title.toString())
            assertTrue(observer.currentPosition in 11_000L..14_000L)
            assertFalse(observer.playWhenReady)
        }
    }

    @Test fun subtitleDuringProbeIsAppliedToTarget() = runBlocking {
        withContext(Dispatchers.Main) {
            player.setQueue(listOf(item("a"), item("b")), 0, 0)
            player.setSubtitle(subtitle.toURI().toString())
            ready("probe subtitle ready")
            hasCue("probe subtitle cue")
            assertEquals("a", observer.currentMediaItem?.mediaId)
            assertTrue(player.tracks.value.any { it.type == com.noneplayer.kernel.api.NpTrackType.TEXT })
        }
    }

    @Test fun switchingSameUriClippedItemsDoesNotLeakSubtitleOrClipping() = runBlocking {
        withContext(Dispatchers.Main) {
            player.setQueue(listOf(item("a", 1_000_000, 40_000_000), item("b", 40_000_000, 80_000_000)), 0, 0)
            ready("clip initial ready")
            player.setSubtitle(subtitle.toURI().toString())
            ready("clip subtitle ready")
            hasCue("clip subtitle cue")
            assertEquals(1_000_000L, observer.currentMediaItem?.clippingConfiguration?.startPositionUs)
            player.selectQueueIndex(1)
            waitUntil("clip switch id") { observer.currentMediaItem?.mediaId == "b" }
            ready("clip switch ready")
            assertEquals(40_000_000L, observer.currentMediaItem?.clippingConfiguration?.startPositionUs)
            assertEquals(80_000_000L, observer.currentMediaItem?.clippingConfiguration?.endPositionUs)
            assertFalse(player.tracks.value.any { it.type == com.noneplayer.kernel.api.NpTrackType.TEXT })
            assertTrue(player.cues.value.isEmpty())
        }
    }

    @Test fun clearQueueClearsDelayedCuesAndRapidLoadKeepsNewestItem() = runBlocking {
        withContext(Dispatchers.Main) {
            player.setQueue(listOf(item("a")), 0, 0)
            ready("clear initial ready")
            player.setSubtitle(subtitle.toURI().toString())
            ready("clear subtitle ready")
            hasCue("clear subtitle cue")
            player.setSubtitleOffsetMs(1000)
            player.clearQueue()
            assertTrue(player.cues.value.isEmpty())
            assertEquals(PlaybackState.Idle, player.state.value)
            player.setQueue(listOf(item("a"), item("b")), 0, 0)
            player.selectQueueIndex(1)
            waitUntil("rapid switch id") { observer.currentMediaItem?.mediaId == "b" }
            ready("rapid switch ready")
            assertFalse(player.tracks.value.any { it.type == com.noneplayer.kernel.api.NpTrackType.TEXT })
            assertTrue(player.cues.value.isEmpty())
        }
    }
}
