package com.noneplayer.plugins.player.exo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * E245：把 media3 的视频计数器接成平台/NextLib 路径的读数线。
 *
 * 两个事实决定了实现形态（真机实测，不是猜的）：
 * - `onVideoFrameProcessingOffset` 这台机器上几乎不回调（只在有迟到帧时触发），
 *   `onVideoDisabled` 要等渲染器停用才来一次——只靠回调会"整段播放一行读数都没有"；
 * - `ExoPlayer.videoDecoderCounters` 是 renderer 实时更新的同一个实例，轮询它才是稳的来源。
 *
 * 所以：AnalyticsListener 只负责"命名/首帧/会话起止"，帧数靠 1s 轮询；
 * 摘要本身仍有 2s 限频（[NpPerf.platformSummary]）。
 *
 * 只在 `log.tag.NpPerf DEBUG` 打开时才挂——默认路径零开销。
 * npff 系（E204 白名单）走自家 LegacyVideoRenderer，那边已有 frames/stages 两行，
 * 这里按解码器名让路，避免同一条会话双份读数。
 */
@OptIn(UnstableApi::class)
internal object NpPlatformAnalytics {

    private val handler = Handler(Looper.getMainLooper())

    fun install(player: ExoPlayer) {
        if (!Log.isLoggable("NpPerf", Log.DEBUG)) return
        val stats = NpPlatformFrameStats()
        var softPathSession = false
        // DecoderCounters 在渲染器生命周期内只增不减，连播切集时也继续累加；
        // 记一份切集基线做差，读数才归当前这一集。
        var baseInputs = 0
        var baseRendered = 0
        var baseDropped = 0
        var baseSkipped = 0
        // 切集不重启渲染器时 onVideoDecoderInitialized 不会再来，名字要自己记住
        var lastDecoderName = "-"
        fun rebase() {
            val counters = player.videoDecoderCounters
            baseInputs = counters?.queuedInputBufferCount ?: 0
            baseRendered = counters?.renderedOutputBufferCount ?: 0
            baseDropped = counters?.droppedBufferCount ?: 0
            baseSkipped = counters?.skippedOutputBufferCount ?: 0
        }
        fun delta(value: Int, base: Int): Long = (value - base).coerceAtLeast(0).toLong()
        val poll = object : Runnable {
            override fun run() {
                if (!softPathSession) {
                    player.videoDecoderCounters?.let { counters ->
                        stats.onCounters(
                            inputs = delta(counters.queuedInputBufferCount, baseInputs),
                            rendered = delta(counters.renderedOutputBufferCount, baseRendered),
                            dropped = delta(counters.droppedBufferCount, baseDropped),
                            skipped = delta(counters.skippedOutputBufferCount, baseSkipped),
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                        NpPerf.platformSummary(stats)
                    }
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        // 连播切集：渲染器不重启用（onVideoEnabled 不再来），会话边界只能靠切集事件划
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                stats.reset()
                stats.onDecoder(lastDecoderName)
                stats.onEnabled(SystemClock.elapsedRealtime())
                rebase()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoEnabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                softPathSession = false
                stats.reset()
                stats.onDecoder(lastDecoderName)
                stats.onEnabled(SystemClock.elapsedRealtime())
                rebase()
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, POLL_INTERVAL_MS)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                softPathSession = isSoftPathDecoder(decoderName)
                if (!softPathSession) {
                    lastDecoderName = decoderName
                    stats.onDecoder(decoderName)
                }
            }

            override fun onVideoDisabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                handler.removeCallbacks(poll)
                if (!softPathSession) {
                    stats.onCounters(
                        inputs = delta(decoderCounters.queuedInputBufferCount, baseInputs),
                        rendered = delta(decoderCounters.renderedOutputBufferCount, baseRendered),
                        dropped = delta(decoderCounters.droppedBufferCount, baseDropped),
                        skipped = delta(decoderCounters.skippedOutputBufferCount, baseSkipped),
                    )
                    NpPerf.platformSummary(stats, force = true)
                }
                stats.reset()
                softPathSession = false
            }
        })
    }

    /** 软解/位图渲染器的解码器名——它们的读数由各自的 frames/stages 行负责。 */
    private fun isSoftPathDecoder(decoderName: String): Boolean =
        decoderName.startsWith("npff-") || decoderName.startsWith("ffmpeg") || decoderName.startsWith("mjpeg")

    private const val POLL_INTERVAL_MS = 1_000L
}
