package com.noneplayer.plugins.player.exo

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Layout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.SubtitleEffectNotice
import com.noneplayer.core.media.probe.FormatProbe
import com.noneplayer.kernel.api.NpChapter
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerConnection
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.NpMediaButtonAction
import com.noneplayer.kernel.api.NpSubtitleFormat
import com.noneplayer.kernel.api.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ExoPlayer 播放器实现——基于 [MediaController] 与后台 [PlaybackService] 会话。
 *
 * 播放器实例驻留服务进程侧：UI 销毁不影响播放（后台播放 + 媒体通知）。
 * [awaitReady] 阻塞到会话建立；字幕/倍速/进度记忆都作用于会话侧播放器。
 */
internal fun shouldEnableTextTracks(type: NpTrackType, enabled: Boolean): Boolean =
    type == NpTrackType.TEXT && !enabled

class ExoVideoPlayer(context: Context) : VideoPlayer {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    /** E209：每个播放器实例独立的打点时钟；旧的 object 静态 t0 会被多实例互相覆盖。 */
    private val perf = NpPerfClock()
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // REV-04：引擎**已真正生效**媒体的身份与进度（与上面的"控制器当前值"分开）。
    // 探测/装载窗口内 controller 的位置/时长仍属于上一条媒体，UI 写历史/进度只能
    // 用这一组：切换媒体后位置立即归零、时长归零，等新媒体的轮询再填。
    // 契约里是普通属性（调用方在写进度时读一次，不需要订阅）。
    private val _appliedMediaKey = MutableStateFlow<String?>(null)
    override val appliedMediaKey: String?
        get() = _appliedMediaKey.value

    private val _appliedPositionMs = MutableStateFlow<Long?>(null)
    override val appliedPositionMs: Long?
        get() = _appliedPositionMs.value

    private val _appliedDurationMs = MutableStateFlow(0L)
    override val appliedDurationMs: Long
        get() = _appliedDurationMs.value

    // REV-10：会话连接状态。断连不是播放错误（不归 PlaybackState.Error）：
    // 服务被回收后可以重连恢复，UI 应显示"已断开/重连中"并提供重试。
    private val _connection = MutableStateFlow(PlayerConnection.CONNECTING)
    override val connection: StateFlow<PlayerConnection> = _connection.asStateFlow()

    // S5 缓冲段可视化：与 position 同一轮询节拍读取（MediaController.bufferedPosition）
    private val _bufferedPositionMs = MutableStateFlow(0L)
    override val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(0f)
    override val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    override val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _currentUri = MutableStateFlow<String?>(null)
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val cues: StateFlow<List<SubtitleCue>> = _cues.asStateFlow()

    // 批次 3：内嵌轨道枚举与章节（轨道面板/章节面板数据源）
    private val _tracks = MutableStateFlow<List<NpTrack>>(emptyList())
    override val tracks: StateFlow<List<NpTrack>> = _tracks.asStateFlow()

    private val _chapters = MutableStateFlow<List<NpChapter>>(emptyList())
    override val chapters: StateFlow<List<NpChapter>> = _chapters.asStateFlow()

    /**
     * 轨道选择键 → (轨道组, 轨道序号)，selectTrack 时还原成 TrackSelectionOverride。
     *
     * E209：契约允许 selectTrack 在任意线程调用，而 onTracksChanged 在播放线程 publish；
     * 旧的 HashMap 会被一边 clear/put、一边读。改为「本地建表 + volatile 整体替换」：
     * 读到的永远是完整快照，不再有半张表。
     */
    @Volatile
    private var trackGroupById: Map<String, Pair<Tracks.Group, Int>> = emptyMap()

    /** C21：起播探测的"仅软解可用"提示（null = 无） */
    private val _decodeNotice = MutableStateFlow<String?>(null)
    override val decodeNotice: StateFlow<String?> = _decodeNotice.asStateFlow()

    /** HDR 概况（"HDR10"/"HLG"；SDR/未知/未探测为 null）——起播探测结果 */
    private val _hdrLabel = MutableStateFlow<String?>(null)
    override val hdrLabel: StateFlow<String?> = _hdrLabel.asStateFlow()

    /** 邻域预探测的协程句柄：真实装载一开始就取消它（见 [prefetchProbeWindow]） */
    private var prefetchJob: Job? = null

    // ---- 队列（§8.2）：写入 media3 会话后，通知 ⏮⏭ 与"播完自动下一集"由框架免费提供 ----
    private val _queue = MutableStateFlow<List<com.noneplayer.core.media.MediaItem>>(emptyList())
    override val queue: StateFlow<List<com.noneplayer.core.media.MediaItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /**
     * 每次连接尝试（首次/重连）都会重新武装的"就绪"信号。
     *
     * 旧实现是一次性 `CompletableDeferred`：断连后它早已完成，[awaitReady] 立即
     * 返回成功，装载链以为会话可用、继续 setQueue/play，全部静默空转（REV-10 的
     * "假成功"）。现在断连即换一条新的 deferred，只有重连成功才完成；重试耗尽以
     * [PlayerConnectionUnavailableException] 快速失败。
     */
    private var connectDeferred = CompletableDeferred<MediaController>()

    private var controller: MediaController? = null

    /** 只有真正连着会话的控制器才能下发命令/读状态（断连后 media3 静默忽略一切） */
    private val activeController: MediaController?
        get() = controller?.takeIf { it.isConnected }

    /** REV-10：连接状态机（纯逻辑，见 [PlayerConnectionTracker]） */
    private val connectionTracker = PlayerConnectionTracker()

    /** 断连期间保留的队列/下标/位置意图：重连成功后按它恢复 setQueue（REV-10） */
    private var pendingSessionRestore = false
    private var restorePositionMs = 0L

    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    /** 最近一次 playWhenReady 变化原因：media3 没有 getter，只能在回调里缓存（A23） */
    private var playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    /**
     * REV-11：媒体尚未真正生效时到达的 seek（探测/装载窗口内的拖动）。
     * 带目标媒体身份：只有落到同一条媒体上才消费/清理，见 [PendingSeek]。
     */
    private var pendingSeek: PendingSeek? = null

    /** 正在装载的目标媒体身份（探测窗口内 seekTo 的归属；装载落地后以 appliedMediaKey 为准） */
    private var loadingTargetKey: String? = null

    /** 已生效媒体的 media3 mediaId（pollPosition 据此判断"控制器当前条目是不是已生效媒体"） */
    private var appliedMediaId: String = ""

    /** REV-09：当前章节归属键（媒体身份优先，退回 uri）；切到不同键时清空章节 */
    private var chaptersKey: String? = null

    /**
     * REV-10：binder 断开的唯一可靠信号。
     *
     * media3 的 `MediaController.Listener` 只能在 Builder 上挂（控制器没有
     * `addListener(MediaController.Listener)` 重载）：服务被回收时 media3 会
     * **自动 release 旧控制器**并回调这里，旧实例不可复用，重连必须新建。
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            handleDisconnected(controller)
        }
    }

    private var attachedDisplay: Any? = null
    private var refreshPausedFrameOnSurface = false
    private var currentMediaUri: String? = null
    private var currentMimeType: String = "video/mp4"
    private var currentTitle: String = ""
    private var currentArtworkUri: String? = null
    /**
     * 当前**选定**的外挂字幕（地址 + 容器格式，A3）。格式必须跟着地址走：
     * 引擎按 uri 末段猜 mime 时，MediaStore 的 `content://media/external/file/1234`
     * 与 SAF 的文档 uri 末段都是 id，一律落到 SubRip——自动匹配的 `.ass` 于是零 Cue。
     */
    private var subtitle: SubtitleMount? = null

    /** 已经烘进当前媒体项的那一条（与 [subtitle] 不同 = 待重建） */
    private var mountedSubtitle: SubtitleMount? = null
    private var textTracksEnabled = true
    private var videoTracksEnabled = true

    /**
     * 刚挂上外挂字幕、或刚切到一条带外挂字幕的媒体项之后，待落实的"选中外挂轨"。
     *
     * 时机只这一次：外挂轨与内嵌轨都带 [C.SELECTION_FLAG_DEFAULT]，media3 平票时按
     * 轨道顺序内嵌先胜，自动挂上的外挂字幕于是只剩菜单里一行"已挂载"、屏上照旧出
     * 内嵌文案（B21②）。落实后置回 false，用户随后在字幕面板里手选内嵌轨不会被抢回。
     */
    private var pendingExternalSubtitleSelect = false

    // E39/E41：探测期间的挂起操作与轨道信息
    private var prepareJob: Job? = null

    /**
     * 自动连播下一集（设置页开关，L1）。连接建立前先记在这里，连上后补发——
     * 会话侧默认是"自动推进"，漏发会静默变成"关不掉自动连播"。
     */
    private var autoplayNext = true

    private var appliedUri: String? = null
    private var loadPending = false
    private var currentMediaId: String = ""
    private var appliedItems: List<MediaItem> = emptyList()

    /**
     * 已应用队列的**身份序列**（A15）：[applyQueue] 落地时逐项记下 id（没有 id 的
     * 条目退化为 uri），与引擎里那份 media3 队列同生共死。
     *
     * 此前切集快路径只比 [MediaController.mediaItemCount]——两份**等长**的文件夹
     * 会被当成同一条队列；探测还在走（[loadPending]）的那 0.5–1.5s 里，用户切到
     * 另一份文件夹的选集，`seekToDefaultPosition` 就落进了无关文件。项数一样
     * 只是"长度恰好相同"，不是"同一份队列"的证据。
     */
    private var appliedQueueIds: List<String> = emptyList()

    /**
     * 装载落地时的起播意图（A08：装载即起播的"起播"到底要不要）。
     *
     * 装载是异步的（探测 + 建表），意图必须在装载**开始**时就定下来，落地时才知道
     * 该不该 `play()`；而且此后**用户说了算**——探测窗口里按下的 ▶/⏸ 会改写它，
     * 不被落地那一刻的旧意图覆盖（"只装载"落点里用户点播放，等一秒才播已是上限，
     * 再被吞掉就不是时序问题了）。
     */
    private var loadIntentPlay = true

    /** 最近一次探测的 uri；与 currentMediaUri 不一致时（如队列快路径切集后），失败文案不得复用这些名字 */
    private var probedUri: String? = null
    private var probedContainer: String? = null
    private var probedVideoCodec: String? = null
    private var probedAudioCodec: String? = null

    // E58 修复配套：prepare() 世代号，见 prepare() 内注释
    private var prepareGeneration = 0

    // 失败闩锁：Error 是终态，之后引擎的 IDLE 等状态回调不得覆盖它；
    // 下一次 prepare 时清除（重试）
    private var terminalFailure = false

    /**
     * A18：解码器初始化失败的一次性回退闸门（媒体键校验 + 切媒体复位）。
     *
     * 回退状态本身在进程级 [decoderFallbackState] 里，供服务进程的
     * `MediaCodecSelector` 包装读取；这里只记"这条媒体已经回退过一次没有"。
     */
    private val decoderFallbackGate = DecoderFallbackGate()

    // E62 修复：release() 可能先于 MediaController.Builder.buildAsync() 的异步连接完成
    // 执行（例如用户快速退出播放页）。该监听器运行在独立于 scope 的 MainExecutor 上，
    // 此前会无条件把新连接的 controller 赋给实例字段——release() 已经跑过，不会再有人
    // 释放这个重新冒出来的连接，造成 MediaController 绑定泄漏。用 @Volatile 标志位
    // 在连接完成时判断：已释放则直接释放新连接，不再挂到实例字段上。
    @Volatile
    private var released = false

    // E43 字幕偏移：media3 的 Cue 流是"当前活跃的 Cue"（逐 Cue 时刻已被过滤掉），
    // 偏移只能靠引擎侧缓冲"时间窗"实现——onCues 每次到达即闭合上一窗、开启新窗，
    // 渲染时按 position - offset 落在哪个窗来取 Cue。
    private var subtitleOffsetMs = 0L
    private class CueWindow(val startMs: Long, var endMs: Long, var cues: List<SubtitleCue>)
    private val cueHistory = ArrayDeque<CueWindow>()

    private fun pushCues(positionMs: Long, mapped: List<SubtitleCue>) {
        val last = cueHistory.lastOrNull()
        if (last != null && last.startMs == positionMs) {
            last.cues = mapped
        } else {
            last?.endMs = (positionMs - 1).coerceAtLeast(0L)
            cueHistory.addLast(CueWindow(positionMs, Long.MAX_VALUE, mapped))
        }
        // 只保留最近的窗口：偏移量级是 ±1s，历史超过 3 分钟没有任何用处
        while (cueHistory.size > 64 || cueHistory.firstOrNull()?.let { positionMs - it.startMs > 180_000 } == true) {
            cueHistory.removeFirst()
        }
        recomputeCues(positionMs)
    }

    private fun recomputeCues(positionMs: Long) {
        if (subtitleOffsetMs == 0L) {
            _cues.value = cueHistory.lastOrNull()?.cues ?: emptyList()
            return
        }
        // 正偏移 = 字幕延后：去找 (position - offset) 时刻活跃的那一窗
        val t = positionMs - subtitleOffsetMs
        _cues.value = cueHistory.lastOrNull { t >= it.startMs && t < it.endMs }?.cues ?: emptyList()
    }

    init {
        // 首次连接：CONNECTING → CONNECTED（失败 → FAILED + Error 终态）
        scope.launch { connectInitial() }
    }

    /**
     * 建一条新连接并等它连上（首次连接与重连共用）。
     *
     * 断连后旧控制器已被 media3 自动释放，**不可复用**——重连必须重新
     * `buildAsync()`。返回 null 表示本次尝试失败/超时，由调用方决定是否重试。
     */
    private suspend fun connectOnce(timeoutMs: Long): MediaController? {
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).setListener(controllerListener).buildAsync()
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    future.addListener({
                        val result = runCatching { future.get() }
                        if (!continuation.isActive) {
                            // 已超时/取消：这条连接没人要了，释放掉，别泄漏 binder
                            result.getOrNull()?.release()
                            return@addListener
                        }
                        result.onSuccess { continuation.resume(it) }
                            .onFailure { continuation.resumeWithException(it) }
                    }, ContextCompat.getMainExecutor(appContext))
                    continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    /** 首次连接：成功后接管控制器；失败则落 Error 终态并让 [awaitReady] 快速失败 */
    private suspend fun connectInitial() {
        _connection.value = PlayerConnection.CONNECTING
        val c = connectOnce(INITIAL_CONNECT_TIMEOUT_MS)
        if (c == null) {
            if (released) return
            connectionTracker.onInitialFailed()
            _connection.value = PlayerConnection.FAILED
            _state.value = PlaybackState.Error("无法建立播放会话")
            connectDeferred.completeExceptionally(
                PlayerConnectionUnavailableException("无法建立播放会话"),
            )
            return
        }
        if (released) {
            // release() 已先执行：这个连接不再被任何人持有，直接释放。
            // connectDeferred 必须一并了结——否则仍挂在 awaitReady() 上的装载链
            // 会一直挂起到它的作用域被取消（表现为"进页一直转圈"）。
            c.release()
            connectDeferred.completeExceptionally(
                CancellationException("播放器已释放，会话连接不再补发"),
            )
            return
        }
        connectionTracker.onConnected()
        _connection.value = PlayerConnection.CONNECTED
        installController(c, restoring = false)
    }

    /**
     * 接管一条已连上的控制器：挂监听、同步会话侧既有状态、启动位置轮询。
     *
     * [restoring] = true 表示这是断连后的重连：会话侧可能已经换了进程、回到默认值，
     * 本实例记住的偏好（倍速/循环）要重新下发；初次连接则以会话为准（后台可能正
     * 在播，读回来而不是覆盖）。
     */
    private fun installController(c: MediaController, restoring: Boolean) {
        controller = c
        c.addListener(playerListener(c))
        if (restoring) {
            runCatching { c.playbackParameters = PlaybackParameters(_speed.value) }
            applyLoopModeTo(c, _loopMode.value)
        } else {
            _speed.value = c.playbackParameters.speed
        }
        // 会话建立后补挂显示表面，并同步会话侧既有状态（速度/当前媒体）
        attachSurfaceTo(c)
        _currentUri.value = c.currentMediaItem?.localConfiguration?.uri?.toString()
        // 会话已在播媒体时，新控制器不会再收到 onVideoSizeChanged：
        // 主动同步一次画面比例，否则重进播放页"横屏视频自动全屏"因比例未知而失效
        c.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { applyVideoSize(it) }
        // 同理：重进时轨道/章节也不会再回调，主动同步一次
        publishTracks(c.currentTracks)
        publishChapters(c.currentTracks, null)
        // 会话侧默认"播完自动推进"：连接建立后补发一次当前偏好，
        // 否则"关闭自动连播"在重建控制器后（换 mime / 重进）会失效
        sendAutoplayFlag()
        setTextTracksEnabled(textTracksEnabled)
        setVideoTracksEnabled(videoTracksEnabled)
        // E144-1：PiP 动作按钮走同进程广播 → 桥 → 本控制器
        NpMediaActionBridge.register(this) { action -> dispatchMediaAction(c, action) }
        pollJob?.cancel()
        pollJob = scope.launch { pollPosition(c) }
        connectDeferred.complete(c)
    }

    /**
     * 会话回调（每次连接新建一个实例：闭包绑定当次控制器 [c]）。
     */
    private fun playerListener(c: MediaController) = object : Player.Listener {
        // 首帧不在这里：media3 不把 onRenderedFirstFrame 转发给
        // MediaController，控制器侧只能看到 STATE_READY。出画时刻记在会话侧
        // （PlaybackService 的播放器监听），同一进程内时间戳可直接对齐。
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (terminalFailure) return
            if (playbackState == Player.STATE_READY) {
                perf.mark("READY")
                // A18：回退重试奏效（解码器已经建起来），撤掉过滤状态——之后字幕重建
                // 之类的同媒体重新 prepare 不再排除 codec；"已回退过"的闸门保留到换媒体。
                if (decoderFallbackState.isArmed) decoderFallbackState.clear()
                // 就绪才预热邻域探测：这一刻之前磁盘都在回填当前集的缓冲
                prefetchProbeWindow()
            }
            // A23：末帧暂停（READY + !playWhenReady + END_OF_MEDIA_ITEM）要译成
            // Ended。同一批回调里 onPlayWhenReadyChanged 可能后到（media3 先派发
            // 状态、再派发 playWhenReady），这里先用已缓存的原因算一次，原因更新
            // 后由 onPlayWhenReadyChanged 再算一次。
            _state.value = npEngineStateOf(playbackState, c.playWhenReady, playWhenReadyChangeReason)
        }

        /**
         * A23：缓存 playWhenReady 变化原因（media3 没有 getter，只能在这里记），
         * 并用它重算状态——关闭自动连播时会话侧 `setPauseAtEndOfMediaItems(true)`
         * 让末帧停在 READY + !playWhenReady，原因正是 END_OF_MEDIA_ITEM。
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyChangeReason = reason
            if (terminalFailure) return
            _state.value = npEngineStateOf(c.playbackState, playWhenReady, reason)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyVideoSize(videoSize)
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (!refreshPausedFrameOnSurface || width <= 0 || height <= 0) return
            refreshPausedFrameOnSurface = false
            // A paused decoder may have already released its only frame to the old surface.
            // E205：装载在途时同样别自动 seek——与 seekTo 的转发闸同理，刚清理过的
            // controller 吃一记 seek 会踩 flush-after-release 竞态。
            if (attachedDisplay != null && videoTracksEnabled && !loadPending &&
                c.playbackState == Player.STATE_READY && !c.playWhenReady
            ) c.seekTo(c.currentPosition)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _speed.value = playbackParameters.speed
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (loadPending) return
            val index = c.currentMediaItemIndex
            // MediaController's Binder snapshot may omit LocalConfiguration.
            // Keep the submitted item's URI/subtitles, and validate its identity.
            val localItem = appliedItems.getOrNull(index)
                ?.takeIf { it.mediaId == mediaItem?.mediaId }
            if (mediaItem != null && localItem == null) return
            clearCues()
            _currentUri.value = localItem?.localConfiguration?.uri?.toString()
            currentMediaUri = _currentUri.value
            appliedUri = currentMediaUri
            currentMediaId = mediaItem?.mediaId.orEmpty()
            mountedSubtitle = localItem?.localConfiguration?.subtitleConfigurations
                ?.firstOrNull()
                ?.let { config ->
                    config.uri?.toString()?.let {
                        SubtitleMount(it, npSubtitleFormatOfMime(config.mimeType))
                    }
                }
            subtitle = mountedSubtitle
            // 换集自动挂上的外挂字幕同样要顶掉内嵌轨的平票（E144）
            pendingExternalSubtitleSelect = mountedSubtitle != null
            // 队列下标跟随会话（自动连播推进到下一集时也要更新，
            // 否则迷你条/选集面板会一直停在旧下标）
            // Clearing the engine after a failed probe is not a queue transition.
            if (mediaItem != null && index in _queue.value.indices &&
                index != _currentIndex.value
            ) _currentIndex.value = index
            // REV-04/REV-09：自动连播/循环/换集后 applied 身份与章节归属切到新条目、
            // 位置归零；同一条目的"重设"（字幕重建后 setMediaItems）保持已记录的
            // 续播位置——身份没变就不该归零。
            val core = _queue.value.getOrNull(index)
            val newKey = core
                ?.takeIf { it.id.isNotEmpty() && it.id == mediaItem?.mediaId }
                ?.let { mediaKeyOf(it.sourceId, it.id) }
            val newId = mediaItem?.mediaId.orEmpty()
            val restarted = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            if (restarted || newKey != _appliedMediaKey.value || newId != appliedMediaId) {
                setAppliedMedia(newKey, newId, 0L, resetDuration = restarted)
                // A18：自动连播/循环到新一条后回退状态复位，避免上一条的
                // "排除 codec / 只走软解"污染下一条媒体
                decoderFallbackGate.onMediaChanged()
                decoderFallbackState.clear()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (terminalFailure) return
            if (isPlaying) {
                _state.value = PlaybackState.Playing
            } else if (_state.value == PlaybackState.Playing) {
                _state.value = PlaybackState.Paused
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 只有"报错的就是刚探测过的那一条"才配得上探测摘要里的容器/编码名；
            // 队列快路径切集没有重新探测，名称属于上一条——复用会把
            // "切到 B 失败"说成"A 的编码不支持"（曾把 H264 失败显示成 WMV2）。
            val probedThis = probedUri != null && probedUri == currentMediaUri
            if (isDecoderFailureCode(error.errorCode)) {
                // A18：跨 binder 后拿不到 DecoderInitializationException 的类型信息，
                // 只能从容错的 message 里解析失败轨的 mime / codec 名。
                val parsed = parseDecoderFailureMessage(error.message)
                val kind = classifyDecoderFailure(error.errorCode, parsed)
                if (isAudioDecoderFailure(kind, parsed.mime)) {
                    // A18：音频轨解码错误绝不能回到下面的 videoCodecUnsupported，
                    // 否则会给出"转码视频"的建议，而问题其实在音轨
                    terminalFailure = true
                    val audioFailure = PlaybackFailure.audioCodecUnsupported(
                        probedContainer.takeIf { probedThis },
                        probedAudioCodec.takeIf { probedThis },
                    )
                    _state.value = PlaybackState.Error(audioFailure.reason, audioFailure)
                    return
                }
                // A18：视频解码失败先做一次受控回退（同一媒体只一次）；重试装载成功
                // 就不会落到错误态，重试再失败才走下面的终态映射。
                val plan = fallbackPlan(kind, parsed)
                val mediaKey = _appliedMediaKey.value ?: loadingTargetKey
                if (plan.action != DecoderFallbackAction.GIVE_UP &&
                    decoderFallbackGate.canRetry(kind, mediaKey)
                ) {
                    retryAfterDecoderFailure(plan, mediaKey)
                    return
                }
            }
            val failure = error.toPlaybackFailure(
                probedContainer.takeIf { probedThis },
                probedVideoCodec.takeIf { probedThis },
                probedAudioCodec.takeIf { probedThis },
            )
            terminalFailure = true
            _state.value = PlaybackState.Error(failure.reason, failure)
        }

        // E1：字幕 Cue 出口——外挂与内嵌轨道统一从这里流向 UI 字幕浮层
        override fun onCues(cueGroup: CueGroup) {
            if (loadPending) return
            val mapped = cueGroup.cues.map { it.toKernelCue() }
            pushCues(c.currentPosition.coerceAtLeast(0), mapped)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // 同一条目里 seek 后，文本渲染器可能再次吐出与上次相同的 Cue。
            // 会话控制器会丢掉重复回调；这里若清空，晚挂字幕就一直空白。
            // 换条目的清空仍由 onMediaItemTransition 负责。
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) clearCues()
        }

        // 批次 3：内嵌轨道枚举（音轨/字幕面板的数据源）
        override fun onTracksChanged(tracks: Tracks) {
            publishTracks(tracks)
            publishChapters(tracks, null)
            // 外挂轨出现的那一刻把它选中（见 pendingExternalSubtitleSelect）
            applyPendingExternalSubtitleSelection(tracks)
        }

        // 章节经 metadata 通道到达（§12.5：MP4/Matroska 章节元数据）
        @OptIn(UnstableApi::class)
        override fun onMetadata(metadata: Metadata) {
            publishChapters(c.currentTracks, metadata)
        }
    }

    /**
     * REV-10：会话断开（服务被回收/进程崩溃/连接被释放）。
     *
     * 旧控制器已被 media3 自动释放，不能再发命令：清掉引用、停掉位置轮询、把连接
     * 状态交给 UI；**保留**队列/下标/起播意图与最后位置，供 [reconnect] 恢复。
     * 断连不是播放错误：不改 `_state` 为 Error（UI 依据 [connection] 显示断开层）。
     */
    private fun handleDisconnected(disconnected: MediaController) {
        if (released) return
        // 旧连接迟到的回调（重连已经换上新控制器）不许把新连接拆掉
        if (controller !== disconnected) return
        controller = null
        pollJob?.cancel()
        pollJob = null
        NpMediaActionBridge.unregister(this)
        // 记下最后位置：重连后按它续播（此刻 _positionMs 还是断连前最后一次轮询值）
        restorePositionMs = _positionMs.value.coerceAtLeast(0L)
        // REV-10 关键：断连后必须把"恢复意图"钉住。正常播放中发生断连时没有走任何
        // 装载路径，pendingSessionRestore 仍是 false——不置位的话重连成功后的
        // restoreSessionState() 会直接返回，新会话里没有 setMediaItems，表现就是
        // "重连成功但画面永远停住/不恢复播放"。用纯函数判定有无可恢复目标，与
        // restoreSessionState 共用，避免以后又漏。
        pendingSessionRestore = shouldRestoreAfterDisconnect(_queue.value.size, currentMediaUri)
        // 引擎里已经没有这条媒体的会话：applied 身份作废（UI 不得再拿它写历史）
        clearAppliedIdentity()
        // 章节/轨道是会话侧的，断连后同样没有归属
        chaptersKey = null
        _chapters.value = emptyList()
        _tracks.value = emptyList()
        clearCues()
        connectionTracker.onDisconnected()
        _connection.value = PlayerConnection.DISCONNECTED
        // 重新武装：断连后 awaitReady() 不许立即返回假成功
        connectDeferred = CompletableDeferred()
    }

    /** 清空"已生效媒体"身份（REV-04）：断连/释放/装载失败后不得再报告旧媒体的进度 */
    private fun clearAppliedIdentity() {
        appliedItems = emptyList()
        appliedUri = null
        appliedQueueIds = emptyList()
        appliedMediaId = ""
        loadingTargetKey = null
        _appliedMediaKey.value = null
        _appliedPositionMs.value = null
        _appliedDurationMs.value = 0L
    }

    /**
     * 记录"引擎已真正生效"的媒体身份（REV-04）：applied* 只在这里被改写。
     *
     * [positionMs] 是装载落地的起播位置；时长先清 0，由 [pollPosition] 在新媒体
     * 真正报出时长后填。章节归属键同时切到新媒体——换媒体必须清空上一集的章节
     * （REV-09）。
     */
    private fun setAppliedMedia(
        mediaKey: String?,
        mediaId: String,
        positionMs: Long,
        resetDuration: Boolean = true,
    ) {
        val changed = mediaKey != _appliedMediaKey.value || mediaId != appliedMediaId
        _appliedMediaKey.value = mediaKey
        appliedMediaId = mediaId
        // 契约：没有身份信息（无 mediaId）时 appliedPositionMs 为 null
        _appliedPositionMs.value = if (mediaKey != null) positionMs.coerceAtLeast(0L) else null
        if (changed || resetDuration) _appliedDurationMs.value = 0L
        syncChaptersKey(mediaKey)
    }

    /** REV-09：媒体切换时清空上一集章节，并把章节归属键切到新媒体 */
    private fun syncChaptersKey(mediaKey: String?) {
        val key = ChapterIdentity.keyOf(mediaKey, currentMediaUri)
        if (key == chaptersKey) return
        chaptersKey = key
        _chapters.value = emptyList()
    }

    /**
     * REV-10：请求重连（UI 在 `connection` 为 DISCONNECTED/FAILED 时调用）。
     *
     * 有界重试：≤3 次，每次尝试前退避 500ms/1s/2s；并发调用只会有一次重连在跑
     * （[PlayerConnectionTracker.beginReconnect] 去重）。成功后按断连前保留的
     * 队列/下标/最后位置恢复装载（见 [restoreSessionState]）；重试耗尽落 FAILED，
     * 并让等待 [awaitReady] 的装载链快速失败而不是静默空转。
     */
    override fun reconnect() {
        if (released) return
        val firstDelayMs = connectionTracker.beginReconnect() ?: return
        _connection.value = PlayerConnection.RECONNECTING
        connectDeferred = CompletableDeferred()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var waitMs = firstDelayMs
            while (isActive) {
                delay(waitMs)
                val c = connectOnce(RECONNECT_ATTEMPT_TIMEOUT_MS)
                if (released) {
                    c?.release()
                    return@launch
                }
                if (c != null) {
                    connectionTracker.onAttemptSucceeded()
                    _connection.value = PlayerConnection.CONNECTED
                    installController(c, restoring = true)
                    restoreSessionState()
                    return@launch
                }
                val nextDelayMs = connectionTracker.onAttemptFailed()
                if (nextDelayMs == null) {
                    _connection.value = PlayerConnection.FAILED
                    connectDeferred.completeExceptionally(
                        PlayerConnectionUnavailableException("会话重连失败（已重试 $RECONNECT_MAX_ATTEMPTS 次）"),
                    )
                    return@launch
                }
                waitMs = nextDelayMs
            }
        }
    }

    /**
     * 重连成功后按断连前保留的意图恢复装载（REV-10，与断连期间 [setQueue] 的登记共用）。
     *
     * 有队列：按 `_queue/_currentIndex/最后位置/起播意图` 重新 setQueue（会再过一遍
     * 起播探测，失败时得到与正常切集一致的结构化失败）；只有单媒体：按
     * `currentMediaUri` 重新走 prepare 同款探测路径，起播位置由 pendingSeek 携带。
     */
    private fun restoreSessionState() {
        if (!pendingSessionRestore) return
        pendingSessionRestore = false
        // 与 handleDisconnected 共用同一判定（REV-10）：没有可恢复目标就什么都不装，
        // 避免空队列 / 空 URI 时 setQueue 空转、也避免误清 restorePositionMs。
        if (!shouldRestoreAfterDisconnect(_queue.value.size, currentMediaUri)) return
        val items = _queue.value
        if (items.isNotEmpty()) {
            val index = _currentIndex.value.takeIf { it in items.indices } ?: 0
            // 断连期间用户可能拖过进度（seekTo 记在 pendingSeek 上）：优先用它，否则
            // 用断连瞬间的最后位置。setQueue 内部会以同一目标键重建 pending，不会
            // 出现"恢复位置被二次消费成 0"——startPositionMs 只在 >0 时覆盖 pending。
            val targetKey = mediaKeyOf(items[index].sourceId, items[index].id)
            val resumeMs = pendingSeek?.takeIf { it.mediaKey == targetKey }?.positionMs
                ?: restorePositionMs
            setQueue(items, index, resumeMs, loadIntentPlay)
            return
        }
        val uri = currentMediaUri ?: return
        // 单媒体路径没有 mediaId（身份键为 null），同样优先用断连期间的 seek 目标
        val resumeMs = pendingSeek?.takeIf { it.mediaKey == null }?.positionMs ?: restorePositionMs
        pendingSeek = nextPendingSeek(pendingSeek, resumeMs, null)
        probeThenLoad(uri, currentMimeType, playIntent = loadIntentPlay) { applyMedia(uri) }
    }

    /**
     * 位置轮询：不设 isPlaying 门槛——缓冲中/暂停时也要回显 seek 结果与时长，
     * 否则进页仍在缓冲时进度条 `valueRange` 只有 1ms、暂停态 seek 不刷新。
     * 仅在值变化时写 StateFlow，避免无意义的 UI 失效。
     *
     * REV-04：只有控制器当前条目**就是已生效媒体**时才更新 applied*（探测/装载
     * 窗口内 controller 的位置/时长仍属于上一条媒体，不能拿去写历史）。
     * REV-11：挂起 seek 只在该媒体已生效、且落点收敛时才清——只判位置不判身份，
     * 就是旧实现"切集后挂起值永远清不掉"的成因。
     */
    private suspend fun pollPosition(c: MediaController) {
        while (currentCoroutineContext().isActive) {
            // 控制器被换掉/断连后这个轮询必须退出：旧控制器上的读数不再有意义
            if (controller !== c || !c.isConnected) return
            // A24：空队列不读位置/时长/缓冲（binder 都是 0 或旧值），只隔 1s 检查有没有新媒体。
            val hasMedia = c.currentMediaItem != null
            if (hasMedia) {
                val position = c.currentPosition.coerceAtLeast(0)
                if (_positionMs.value != position) _positionMs.value = position
                val duration = c.duration
                if (duration > 0 && _durationMs.value != duration) _durationMs.value = duration
                // 缓冲前沿夹在 [0, duration]：未就绪/未知时 media3 可能给 TIME_UNSET 或超出值，
                // 直接透传会让进度条画出一段超出时长的"假缓冲"
                val buffered = c.bufferedPosition.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
                if (_bufferedPositionMs.value != buffered) _bufferedPositionMs.value = buffered
                // REV-04：applied 身份只随已生效媒体更新
                if (isAppliedMediaCurrent(c)) {
                    if (_appliedPositionMs.value != position) _appliedPositionMs.value = position
                    if (duration > 0 && _appliedDurationMs.value != duration) _appliedDurationMs.value = duration
                    // REV-11：挂起 seek 只属于当前已生效媒体，且位置收敛到目标附近才算落实
                    if (shouldClearPendingSeekAfterPoll(pendingSeek, _appliedMediaKey.value, position, SEEK_SETTLE_MS)) {
                        pendingSeek = null
                    }
                }
                // 字幕偏移非零时，按位置重取对应时间窗的 Cue（窗口边界由 onCues 到达时机维护）
                if (subtitleOffsetMs != 0L) recomputeCues(position)
            }
            val state = _state.value
            delay(
                pollIntervalMs(
                    hasMedia = hasMedia,
                    playing = state == PlaybackState.Playing,
                    buffering = state == PlaybackState.Buffering,
                    subtitleOffsetMs = subtitleOffsetMs,
                ),
            )
        }
    }

    /** 控制器当前条目是不是"已生效媒体"（mediaId 是 media3 侧稳定身份） */
    private fun isAppliedMediaCurrent(c: MediaController): Boolean {
        if (_appliedMediaKey.value == null) return false
        val engineMediaId = c.currentMediaItem?.mediaId ?: return false
        return engineMediaId.isNotEmpty() && engineMediaId == appliedMediaId
    }

    private fun applyVideoSize(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            _videoAspectRatio.value = 0f
            return
        }
        // 旋转已在 MediaCodecVideoRenderer 侧处理：format.rotationDegrees 为 90/270 时
        // 那里已交换宽高并取倒数 pixelWidthHeightRatio，报上来的 VideoSize 就是显示尺寸
        // （unappliedRotationDegrees 恒为 0 且已弃用），这里直接算比例即可。
        _videoAspectRatio.value = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio /
            videoSize.height.toFloat()
    }

    /**
     * 等待会话就绪（REV-10）。
     *
     * 旧实现 `connected.await()` 的 deferred 是一次性的：断连后它早已完成，这里会
     * 立即"成功"返回，装载链继续 setQueue/play——全部落进已释放的控制器静默空转
     * （REV-10 的"假成功"）。现在每次连接尝试都会重新武装 deferred：
     * - CONNECTED：立即返回；
     * - CONNECTING/DISCONNECTED/RECONNECTING：挂到本次尝试上（断连时等待重连；
     *   调用方应同时观察 [connection]，DISCONNECTED 时提示用户重试）；
     * - FAILED / 本次尝试失败：抛 [PlayerConnectionUnavailableException]（可捕获），
     *   调用方据此显示"已断开"而不是无限转圈。
     */
    override suspend fun awaitReady() {
        when (_connection.value) {
            PlayerConnection.CONNECTED -> return
            PlayerConnection.FAILED -> throw PlayerConnectionUnavailableException("播放会话不可用")
            // 已断连：顺手发起一次有界重连（并发去重由状态机保证），然后等这一次
            // 尝试的结果。若 UI 已经先点了重试，这里只是挂到同一个 deferred 上。
            PlayerConnection.DISCONNECTED -> reconnect()
            else -> Unit
        }
        connectDeferred.await()
    }

    /**
     * 准备播放（E39）：先做格式预探测——"容器无解封装"或"视频编码无解码器"时
     * 直接进入结构化失败态（省掉 3–8s 白转圈）；探测通过/无结论才装载媒体。
     */
    override fun prepare(uri: String, mimeType: String, title: String, artworkUri: String?) {
        currentMediaId = ""
        currentMediaUri = uri
        currentMimeType = mimeType
        currentTitle = title
        currentArtworkUri = artworkUri
        // 单媒体 prepare 没有媒体 id，也就没有身份键（appliedMediaKey = null）
        loadingTargetKey = null
        // 新的单媒体意图不继承上一条媒体的挂起 seek
        pendingSeek = null
        // A18：新的播放意图到来，上一条媒体的回退状态/闸门一律复位
        decoderFallbackGate.onMediaChanged()
        decoderFallbackState.clear()
        probeThenLoad(uri, currentMimeType) { applyMedia(uri) }
    }

    /**
     * A18：解码器初始化失败后的**一次性受控回退**。
     *
     * 1. 把「排除失败的平台 codec」或「mp4v 只走软解链」写进进程级
     *    [decoderFallbackState]——服务进程里的解码器选择器包装（见
     *    [NpRenderersFactory]）会在下一次 prepare 查询候选时读到它；
     * 2. 记下当前位置，重新走探测 + 装载同一条媒体，尽量从原位置续播；
     * 3. [decoderFallbackGate] 保证同一媒体只回退一次：重试再失败就直接落 Error。
     *
     * 不做无限重试；解析不出 codec 名时只做"同媒体重试一次"，不臆造过滤条件。
     */
    private fun retryAfterDecoderFailure(plan: DecoderFallbackPlan, mediaKey: String?) {
        val uri = currentMediaUri ?: return
        decoderFallbackGate.markAttempted(mediaKey)
        when (plan.action) {
            DecoderFallbackAction.RETRY_WITHOUT_FAILED_CODEC -> {
                val codecName = plan.codecName
                val mime = plan.mime
                if (codecName != null && mime != null) {
                    decoderFallbackState.armExcludedCodec(mediaKey, mime, codecName)
                }
            }

            DecoderFallbackAction.RETRY_WITH_SOFT_ONLY ->
                decoderFallbackState.armSoftOnlyVideo(mediaKey, MP4V_VIDEO_MIME)

            DecoderFallbackAction.GIVE_UP -> return
        }
        // 回退不该把用户送回片头：按当前位置重新起播（媒体身份不变，
        // pollPosition 落地后会继续维护 appliedPositionMs）
        val resumeMs = _positionMs.value.coerceAtLeast(0L)
        pendingSeek = nextPendingSeek(pendingSeek, resumeMs, mediaKey)
        // 队列里能查到同 uri 的条目就带上体积/时间戳，让探测缓存命中
        val core = _queue.value.firstOrNull { it.uri == uri }
        probeThenLoad(
            uri = uri,
            expectedMime = currentMimeType,
            sizeBytes = core?.sizeBytes ?: 0L,
            lastModifiedSec = core?.lastModifiedSec ?: 0L,
            playIntent = loadIntentPlay,
            // A18 回退是"失败后的再来一次"：失败闩此刻没置、状态可能仍停在 Playing，
            // 必须显式绕过探测缓存（A17），否则回退探测会拿到上次的旧结论。
            retryingAfterFailure = true,
        ) {
            applyMedia(uri)
        }
    }

    /**
     * 起播前探测的统一入口（E80：**每条装载路径都必须过探测**）。
     *
     * [prepare] / [setQueue] / [selectQueueIndex] 三个入口最终都收敛到这里：
     *
     * ① 取消上一轮探测并把失败闩（[terminalFailure]）放下——它是"Error 是终态"的
     *    闩锁，media3 的后续回调都会被它挡掉；不在这里复位，从错误态切到可播媒体时
     *    状态永远停在旧 Error，错误面板不消、页头也不刷新；
     * ② 状态**同步**落到非终态 [PlaybackState.Buffering]（并清掉上一条的软解提示）：
     *    探测是异步的，不覆盖的话探测窗口内 UI 读到的仍是上一条媒体的 Ended / Error，
     *    会先闪一屏"播放结束"或旧错误再开始播；
     * ③ 世代号（E58 二次防御）：快速切换 A→B 时，A 的探测协程即便在极端调度下
     *    "取消晚了一步"跑完，落地前也会先核对世代号——不是最新一次就直接丢弃。
     *
     * [apply] 是 suspend 的：装载落地里最重的一段（整表 MediaItem 构建 + 封面读盘）
     * 必须能切到 IO 线程执行，见 [applyQueue]。
     */
    private fun probeThenLoad(
        uri: String,
        expectedMime: String? = null,
        sizeBytes: Long = 0L,
        lastModifiedSec: Long = 0L,
        /** 这一次装载落地后起不起播（见 [loadIntentPlay]）；探测窗口内用户按下的意图会覆盖它 */
        playIntent: Boolean = true,
        /**
         * A17：本次是不是"A18 解码器失败后的受控回退重探"。
         *
         * 该路径进入时失败闩还没置、`_state` 也可能仍停在 Playing，仅靠状态判定会漏掉，
         * 所以由 [retryAfterDecoderFailure] 显式带进来强制绕过探测缓存。
         */
        retryingAfterFailure: Boolean = false,
        apply: suspend () -> Unit,
    ) {
        // A17：必须在下面把 [terminalFailure] 复位**之前**捕获"这是不是错误后的重试"。
        // 是则本次探测 bypassCache——缓存里那条确定性失败结论是上次错误的来源，命中它
        // 等于让「重试」原样回到同一个错误。常规装载仍走缓存。
        val bypassProbeCache = shouldBypassProbeCache(
            terminalFailure = terminalFailure,
            state = _state.value,
            retryingAfterFailure = retryingAfterFailure,
        )
        if (bypassProbeCache) {
            // 与 bypassCache 互补（见 FormatProbe.invalidate）：把旧结论清掉，否则这次
            // 重探若落成瞬态结论（Unknown/读取异常不写缓存），旧的失败条目会继续躺着，
            // 下次**常规**装载还会被它"二次定罪"。
            FormatProbe.invalidate(uri)
        }
        prepareJob?.cancel()
        loadIntentPlay = playIntent
        loadPending = true
        appliedUri = null
        // 注意：这里**不**清 applied 身份（REV-04）——探测/装载窗口内旧媒体仍在
        // 会话里播，appliedMediaKey/appliedPositionMs 必须继续指向它；身份在真正
        // 换媒体（applyQueue/applyMedia）或装载失败时才改写。
        clearCues()
        // 用户已经动手切了，就别再替他猜：预探测和这一次真探测走同一条磁盘通道，
        // 平板实测撞上时单条探测从 589ms 涨到 1799ms——预热反倒拖慢了切换。
        prefetchJob?.cancel()
        terminalFailure = false
        _state.value = PlaybackState.Buffering
        _decodeNotice.value = null
        _hdrLabel.value = null
        val myGeneration = ++prepareGeneration
        perf.begin("probe uri=${uri.substringAfterLast('/')}")
        prepareJob = scope.launch {
            val probe = FormatProbe.probe(
                appContext,
                uri,
                expectedMime,
                sizeBytes,
                lastModifiedSec,
                // A17：错误后的重试强制现场重探，不被缓存里那条失败结论"二次定罪"
                bypassCache = bypassProbeCache,
            )
            perf.mark("probe done (${probe.javaClass.simpleName})")
            if (myGeneration != prepareGeneration) return@launch // 已有更新的装载，丢弃
            when (probe) {
                is FormatProbe.Result.Unplayable -> {
                    loadPending = false
                    // 引擎里的媒体已被 stop + clearMediaItems：身份/章节一并作废
                    clearAppliedIdentity()
                    chaptersKey = null
                    _chapters.value = emptyList()
                    // A12 第一期：装载失败后没有可显示的字幕，特效提示也撤掉
                    SubtitleEffectNotice.clear()
                    probedUri = uri
                    probedContainer = probe.failure.container
                    probedVideoCodec = probe.failure.videoCodec
                    probedAudioCodec = probe.failure.audioCodec
                    // 先停掉会话里可能还在播的上一个媒体，再落终态失败
                    runCatching { activeController?.stop() }
                    // 连同**上一条媒体的进度残留**一起清掉：media3 的 stop() 不重置
                    // 播放位置/时长（上一条停在 1:03 就还是 1:03），不清的话错误面板
                    // 背後与迷你条会把"什么都没装载"显示成"快播完了"。
                    runCatching { activeController?.clearMediaItems() }
                    _positionMs.value = 0L
                    _durationMs.value = 0L
                    _bufferedPositionMs.value = 0L
                    terminalFailure = true
                    _state.value = PlaybackState.Error(probe.failure.reason, probe.failure)
                }
                is FormatProbe.Result.Playable -> {
                    probedUri = uri
                    probedContainer = probe.container
                    probedVideoCodec = probe.videoCodec
                    probedAudioCodec = probe.audioCodec
                    _hdrLabel.value = probe.hdr
                    // C21：仅软解可用时给前置提示（能播，但可能发热/掉帧）。
                    // A11：音轨不可解时也必须说清——播放继续，但用户会以为是设备坏了。
                    _decodeNotice.value = buildList {
                        if (probe.audioUnsupported) add("音轨编码本机不可解，将静音播放")
                        if (probe.softwareVideoOnly) add("该视频仅有软件解码器可用，高码率下可能掉帧或发热")
                    }.takeIf { it.isNotEmpty() }?.joinToString("；")
                    apply()
                }
                FormatProbe.Result.Unknown -> {
                    _decodeNotice.value = null
                    apply()
                }
            }
        }
    }

    /**
     * 邻域预探测（§11.2 L2 的探测侧一半）。
     *
     * L2 已经把下一集的**数据**预缓冲好了，可 E80 的起播探测仍留在切集那一刻现场跑——
     * 实测 0.5–1.5s 全落在用户等的那段时间里（手机基线：选集切换 1255–1861ms 中
     * probe 占 507–953ms）。当前集进入 READY 后，把预载窗口内的邻域条目按同一文件指纹
     * 先探一遍、写进 [FormatProbe] 的结果缓存：用户点过去时探测直接命中。
     *
     * 串行探，且每探一条前核对当前下标——切集之后邻域窗口已经变了，旧循环不该继续抢 I/O。
     */
    private fun prefetchProbeWindow() {
        val items = _queue.value
        val current = _currentIndex.value
        if (items.isEmpty() || current !in items.indices) return
        val window = ((current - PROBE_PREFETCH_BACK)..(current + PROBE_PREFETCH_FWD))
            .filter { it in items.indices && it != current }
        if (window.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(PROBE_PREFETCH_START_DELAY_MS)
            for (index in window) {
                // 下标一动，剩下的邻域就不是"用户接下来最可能点的那几条"了，收手
                if (_currentIndex.value != current) return@launch
                // **不许和当前集的缓冲抢磁盘**：等当前项的缓冲前沿领先到准入线再探。
                // 没有这道闸时预热会在起播刚就绪就去读另外两条 1GB 文件的头，
                // 手机同条件 A/B 实测把同一次冷启的探测从 1.6s 拖到 2.4s。
                // 等不到就放弃本轮（慢盘 / 一直缺缓冲），下一次 READY 再来——
                // 预热是锦上添花，不许变成新的等待来源。
                var waitedMs = 0L
                while (_bufferedPositionMs.value - _positionMs.value < PROBE_PREFETCH_MIN_AHEAD_MS) {
                    if (_currentIndex.value != current) return@launch
                    if (waitedMs >= PROBE_PREFETCH_WAIT_BUDGET_MS) return@launch
                    delay(PROBE_PREFETCH_WAIT_STEP_MS)
                    waitedMs += PROBE_PREFETCH_WAIT_STEP_MS
                }
                val item = items[index]
                FormatProbe.probe(
                    appContext,
                    item.uri,
                    item.mimeType,
                    item.sizeBytes,
                    item.lastModifiedSec,
                    // 排障面板只该看见"最近起播的那一条"，预热不写它
                    recordMemo = false,
                )
            }
        }
    }

    override fun attachDisplay(view: Any?) {
        if (attachedDisplay === view) return
        NpPerf.sessionMark("display attach ${System.identityHashCode(view)} previous=${System.identityHashCode(attachedDisplay)}")
        attachedDisplay = view
        refreshPausedFrameOnSurface = view != null
        attachSurfaceTo(controller)
    }

    override fun detachDisplay(view: Any) {
        NpPerf.sessionMark("display detach ${System.identityHashCode(view)} current=${System.identityHashCode(attachedDisplay)}")
        if (attachedDisplay !== view) return
        attachedDisplay = null
        refreshPausedFrameOnSurface = false
        attachSurfaceTo(controller)
    }

    /**
     * 把 UI 侧表面挂到会话。
     *
     * 批次 3 用 TextureView 而非 SurfaceView：镜像/旋转只能靠视图变换实现，而
     * SurfaceView 的画面由 SurfaceFlinger 独立合成、不吃任何 View/Compose 变换
     * （`setProjection` 又不是公开 API）。TextureView 走正常渲染节点，变换生效。
     */
    private fun attachSurfaceTo(c: MediaController?) {
        if (c == null) return
        when (val view = attachedDisplay) {
            is SurfaceView -> c.setVideoSurfaceView(view)
            is TextureView -> c.setVideoTextureView(view)
            else -> c.setVideoSurfaceView(null)
        }
    }

    /**
     * 装载整个队列并从 [startIndex] 起播（§8.2）。
     *
     * 走 `setMediaItems(items, startIndex, startPositionMs)` 而非逐项
     * `setMediaItem`：队列进入会话后，系统媒体通知自动出现 ⏮⏭，
     * `STATE_ENDED` 时由 media3 自动推进到下一项——连播由此**免费获得**，
     * 无需自己监听结束再手工装载（手工装载在后台/锁屏场景下容易漏）。
     *
     * 每项都按 [prepare] 同款带上标题与封面，否则切集后通知栏会丢掉元数据。
     */
    override fun setQueue(
        items: List<com.noneplayer.core.media.MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        playOnLoad: Boolean,
    ) {
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        // REV-10：先把"要装什么"记进引擎，再判连接。断连期间用户点播放/切集时
        // controller 为 null，若像旧实现那样直接 return，意图就丢了、重连后无从恢复。
        _queue.value = items
        _currentIndex.value = safeIndex
        loadIntentPlay = playOnLoad
        val target = items[safeIndex]
        setCurrentTarget(target)
        // A18：新的队列/新的落点 = 新的播放意图，回退状态与闸门复位
        decoderFallbackGate.onMediaChanged()
        decoderFallbackState.clear()
        // REV-11：本次装载的起播位置挂到目标媒体的身份键上，只有该媒体真正生效
        // （applyQueue）后才消费；探测窗口里它一直保留，不会被误当成旧媒体的位置。
        // 起播位置为 0（从头播）时：只保留本来就属于目标媒体的挂起 seek（用户在
        // 探测窗口里拖过这一条），旧媒体的挂起值一律丢弃，不让它残留到下一次装载。
        loadingTargetKey = mediaKeyOf(target.sourceId, target.id)
        pendingSeek = if (startPositionMs > 0) {
            nextPendingSeek(pendingSeek, startPositionMs, loadingTargetKey)
        } else {
            pendingSeek?.takeIf { it.mediaKey == loadingTargetKey }
        }
        if (activeController == null) {
            // 断连：只登记意图，等 reconnect() 成功后由 restoreSessionState() 恢复
            loadPending = false
            pendingSessionRestore = true
            return
        }

        // E80 修复：**队列路径也必须过起播前探测**（详细缘由见 [probeThenLoad]）。
        //
        // 批次 1 引入队列后，播放页改走 setQueue()，而 setQueue 直接 setMediaItems +
        // c.prepare()，**绕过了 prepare() 里的 FormatProbe**。后果是 E39 的"体面失败"
        // 在队列路径上整体失效：以 wmv2-clip.avi 为例，AviExtractor 会丢弃它不认识的
        // WMV2 视频轨（`Missing Stream Format`），只剩 MP3 音轨——于是播放器"成功地"
        // 播完 4 秒音频并显示"播放结束"，用户只看到**纯黑屏**，无从知道是编码不支持。
        //
        // 这里对**即将播放的那一项**做探测：不可解则不装载队列，直接落结构化失败态。
        probeThenLoad(
            uri = target.uri,
            expectedMime = target.mimeType,
            sizeBytes = target.sizeBytes,
            lastModifiedSec = target.lastModifiedSec,
            playIntent = playOnLoad,
        ) {
            applyQueue(items, safeIndex, startPositionMs)
        }
    }


    /** setQueue 成功路径的装载体（探测通过后调用），保持与探测前的行为一致 */
    @OptIn(UnstableApi::class)
    private suspend fun applyQueue(
        coreItems: List<com.noneplayer.core.media.MediaItem>,
        safeIndex: Int,
        startPositionMs: Long,
    ) {
        val c = activeController ?: run {
            // 断连：装载没有落地，保留意图等重连恢复（绝不静默假成功）
            loadPending = false
            pendingSessionRestore = true
            return
        }
        // 同 [applyMedia]：任何一次新装载都放下失败闩（旧错误面板不许跨媒体残留）
        terminalFailure = false
        coreItems.getOrNull(safeIndex)?.let { _currentUri.value = it.uri }
        // REV-11/REV-04：pending 只属于"目标媒体"，且只有目标媒体真正落地才消费；
        // 落点用 pending 的位置，而不是 setQueue 时捕获的旧 startPositionMs。
        val target = coreItems[safeIndex]
        val targetKey = mediaKeyOf(target.sourceId, target.id)
        val resolvedStartMs = resolveStartPosition(pendingSeek, targetKey, startPositionMs)
        if (shouldConsumePendingSeek(pendingSeek, targetKey)) pendingSeek = null
        loadingTargetKey = targetKey
        // 批次 3 修复：队列路径此前从不带 SubtitleConfiguration——字幕记忆/同名挂载
        // 在"进页即 setQueue"的主流程上整体失效（applyMedia 只服务单媒体项路径）。
        // 给**即将播放的那一项**烘上当前字幕地址；切集后由 setSubtitle 走重建路径。
        val mount = subtitle
        // 建表必须在 IO：整表 MediaItem 会为**每一项**准备封面元数据，此前还逐项把封面
        // 文件读成 artworkData——一季 100 集就是 100 次同步读盘 + 一次超大 Binder 事务，
        // 全压在装载协程（主线程）上，表现就是"点播放转圈很久 / 切集卡死"。
        // 现在只有**即将播放的那一项**带封面字节（通知/锁屏立即可见），其余项只给
        // artworkUri 让系统异步加载——队列语义不变，成本从 O(队列) 降到 O(1)。
        val items = withContext(Dispatchers.IO) {
            coreItems.mapIndexed { index, src ->
                val built = src.toMediaItem(withArtwork = index == safeIndex)
                if (mount != null && index == safeIndex) {
                    built.buildUpon()
                        .setSubtitleConfigurations(listOf(subtitleConfiguration(mount)))
                        .build()
                } else {
                    built
                }
            }.also { perf.mark("built ${it.size} media items") }
        }
        // 建表期间可能已经来了更新的装载（A→B 快速切换会 cancel 本次 prepareJob）：
        // withContext 自身会在挂起点抛出取消，但"IO 刚回来、还没落盘"这一小段
        // 是纯同步代码，必须自己再核一次，别把旧队列盖到新装载上。
        currentCoroutineContext().ensureActive()
        appliedUri = items[safeIndex].localConfiguration?.uri?.toString()
        appliedItems = items
        appliedQueueIds = queueIdentity(coreItems)
        mountedSubtitle = mount
        // A12 第一期：目标媒体没挂字幕时，上一条片子的「含特效」提示不该残留
        if (mount == null) SubtitleEffectNotice.clear()
        loadPending = false
        c.setMediaItems(items, safeIndex, resolvedStartMs.coerceAtLeast(0L))
        c.prepare()
        // REV-04：整表装载落地 = 目标媒体真正生效，applied 身份切到它（位置归零/
        // 续播位置，时长先清 0 等轮询填）
        setAppliedMedia(targetKey, items[safeIndex].mediaId, resolvedStartMs)
        // A08 的"装载即起播"要按**这一次装载的意图**决定：`playOnLoad = false`
        // （竖屏未播放态）时停在就绪态。不能靠调用方事后 `pause()`——那一记落在
        // 探测窗口里是空操作，被这里的一句 `play()` 原样覆盖（真机实报自动起播）。
        // 读的是 [loadIntentPlay] 而不是入参：探测窗口里用户按下的 ▶/⏸ 已经改写它。
        if (loadIntentPlay) c.play() else c.pause()
        perf.mark("setMediaItems idx=$safeIndex applied")
    }

    /**
     * 选集/自动切集的落点：**同样是一条装载路径**，因此与 setQueue 走同一条探测闸。
     *
     * 曾经的实现只做 `seekToDefaultPosition(index)`：从失败项（Error 终态 + 闩锁）
     * 切到正常项时，闩锁不复位、media3 回调被挡，错误面板就永远盖在画面上；
     * 切到不可解项时又会绕过 E80 的探测，黑屏把音轨播完。
     *
     * 所以这里分两条路：
     *
     * ① **快路径**——只有 [canFastSelectQueueIndex] 全部放行才走（控制器里装着的
     *    **就是**这份队列：已应用 id 序列逐项相同、引擎项数一致、没有在途装载、
     *    没有失败闩）。此时直接 seek 到那一项：再整表 `setMediaItems` + 重新探测
     *    会和正在打开的文件抢句柄，切集停在转圈，直到进程重启才把句柄放掉。
     * ② **探测 + 整表重设**——其余情况（含探测尚未落地、队列身份对不上、从失败态
     *    切出）。队列可能因上一次探测失败**根本没进 media3**（setQueue 失败时
     *    applyQueue 未执行），此时对旧播放列表 seek 会指到别的媒体甚至越界；
     *    另外两份**等长**的文件夹也不能按项数就当成同一条队列（A15）。
     */
    override fun selectQueueIndex(index: Int) {
        val items = _queue.value
        if (index !in items.indices) return
        val target = items[index]
        val targetKey = mediaKeyOf(target.sourceId, target.id)
        // A18：切集是新的播放意图（重播同一集也算），回退状态与闸门复位
        decoderFallbackGate.onMediaChanged()
        decoderFallbackState.clear()
        val c = activeController
        if (c == null) {
            // REV-10：断连期间切集也要登记意图（用户点的那一集是重连后的落点）
            _currentIndex.value = index
            setCurrentTarget(target)
            _currentUri.value = target.uri
            loadIntentPlay = true
            pendingSessionRestore = true
            return
        }
        val fast = canFastSelectQueueIndex(
            appliedQueueIds = appliedQueueIds,
            requestedQueueIds = queueIdentity(items),
            engineItemCount = c.mediaItemCount,
            loadPending = loadPending,
            terminalFailure = terminalFailure,
        )
        if (fast && index == c.currentMediaItemIndex) {
            // 同一集：挂起 seek 只在属于它时才保留（REV-11，旧实现的"无脑保留"会
            // 在切集后把位置拉回上一集的目标）
            pendingSeek = pendingSeek?.takeIf { it.mediaKey == targetKey }
            // A23：播完后再点同一集 = 重播，ENDED 上直接 play() 是空操作，先退位
            if (_state.value == PlaybackState.Ended) {
                c.seekTo(0L)
                pendingSeek = null
                if (_appliedMediaKey.value != null) _appliedPositionMs.value = 0L
            }
            c.play()
            return
        }
        if (fast) {
            _currentIndex.value = index
            // 新一集不该继承上一集的外挂字幕（与整表重建分支同一语义）。
            // 该集自己的记忆字幕由会话层在切下标后解析并重挂（A4）。
            subtitle = null
            mountedSubtitle = null
            // REV-11：快路径没有重新探测，pending 只在属于目标媒体时才带过去
            pendingSeek = pendingSeek?.takeIf { it.mediaKey == targetKey }
            loadingTargetKey = targetKey
            setCurrentTarget(target)
            _currentUri.value = target.uri
            appliedUri = target.uri
            loadPending = false
            c.seekToDefaultPosition(index)
            c.play()
            return
        }
        _currentIndex.value = index
        subtitle = null
        mountedSubtitle = null
        // REV-11：慢路径丢弃属于上一集的挂起 seek（它已经不可能落实了），
        // 只保留属于目标媒体的那条；旧实现这里直接置 null，会把"切集前 seek
        // 到 30s 再切集"的意图连同用户的 seek 一起丢掉。
        pendingSeek = pendingSeek?.takeIf { it.mediaKey == targetKey }
        loadingTargetKey = targetKey
        setCurrentTarget(target)
        probeThenLoad(target.uri, target.mimeType, target.sizeBytes, target.lastModifiedSec) {
            // 切集是一次播放意图（用户点的那一集就是要看的），照旧装载即起播
            applyQueue(items, index, 0L)
        }
    }

    /**
     * 清空队列与已装载媒体（迷你条 ✕ / "后台播放"关闭退页，L8）。
     *
     * `clearMediaItems()` 是这里的关键：系统媒体通知与 PlaybackService 的生命周期
     * 由 media3 会话的 media items 决定，只 `pause()` 会让通知以"暂停态"残留。
     * 同时把位置/时长/缓冲位清回 0——media3 的 `stop()` 不重置这些，留给上一条媒体
     * 的话，迷你条会把"什么都没装载"画成"快播完了"。
     */
    override fun clearQueue() {
        prepareJob?.cancel()
        prefetchJob?.cancel()
        prepareGeneration++
        terminalFailure = false
        // 会话级状态不跟着媒体残留：循环/擦洗/裁剪一并复位
        setLoopMode(NpLoopMode.OFF)
        setScrubbing(false)
        setVideoCrop(false)
        val c = activeController
        runCatching { c?.stop() }
        runCatching { c?.clearMediaItems() }
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentUri.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedPositionMs.value = 0L
        // REV-11：队列都没了，挂起 seek 与断连恢复意图一并作废
        pendingSeek = null
        loadingTargetKey = null
        pendingSessionRestore = false
        restorePositionMs = 0L
        // A18：队列都没了，解码器回退状态与闸门一并复位
        decoderFallbackGate.onMediaChanged()
        decoderFallbackState.clear()
        // REV-04/REV-09：身份与章节归属随媒体一起清空（旧实现不清 _chapters，
        // 迷你条/播放页会一直显示上一个视频的章节列表）
        clearAppliedIdentity()
        chaptersKey = null
        _chapters.value = emptyList()
        loadPending = false
        currentMediaUri = null
        currentMediaId = ""
        currentArtworkUri = null
        currentTitle = ""
        subtitle = null
        mountedSubtitle = null
        // A12 第一期：队列清空后字幕与特效提示都不再属于任何媒体
        SubtitleEffectNotice.clear()
        _state.value = PlaybackState.Idle
        clearCues()
        _decodeNotice.value = null
        _hdrLabel.value = null
    }

    override fun playNext() {
        val next = _currentIndex.value + 1
        if (next in _queue.value.indices) {
            perf.begin("nav next")
            selectQueueIndex(next)
        }
    }

    override fun playPrevious() {
        val previous = _currentIndex.value - 1
        if (previous in _queue.value.indices) {
            perf.begin("nav previous")
            selectQueueIndex(previous)
        }
    }

    /**
     * 媒体键按钮 → 同进程广播（A2 / E144-1）。
     *
     * 旧实现把 `ACTION_MEDIA_BUTTON` 广播给 media3 的 `MediaButtonReceiver`：那套通路
     * 自带"服务被系统杀掉也能被叫回来"的语义，但 media3 在 API 26+ **只允许 PLAY 类
     * 按键冷启动前台服务**（避免 `ForegroundServiceDidNotStartInTimeException`），
     * 非 PLAY 键一律丢弃——真机 logcat 实证：
     *
     * ```
     * W MediaButtonReceiver: Ignore key event that is not a 'play' command on API 26 or
     *   above to avoid an 'ForegroundServiceDidNotStartInTimeException'
     * ```
     *
     * 小窗上的 ⏸ / ⏭ 因此静默无操作（▶ 能用）。这里改走自建接收器
     * [NpMediaActionReceiver] + [NpMediaActionBridge]：显式组件、同进程直达当前
     * 引擎实例的 `MediaController`，不依赖系统媒体栈，也不触发前台服务冷启动。
     * 请求码用 action 序号：一颗钮一份 PendingIntent，互不覆盖。
     */
    override fun mediaButtonIntent(action: NpMediaButtonAction): Any? {
        val intent = Intent(appContext, NpMediaActionReceiver::class.java).apply {
            this.action = NpMediaActionReceiver.ACTION_NP_MEDIA_BUTTON
            putExtra(NpMediaActionReceiver.EXTRA_ACTION, action.name)
        }
        return PendingIntent.getBroadcast(
            appContext,
            action.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** E144-1：把 PiP/通知动作落到会话控制器上（接收器经 [NpMediaActionBridge] 调用）。 */
    private fun dispatchMediaAction(c: MediaController, action: NpMediaButtonAction) {
        runCatching {
            when (action) {
                NpMediaButtonAction.PLAY -> c.play()
                NpMediaButtonAction.PAUSE -> c.pause()
                NpMediaButtonAction.NEXT -> c.seekToNext()
                NpMediaButtonAction.PREVIOUS -> c.seekToPrevious()
            }
        }
    }

    /**
     * 队列项 → media3 MediaItem（id、标题、封面、字幕与 `.cue` 裁剪窗口一并带上）。
     *
     * [withArtwork] = true 时才把封面**读成字节**（artworkData）：本地缩略图很小，
     * 烘成字节经 Binder 同步送达会话，通知栏/锁屏立即可见。这只对"即将播放的那一项"
     * 值得做——整表逐项读盘会让一季 100 集的队列装载多出 100 次同步 IO 与一次超大
     * Binder 事务（点播放转圈 / 切集卡死的主因）。其余项只给 artworkUri，由系统异步加载。
     */
    @OptIn(UnstableApi::class)
    private fun com.noneplayer.core.media.MediaItem.toMediaItem(withArtwork: Boolean): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            // A1-2：把权威 id 烘进 media3 条目——通知/锁屏的点击落点由 PlaybackService
            // 从当前条目重建深链，此前那里只能取到 uri，回跳后路由 id 为空，
            // 全部 per-media 记忆（断点/字幕/画面/仅音频）静默退化到空键桶。
            .setMediaId(id)
            .apply {
                // `.cue` 分轨（§12.4/G4，批次 8）：窗口圈定后 position 从窗口起点计
                // （seek/断点都是"轨内"语义）。WAV/FLAC 可 seek，无需 allowUnseekableMedia 特批（G4 ④）
                if (isClipped) {
                    setClippingConfiguration(
                        ClippingConfiguration.Builder()
                            .setStartPositionUs(clipStartUs)
                            .apply { if (clipEndUs > 0) setEndPositionUs(clipEndUs) }
                            .build(),
                    )
                }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { null })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply {
                        val bytes = if (withArtwork) readArtworkBytes(coverUri) else null
                        if (bytes != null) {
                            setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        } else {
                            coverUri?.let { setArtworkUri(it.toUri()) }
                        }
                    }
                    .build(),
            )
            .build()

    override fun setSpeed(speed: Float) {
        // 先记偏好再下发：断连期间也要保留，重连后由 installController 重新下发
        _speed.value = speed
        activeController?.playbackParameters = PlaybackParameters(speed)
    }

    private val _loopMode = MutableStateFlow(NpLoopMode.OFF)
    override val loopMode: StateFlow<NpLoopMode> = _loopMode.asStateFlow()

    /**
     * 循环模式 → media3：OFF/ONE/ALL 直接映射 repeatMode；
     * SHUFFLE = 列表循环 + shuffleModeEnabled（media3 按默认 ShuffleOrder 洗牌队列）。
     */
    override fun setLoopMode(mode: NpLoopMode) {
        // 先记偏好再下发：断连期间保留，重连后由 installController 重新下发
        _loopMode.value = mode
        activeController?.let { applyLoopModeTo(it, mode) }
    }

    /** 把循环模式下发到一条连接（[setLoopMode] 与重连恢复共用） */
    private fun applyLoopModeTo(c: MediaController, mode: NpLoopMode) {
        when (mode) {
            NpLoopMode.OFF -> {
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.shuffleModeEnabled = false
            }
            NpLoopMode.ONE -> {
                c.repeatMode = Player.REPEAT_MODE_ONE
                c.shuffleModeEnabled = false
            }
            NpLoopMode.ALL -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = false
            }
            NpLoopMode.SHUFFLE -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = true
            }
        }
    }

    /**
     * 挂载/清除外挂字幕（E32）：清除与"复原已挂载文件"改走文本轨开关，
     * 不重建媒体项、不重新缓冲；只有更换新文件才更新媒体项。
     *
     * [format] 与地址一起来（A3）：只换格式不换地址（例如记忆里的字幕重新判出
     * `.ass`）同样要重建，所以比较用整个挂载对，不用裸 uri。
     */
    override fun setSubtitle(uri: String?, format: NpSubtitleFormat?) {
        // A12 第一期：字幕卸下时一并撤掉「含特效」提示，避免下一条片子/无字幕画面残留
        if (uri == null) SubtitleEffectNotice.clear()
        val previous = subtitle
        subtitle = uri?.let { SubtitleMount(it, format) }
        val media = currentMediaUri ?: return
        if (activeController == null) return
        when {
            subtitle == previous -> Unit
            subtitle == null && mountedSubtitle != null ->
                setTextTracksEnabled(false)
            subtitle != null && subtitle == mountedSubtitle ->
                setTextTracksEnabled(true)
            // 更换字幕文件：唯一需要重建媒体项的分支（探测未完成时由 applyMedia 带上）
            canRemountCurrentItem(media) -> replaceCurrentSubtitle()
            else -> Unit
        }
    }

    private fun clearCues() {
        cueHistory.clear()
        _cues.value = emptyList()
    }

    private fun setCurrentTarget(item: com.noneplayer.core.media.MediaItem) {
        currentMediaUri = item.uri
        currentMediaId = item.id
        currentMimeType = item.mimeType
        currentTitle = item.title
        currentArtworkUri = item.coverUri
    }

    /** Preserve the actual item, including identity, metadata and CUE clipping. */
    @OptIn(UnstableApi::class)
    private fun replaceCurrentSubtitle() {
        val c = activeController ?: return
        val items = appliedItems
        if (items.isEmpty()) {
            // applyMedia 现在是 suspend（封面读盘走 IO），这里只能起协程接力——
            // 语义不变：队列未装载时按单媒体项重建。
            currentMediaUri?.let { uri -> scope.launch { applyMedia(uri) } }
            return
        }
        val index = _currentIndex.value.takeIf { it in items.indices }
            ?: c.currentMediaItemIndex.takeIf { it in items.indices }
            ?: return
        val local = items[index]
        // REV-11：pending 只有在**属于当前已生效媒体**时才是"重建后的恢复位置"；
        // 属于上一条/目标媒体的挂起 seek 不能拿来定位（旧实现直接用裸 pendingSeekMs）。
        val position = pendingSeek
            ?.takeIf { it.mediaKey == _appliedMediaKey.value }
            ?.positionMs
            ?: c.currentPosition.coerceAtLeast(0L)
        val playWhenReady = c.playWhenReady
        val subtitles = subtitle?.let { listOf(subtitleConfiguration(it)) }.orEmpty()
        // 控制器回传的 MediaItem 会丢掉 LocalConfiguration，不能拿它的 mediaId/uri 当闸门。
        // replaceMediaItem 对当前项的外挂字幕经常不重新挂上；整表 setMediaItems 是
        // 探测期间挂字幕已经验证过的路径，同时保留队列、mediaId、标题和裁剪窗口。
        val replacement = local.buildUpon()
            .setSubtitleConfigurations(subtitles).build()
        val updated = items.toMutableList().also { it[index] = replacement }
        appliedItems = updated
        val mediaUri = local.localConfiguration?.uri?.toString()
        if (mediaUri != null) {
            appliedUri = mediaUri
            currentMediaUri = mediaUri
        }
        currentMediaId = local.mediaId
        c.setMediaItems(updated, index, position.coerceAtLeast(0L))
        c.prepare()
        c.playWhenReady = playWhenReady
        // REV-04：同一条目重建（身份不变），applied 位置跟着落到重建位置；
        // 时长不清——媒体没变，等轮询照旧刷新
        if (_appliedMediaKey.value != null) _appliedPositionMs.value = position.coerceAtLeast(0L)
        mountedSubtitle = subtitle
        // 新挂上的外挂字幕要顶掉内嵌轨的 SELECTION_FLAG_DEFAULT 平票（E144）
        pendingExternalSubtitleSelect = mountedSubtitle != null
        setTextTracksEnabled(textTracksEnabled)
    }

    /**
     * 晚挂字幕时，控制器侧 URI 和本地提交的 URI 可能只差 `file:/` 与 `file:///`。
     * 字符串相等会让 [setSubtitle] 直接返回，外挂字幕永远不进当前队列项。
     */
    private fun canRemountCurrentItem(media: String): Boolean {
        if (loadPending || appliedItems.isEmpty()) return false
        if (sameMediaUri(media, appliedUri)) return true
        val index = _currentIndex.value.takeIf { it in appliedItems.indices } ?: return false
        return sameMediaUri(media, appliedItems[index].localConfiguration?.uri?.toString())
    }

    private fun sameMediaUri(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        return runCatching { a.toUri() == b.toUri() }.getOrDefault(false)
    }

    /**
     * 文本轨开关（TrackSelectionParameters 对当前与后续媒体项生效）。
     * 批次 3 起是公开契约：字幕面板的"关闭内嵌字幕"与外挂挂载共用同一开关（E32）。
     */
    override fun setTextTracksEnabled(enabled: Boolean) {
        textTracksEnabled = enabled
        val c = activeController ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    override fun setVideoTracksEnabled(enabled: Boolean) {
        videoTracksEnabled = enabled
        val c = activeController ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** 内嵌轨道枚举：音轨/字幕轨各一条目，label 组合语言/标题/声道/角色标签 */
    private fun publishTracks(tracks: Tracks) {
        val map = HashMap<String, Pair<Tracks.Group, Int>>()
        val list = buildList {
            tracks.groups.forEach { group ->
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> NpTrackType.AUDIO
                    C.TRACK_TYPE_TEXT -> NpTrackType.TEXT
                    else -> return@forEach
                }
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    // 同一 format.id 的多轨道（极少见）只暴露第一条，选择键保持稳定
                    val id = format.id ?: "${type.name}_$i"
                    if (map.containsKey(id)) continue
                    map[id] = group to i
                    // 外挂字幕被 media3 平铺进同一条文本轨组、没有自己的名字，
                    // 落到下面的"轨道 N"兜底就把外挂读成了内嵌轨（B4 真机实报）。
                    val label = "外挂字幕".takeIf {
                        type == NpTrackType.TEXT && id.endsWith(EXTERNAL_SUBTITLE_TRACK_ID)
                    } ?: buildList {
                        format.label?.let { add(it) }
                        format.language?.let { add(languageName(it)) }
                        if (type == NpTrackType.AUDIO) {
                            format.channelCount.takeIf { it > 0 }?.let { add("${it}声道") }
                        }
                        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add("导评")
                        if (format.roleFlags and C.ROLE_FLAG_DUB != 0) add("配音")
                        if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) add("解说")
                    }.ifEmpty { listOf("轨道 ${size + 1}") }
                        .distinct()
                        .joinToString(" · ")
                    add(NpTrack(id = id, type = type, label = label, selected = group.isTrackSelected(i)))
                }
            }
        }
        trackGroupById = map
        _tracks.value = list
    }

    /** ISO 639 语言码的轻量展示（内嵌轨道面板不引 ICU 大表，常见码直译，其余原样展示） */
    private fun languageName(code: String): String = when (code.lowercase()) {
        "zh", "zho", "chi" -> "中文"
        "en", "eng" -> "英语"
        "ja", "jpn" -> "日语"
        "ko", "kor" -> "韩语"
        "und", "mis" -> "未知"
        else -> code
    }

    /** 章节聚合：tracks 各 format 的 metadata + onMetadata 推送，按 startMs 去重排序 */
    @OptIn(UnstableApi::class)
    private fun publishChapters(tracks: Tracks, metadata: Metadata?) {
        val raw = buildList {
            tracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    group.getTrackFormat(i).metadata?.let { md ->
                        for (j in 0 until md.length()) add(md[j])
                    }
                }
            }
            metadata?.let { md ->
                for (j in 0 until md.length()) add(md[j])
            }
        }
        val entries = raw.filterIsInstance<Chapter>()
            .filter { !it.isHidden }
            .mapNotNull { entry ->
                val title = entry.title?.value?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NpChapter(
                    startMs = entry.startTimeMs,
                    endMs = entry.endTimeMs,
                    title = title,
                )
            }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
        // REV-09：章节属于"当前已生效媒体"。
        // ① 回调可能晚到（换集瞬间旧媒体的 metadata 才到）——只在回调键与当前
        //    归属键一致时才写，不能把上一集的章节写到新一集上；
        // ② 同一媒体收到空回调（部分容器只在 metadata 通道给章节、tracks 通道
        //    为空）不许清掉已有效章节——旧实现 `if (entries.isNotEmpty())` 正是
        //    为躲这个坑，但代价是永不清空（换集后仍显示上一集章节）。
        val callbackKey = ChapterIdentity.keyOf(
            _appliedMediaKey.value,
            currentMediaUri ?: _currentUri.value,
        ) ?: return
        if (!ChapterIdentity.shouldApplyChapters(
                currentKey = chaptersKey,
                callbackKey = callbackKey,
                incomingCount = entries.size,
                existingCount = _chapters.value.size,
            )
        ) {
            return
        }
        chaptersKey = callbackKey
        _chapters.value = entries
    }

    /**
     * 轨道选择：清掉同类型既有 override 再加新 override（TrackSelectionParameters
     * 经 MediaController 设置，对会话侧播放器生效且不重建媒体项）。
     */
    override fun selectTrack(type: NpTrackType, id: String) {
        val c = activeController ?: return
        // 用户手选轨道即作废"待选中外挂轨"：之后轨道回调不再把选择抢回外挂轨
        pendingExternalSubtitleSelect = false
        // 文本轨整体被关掉时，单加 override 不会让它出字；先恢复类型开关。
        if (shouldEnableTextTracks(type, textTracksEnabled)) setTextTracksEnabled(true)
        // 轨道表可能在上一次 publish 之后已经换过：用当前 Tracks 重建，避免旧 group/index。
        publishTracks(c.currentTracks)
        val (group, index) = trackGroupById[id] ?: return
        val trackType = when (type) {
            NpTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            NpTrackType.TEXT -> C.TRACK_TYPE_TEXT
        }
        runCatching {
            val trackGroup = group.mediaTrackGroup
            c.trackSelectionParameters = c.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(trackType)
                .addOverride(TrackSelectionOverride(trackGroup, index))
                .build()
        }
    }

    /**
     * 挂上外挂字幕之后，把轨道选中落到外挂轨上（E144）。
     *
     * 外挂轨与内嵌轨都带 [C.SELECTION_FLAG_DEFAULT]：平票时 media3 按轨道顺序选，
     * 内嵌轨（容器里先出现）赢——于是"已挂载"只是菜单里的一行，屏上仍是内嵌文案。
     * 只在 [pendingExternalSubtitleSelect] 这一拍强制一次；用户手选过轨道后
     * （[selectTrack] 清标记）不再干预。
     */
    private fun applyPendingExternalSubtitleSelection(tracks: Tracks) {
        if (!pendingExternalSubtitleSelect) return
        val group = tracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && g.length > 0 &&
                // media3 会给侧载字幕的 Format.id 加一段容器/条目前缀（实测形如
                // "1:np.external.subtitle"），用后缀匹配；不同版本加不加前缀都能中。
                g.getTrackFormat(0).id?.endsWith(EXTERNAL_SUBTITLE_TRACK_ID) == true
        } ?: return
        if (!needsExternalSubtitleSelection(
                pending = pendingExternalSubtitleSelect,
                textTracksEnabled = textTracksEnabled,
                externalTrackPresent = true,
                externalTrackSelected = group.isTrackSelected(0),
            )
        ) return
        pendingExternalSubtitleSelect = false
        val c = activeController ?: return
        runCatching {
            c.trackSelectionParameters = c.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        }
    }

    /** 字幕偏移（E43）：正数=延后。立即按当前位置重取时间窗 */
    override fun setSubtitleOffsetMs(offsetMs: Long) {
        subtitleOffsetMs = offsetMs
        activeController?.let { recomputeCues(it.currentPosition.coerceAtLeast(0)) }
    }

    /**
     * 画面裁剪（§2.4）：cover / fit。setVideoScalingMode 是 ExoPlayer（会话侧）
     * API、MediaController 不暴露，与 np.scrub 同路转发（见 PlaybackService）。
     */
    override fun setVideoCrop(crop: Boolean) {
        activeController?.sendCustomCommand(
            SessionCommand(CROP_COMMAND, Bundle.EMPTY),
            Bundle().apply { putBoolean(CROP_ARG_ACTIVE, crop) },
        )
    }

    /**
     * 自动连播下一集（设置页「播放」组，L1）。
     *
     * 关闭时让会话侧每条播完停在末帧（`setPauseAtEndOfMediaItems(true)`）——
     * 那是 ExoPlayer 而非 `Player` 接口上的方法，MediaController 调不到，
     * 与 np.scrub / np.crop 同路经自定义命令转发（见 PlaybackService）。
     */
    override fun setAutoplayNext(enabled: Boolean) {
        autoplayNext = enabled
        sendAutoplayFlag()
    }

    /** 补发当前自动连播偏好；控制器未就绪时是空操作（连上后会补发一次） */
    private fun sendAutoplayFlag() {
        activeController?.sendCustomCommand(
            SessionCommand(AUTOPLAY_COMMAND, Bundle.EMPTY),
            Bundle().apply { putBoolean(AUTOPLAY_ARG_ENABLED, autoplayNext) },
        )
    }

    override fun play() {
        // 用户意图即最新意图：探测没落地时先记下，落地按它起播（见 [loadIntentPlay]）
        loadIntentPlay = true
        val c = activeController ?: return
        // A23：关闭自动连播时，末帧停在 READY + !playWhenReady（原因 END_OF_MEDIA_ITEM），
        // 状态已译成 Ended；此时对 media3 直接 play() 是空操作（STATE_ENDED 不会自己
        // 回到播放），用户按播放像"没反应"。先回退到 0 再起播，并把已生效位置归零。
        if (_state.value == PlaybackState.Ended) {
            c.seekTo(0L)
            pendingSeek = null
            if (_appliedMediaKey.value != null) _appliedPositionMs.value = 0L
        }
        c.play()
    }

    override fun pause() {
        loadIntentPlay = false
        activeController?.pause()
    }

    override fun seekTo(positionMs: Long) {
        // 探测阶段媒体尚未装载：挂起 seek，装载完成后再落实（E39 使 prepare 变为异步）。
        // REV-11：挂起值必须带上"目标属于哪条媒体"——只判位置不判身份，切集后它
        // 永远清不掉，又被下一次重建当成恢复位置用（位置回退）。
        val target = positionMs.coerceAtLeast(0L)
        val targetKey = if (loadPending) loadingTargetKey else _appliedMediaKey.value
        pendingSeek = nextPendingSeek(pendingSeek, target, targetKey)
        // E205：探测窗口内不向 controller 转发——此刻它要么还挂着上一条媒体（转发会把
        // seek 打到用户已离开的内容上），要么刚被错误清理 stop/clear。真机实录：热切出
        // 错后点「重试」，prepare 完紧跟的这记转发 seek 触发 re-prepare 的 onPositionReset
        // 对已 release 的异步 codec flush（"flush() is valid only at Executing states"）。
        // pendingSeek 会在装载落地时作为起播位置落实（applyMedia/applyQueue/断连重建）。
        if (loadPending) return
        activeController?.seekTo(target)
    }

    /**
     * 擦洗模式：会话侧据此切换 SeekParameters（拖动中 CLOSEST_SYNC，松手 EXACT）。
     * setSeekParameters 是 ExoPlayer（会话侧）API，MediaController 不暴露，
     * 故经自定义命令 np.scrub 转发给 PlaybackService。
     */
    override fun setScrubbing(active: Boolean) {
        activeController?.sendCustomCommand(
            SessionCommand(SCRUB_COMMAND, Bundle.EMPTY),
            Bundle().apply { putBoolean(SCRUB_ARG_ACTIVE, active) },
        )
    }

    /**
     * 擦洗结束/取消走**一条** np.scrub：会话侧一次性完成「退出擦洗 + 精确 seek + 按需 play」。
     * 旧路径 setScrubbing(false) + seekTo + play 是三次跨进程调用，中间任何一步被服务回收
     * 打断都会留下"已退出擦洗但没落点/没恢复"的半截状态。
     */
    override fun finishScrub(targetMs: Long, resume: Boolean) {
        val controller = activeController
        if (controller == null) {
            super.finishScrub(targetMs, resume)
            return
        }
        controller.sendCustomCommand(
            SessionCommand(SCRUB_COMMAND, Bundle.EMPTY),
            Bundle().apply {
                putBoolean(SCRUB_ARG_ACTIVE, false)
                putLong(SCRUB_ARG_TARGET_MS, targetMs.coerceAtLeast(0L))
                putBoolean(SCRUB_ARG_RESUME, resume)
            },
        )
    }


    /**
     * 读取本地封面字节（file/content），失败返回 null；远程 http(s) 不在此处理。
     *
     * E63 修复：此前用 `readBytes()` 把整个文件/流一次性读进内存，再检查是否超过
     * [MAX_ARTWORK_BYTES]——"封面"若意外指向一个几 GB 的大文件（现场抽帧兜底 URI
     * 理论上也可能传入非预期地址），读取本身就先触发一次性大内存分配，上限形同虚设。
     * 改为有界读取：读满上限 +1 字节即判定超限并立即中止，不等整份数据落地。
     */
    private fun readArtworkBytes(uriString: String?): ByteArray? = runCatching {
        val uri = uriString?.toUri() ?: return null
        val stream = when (uri.scheme) {
            "file" -> uri.path
                ?.let { java.io.File(it) }
                ?.takeIf { f -> f.isFile }
                ?.let { java.io.FileInputStream(it) }
            "content" -> appContext.contentResolver.openInputStream(uri)
            else -> null
        } ?: return null
        readBounded(stream, MAX_ARTWORK_BYTES)
    }.getOrNull()

    /** 有界读取：读满 limit+1 字节即视为超限并返回 null，不把超限数据整份读入内存 */
    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray? = stream.use {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = it.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 512 * 1024

        /** seek 落定判据：位置与挂起目标相差在此之内即视为 seek 已落实 */
        const val SEEK_SETTLE_MS = 1_500L

        /** 首次连接超时：会话起不来时要让 awaitReady 失败，不能无限挂起 */
        const val INITIAL_CONNECT_TIMEOUT_MS = 10_000L

        /** 单次重连尝试超时（每次尝试都新建控制器，超时后丢弃再试） */
        const val RECONNECT_ATTEMPT_TIMEOUT_MS = 5_000L

        /** 重连最多尝试次数（与 [PlayerConnectionTracker] 默认一致） */
        const val RECONNECT_MAX_ATTEMPTS = 3

        /** 预探测窗口：与 [PlaybackService] 的 L2 预载窗口同量级（后 3 前 1），多探无益 */
        const val PROBE_PREFETCH_FWD = 3
        const val PROBE_PREFETCH_BACK = 1

        /**
         * 起播就绪后缓 **3s** 再预热：这一刻当前集的缓冲池还在从 0 往 `minBufferMs` 爬，
         * 磁盘是它的主场；3s 之后才轮到投机性的活。
         */
        const val PROBE_PREFETCH_START_DELAY_MS = 3_000L

        /** 预热的准入线：当前项已缓冲领先在播位置 30s（`minBufferMs` 的两倍） */
        const val PROBE_PREFETCH_MIN_AHEAD_MS = 30_000L

        /** 等准入线的步长与预算：等满 12s 还不达标就放弃这一轮预热 */
        const val PROBE_PREFETCH_WAIT_STEP_MS = 500L
        const val PROBE_PREFETCH_WAIT_BUDGET_MS = 12_000L
    }

    override fun release() {
        released = true
        scope.cancel()
        // E144-1：摘掉 PiP 动作桥（按 owner 校验，不误清后来者的登记）
        NpMediaActionBridge.unregister(this)
        pollJob?.cancel()
        pollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // 注意：会话侧播放器由 PlaybackService 持有，后台播放继续；
        // 这里只解除 UI 与会话的连接。先摘掉会话侧的视频表面，避免旧 Surface 留在
        // 服务播放器上，下一次挂载时画面还连着上一页。
        runCatching { activeController?.clearVideoSurface() }
        controller?.release()
        controller = null
        // REV-04：本实例已不再观察任何媒体，身份作废；仍挂在 awaitReady 上的
        // 装载链要立刻失败，不能挂到作用域取消为止
        clearAppliedIdentity()
        chaptersKey = null
        _chapters.value = emptyList()
        // A18：释放时撤掉进程级回退过滤状态，别影响同一进程里后续的播放意图
        decoderFallbackGate.onMediaChanged()
        decoderFallbackState.clear()
        // A12 第一期：本实例不再观察字幕，特效提示一并撤掉
        SubtitleEffectNotice.clear()
        if (!connectDeferred.isCompleted) {
            connectDeferred.completeExceptionally(CancellationException("播放器已释放"))
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun applyMedia(mediaUri: String) {
        val c = activeController ?: run {
            // REV-10：断连时装载没落地，保留意图等重连恢复
            loadPending = false
            pendingSessionRestore = true
            return
        }
        // 失败闩必须随**任何一次新装载**放下（用户实报：看过不可播的视频后换别的视频，
        // 旧错误面板一直挂着）。闩的本意是"stop+clearMediaItems 之后 Error 不许被
        // Idle 冲掉"，但此前只在 probeThenLoad 里复位——凡是不经探测的装载路径
        // （同媒体续播、预载命中、字幕重建之外的直装等）都会带着闩进来：引擎其实
        // 已经在新视频上正常播放，状态监听却被闩住，UI 停在上一支的 Error 上。
        terminalFailure = false
        // REV-04：单媒体路径的身份键——队列当前项若确实是这条 uri 才带身份
        val core = _queue.value.getOrNull(c.currentMediaItemIndex)
            ?.takeIf { it.uri == mediaUri }
        val targetKey = core?.let { mediaKeyOf(it.sourceId, it.id) }
        // REV-11：挂起 seek 只在属于这条媒体时才消费；E74 的"重建时优先用挂起位置"
        // 语义保留，但不再无条件信任裸值（它可能属于上一条媒体）。
        val pending = pendingSeek?.takeIf { shouldConsumePendingSeek(it, targetKey) }
        val position = pending?.positionMs ?: c.currentPosition.coerceAtLeast(0L)
        if (pending != null) pendingSeek = null
        val wasPlaying = c.isPlaying || c.playWhenReady
        // 封面读盘放 IO：装载协程跑在主线程，即便只有一张小图也不该在主线程同步读
        val artworkUri = currentArtworkUri
        val artwork = withContext(Dispatchers.IO) { readArtworkBytes(artworkUri) }
        val builder = MediaItem.Builder()
            .setMediaId(currentMediaId)
            .setUri(mediaUri)
            .setMimeType(currentMimeType)
            .apply {
                // `.cue` 分轨（批次 8）：字幕更换会重建当前媒体项——裁剪窗口必须跟着
                // 复刻，否则重建后窗口丢失、整文件从 0 重播（分轨内 seek/断点全错位）
                _queue.value.getOrNull(c.currentMediaItemIndex)
                    ?.takeIf { it.uri == mediaUri && it.isClipped }
                    ?.let { src ->
                        setClippingConfiguration(
                            ClippingConfiguration.Builder()
                                .setStartPositionUs(src.clipStartUs)
                                .apply { if (src.clipEndUs > 0) setEndPositionUs(src.clipEndUs) }
                                .build(),
                        )
                    }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(currentTitle.ifBlank { null })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply {
                        // 本地封面直接读为 artworkData：小图（缩略图缓存 <200KB）经 Binder
                        // 同步送达会话，通知栏/锁屏立即可见；读不到时退回 URI 由系统异步加载
                        if (artwork != null) {
                            setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        } else {
                            artworkUri?.let { setArtworkUri(it.toUri()) }
                        }
                    }
                    .build(),
            )
        subtitle?.let { sub ->
            builder.setSubtitleConfigurations(listOf(subtitleConfiguration(sub)))
        }
        val item = builder.build()
        loadPending = false
        // 队列完整性（E32 后续）：字幕更换需要重建**当前媒体项**（SubtitleConfiguration
        // 烘焙在 MediaItem 里，改不了），但**不能**用 setMediaItem——那是"替换整个队列为
        // 单项"，会把同文件夹队列连同通知栏 ⏮⏭ 一起清掉，连播随即失效。
        // 仅当目标 uri 确实是队列中的当前项时才 replaceMediaItem(当前下标)：只换那一项、
        // 队列结构不动；若是首次装载或换到了别的媒体，才走 setMediaItem（单项队列）。
        val currentQueueItem = _queue.value.getOrNull(c.currentMediaItemIndex)
        val replaceInPlace = c.mediaItemCount > 1 &&
            currentQueueItem?.uri == mediaUri &&
            c.currentMediaItemIndex in 0 until c.mediaItemCount
        if (replaceInPlace) {
            appliedItems = appliedItems.toMutableList().apply { this[c.currentMediaItemIndex] = item }
            c.replaceMediaItem(c.currentMediaItemIndex, item)
            c.prepare()
            // replaceMediaItem 把位置重置到该项起点，需显式还原（含待落实 seek）
            c.seekTo(position)
            // REV-04：同一条目重建（身份不变），applied 位置落到重建位置；时长不清
            setAppliedMedia(targetKey, item.mediaId, position, resetDuration = false)
        } else {
            appliedItems = listOf(item)
            // 换成的是一条单项队列（不再是"同一份文件夹队列"），身份作废：
            // 否则切集可能拿着旧队列的 id 序列走快路径（A15）。
            appliedQueueIds = emptyList()
            c.setMediaItem(item, position)
            c.prepare()
            // REV-04：单项装载落地 = 新身份生效（无 id 时 appliedMediaKey 为 null）
            setAppliedMedia(targetKey, item.mediaId, position)
        }
        appliedUri = mediaUri
        mountedSubtitle = subtitle
        setTextTracksEnabled(textTracksEnabled)
        if (wasPlaying) c.play()
    }

    private fun subtitleConfiguration(mount: SubtitleMount): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(mount.uri.toUri())
            .setId(EXTERNAL_SUBTITLE_TRACK_ID)
            .setMimeType(npSubtitleMime(mount.uri, mount.format))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
}

/**
 * 外挂字幕轨的稳定 id（E144）。
 *
 * 烘进 [MediaItem.SubtitleConfiguration] 的 id 会原样成为对应文本轨 `Format.id`：
 * 内外轨平票时靠它把"自动挂上的这一条"从内嵌轨里认出来，交给
 * [TrackSelectionOverride] 显式选中。
 */
internal const val EXTERNAL_SUBTITLE_TRACK_ID = "np.external.subtitle"

/**
 * 是否需要强制选中外挂字幕轨（E144）：刚挂上/刚切到带外挂字幕的条目、文本轨开着、
 * 外挂轨存在且尚未被选中，四个条件同时成立才动 [TrackSelectionParameters]。
 */
internal fun needsExternalSubtitleSelection(
    pending: Boolean,
    textTracksEnabled: Boolean,
    externalTrackPresent: Boolean,
    externalTrackSelected: Boolean,
): Boolean = pending && textTracksEnabled && externalTrackPresent && !externalTrackSelected

/** 一条外挂字幕：地址 + 容器格式（A3）。两者必须成对流转，缺格式就退回按 uri 猜。 */
internal data class SubtitleMount(val uri: String, val format: NpSubtitleFormat?)

/**
 * 外挂字幕的 media3 mime（A3）。
 *
 * 优先用挂载方显式给出的 [format]；没给才退回按 uri 末段猜。末段那条路对
 * `content://media/external/file/1234`（MediaStore）与 SAF 的文档 uri 一律落到 SubRip——
 * 自动匹配到的 `.ass` 于是零 Cue，所以格式必须由认得文件名的那一侧带进来。
 */
internal fun npSubtitleMime(uri: String, format: NpSubtitleFormat?): String = when (format) {
    NpSubtitleFormat.SUBRIP -> MimeTypes.APPLICATION_SUBRIP
    NpSubtitleFormat.WEBVTT -> MimeTypes.TEXT_VTT
    NpSubtitleFormat.SSA -> MimeTypes.TEXT_SSA
    NpSubtitleFormat.TTML -> MimeTypes.APPLICATION_TTML
    null -> when (uri.substringAfterLast('.', "").substringBefore('/').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/** 从已烘进媒体项的 mime 读回格式（`onMediaItemTransition` 的读回侧）。 */
internal fun npSubtitleFormatOfMime(mimeType: String?): NpSubtitleFormat? = when (mimeType) {
    MimeTypes.APPLICATION_SUBRIP -> NpSubtitleFormat.SUBRIP
    MimeTypes.TEXT_VTT -> NpSubtitleFormat.WEBVTT
    MimeTypes.TEXT_SSA -> NpSubtitleFormat.SSA
    MimeTypes.APPLICATION_TTML -> NpSubtitleFormat.TTML
    else -> null
}

/**
 * media3 的 `(playbackState, playWhenReady)` → 引擎对外状态（A5）。
 *
 * `STATE_READY` **不再映射成独立的 `Ready`**：那个状态全仓没有消费者，只被
 * `onIsPlayingChanged` 越过，于是"暂停后拖进度条"（BUFFERING → READY，`isPlaying`
 * 全程 false，不会再回调）会把状态永久停在 `Ready` 上——中央播放钮消失、
 * `FLAG_KEEP_SCREEN_ON` 与播放降档与弹幕判定一起错。就绪态本来就是二者之一：
 * 该播的（`playWhenReady`）是 `Playing`，停着的（含 `play=0` 只装载）是 `Paused`。
 *
 * A23：关闭自动连播时会话侧 `setPauseAtEndOfMediaItems(true)`，一集播完停在
 * `READY + !playWhenReady`，但 `playWhenReady` 变化原因是 `END_OF_MEDIA_ITEM`——
 * 这是"播完了"，不是"用户暂停"。必须译成 `Ended`，否则 UI 不显示重播/下一集，
 * 用户按播放也像没反应（media3 在 ENDED 上 play() 是空操作）。
 */
internal fun npEngineStateOf(
    playbackState: Int,
    playWhenReady: Boolean,
    playWhenReadyChangeReason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
): PlaybackState = when (playbackState) {
    Player.STATE_BUFFERING -> PlaybackState.Buffering
    Player.STATE_READY -> when {
        playWhenReady -> PlaybackState.Playing
        playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
            PlaybackState.Ended
        else -> PlaybackState.Paused
    }
    Player.STATE_ENDED -> PlaybackState.Ended
    else -> PlaybackState.Idle
}

/**
 * A17 收尾：本次起播前探测是否必须绕过 `FormatProbe` 结果缓存。
 *
 * 常规装载（首次打开 / 切集 / 播完重播）照旧走缓存——命中能把探测那 0.5–1.5s 省掉，
 * 那正是缓存存在的理由。**错误之后的再来一次**不行：缓存里若躺着一条确定性失败结论
 * （容器不支持 / 视频编码不支持），`probe()` 会把同一条结论原样送回，用户点「重试」
 * 永远回到同一个错误（A17 的"二次定罪"）。
 *
 * 两条重试路径都要覆盖：
 * - 用户点错误面板「重试」：引擎里还留着失败闩 [terminalFailure]，状态停在
 *   [PlaybackState.Error]——所以要在 `probeThenLoad` 复位闩**之前**捕获；
 * - A18 解码器初始化失败的受控回退：此时失败闩没置、`_state` 也可能还停在 Playing，
 *   靠 [retryingAfterFailure] 显式带进来（否则回退探测会命中"可播"缓存，白跑一趟）。
 *
 * 纯函数，便于 JVM 单测（见 ProbeBypassOnRetryTest）。
 */
internal fun shouldBypassProbeCache(
    terminalFailure: Boolean,
    state: PlaybackState,
    retryingAfterFailure: Boolean,
): Boolean = retryingAfterFailure || terminalFailure || state is PlaybackState.Error

/**
 * 队列身份序列（A15）：逐项取 id，没有 id 的条目退化为 uri。
 *
 * 身份必须"同一份队列"可判：两份文件夹长度相同、甚至标题相似，只要 id 序列不同
 * 就不是同一条队列。纯函数，便于 JVM 单测（见 QueueIdentityFastSelectTest）。
 */
internal fun queueIdentity(items: List<com.noneplayer.core.media.MediaItem>): List<String> =
    items.map { it.id.ifEmpty { it.uri } }

/**
 * 切集快路径判据（A15 提纯，便于单测）。
 *
 * 只有能**证明**控制器里装着的就是这一份队列时才允许 `seekToDefaultPosition`：
 * - [appliedQueueIds] 与 [requestedQueueIds] 逐项相同（同一份队列，同序）；
 * - 引擎媒体项数 [engineItemCount] 与请求项数一致（防御 media3 侧异步变更）；
 * - 没有在途装载 [loadPending]：探测还在走时按项数 seek 会落进无关文件；
 * - 没有失败闩 [terminalFailure]：从错误态切出必须走探测闸复位闩锁；
 * - 请求队列非空（空队列没有可切的项）。
 */
internal fun canFastSelectQueueIndex(
    appliedQueueIds: List<String>,
    requestedQueueIds: List<String>,
    engineItemCount: Int,
    loadPending: Boolean,
    terminalFailure: Boolean,
): Boolean =
    requestedQueueIds.isNotEmpty() &&
        !loadPending &&
        !terminalFailure &&
        engineItemCount == requestedQueueIds.size &&
        appliedQueueIds == requestedQueueIds

/**
 * media3 Cue → 内核 SubtitleCue（E1）。
 *
 * 文本 Cue 直接取文本；位图 Cue（PGS/VobSub）带上 bitmap 与按画面归一化的摆放。
 * media3 1.5 只为位图给出 `bitmapHeight`（画面高度占比），宽度按图片宽高比换算；
 * `DIMEN_UNSET` 表示未设置，按底部居中惯例兜底。
 */
private fun Cue.toKernelCue(): SubtitleCue {
    val bmp = bitmap
    if (bmp != null) {
        return SubtitleCue(
            bitmap = bmp,
            placement = npCuePlacement(
                bitmapWidthPx = bmp.width,
                bitmapHeightPx = bmp.height,
                heightFraction = bitmapHeight.takeIf { it != Cue.DIMEN_UNSET },
                anchorX = position.takeIf { it != Cue.DIMEN_UNSET },
                baselineY = line
                    .takeIf { it != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION },
            ),
        )
    }
    // A12 第一期：容器已有的对齐、垂直/水平位置与主色带出去。文本仍是纯文本
    // （富文本 span 第二阶段再做），但顶部对齐/双语分置不再被一律压到底部。
    val alignment = npCueAlignment(
        isStartAligned = textAlignment == Layout.Alignment.ALIGN_NORMAL,
        isEndAligned = textAlignment == Layout.Alignment.ALIGN_OPPOSITE,
    )
    val lineFraction = npCueFraction(
        line.takeIf { it != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION },
    )
    val positionFraction = npCueFraction(position.takeIf { it != Cue.DIMEN_UNSET })
    // media3 的 SSA 解码器把 ASS 主色放进 ForegroundColorSpan；只取首个前景色当
    // "主色"。用户显式改过颜色时渲染层优先用户值（见 SubtitleLayer）。
    val textColorArgb = (text as? Spanned)
        ?.let { spanned ->
            spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
                ?.firstOrNull()
                ?.foregroundColor
        }
        .let { npCueColorArgb(it) }
    return SubtitleCue(
        text = text?.toString(),
        alignment = alignment,
        lineFraction = lineFraction,
        positionFraction = positionFraction,
        textColorArgb = textColorArgb,
    )
}

/**
 * 运行期错误 → 结构化失败信息（E41）：
 * 错误码分四类映射"可读原因"，容器/编码名优先用起播前探测结果补齐。
 */
@OptIn(UnstableApi::class)
private fun PlaybackException.toPlaybackFailure(
    container: String?,
    videoCodec: String?,
    audioCodec: String?,
): PlaybackFailure = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure.fileMissing()
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailure.noPermission()
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_PERMISSION_DENIED,
    PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
    PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
    -> PlaybackFailure.accessDenied(localizedMessage)

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    -> PlaybackFailure.ioTransient(localizedMessage)

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> PlaybackFailure.containerUnsupported(container)

    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    -> PlaybackFailure.audioOutput(localizedMessage)

    PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
    PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
    -> PlaybackFailure.videoRender(localizedMessage)

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> PlaybackFailure.videoCodecUnsupported(container, videoCodec, audioCodec)

    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    -> PlaybackFailure.videoDecodeTransient(localizedMessage)

    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
    PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
    PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
    PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
    -> PlaybackFailure.drm(localizedMessage)

    else -> PlaybackFailure.unexpected(localizedMessage ?: "播放出错")
}
