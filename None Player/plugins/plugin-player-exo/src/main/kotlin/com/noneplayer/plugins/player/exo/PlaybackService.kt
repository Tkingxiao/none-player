package com.noneplayer.plugins.player.exo

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.MediaSourceFactorySupplier
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.noneplayer.core.data.AppSettings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.noneplayer.core.media.extract.NpExtractorsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 本文件的日志标签（服务基类自带一个私有的 `TAG`，不复用它以免撞名） */
private const val SERVICE_LOG_TAG = "PlaybackService"

// np.scrub / np.crop / np.autoplay 三条私有命令的常量与身份/参数校验统一放在
// PrivateCommands.kt（REV-07：只有同 UID 控制器才被授予，且 onCustomCommand 复核）。

/** 会话通知点击落点的 PendingIntent 请求码 */
private const val REQUEST_LAUNCH = 100
private const val REQUEST_DEEPLINK = 101

/** L2 预载目标（秒）：下一集预缓冲 30s，下下集只预解析 5s（§11.2 L2） */
private const val PRELOAD_NEXT_MS = 30
private const val PRELOAD_NEXT_NEXT_MS = 5

/** 喂给预载管理器的窗口：当前项前后各若干项（见 syncPreloadQueue 注释） */
private const val PRELOAD_WINDOW_BACK = 1
private const val PRELOAD_WINDOW_FORWARD = 3

/** 读「硬件解码优先」偏好的超时上限：读不到就用默认档，不让服务卡在启动上 */
private const val HARDWARE_DECODE_READ_TIMEOUT_MS = 1_500L

/**
 * 后台播放服务——media3 MediaSessionService。
 *
 * 播放器实例驻留本服务：UI 退到后台（甚至销毁）后音频继续，
 * 系统媒体通知自动提供播放/暂停/进度控制（锁屏、通知中心一致）。
 * ExoVideoPlayer 通过 MediaController 与本会话通信。
 *
 * media3 的预载/会话连接 API 仍处于 @UnstableApi 面：成员级 @OptIn 对属性类型
 * 引用不生效（lint 只认外层声明链），故在类与嵌套回调类上统一 opt-in。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * L2（§11.2）下一集预载管理器。与 [mediaSession] 同在服务作用域，
     * 会话寿命 = 播放器寿命，退页不释放（L0 的另一半）。
     */
    private var preloadManager: DefaultPreloadManager? = null

    /**
     * 预载优先级换算用的"当前播放下标"。
     *
     * `getTargetPreloadStatus` 只收到队列下标（rankingData），拿不到"当前在播哪个"，
     * 因此必须自己记住它再算距离（见 [targetPreloadStatusControl]）。
     */
    @Volatile
    private var currentPlayingIndex = 0

    /**
     * 已经注册进 [preloadManager] 的窗口（按队列顺序）。
     *
     * 用于把 [syncPreloadQueue] 变成**增量**同步——见该方法内注释。
     */
    private var preloadedWindow: List<MediaItem> = emptyList()

    /** 服务侧直建 ExoPlayer（含预载/通知链）也必须接住运行期视频解码失败。 */
    private val serviceDecoderFallbackGate = DecoderFallbackGate()
    private var serviceDecoderMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        // E21 兜底链路：解码器失败自动换下一个（软解/低档位）；倍速经 AudioTrack 原生处理。
        // NpRenderersFactory = 平台硬解 + NextLib 的 FFmpeg 软解 + AVI MJPEG 位图软解
        // （三段来源与顺序见该类注释）。
        // L2：「硬件解码优先」设置的真实接线（此前是假开关——工厂恒定 ON，不看设置）。
        // 渲染器集合在 ExoPlayer **构建期**定型（setExtensionRendererMode 是工厂属性，
        // MediaController 运行期改不了），所以建播放器之前读一次偏好；服务随播放会话建立，
        // 所以切换开关在下一个播放器实例（下一次播放会话）上生效——设置行的副题如实标注。
        // 读取超时/失败退回默认（硬解优先），不让设置层的问题拖垮服务启动。
        //
        // A10：兼容档不再把全局模式翻成 PREFER（那会让 H.264/HEVC 有硬解也先走软解）。
        // 扩展渲染器模式恒为 ON，兼容档由工厂内的**逐轨选择器**表达：平台有硬解就硬解，
        // 只有平台软解时才把位置让给 FFmpeg / MJPEG / legacy。
        // **不能映射成 OFF**——那会把扩展渲染器整条摘掉，AVI/MJPEG 之外的一票旧编码直接变
        // "无法播放"，与设置行承诺的"兼容性更好"正好相反。
        // 优先取启动时预热好的进程内缓存——服务创建落在"点播放"的关键路径上，
        // 没有缓存才退回有界等待（首次读盘）。两条路都拿不到就用默认值。
        // A23 服务异常兜底（REV-07 同批）：首读可能抛任何异常（DataStore 文件损坏/IO、
        // 协程取消等），这里统一 runCatching 兜住，读不到就用默认档（硬解优先），
        // 绝不让"设置层首读"把整个播放服务拖死在 onCreate 里。
        val hardwareFirst = runCatching {
            AppSettings.cachedHardwareDecodePreferred()
                ?: runBlocking {
                    withTimeoutOrNull(HARDWARE_DECODE_READ_TIMEOUT_MS) {
                        AppSettings(this@PlaybackService).hardwareDecodePreferred.first()
                    }
                }
        }.getOrNull() ?: true
        val renderersFactory = NpRenderersFactory(
            context = this,
            preferSoftwareWhenNoHardware = !hardwareFirst,
        )
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            // E147：不要用 AudioTrack 原生倍速（setEnableAudioOutputPlaybackParameters）。
            // 真机上 6 声道 48kHz 音轨调 AudioTrack.setPlaybackParams 直接抛
            // IllegalArgumentException("arguments out of range")，media3 随即把倍速回滚到
            // 1.0——用户点 2.00x/长按 3x 都看着"没反应"。交给 SonicAudioProcessor 变速
            // （默认路径）后，倍速与音调保持与音轨声道数无关。
            .setEnableAudioOutputPlaybackParameters(false)
        // AVI 交给 NpExtractorsFactory：非 WMV2 仍是 Media3Avi，WMV2 补轨，确需软解的
        // MPEG-4 ASP 改专用 mime。必须与 FormatProbe 的探测链同源
        // （见 FormatProbe.AVI_EXTRACTORS 注释）：不同源会让"探测结论"与"实际能否出画"分叉。
        // 同理，FormatProbe 的"有没有解码器"也必须把上面这条链算全：FFmpeg 软解
        // （FormatProbe.ffmpegSupports）与末端的 MJPEG 位图软解（FormatProbe.bitmapRendererSupports），
        // 否则会把"能软解"的文件误判为不可播。
        // A1 收口：本地 file/content 走可随机访问的 DataSource（close→open 不再重开 fd，
        // 解封装器 RESULT_SEEK 降为 channel.position()）；远程/流式仍是 DefaultDataSource。
        // 探测侧（FormatProbe）同一思路：单 fd + FileChannel 定位。
        val mediaSourceFactory = DefaultMediaSourceFactory(
            com.noneplayer.core.media.datasource.SeekableLocalDataSourceFactory(this),
            NpExtractorsFactory(),
        )
        val loadControl = buildLoadControl()
        // L1/L2（§11.2）：预载管理器与播放器必须出自**同一个 builder**——
        // `buildExoPlayer` 会把同一个 MediaSource.Factory / RenderersFactory / LoadControl
        // 灌给 ExoPlayer，预载好的源才能被播放器直接接上（§11.2 明确要求"必须同 builder 出"）。
        val preloadManagerBuilder = DefaultPreloadManager.Builder(this, targetPreloadStatusControl())
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            // 预载管理器自己也要建 MediaSource：不喂同一个工厂，它预载出来的源
            // 与播放器认的源就不是一套（AVI 会在预载侧解析失败/被丢弃，白预载）。
            // 1.11 起 Builder.setMediaSourceFactory 弃用（该重载不会把 Cache/DataSourceFactory
            // 注入进来），改用 Supplier 形态；这里返回的仍是同一个自建工厂，行为与弃用前一致。
            .setMediaSourceFactorySupplier(
                object : MediaSourceFactorySupplier {
                    override fun setCache(cache: Cache?): MediaSourceFactorySupplier = this

                    override fun setDataSourceFactory(
                        dataSourceFactory: DataSource.Factory?,
                    ): MediaSourceFactorySupplier = this

                    override fun get(): MediaSource.Factory = mediaSourceFactory
                },
            )
        val player = preloadManagerBuilder.buildExoPlayer(
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
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
        // E245：平台 / NextLib 路径的帧读数（默认静默，只有 log.tag.NpPerf DEBUG 才挂监听）
        NpPlatformAnalytics.install(player)
        val session = MediaSession.Builder(this, player)
            // E3：点媒体通知回应用；具体媒体装载后更新为其深链（见下方监听）
            .setSessionActivity(launcherPendingIntent())
            .setCallback(ScrubAwareCallback(player))
            .build()
        mediaSession = session
        preloadManager = preloadManagerBuilder.build()
        // 通知/锁屏的点击落点跟随当前媒体：noneplayer://player?uri=…&id=… 经 E2 深链回播放页
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // E241：选择器拿不到 Player/MediaItem，只能由会话侧把当前 id 原子同步给
                // 进程级回退状态；同 id 的重建保留已 arm 的过滤，真正切集才清空。
                val currentMediaId = mediaItem?.mediaId?.takeUnless { it.isBlank() }
                if (currentMediaId != serviceDecoderMediaId) {
                    serviceDecoderMediaId = currentMediaId
                    serviceDecoderFallbackGate.onMediaChanged()
                }
                decoderFallbackState.onMediaItemChanged(currentMediaId)
                NpPerf.sessionMark(
                    "transition reason=$reason uri=${mediaItem?.localConfiguration?.uri?.lastPathSegment}",
                )
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                // A1-2：id 一起带上（media3 条目由 ExoVideoPlayer.toMediaItem 烘入权威 id）。
                // 只带 uri 时回跳的路由 id 为空，per-media 记忆全部落进空键桶。
                val mediaId = mediaItem?.mediaId.orEmpty()
                    .takeIf { it != MediaItem.DEFAULT_MEDIA_ID }.orEmpty()
                session.setSessionActivity(
                    if (uri.isNullOrEmpty()) {
                        launcherPendingIntent()
                    } else {
                        deepLinkPendingIntent(uri, mediaId)
                    },
                )
                // 反哺预载优先级：当前项前移后，下一集的预载目标随之改变（§11.2 L2）
                runCatching { syncPreloadQueue(player) }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retryServiceDecoderFailure(player, error)) return
                Log.w(SERVICE_LOG_TAG, "播放服务解码失败：${error.errorCodeName} ${error.message}")
            }

            /** 队列整体被替换（setMediaItems）时也要重新喂预载 */
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                runCatching { syncPreloadQueue(player) }
            }

            /**
             * 用户眼里的"切完了"就是这一帧（解码出画才看得见画面，READY 时仍是上一帧或黑）。
             * 只有会话侧播放器收得到这个回调，故切换耗时 A/B 的主指标记在这里。
             */
            override fun onRenderedFirstFrame() {
                NpPerf.sessionMark("first frame rendered")
            }
        })
        // 监听器安装在播放器创建之后；若服务重建时播放器已带媒体，补一次当前身份。
        decoderFallbackState.onMediaItemChanged(player.currentMediaItem?.mediaId)
    }

    /**
     * 服务侧播放器的运行期解码回退（E241）。
     *
     * UI 侧 MediaController 只收到 Binder 后的错误；但预载/专项测试可能直接持有
     * 服务播放器。回退状态必须在 renderer 再次查询候选之前武装，并且同一媒体/轨道
     * 只允许一次，避免 `prepare()` 在平台 codec 与扩展链之间无限重建。
     */
    private fun retryServiceDecoderFailure(player: ExoPlayer, error: PlaybackException): Boolean {
        if (!isDecoderFailureCode(error.errorCode)) return false
        val parsed = parseDecoderFailureMessage(error.message)
        val kind = classifyDecoderFailure(error.errorCode, parsed)
        val plan = fallbackPlan(kind, parsed)
        if (plan.action == DecoderFallbackAction.GIVE_UP) return false
        val mediaId = player.currentMediaItem?.mediaId?.takeUnless { it.isBlank() }
        val mediaKey = mediaId ?: player.currentMediaItem?.localConfiguration?.uri?.toString()
        val trackKey = parsed.mime ?: parsed.rendererTrack.name
        if (!serviceDecoderFallbackGate.canRetry(kind, mediaKey, trackKey)) return false

        serviceDecoderFallbackGate.markAttempted(mediaKey, trackKey)
        when (plan.action) {
            DecoderFallbackAction.RETRY_WITH_SOFT_ONLY ->
                decoderFallbackState.armSoftOnlyVideo(mediaKey, MP4V_VIDEO_MIME, mediaId)

            DecoderFallbackAction.RETRY_WITHOUT_FAILED_CODEC -> {
                val mime = plan.mime
                val codec = plan.codecName
                if (mime == null || codec == null) return false
                decoderFallbackState.armExcludedCodec(mediaKey, mime, codec, mediaId)
            }

            DecoderFallbackAction.GIVE_UP -> return false
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        player.prepare()
        if (position > 0L) player.seekTo(position)
        if (player.playWhenReady) player.play()
        Log.w(SERVICE_LOG_TAG, "运行期视频解码失败，已回退一次 mime=${plan.mime} codec=${plan.codecName}")
        return true
    }

    /**
     * 把当前队列喂给预载管理器，并同步"正在播的下标"（§11.2 L2）。
     *
     * 只喂当前项附近的一个窗口（前 1、后 3）：本地播放下把整个大文件夹（可能 80+ 项）
     * 全部注册进预载只会白白创建一堆 MediaSource 并干扰优先级排序，
     * 而 [targetPreloadStatusControl] 本身也只对距离 ≤ 2 的项给出非零目标。
     *
     * **必须增量同步**（§4.5 缓冲停摆的根因之一）：`BasePreloadManager.add()` 内部是
     * `map.put(MediaItem → holder)`——对同一个 MediaItem 再 add 只会把旧 holder 从表里
     * 挤掉、**不会释放**它；旧 holder 的 `PreloadMediaSource` 若已在预载，其 loader
     * 与文件句柄就成了孤儿继续跑。本方法挂在 `onTimelineChanged` 上，而缓冲期 timeline
     * 会变化多次，整窗重加会不断堆积孤儿预载源，与前台读取抢 I/O，表现为"缓冲中 seek
     * 后位置不再前进"。所以只对差集增删，窗口没变时仅更新当前下标。
     */
    private fun syncPreloadQueue(player: ExoPlayer) {
        val manager = preloadManager ?: return
        val count = player.mediaItemCount
        if (count == 0) {
            if (preloadedWindow.isNotEmpty()) {
                manager.removeMediaItems(preloadedWindow)
                preloadedWindow = emptyList()
            }
            return
        }
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        currentPlayingIndex = current
        val from = (current - PRELOAD_WINDOW_BACK).coerceAtLeast(0)
        val to = (current + PRELOAD_WINDOW_FORWARD).coerceAtMost(count - 1)
        if (to < from) return
        val items = (from..to).map { player.getMediaItemAt(it) }
        val stale = preloadedWindow.filter { it !in items }
        if (stale.isNotEmpty()) manager.removeMediaItems(stale)
        val fresh = items.filter { it !in preloadedWindow }
        if (fresh.isNotEmpty()) {
            manager.addMediaItems(fresh, fresh.map { items.indexOf(it) + from })
        }
        preloadedWindow = items
        manager.setCurrentPlayingIndex(current)
    }

    /**
     * L1（§11.2）：LoadControl 调参——本地大文件为主，默认
     * `minBufferMs = maxBufferMs = 50s` / `bufferForPlaybackMs = 2500` 偏保守。
     *
     * 起点值按 §11.2 表：起播缓冲门槛 2.5s → 1s、回缓冲 5s → 2s，
     * 并开 `prioritizeTimeOverSizeThresholds`（大文件下按时长而非字节数判充分）。
     */
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
     * 当前媒体的深链 PendingIntent：noneplayer://player?uri=…&id=…（E2 已注册 scheme）。
     * 用 launcher intent 作为底（Component 指向本应用入口），不直接依赖 app 模块的类。
     *
     * [mediaId] 为空时只拼 uri（外部来源的条目没有 id，行为与修复前一致）；
     * `Uri.Builder` 负责转义——`.cue` 分轨的 id 含 `#`，裸拼进查询串会被当成分隔符。
     */
    private fun deepLinkPendingIntent(uri: String, mediaId: String = ""): PendingIntent {
        val base = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val intent = Intent(base).apply {
            action = Intent.ACTION_VIEW
            data = "noneplayer://player".toUri()
                .buildUpon()
                .appendQueryParameter("uri", uri)
                .apply {
                    if (mediaId.isNotEmpty()) appendQueryParameter("id", mediaId)
                }
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
     * 会话回调：放行 np.scrub / np.crop / np.autoplay 自定义命令，并在收到时切换
     * ExoPlayer 的擦洗模式、画面缩放模式与"播完是否自动推进"。
     *
     * 1.8+ 官方擦洗模式（CLOSEST_SYNC 就近定位 + 关闭开销大的后处理），
     * 松手恢复常规精确 seek。setScrubbingModeEnabled / setVideoScalingMode /
     * setPauseAtEndOfMediaItems 都是 ExoPlayer（会话侧）API，MediaController 无法
     * 直接调用，必须经会话侧转发。
     */
    @OptIn(UnstableApi::class)
    private class ScrubAwareCallback(
        private val player: ExoPlayer,
    ) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // REV-07：私有命令（np.scrub / np.crop / np.autoplay）只授予与本应用同 UID 的
            // 控制器。服务保持 exported（系统媒体通知/车机/蓝牙都要能连），不受信的第三方
            // 仍拿默认命令集（标准播放控制可用），但拿不到三条私有命令。
            val trusted = isSameUid(controller.uid)
            Log.i(
                SERVICE_LOG_TAG,
                "controller connected uid=${controller.uid} pkg=${controller.packageName} private=$trusted",
            )
            val commands = if (trusted) {
                val builder =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                privateSessionCommands().forEach { command -> builder.add(command) }
                builder.build()
            } else {
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            }
            // 用 (session, controller) 重载：受信控制器拿默认命令集，不受信（第三方 App）
            // 拿收紧后的 UNTRUSTED 默认集。单参重载已弃用，且对第三方一律放全量命令。
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        /**
         * E209：系统「最近媒体/恢复播放」入口。旧实现没覆写，media3 默认实现返回
         * UnsupportedOperationException，真机起播持续告警且系统恢复面板拿不到本应用。
         *
         * 冷查询最近一条历史（不能读 recent StateFlow 的首帧，它在无订阅者时是空表），
         * 用历史快照构造 media3 条目，起播位置=上次落点。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val history = PlaybackResumeHistory.repository
                ?: run {
                    Log.i(SERVICE_LOG_TAG, "onPlaybackResumption 无 HistoryRepository，返回空队列")
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L),
                    )
                }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { history.mostRecentForResumption() }
                    .onSuccess { entry ->
                        Log.i(
                            SERVICE_LOG_TAG,
                            "onPlaybackResumption isForPlayback=$isForPlayback " +
                                "media=${entry?.mediaKey} pos=${entry?.positionMs}",
                        )
                        future.set(
                            if (entry == null) {
                                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                            } else {
                                MediaSession.MediaItemsWithStartPosition(
                                    listOf(entry.toResumeMediaItem()),
                                    0,
                                    entry.positionMs.coerceAtLeast(0L),
                                )
                            },
                        )
                    }
                    .onFailure { future.setException(it) }
            }
            return future
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val action = customCommand.customAction
            // 非私有命令：交回 media3 默认处理（标准会话命令由框架实现）
            if (!isPrivateCommand(action)) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            // REV-07：命令授予只是连接期快照，不能当授权凭据——这里按调用方 UID 再复核一次。
            // 第三方控制器即便自己构造出 np.* 命令也执行不了（记录 uid/pkg 便于真机取证）。
            if (!isSameUid(controller.uid)) {
                Log.w(
                    SERVICE_LOG_TAG,
                    "private command rejected uid=${controller.uid}" +
                        " pkg=${controller.packageName} action=$action reason=not-same-uid",
                )
                return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_PERMISSION_DENIED),
                )
            }
            // 参数缺失/类型不符一律拒绝：合法命令必然带布尔参数（见 PrivateCommands.kt）
            if (!validatePrivateCommandArgs(action, args)) {
                Log.w(
                    SERVICE_LOG_TAG,
                    "private command rejected uid=${controller.uid}" +
                        " action=$action reason=bad-args keys=${args.keySet()}",
                )
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            if (action == SCRUB_COMMAND &&
                !validateScrubArgs(
                    active = args.getBoolean(SCRUB_ARG_ACTIVE),
                    targetMs = args.get(SCRUB_ARG_TARGET_MS),
                    resume = args.get(SCRUB_ARG_RESUME),
                )
            ) {
                Log.w(
                    SERVICE_LOG_TAG,
                    "private command rejected uid=${controller.uid}" +
                        " action=$action reason=bad-scrub-args keys=${args.keySet()}",
                )
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            when (action) {
                SCRUB_COMMAND -> {
                    val active = args.getBoolean(SCRUB_ARG_ACTIVE)
                    player.setScrubbingModeEnabled(active)
                    if (!active) {
                        if (args.containsKey(SCRUB_ARG_TARGET_MS)) {
                            player.seekTo(args.getLong(SCRUB_ARG_TARGET_MS).coerceAtLeast(0L))
                        }
                        if (args.getBoolean(SCRUB_ARG_RESUME, false)) player.play()
                    }
                }
                CROP_COMMAND -> {
                    // 满窗裁剪（cover）/ 按比例适配（fit）——§2.4「画面」面板的引擎侧开关；
                    // 比例/镜像/旋转由 UI 层布局与 graphicsLayer 承担，不经会话
                    player.setVideoScalingMode(
                        if (args.getBoolean(CROP_ARG_ACTIVE, false)) {
                            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                        } else {
                            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        },
                    )
                }
                AUTOPLAY_COMMAND -> {
                    // L1：「自动连播下一集」关闭 = 每条播完停在末帧。media3 的开关语义与
                    // 设置项相反，这里做唯一一次取反；缺省 true（与设置默认值一致），
                    // 免得漏发一次参数就把"不自动连播"当成"自动连播"。
                    player.setPauseAtEndOfMediaItems(!args.getBoolean(AUTOPLAY_ARG_ENABLED, true))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
        decoderFallbackState.clear()
        decoderFallbackState.onMediaItemChanged(null)
        serviceDecoderFallbackGate.onMediaChanged()
        serviceDecoderMediaId = null
        super.onDestroy()
    }
}
