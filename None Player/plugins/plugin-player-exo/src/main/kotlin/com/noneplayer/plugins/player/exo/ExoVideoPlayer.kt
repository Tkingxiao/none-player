package com.noneplayer.plugins.player.exo

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Label
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
import androidx.media3.common.TrackGroup
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    // ---- 队列（§8.2）：写入 media3 会话后，通知 ⏮⏭ 与"播完自动下一集"由框架免费提供 ----
    private val _queue = MutableStateFlow<List<com.noneplayer.core.media.MediaItem>>(emptyList())
    override val queue: StateFlow<List<com.noneplayer.core.media.MediaItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val connected = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private var attachedDisplay: Any? = null
    private var currentMediaUri: String? = null
    private var currentMimeType: String = "video/mp4"
    private var currentTitle: String = ""
    private var currentArtworkUri: String? = null
    private var subtitleUri: String? = null
    private var mountedSubtitleUri: String? = null

    // E39/E41：探测期间的挂起操作与轨道信息
    private var prepareJob: Job? = null
    private var pendingSeekMs: Long? = null

    /**
     * 自动连播下一集（设置页开关，L1）。连接建立前先记在这里，连上后补发——
     * 会话侧默认是"自动推进"，漏发会静默变成"关不掉自动连播"。
     */
    private var autoplayNext = true

    private var appliedUri: String? = null
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
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (terminalFailure) return
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

                        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                            _speed.value = playbackParameters.speed
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            _currentUri.value = mediaItem?.localConfiguration?.uri?.toString()
                            // 队列下标跟随会话（自动连播推进到下一集时也要更新，
                            // 否则迷你条/选集面板会一直停在旧下标）
                            val index = c.currentMediaItemIndex
                            if (index != _currentIndex.value) _currentIndex.value = index
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
                            val failure = error.toPlaybackFailure(
                                probedContainer,
                                probedVideoCodec,
                                probedAudioCodec,
                            )
                            terminalFailure = true
                            _state.value = PlaybackState.Error(failure.reason, failure)
                        }

                        // E1：字幕 Cue 出口——外挂与内嵌轨道统一从这里流向 UI 字幕浮层
                        override fun onCues(cueGroup: CueGroup) {
                            val mapped = cueGroup.cues.map { it.toKernelCue() }
                            pushCues(controller?.currentPosition?.coerceAtLeast(0) ?: 0L, mapped)
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
        var ratio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio /
            videoSize.height.toFloat()
        // 竖向旋转的横拍视频：宽高对调
        if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
            ratio = 1f / ratio
        }
        _videoAspectRatio.value = ratio
    }

    override suspend fun awaitReady() {
        connected.await()
    }

    /**
     * 准备播放（E39）：先做格式预探测——"容器无解封装"或"视频编码无解码器"时
     * 直接进入结构化失败态（省掉 3–8s 白转圈）；探测通过/无结论才装载媒体。
     */
    override fun prepare(uri: String, mimeType: String, title: String, artworkUri: String?) {
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
     */
    private fun probeThenLoad(uri: String, expectedMime: String? = null, apply: () -> Unit) {
        prepareJob?.cancel()
        terminalFailure = false
        _state.value = PlaybackState.Buffering
        _decodeNotice.value = null
        val myGeneration = ++prepareGeneration
        prepareJob = scope.launch {
            val probe = FormatProbe.probe(appContext, uri, expectedMime)
            if (myGeneration != prepareGeneration) return@launch // 已有更新的装载，丢弃
            when (probe) {
                is FormatProbe.Result.Unplayable -> {
                    appliedUri = null
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
                    probedContainer = probe.container
                    probedVideoCodec = probe.videoCodec
                    probedAudioCodec = probe.audioCodec
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

    override fun attachDisplay(view: Any?) {
        attachedDisplay = view
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
        probeThenLoad(items[safeIndex].uri, items[safeIndex].mimeType) {
            applyQueue(mediaItemsOf(items), safeIndex, startPositionMs)
        }
    }

    /** setQueue 成功路径的装载体（探测通过后调用），保持与探测前的行为一致 */
    @OptIn(UnstableApi::class)
    private fun applyQueue(
        mediaItems: List<MediaItem>,
        safeIndex: Int,
        startPositionMs: Long,
    ) {
        val c = controller ?: return
        _queue.value.let { if (it.isNotEmpty()) _currentUri.value = it[safeIndex].uri }
        // 批次 3 修复：队列路径此前从不带 SubtitleConfiguration——字幕记忆/同名挂载
        // 在"进页即 setQueue"的主流程上整体失效（applyMedia 只服务单媒体项路径）。
        // 给**即将播放的那一项**烘上当前字幕地址；切集后由 setSubtitle 走重建路径。
        val subtitle = subtitleUri
        val items = if (subtitle != null) {
            mediaItems.mapIndexed { index, item ->
                if (index == safeIndex) {
                    item.buildUpon()
                        .setSubtitleConfigurations(listOf(subtitleConfiguration(subtitle)))
                        .build()
                } else {
                    item
                }
            }
        } else {
            mediaItems
        }
        c.setMediaItems(items, safeIndex, startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
    }

    private fun mediaItemsOf(items: List<com.noneplayer.core.media.MediaItem>): List<MediaItem> =
        items.map { it.toMediaItem() }

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
        if (controller == null) return
        val items = _queue.value
        if (index !in items.indices) return
        _currentIndex.value = index
        probeThenLoad(items[index].uri, items[index].mimeType) {
            applyQueue(mediaItemsOf(items), index, 0L)
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
    }

    override fun playNext() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        }
    }

    override fun playPrevious() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
            c.play()
        }
    }

    /** 队列项 → media3 MediaItem（标题/封面/字幕/`.cue` 裁剪窗口一并带上） */
    @OptIn(UnstableApi::class)
    private fun com.noneplayer.core.media.MediaItem.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
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
                        val bytes = readArtworkBytes(coverUri)
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
        val c = controller ?: return
        when {
            uri == previous -> Unit
            uri == null && mountedSubtitleUri != null ->
                setTextTracksEnabled(false)
            uri != null && uri == mountedSubtitleUri ->
                setTextTracksEnabled(true)
            // 更换字幕文件：唯一需要重建媒体项的分支（探测未完成时由 applyMedia 带上）
            media == appliedUri -> applyMedia(media)
            else -> Unit
        }
    }

    /**
     * 文本轨开关（TrackSelectionParameters 对当前与后续媒体项生效）。
     * 批次 3 起是公开契约：字幕面板的"关闭内嵌字幕"与外挂挂载共用同一开关（E32）。
     */
    override fun setTextTracksEnabled(enabled: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
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
            // Tracks.Group.mediaTrackGroup 声明为 MediaTrackGroup 接口，实际必是 TrackGroup
            val trackGroup = group.mediaTrackGroup as TrackGroup
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
    private fun applyMedia(mediaUri: String) {
        val c = controller ?: return
        // E74 修复：此前恢复位置只读 c.currentPosition，没有优先消费未落实的
        // pendingSeekMs——几乎同时切字幕与拖动进度条时，字幕触发的媒体项重建会用
        // 稍微过期的位置恢复播放，产生极窄的"位置回退"窗口。
        val position = pendingSeekMs ?: c.currentPosition
        val wasPlaying = c.isPlaying || c.playWhenReady
        val builder = MediaItem.Builder()
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
                        val bytes = readArtworkBytes(currentArtworkUri)
                        if (bytes != null) {
                            setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        } else {
                            currentArtworkUri?.let { setArtworkUri(it.toUri()) }
                        }
                    }
                    .build(),
            )
        subtitleUri?.let { sub ->
            builder.setSubtitleConfigurations(listOf(subtitleConfiguration(sub)))
        }
        val item = builder.build()
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
            c.replaceMediaItem(c.currentMediaItemIndex, item)
            c.prepare()
            // replaceMediaItem 把位置重置到该项起点，需显式还原（含待落实 seek）
            c.seekTo(position)
        } else {
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
        // 媒体项重建后保证文本轨开启（清除态不经此路径，不会被误开）
        setTextTracksEnabled(true)
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
