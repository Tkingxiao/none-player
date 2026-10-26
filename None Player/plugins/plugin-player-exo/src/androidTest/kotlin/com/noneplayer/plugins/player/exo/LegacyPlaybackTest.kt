package com.noneplayer.plugins.player.exo

import android.content.Context
import android.graphics.Bitmap
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noneplayer.core.media.extract.NpExtractorsFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器上的出画验收：WMV2 / Qpel 走**软解后端**（`npff`（NextLib 共享 libavcodec）
 * 或 `nplegacy` 静态最小 avcodec，按库可用性路由；E239 起两者都算合规）、
 * Simple Profile 仍是平台 mpeg4、MJPEG 仍是 BitmapFactory。
 * 硬件优先用 EXTENSION_RENDERER_MODE_ON。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LegacyPlaybackTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun legacyRendererFactoryBuildsTwice() {
        OpenFactory(instrumentation.targetContext).buildTwice()
    }

    @Test
    fun wmv2RendersFrames() {
        val played = play("np-wmv2.avi", 320, 240)
        assertSoftwareBackend(played.decoder, "wmv2")
        assertTrue("frames=${played.frames} images=${played.images}", played.frames > 0 && played.images > 0)
        assertTrue("duration=${played.durationMs}", played.durationMs in 2_500..3_500)
    }

    /** E239：软解后端断言——路由可在 npff（NextLib 共享 libavcodec）与 nplegacy 间按可用性切换，两者都算合规。 */
    private fun assertSoftwareBackend(decoder: String, codec: String) {
        assertTrue(
            decoder,
            decoder.contains("npff-$codec") || decoder.contains("nplegacy-$codec"),
        )
    }

    @Test
    fun qpelRendersFramesInSyncWithAudio() {
        val played = play("xvid-qpel.avi", 320, 240)
        assertSoftwareBackend(played.decoder, "mpeg4")
        assertTrue(played.audioMime, played.audioMime == "audio/mpeg")
        assertTrue("frames=${played.frames} images=${played.images}", played.frames > 0 && played.images > 0)
        assertTrue("duration=${played.durationMs}", played.durationMs in 3_500..5_000)
    }

    @Test
    fun xvidClipStaysOnPlatformDecoder() {
        val played = play("xvid-clip.avi", 320, 240)
        assertFalse(played.decoder, played.decoder.contains("nplegacy"))
        assertTrue(played.decoder, played.decoder.contains("mpeg4"))
        assertTrue(played.frames > 0)
    }

    @Test
    fun wmv2PictureSample() {
        val played = play("np-wmv2.avi", 320, 240, saveFrame = true)
        assertSoftwareBackend(played.decoder, "wmv2")
        assertTrue(played.images > 0)
        assertWmv2ColorBars()
    }

    /**
     * 花屏回归闸（端到端）：样本是彩条（六段纯色横贯全高，任意帧一致；参考值来自
     * 离线 ffmpeg 解码）。抽样点落进噪声/错色即失败——extradata 丢失导致的宏块
     * 脱轨会在这里现形。
     */
    private fun assertWmv2ColorBars() {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "wmv2-frame.png")
        // 保存跑在 imageThread 上，play() 返回时可能还没落盘——短轮询等一下
        val deadline = System.currentTimeMillis() + 3_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("未找到抽帧文件 ${file.absolutePath}", file.isFile)
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        assertTrue("抽帧解码失败", bmp != null)
        val pixels = IntArray(bmp!!.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = pixels[y * bmp.width + x]
            return Triple((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }
        fun bright(v: Int) = v > 150
        fun dark(v: Int) = v < 110
        val y = 230
        val red = rgb(10, y)
        val green = rgb(80, y)
        val yellow = rgb(135, y)
        val blue = rgb(185, y)
        val magenta = rgb(235, y)
        val cyan = rgb(290, y)
        assertTrue("红条异常 $red", bright(red.first) && dark(red.second) && dark(red.third))
        assertTrue("绿条异常 $green", bright(green.second) && dark(green.first) && dark(green.third))
        assertTrue("黄条异常 $yellow", bright(yellow.first) && bright(yellow.second) && dark(yellow.third))
        assertTrue("蓝条异常 $blue", bright(blue.third) && dark(blue.first) && dark(blue.second))
        assertTrue("品红条异常 $magenta", bright(magenta.first) && bright(magenta.third) && dark(magenta.second))
        assertTrue("青条异常 $cyan", bright(cyan.second) && bright(cyan.third) && dark(cyan.first))
        bmp.recycle()
    }

    @Test
    fun switchBetweenH264AndWmv2DoesNotFail() {
        runSwitchTest("np-h264.mp4", "np-wmv2.avi", listOf("npff-wmv2", "nplegacy-wmv2"))
    }

    /** 切换回平台流时，末端的 MJPEG 位图渲染器不得留下 Surface 的 CPU 连接（同 WMV2 的类缺陷）。 */
    @Test
    fun switchBetweenH264AndMjpegDoesNotFail() {
        runSwitchTest("np-h264.mp4", "np-mjpeg.avi", listOf("BitmapFactory"))
    }

    /**
     * A（平台流）→ B（软解流）→ 回到 A：三步都不能报错，且 B 必须由 [expectDecoderForB] 出画。
     * 靶子是 Surface 连接：CPU 锁定会以 NATIVE_WINDOW_API_CPU 连上共享 BufferQueue，
     * 不随渲染结束归还的话，下一条平台 MediaCodec 会 `connect: already connected` 起不来。
     */
    private fun runSwitchTest(assetA: String, assetB: String, expectDecoderCandidates: List<String>) {
        val first = copyAsset(assetA)
        val second = copyAsset(assetB)
        val context = instrumentation.targetContext
        val playerThread = HandlerThread("switch-player").apply { start() }
        val handler = Handler(playerThread.looper)
        val reader = ImageReader.newInstance(640, 360, android.graphics.PixelFormat.RGBA_8888, 3)
        val error = AtomicReference<PlaybackException?>(null)
        val decoders = mutableListOf<String>()
        val ready = CountDownLatch(1)
        val playerRef = AtomicReference<ExoPlayer?>(null)
        handler.post {
            val renderers = NpRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
            val player = ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, NpExtractorsFactory()))
                .build()
            playerRef.set(player)
            player.setVideoSurface(reader.surface)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    synchronized(decoders) { decoders.add(decoderName) }
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error.set(playbackError)
                    ready.countDown()
                }
            })
            player.setMediaItems(
                listOf(
                    MediaItem.fromUri(Uri.fromFile(first)),
                    MediaItem.fromUri(Uri.fromFile(second)),
                ),
            )
            player.prepare()
            player.play()
        }
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            assertTrue(error.get()?.message, error.get() == null)
            val switched = CountDownLatch(1)
            handler.post {
                playerRef.get()?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) switched.countDown()
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        error.set(playbackError)
                        switched.countDown()
                    }
                })
                playerRef.get()?.seekToNextMediaItem()
                playerRef.get()?.play()
            }
            assertTrue("switch did not settle ${error.get()?.message}", switched.await(20, TimeUnit.SECONDS))
            val failure = error.get()
            assertTrue("${failure?.errorCodeName} ${failure?.message} decoders=$decoders", failure == null)
            synchronized(decoders) {
                // E239：B 必须由期望的软解/位图渲染器出画——软解后端在 npff 与 nplegacy
                // 之间按库可用性路由，两者都算合规；MJPEG 仍是裸 `BitmapFactoryDecoder`。
                assertTrue(
                    decoders.toString(),
                    decoders.any { decoder ->
                        expectDecoderCandidates.any { decoder.contains(it) }
                    },
                )
            }
            val back = CountDownLatch(1)
            handler.post {
                playerRef.get()?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && playerRef.get()?.currentMediaItemIndex == 0) {
                            back.countDown()
                        }
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        error.set(playbackError)
                        back.countDown()
                    }
                })
                playerRef.get()?.seekTo(0, 0)
                playerRef.get()?.play()
            }
            assertTrue("switch back did not settle ${error.get()?.message}", back.await(20, TimeUnit.SECONDS))
            val backFailure = error.get()
            assertTrue(
                "${backFailure?.errorCodeName} ${backFailure?.message} decoders=$decoders",
                backFailure == null,
            )
        } finally {
            val released = CountDownLatch(1)
            handler.post {
                playerRef.get()?.release()
                reader.close()
                released.countDown()
            }
            released.await(5, TimeUnit.SECONDS)
            playerThread.quitSafely()
        }
    }

    @Test
    fun mjpegStaysOnBitmapRenderer() {
        val played = play("np-mjpeg.avi", 480, 320)
        assertTrue(played.decoder, played.decoder.contains("BitmapFactory"))
        assertFalse(played.decoder.contains("nplegacy"))
        assertTrue(played.frames > 0 && played.images > 0)
    }

    private fun copyAsset(asset: String): File {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        return file
    }

    private fun play(asset: String, width: Int, height: Int, saveFrame: Boolean = false): Played {
        val context = instrumentation.targetContext
        val file = copyAsset(asset)
        val playerThread = HandlerThread("legacy-player").apply { start() }
        val imageThread = HandlerThread("legacy-images").apply { start() }
        val playerHandler = Handler(playerThread.looper)
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        val images = AtomicInteger()
        val saved = AtomicInteger()
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val n = images.incrementAndGet()
                // 先判 n 再 compareAndSet：原顺序会在第 1 帧就把闸门锁死，文件永远不落盘
                if (saveFrame && n >= 1 && saved.compareAndSet(0, 1)) {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val buffer = plane.buffer
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height)
                    val row = ByteArray(rowStride)
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
                        for (x in 0 until width) {
                            val i = x * pixelStride
                            val r = row[i].toInt() and 0xff
                            val g = row[i + 1].toInt() and 0xff
                            val b = row[i + 2].toInt() and 0xff
                            val a = row[i + 3].toInt() and 0xff
                            pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
                    val out = File(instrumentation.targetContext.getExternalFilesDir(null), "wmv2-frame.png")
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
            } finally {
                image.close()
            }
        }, Handler(imageThread.looper))
        val decoder = AtomicReference("")
        val error = AtomicReference<PlaybackException?>(null)
        val ended = CountDownLatch(1)
        val playerRef = AtomicReference<ExoPlayer?>(null)
        playerHandler.post {
            val renderers = NpRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
            val player = ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, NpExtractorsFactory()))
                .build()
            playerRef.set(player)
            player.setVideoSurface(reader.surface)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoder.set(decoderName)
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) ended.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error.set(playbackError)
                    ended.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("playback did not finish: $asset decoder=${decoder.get()}", ended.await(45, TimeUnit.SECONDS))
            val failure = error.get()
            assertTrue(failure?.message ?: failure?.errorCodeName, failure == null)
            val stats = AtomicReference<Played?>(null)
            val statsLatch = CountDownLatch(1)
            playerHandler.post {
                val player = playerRef.get()
                stats.set(
                    Played(
                        decoder = decoder.get(),
                        audioMime = player?.audioFormat?.sampleMimeType,
                        frames = player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0,
                        images = images.get(),
                        durationMs = player?.duration ?: -1L,
                    ),
                )
                statsLatch.countDown()
            }
            assertTrue(statsLatch.await(5, TimeUnit.SECONDS))
            return stats.get() ?: error("missing playback stats")
        } finally {
            val released = CountDownLatch(1)
            playerHandler.post {
                playerRef.get()?.release()
                reader.close()
                released.countDown()
            }
            released.await(5, TimeUnit.SECONDS)
            playerThread.quitSafely()
            imageThread.quitSafely()
        }
    }

    private class Played(
        val decoder: String,
        val audioMime: String?,
        val frames: Int,
        val images: Int,
        val durationMs: Long,
    )
}

@UnstableApi
private class OpenFactory(private val appContext: Context) : NpRenderersFactory(appContext) {
    fun buildTwice() {
        val handler = Handler(Looper.getMainLooper())
        repeat(2) {
            val out = ArrayList<Renderer>()
            buildVideoRenderers(
                appContext,
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                MediaCodecSelector.DEFAULT,
                true,
                handler,
                object : VideoRendererEventListener {},
                0L,
                out,
            )
        }
    }
}
