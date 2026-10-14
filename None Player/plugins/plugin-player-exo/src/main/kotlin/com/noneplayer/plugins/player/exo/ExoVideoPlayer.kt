package com.noneplayer.plugins.player.exo

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import com.noneplayer.core.media.probe.FormatProbe
import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.NpChapter
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.NpLoopMode
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
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * ExoPlayer 播放器实现——基于 [MediaController] 与后台 [PlaybackService] 会话。
 *
 * 播放器实例驻留服务进程侧：UI 销毁不影响播放（后台播放 + 媒体通知）。
 * [awaitReady] 阻塞到会话建立；字幕/倍速/进度记忆都作用于会话侧播放器。
 */
class ExoVideoPlayer(context: Context) : VideoPlayer {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

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

    /** 轨道选择键 → (轨道组, 轨道序号)，selectTrack 时还原成 TrackSelectionOverride */
    private val trackGroupById = HashMap<String, Pair<Tracks.Group, Int>>()

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

    private val connected = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private var attachedDisplay: Any? = null
    private var refreshPausedFrameOnSurface = false
    private var currentMediaUri: String? = null
    private var currentMimeType: String = "video/mp4"
    private var currentTitle: String = ""
    private var currentArtworkUri: String? = null
    private var subtitleUri: String? = null
    private var mountedSubtitleUri: String? = null
    private var textTracksEnabled = true
    private var videoTracksEnabled = true

    // E39/E41：探测期间的挂起操作与轨道信息
    private var prepareJob: Job? = null
    private var pendingSeekMs: Long? = null

    /**
     * 自动连播下一集（设置页开关，L1）。连接建立前先记在这里，连上后补发——
     * 会话侧默认是"自动推进"，漏发会静默变成"关不掉自动连播"。
     */
    private var autoplayNext = true

    private var appliedUri: String? = null
    private var loadPending = false
    private var currentMediaId: String = ""
    private var appliedItems: List<MediaItem> = emptyList()

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
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    if (released) {
                        // release() 已先执行：这个连接不再被任何人持有，直接释放。
                        // connected 必须一并了结——否则仍挂在 awaitReady() 上的装载链
                        // 会一直挂起到它的作用域被取消（表现为"进页一直转圈"）。
                        c.release()
                        connected.completeExceptionally(
                            CancellationException("播放器已释放，会话连接不再补发"),
                        )
                        return@addListener
                    }
                    controller = c
                    c.addListener(object : Player.Listener {
                        // 首帧不在这里：media3 不把 onRenderedFirstFrame 转发给
                        // MediaController，控制器侧只能看到 STATE_READY。出画时刻记在会话侧
                        // （PlaybackService 的播放器监听），同一进程内时间戳可直接对齐。
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (terminalFailure) return
                            if (playbackState == Player.STATE_READY) {
                                NpPerf.mark("READY")
                                // 就绪才预热邻域探测：这一刻之前磁盘都在回填当前集的缓冲
                                prefetchProbeWindow()
                            }
                            _state.value = when (playbackState) {
                                Player.STATE_BUFFERING -> PlaybackState.Buffering
                                Player.STATE_READY -> PlaybackState.Ready
                                Player.STATE_ENDED -> PlaybackState.Ended
                                else -> PlaybackState.Idle
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            applyVideoSize(videoSize)
                        }

                        override fun onSurfaceSizeChanged(width: Int, height: Int) {
                            if (!refreshPausedFrameOnSurface || width <= 0 || height <= 0) return
                            refreshPausedFrameOnSurface = false
                            // A paused decoder may have already released its only frame to the old surface.
                            if (attachedDisplay != null && videoTracksEnabled &&
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
                            mountedSubtitleUri = localItem?.localConfiguration?.subtitleConfigurations
                                ?.firstOrNull()?.uri?.toString()
                            subtitleUri = mountedSubtitleUri
                            // 队列下标跟随会话（自动连播推进到下一集时也要更新，
                            // 否则迷你条/选集面板会一直停在旧下标）
                            // Clearing the engine after a failed probe is not a queue transition.
                            if (mediaItem != null && index in _queue.value.indices &&
                                index != _currentIndex.value
                            ) _currentIndex.value = index
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
                            pushCues(controller?.currentPosition?.coerceAtLeast(0) ?: 0L, mapped)
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
                        }

                        // 章节经 metadata 通道到达（§12.5：MP4/Matroska 章节元数据）
                        @OptIn(UnstableApi::class)
                        override fun onMetadata(metadata: Metadata) {
                            publishChapters(controller?.currentTracks ?: Tracks.EMPTY, metadata)
                        }
                    })
                    // 会话建立后补挂显示表面，并同步会话侧既有状态（速度/当前媒体）
                    attachSurfaceTo(c)
                    _speed.value = c.playbackParameters.speed
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
                    connected.complete(c)
                    scope.launch { pollPosition(c) }
                } catch (t: Throwable) {
                    _state.value = PlaybackState.Error(t.message ?: "无法建立播放会话")
                    connected.completeExceptionally(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * 位置轮询：不设 isPlaying 门槛——缓冲中/暂停时也要回显 seek 结果与时长，
     * 否则进页仍在缓冲时进度条 `valueRange` 只有 1ms、暂停态 seek 不刷新。
     * 仅在值变化时写 StateFlow，避免无意义的 UI 失效。
     */
    private suspend fun pollPosition(c: MediaController) {
        while (currentCoroutineContext().isActive) {
            val position = c.currentPosition.coerceAtLeast(0)
            if (_positionMs.value != position) _positionMs.value = position
            // seek 落实（位置收敛到目标）后清掉挂起值：它只该服务"媒体装载完成前
            // 到达的 seek"（E39）。不清理的话会一直留到下一次媒体项重建
            // （切字幕等触发 applyMedia），被当成"恢复位置"用，把已播到的位置
            // 拉回上一次 seek 的目标（位置回退）。
            pendingSeekMs?.let { pending -> if (abs(position - pending) <= SEEK_SETTLE_MS) pendingSeekMs = null }
            val duration = c.duration
            if (duration > 0 && _durationMs.value != duration) _durationMs.value = duration
            // 缓冲前沿夹在 [0, duration]：未就绪/未知时 media3 可能给 TIME_UNSET 或超出值，
            // 直接透传会让进度条画出一段超出时长的"假缓冲"
            val buffered = c.bufferedPosition.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            if (_bufferedPositionMs.value != buffered) _bufferedPositionMs.value = buffered
            // 字幕偏移非零时，按位置重取对应时间窗的 Cue（窗口边界由 onCues 到达时机维护）
            if (subtitleOffsetMs != 0L) recomputeCues(position)
            delay(250)
        }
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

    override suspend fun awaitReady() {
        connected.await()
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
        probeThenLoad(uri, currentMimeType) { applyMedia(uri) }
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
        apply: suspend () -> Unit,
    ) {
        prepareJob?.cancel()
        loadPending = true
        appliedUri = null
        clearCues()
        // 用户已经动手切了，就别再替他猜：预探测和这一次真探测走同一条磁盘通道，
        // 平板实测撞上时单条探测从 589ms 涨到 1799ms——预热反倒拖慢了切换。
        prefetchJob?.cancel()
        terminalFailure = false
        _state.value = PlaybackState.Buffering
        _decodeNotice.value = null
        _hdrLabel.value = null
        val myGeneration = ++prepareGeneration
        NpPerf.begin("probe uri=${uri.substringAfterLast('/')}")
        prepareJob = scope.launch {
            val probe = FormatProbe.probe(appContext, uri, expectedMime, sizeBytes, lastModifiedSec)
            NpPerf.mark("probe done (${probe.javaClass.simpleName})")
            if (myGeneration != prepareGeneration) return@launch // 已有更新的装载，丢弃
            when (probe) {
                is FormatProbe.Result.Unplayable -> {
                    loadPending = false
                    appliedUri = null
                    probedUri = uri
                    probedContainer = probe.failure.container
                    probedVideoCodec = probe.failure.videoCodec
                    probedAudioCodec = probe.failure.audioCodec
                    // 先停掉会话里可能还在播的上一个媒体，再落终态失败
                    runCatching { controller?.stop() }
                    // 连同**上一条媒体的进度残留**一起清掉：media3 的 stop() 不重置
                    // 播放位置/时长（上一条停在 1:03 就还是 1:03），不清的话错误面板
                    // 背後与迷你条会把"什么都没装载"显示成"快播完了"。
                    runCatching { controller?.clearMediaItems() }
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
                    // C21：仅软解可用时给前置提示（能播，但可能发热/掉帧）
                    _decodeNotice.value = if (probe.softwareVideoOnly) {
                        "该视频仅有软件解码器可用，高码率下可能掉帧或发热"
                    } else {
                        null
                    }
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
    ) {
        if (controller == null) return
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        _queue.value = items
        _currentIndex.value = safeIndex
        pendingSeekMs = startPositionMs.takeIf { it > 0 }

        // E80 修复：**队列路径也必须过起播前探测**（详细缘由见 [probeThenLoad]）。
        //
        // 批次 1 引入队列后，播放页改走 setQueue()，而 setQueue 直接 setMediaItems +
        // c.prepare()，**绕过了 prepare() 里的 FormatProbe**。后果是 E39 的"体面失败"
        // 在队列路径上整体失效：以 wmv2-clip.avi 为例，AviExtractor 会丢弃它不认识的
        // WMV2 视频轨（`Missing Stream Format`），只剩 MP3 音轨——于是播放器"成功地"
        // 播完 4 秒音频并显示"播放结束"，用户只看到**纯黑屏**，无从知道是编码不支持。
        //
        // 这里对**即将播放的那一项**做探测：不可解则不装载队列，直接落结构化失败态。
        val target = items[safeIndex]
        setCurrentTarget(target)
        probeThenLoad(target.uri, target.mimeType, target.sizeBytes, target.lastModifiedSec) {
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
        val c = controller ?: return
        // 同 [applyMedia]：任何一次新装载都放下失败闩（旧错误面板不许跨媒体残留）
        terminalFailure = false
        coreItems.getOrNull(safeIndex)?.let { _currentUri.value = it.uri }
        // 批次 3 修复：队列路径此前从不带 SubtitleConfiguration——字幕记忆/同名挂载
        // 在"进页即 setQueue"的主流程上整体失效（applyMedia 只服务单媒体项路径）。
        // 给**即将播放的那一项**烘上当前字幕地址；切集后由 setSubtitle 走重建路径。
        val subtitle = subtitleUri
        // 建表必须在 IO：整表 MediaItem 会为**每一项**准备封面元数据，此前还逐项把封面
        // 文件读成 artworkData——一季 100 集就是 100 次同步读盘 + 一次超大 Binder 事务，
        // 全压在装载协程（主线程）上，表现就是"点播放转圈很久 / 切集卡死"。
        // 现在只有**即将播放的那一项**带封面字节（通知/锁屏立即可见），其余项只给
        // artworkUri 让系统异步加载——队列语义不变，成本从 O(队列) 降到 O(1)。
        val items = withContext(Dispatchers.IO) {
            coreItems.mapIndexed { index, src ->
                val built = src.toMediaItem(withArtwork = index == safeIndex)
                if (subtitle != null && index == safeIndex) {
                    built.buildUpon()
                        .setSubtitleConfigurations(listOf(subtitleConfiguration(subtitle)))
                        .build()
                } else {
                    built
                }
            }.also { NpPerf.mark("built ${it.size} media items") }
        }
        // 建表期间可能已经来了更新的装载（A→B 快速切换会 cancel 本次 prepareJob）：
        // withContext 自身会在挂起点抛出取消，但"IO 刚回来、还没落盘"这一小段
        // 是纯同步代码，必须自己再核一次，别把旧队列盖到新装载上。
        currentCoroutineContext().ensureActive()
        appliedUri = items[safeIndex].localConfiguration?.uri?.toString()
        appliedItems = items
        mountedSubtitleUri = subtitle
        loadPending = false
        c.setMediaItems(items, safeIndex, startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
        NpPerf.mark("setMediaItems idx=$safeIndex applied")
    }

    /**
     * 选集/自动切集的落点：**同样是一条装载路径**，因此与 setQueue 走同一条探测闸。
     *
     * 曾经的实现只做 `seekToDefaultPosition(index)`：从失败项（Error 终态 + 闩锁）
     * 切到正常项时，闩锁不复位、media3 回调被挡，错误面板就永远盖在画面上；
     * 切到不可解项时又会绕过 E80 的探测，黑屏把音轨播完。
     *
     * 改用 `setMediaItems(items, index)` 整表重设而非仅 seek：队列可能因上一次
     * 探测失败**根本没进 media3**（setQueue 失败时 applyQueue 未执行），
     * 此时对旧播放列表 seek 会指到别的媒体甚至越界。
     */
    override fun selectQueueIndex(index: Int) {
        val c = controller ?: return
        val items = _queue.value
        if (index !in items.indices) return
        // 队列已经在播：直接跳到那一项。再整表 setMediaItems + 重新探测会和正在打开的文件抢句柄，
        // 切集停在转圈，直到进程重启把句柄放掉。
        //
        // 判据只看**控制器里已经装着同一份队列**（项数一致）。此前还额外要求
        // `appliedItems.size == items.size`——那是"我这边也记着同一份"的代理条件，
        // 而单媒体项装载（applyMedia / 预载命中）之后 appliedItems 为空，于是每一次
        // 切集都掉进下面的整表重建 + 重新探测分支：一季几十集就是几十次无谓重装。
        val queueMounted = c.mediaItemCount == items.size
        if (queueMounted && index == c.currentMediaItemIndex) {
            c.play()
            return
        }
        if (queueMounted && !terminalFailure) {
            _currentIndex.value = index
            val target = items[index]
            // 新一集不该继承上一集的外挂字幕（与整表重建分支同一语义）
            subtitleUri = null
            mountedSubtitleUri = null
            setCurrentTarget(target)
            _currentUri.value = target.uri
            appliedUri = target.uri
            loadPending = false
            c.seekToDefaultPosition(index)
            c.play()
            return
        }
        _currentIndex.value = index
        val target = items[index]
        subtitleUri = null
        mountedSubtitleUri = null
        pendingSeekMs = null
        setCurrentTarget(target)
        probeThenLoad(target.uri, target.mimeType, target.sizeBytes, target.lastModifiedSec) {
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
        val c = controller
        runCatching { c?.stop() }
        runCatching { c?.clearMediaItems() }
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentUri.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedPositionMs.value = 0L
        pendingSeekMs = null
        appliedUri = null
        appliedItems = emptyList()
        loadPending = false
        currentMediaUri = null
        currentMediaId = ""
        currentArtworkUri = null
        currentTitle = ""
        subtitleUri = null
        mountedSubtitleUri = null
        _state.value = PlaybackState.Idle
        clearCues()
        _decodeNotice.value = null
        _hdrLabel.value = null
    }

    override fun playNext() {
        val next = _currentIndex.value + 1
        if (next in _queue.value.indices) {
            NpPerf.begin("nav next")
            selectQueueIndex(next)
        }
    }

    override fun playPrevious() {
        val previous = _currentIndex.value - 1
        if (previous in _queue.value.indices) {
            NpPerf.begin("nav previous")
            selectQueueIndex(previous)
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
        controller?.playbackParameters = PlaybackParameters(speed)
        _speed.value = speed
    }

    private val _loopMode = MutableStateFlow(NpLoopMode.OFF)
    override val loopMode: StateFlow<NpLoopMode> = _loopMode.asStateFlow()

    /**
     * 循环模式 → media3：OFF/ONE/ALL 直接映射 repeatMode；
     * SHUFFLE = 列表循环 + shuffleModeEnabled（media3 按默认 ShuffleOrder 洗牌队列）。
     */
    override fun setLoopMode(mode: NpLoopMode) {
        val c = controller ?: return
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
        _loopMode.value = mode
    }

    /**
     * 挂载/清除外挂字幕（E32）：清除与"复原已挂载文件"改走文本轨开关，
     * 不重建媒体项、不重新缓冲；只有更换新文件才更新媒体项。
     */
    override fun setSubtitle(uri: String?) {
        val previous = subtitleUri
        subtitleUri = uri
        val media = currentMediaUri ?: return
        controller ?: return
        when {
            uri == previous -> Unit
            uri == null && mountedSubtitleUri != null ->
                setTextTracksEnabled(false)
            uri != null && uri == mountedSubtitleUri ->
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
        val c = controller ?: return
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
        val position = pendingSeekMs ?: c.currentPosition.coerceAtLeast(0L)
        val playWhenReady = c.playWhenReady
        val subtitles = subtitleUri?.let { listOf(subtitleConfiguration(it)) }.orEmpty()
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
        mountedSubtitleUri = subtitleUri
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
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    override fun setVideoTracksEnabled(enabled: Boolean) {
        videoTracksEnabled = enabled
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** 内嵌轨道枚举：音轨/字幕轨各一条目，label 组合语言/标题/声道/角色标签 */
    private fun publishTracks(tracks: Tracks) {
        trackGroupById.clear()
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
                    if (trackGroupById.containsKey(id)) continue
                    trackGroupById[id] = group to i
                    val label = buildList {
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
        if (entries.isNotEmpty()) _chapters.value = entries
    }

    /**
     * 轨道选择：清掉同类型既有 override 再加新 override（TrackSelectionParameters
     * 经 MediaController 设置，对会话侧播放器生效且不重建媒体项）。
     */
    override fun selectTrack(type: NpTrackType, id: String) {
        val c = controller ?: return
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

    /** 字幕偏移（E43）：正数=延后。立即按当前位置重取时间窗 */
    override fun setSubtitleOffsetMs(offsetMs: Long) {
        subtitleOffsetMs = offsetMs
        controller?.let { recomputeCues(it.currentPosition.coerceAtLeast(0)) }
    }

    /**
     * 画面裁剪（§2.4）：cover / fit。setVideoScalingMode 是 ExoPlayer（会话侧）
     * API、MediaController 不暴露，与 np.scrub 同路转发（见 PlaybackService）。
     */
    override fun setVideoCrop(crop: Boolean) {
        controller?.sendCustomCommand(
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
        controller?.sendCustomCommand(
            SessionCommand(AUTOPLAY_COMMAND, Bundle.EMPTY),
            Bundle().apply { putBoolean(AUTOPLAY_ARG_ENABLED, autoplayNext) },
        )
    }

    override fun play() {
        controller?.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun seekTo(positionMs: Long) {
        // 探测阶段媒体尚未装载：挂起 seek，装载完成后再落实（E39 使 prepare 变为异步）
        pendingSeekMs = positionMs
        controller?.seekTo(positionMs)
    }

    /**
     * 擦洗模式：会话侧据此切换 SeekParameters（拖动中 CLOSEST_SYNC，松手 EXACT）。
     * setSeekParameters 是 ExoPlayer（会话侧）API，MediaController 不暴露，
     * 故经自定义命令 np.scrub 转发给 PlaybackService。
     */
    override fun setScrubbing(active: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(SCRUB_COMMAND, Bundle.EMPTY),
            Bundle().apply { putBoolean(SCRUB_ARG_ACTIVE, active) },
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
        // 注意：会话侧播放器由 PlaybackService 持有，后台播放继续；
        // 这里只解除 UI 与会话的连接
        controller?.release()
        controller = null
    }

    @OptIn(UnstableApi::class)
    private suspend fun applyMedia(mediaUri: String) {
        val c = controller ?: return
        // 失败闩必须随**任何一次新装载**放下（用户实报：看过不可播的视频后换别的视频，
        // 旧错误面板一直挂着）。闩的本意是"stop+clearMediaItems 之后 Error 不许被
        // Idle 冲掉"，但此前只在 probeThenLoad 里复位——凡是不经探测的装载路径
        // （同媒体续播、预载命中、字幕重建之外的直装等）都会带着闩进来：引擎其实
        // 已经在新视频上正常播放，状态监听却被闩住，UI 停在上一支的 Error 上。
        terminalFailure = false
        // E74 修复：此前恢复位置只读 c.currentPosition，没有优先消费未落实的
        // pendingSeekMs——几乎同时切字幕与拖动进度条时，字幕触发的媒体项重建会用
        // 稍微过期的位置恢复播放，产生极窄的"位置回退"窗口。
        val position = pendingSeekMs ?: c.currentPosition
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
        subtitleUri?.let { sub ->
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
        } else {
            appliedItems = listOf(item)
            c.setMediaItem(item, position)
            c.prepare()
        }
        // 落实探测期间挂起的 seek（进页断点续播在 prepare 与 probe 完成之间到达）
        pendingSeekMs?.let { pending ->
            c.seekTo(pending)
            pendingSeekMs = null
        }
        appliedUri = mediaUri
        mountedSubtitleUri = subtitleUri
        setTextTracksEnabled(textTracksEnabled)
        if (wasPlaying) c.play()
    }

    private fun subtitleConfiguration(fileUri: String): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(fileUri.toUri())
            .setMimeType(subtitleMime(fileUri))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

    private fun subtitleMime(uri: String): String = when (uri.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

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
        val heightFraction = bitmapHeight.takeIf { it != Cue.DIMEN_UNSET && it > 0f && it <= 1f } ?: 1f
        val aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
        val widthFraction = (heightFraction * aspect).coerceAtMost(1f)
        val anchorX = position.takeIf { it != Cue.DIMEN_UNSET } ?: 0.5f
        val baselineY = line
            .takeIf { it != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION } ?: 1f
        val left = (anchorX - widthFraction / 2f).coerceIn(0f, 1f)
        val top = (baselineY - heightFraction).coerceIn(0f, 1f)
        return SubtitleCue(
            bitmap = bmp,
            placement = CuePlacement(left, top, widthFraction, heightFraction),
        )
    }
    return SubtitleCue(text = text?.toString())
}

/**
 * 运行期错误 → 结构化失败信息（E41）：
 * 错误码分四类映射"可读原因"，容器/编码名优先用起播前探测结果补齐。
 */
private fun PlaybackException.toPlaybackFailure(
    container: String?,
    videoCodec: String?,
    audioCodec: String?,
): PlaybackFailure = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure.fileMissing()
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailure.noPermission()
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> PlaybackFailure.containerUnsupported(container)

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> PlaybackFailure.videoCodecUnsupported(container, videoCodec, audioCodec)

    else -> PlaybackFailure.unexpected(localizedMessage ?: "播放出错")
}
