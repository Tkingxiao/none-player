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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

/**
 * 后台播放服务——media3 MediaSessionService。
 *
 * 播放器实例驻留本服务：UI 退到后台（甚至销毁）后音频继续，
 * 系统媒体通知自动提供播放/暂停/进度控制（锁屏、通知中心一致）。
 * ExoVideoPlayer 通过 MediaController 与本会话通信。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // E21 兜底链路：解码器失败自动换下一个（软解/低档位）；倍速经 AudioTrack 原生处理
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setEnableAudioTrackPlaybackParams(true)
        val player = ExoPlayer.Builder(this, renderersFactory)
            // 音频焦点：与其他 App（语音、音乐）抢占式协调；拔耳机自动暂停
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val session = MediaSession.Builder(this, player)
            // E3：点媒体通知回应用；具体媒体装载后更新为其深链（见下方监听）
            .setSessionActivity(launcherPendingIntent())
            .setCallback(ScrubAwareCallback(player))
            .build()
        mediaSession = session
        // 通知/锁屏的点击落点跟随当前媒体：noneplayer://player?uri=… 经 E2 深链回播放页
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                session.setSessionActivity(
                    if (uri.isNullOrEmpty()) launcherPendingIntent() else deepLinkPendingIntent(uri),
                )
            }
        })
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
        // 没有在播的内容时，任务被移除即停止服务（不留僵尸通知）
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
