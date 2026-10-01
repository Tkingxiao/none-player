package com.noneplayer.plugins.player.exo

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** 擦洗命令：UI 拖动进度条时切换 seek 精度（会话侧 API 不在 MediaController 暴露） */
internal const val SCRUB_COMMAND = "np.scrub"
internal const val SCRUB_ARG_ACTIVE = "active"

/** 会话通知点击落点的 PendingIntent 请求码 */
private const val REQUEST_LAUNCH = 100
private const val REQUEST_DEEPLINK = 101

/** L2 预载目标（秒）：下一集预缓冲 30s，下下集只预解析 5s（§11.2 L2） */
private const val PRELOAD_NEXT_MS = 30
private const val PRELOAD_NEXT_NEXT_MS = 5

/** 喂给预载管理器的窗口：当前项前后各若干项（见 syncPreloadQueue 注释） */
private const val PRELOAD_WINDOW_BACK = 1
private const val PRELOAD_WINDOW_FORWARD = 3

/**
 * 后台播放服务——media3 MediaSessionService。
 *
 * 播放器实例驻留本服务：UI 退到后台（甚至销毁）后音频继续，
 * 系统媒体通知自动提供播放/暂停/进度控制（锁屏、通知中心一致）。
 * ExoVideoPlayer 通过 MediaController 与本会话通信。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * L2（§11.2）下一集预载管理器。与 [mediaSession] 同在服务作用域，
     * 会话寿命 = 播放器寿命，退页不释放（L0 的另一半）。
     */
    @OptIn(UnstableApi::class)
    private var preloadManager: DefaultPreloadManager? = null

    /**
     * 预载优先级换算用的"当前播放下标"。
     *
     * `getTargetPreloadStatus` 只收到队列下标（rankingData），拿不到"当前在播哪个"，
     * 因此必须自己记住它再算距离（见 [targetPreloadStatusControl]）。
     */
    @Volatile
    private var currentPlayingIndex = 0

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // E21 兜底链路：解码器失败自动换下一个（软解/低档位）；倍速经 AudioTrack 原生处理
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setEnableAudioTrackPlaybackParams(true)
        val loadControl = buildLoadControl()
        // L1/L2（§11.2）：预载管理器与播放器必须出自**同一个 builder**——
        // `buildExoPlayer` 会把同一个 MediaSource.Factory / RenderersFactory / LoadControl
        // 灌给 ExoPlayer，预载好的源才能被播放器直接接上（§11.2 明确要求"必须同 builder 出"）。
        val preloadManagerBuilder = DefaultPreloadManager.Builder(this, targetPreloadStatusControl())
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
        val player = preloadManagerBuilder.buildExoPlayer(
            ExoPlayer.Builder(this, renderersFactory)
                // 音频焦点：与其他 App（语音、音乐）抢占式协调；拔耳机自动暂停
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true),
        )
        val session = MediaSession.Builder(this, player)
            // E3：点媒体通知回应用；具体媒体装载后更新为其深链（见下方监听）
            .setSessionActivity(launcherPendingIntent())
            .setCallback(ScrubAwareCallback(player))
            .build()
        mediaSession = session
        preloadManager = preloadManagerBuilder.build()
        // 通知/锁屏的点击落点跟随当前媒体：noneplayer://player?uri=… 经 E2 深链回播放页
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                session.setSessionActivity(
                    if (uri.isNullOrEmpty()) launcherPendingIntent() else deepLinkPendingIntent(uri),
                )
                // 反哺预载优先级：当前项前移后，下一集的预载目标随之改变（§11.2 L2）
                runCatching { syncPreloadQueue(player) }
            }

            /** 队列整体被替换（setMediaItems）时也要重新喂预载 */
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                runCatching { syncPreloadQueue(player) }
            }
        })
    }

    /**
     * 把当前队列喂给预载管理器，并同步"正在播的下标"（§11.2 L2）。
     *
     * 只喂当前项附近的一个窗口（前 1、后 3）：本地播放下把整个大文件夹（可能 80+ 项）
     * 全部注册进预载只会白白创建一堆 MediaSource 并干扰优先级排序，
     * 而 [targetPreloadStatusControl] 本身也只对距离 ≤ 2 的项给出非零目标。
     */
    @OptIn(UnstableApi::class)
    private fun syncPreloadQueue(player: ExoPlayer) {
        val manager = preloadManager ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        currentPlayingIndex = current
        val from = (current - PRELOAD_WINDOW_BACK).coerceAtLeast(0)
        val to = (current + PRELOAD_WINDOW_FORWARD).coerceAtMost(count - 1)
        if (to < from) return
        val items = (from..to).map { player.getMediaItemAt(it) }
        val ranking = (from..to).toList()
        manager.addMediaItems(items, ranking)
        manager.setCurrentPlayingIndex(current)
    }

    /**
     * L1（§11.2）：LoadControl 调参——本地大文件为主，默认
     * `minBufferMs = maxBufferMs = 50s` / `bufferForPlaybackMs = 2500` 偏保守。
     *
     * 起点值按 §11.2 表：起播缓冲门槛 2.5s → 1s、回缓冲 5s → 2s，
     * 并开 `prioritizeTimeOverSizeThresholds`（大文件下按时长而非字节数判充分）。
     */
    @OptIn(UnstableApi::class)
    private fun buildLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 60_000,
            /* bufferForPlaybackMs = */ 1_000,
            /* bufferForPlaybackAfterRebufferMs = */ 2_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(30_000, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * L2（§11.2）：下一集预解析/预缓冲的目标状态。
     *
     * ⚠️ 参数是**队列下标**（`rankingData`），不是"距离"——`DefaultPreloadManager.Builder`
     * 的单参构造默认用 `SimpleRankingDataComparator`，它把喂进去的 `Int` 当"媒体项在队列中的
     * 位置"来排序，而 `getTargetPreloadStatus` 收到的就是这个 `Int` 本身。
     * 距离必须自己拿"当前下标"换算（见 [currentPlayingIndex]）。
     *
     * 只用公开的工厂方法构造 [DefaultPreloadManager.PreloadStatus]——它的构造函数是 private。
     */
    @OptIn(UnstableApi::class)
    private fun targetPreloadStatusControl(): TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> =
        TargetPreloadStatusControl { index ->
            when (kotlin.math.abs(index - currentPlayingIndex)) {
                // 正在播的那一项不需要预载
                0 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                // 下一集：预解析 + 预载 30s，连播/选集点击直接出帧
                1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_NEXT_MS * 1_000L)
                // 下下集：只预解析 + 5s，避免与当前集抢 I/O
                2 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_NEXT_NEXT_MS * 1_000L)
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }

    /** 应用启动入口的 PendingIntent（无媒体时的通知点击落点） */
    private fun launcherPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        return PendingIntent.getActivity(
            this,
            REQUEST_LAUNCH,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 当前媒体的深链 PendingIntent：noneplayer://player?uri=…（E2 已注册 scheme）。
     * 用 launcher intent 作为底（Component 指向本应用入口），不直接依赖 app 模块的类。
     */
    private fun deepLinkPendingIntent(uri: String): PendingIntent {
        val base = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val intent = Intent(base).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("noneplayer://player")
                .buildUpon()
                .appendQueryParameter("uri", uri)
                .build()
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_DEEPLINK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
    }

    /**
     * 会话回调：放行 np.scrub 自定义命令，并在收到时切换 ExoPlayer 的擦洗模式。
     *
     * 1.8+ 官方擦洗模式（CLOSEST_SYNC 就近定位 + 关闭开销大的后处理），
     * 松手恢复常规精确 seek。MediaController 无法直接调用 setScrubbingModeEnabled，
     * 必须经会话侧转发。
     */
    private class ScrubAwareCallback(
        private val player: ExoPlayer,
    ) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(SCRUB_COMMAND, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SCRUB_COMMAND) {
                player.setScrubbingModeEnabled(args.getBoolean(SCRUB_ARG_ACTIVE))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // E60 修复：没有媒体可恢复时，任务被移除即停止服务（不留僵尸通知）。
        // 此前多算了 `!player.playWhenReady`，把"用户主动暂停"误判为"无内容"——
        // 暂停态划掉最近任务会直接终止会话，通知/断点续播随之消失，
        // 与 nPlayer/MX Player/系统音乐 App 的标准行为（暂停可从通知恢复）相悖。
        if (player == null || player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // L2：预载管理器持有自己的 MediaSource/解码资源，必须先于播放器释放
        runCatching { preloadManager?.release() }
        preloadManager = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
