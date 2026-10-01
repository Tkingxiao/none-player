package com.noneplayer.plugins.player.exo

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.probe.FormatProbe
import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val _videoAspectRatio = MutableStateFlow(0f)
    override val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    override val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _currentUri = MutableStateFlow<String?>(null)
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val cues: StateFlow<List<SubtitleCue>> = _cues.asStateFlow()

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
                        // release() 已先执行：这个连接不再被任何人持有，直接释放
                        c.release()
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
                            _cues.value = cueGroup.cues.map { it.toKernelCue() }
                        }
                    })
                    // 会话建立后补挂显示表面，并同步会话侧既有状态（速度/当前媒体）
                    attachedDisplay?.let { c.setVideoSurfaceView(it as? SurfaceView) }
                    _speed.value = c.playbackParameters.speed
                    _currentUri.value = c.currentMediaItem?.localConfiguration?.uri?.toString()
                    // 会话已在播媒体时，新控制器不会再收到 onVideoSizeChanged：
                    // 主动同步一次画面比例，否则重进播放页"横屏视频自动全屏"因比例未知而失效
                    c.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { applyVideoSize(it) }
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
            val duration = c.duration
            if (duration > 0 && _durationMs.value != duration) _durationMs.value = duration
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
     *
     * E58 修复配套（二次防御）：即便 FormatProbe.probe 已正确区分取消异常
     * （见 FormatProbe.kt），这里仍为每次 prepare() 打一个单调递增的世代号。
     * 快速切换 A→B 时，A 的探测协程即便在极端调度下"取消晚了一步"跑完，
     * 落地前也会先核对世代号——不是最新一次就直接丢弃，不会覆盖 B 的状态。
     */
    override fun prepare(uri: String, mimeType: String, title: String, artworkUri: String?) {
        currentMediaUri = uri
        currentMimeType = mimeType
        currentTitle = title
        currentArtworkUri = artworkUri
        prepareJob?.cancel()
        terminalFailure = false
        val myGeneration = ++prepareGeneration
        prepareJob = scope.launch {
            val probe = FormatProbe.probe(appContext, uri)
            if (myGeneration != prepareGeneration) return@launch // 已有更新的 prepare()，丢弃
            when (probe) {
                is FormatProbe.Result.Unplayable -> {
                    appliedUri = null
                    probedContainer = probe.failure.container
                    probedVideoCodec = probe.failure.videoCodec
                    probedAudioCodec = probe.failure.audioCodec
                    // 先停掉会话里可能还在播的上一个媒体，再落终态失败
                    runCatching { controller?.stop() }
                    terminalFailure = true
                    _state.value = PlaybackState.Error(probe.failure.reason, probe.failure)
                }
                is FormatProbe.Result.Playable -> {
                    probedContainer = probe.container
                    probedVideoCodec = probe.videoCodec
                    probedAudioCodec = probe.audioCodec
                    applyMedia(uri)
                }
                FormatProbe.Result.Unknown -> applyMedia(uri)
            }
        }
    }

    override fun attachDisplay(view: Any?) {
        attachedDisplay = view
        controller?.setVideoSurfaceView(view as? SurfaceView)
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
        val c = controller ?: return
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        _queue.value = items
        _currentIndex.value = safeIndex
        // 队列装载即覆盖当前媒体：重置世代号，避免上一次探测结果回写
        prepareJob?.cancel()
        terminalFailure = false
        pendingSeekMs = startPositionMs.takeIf { it > 0 }

        // E80 修复：**队列路径也必须过起播前探测**。
        //
        // 批次 1 引入队列后，播放页改走 setQueue()，而 setQueue 直接 setMediaItems +
        // c.prepare()，**绕过了 prepare() 里的 FormatProbe**。后果是 E39 的"体面失败"
        // 在队列路径上整体失效：以 wmv2-clip.avi 为例，AviExtractor 会丢弃它不认识的
        // WMV2 视频轨（`Missing Stream Format`），只剩 MP3 音轨——于是播放器"成功地"
        // 播完 4 秒音频并显示"播放结束"，用户只看到**纯黑屏**，无从知道是编码不支持。
        //
        // 这里对**即将播放的那一项**做探测：不可解则不装载队列，直接落结构化失败态。
        val target = items[safeIndex]
        val myGeneration = ++prepareGeneration
        prepareJob = scope.launch {
            val probe = FormatProbe.probe(appContext, target.uri)
            if (myGeneration != prepareGeneration) return@launch // 已有更新的装载，丢弃
            when (probe) {
                is FormatProbe.Result.Unplayable -> {
                    appliedUri = null
                    probedContainer = probe.failure.container
                    probedVideoCodec = probe.failure.videoCodec
                    probedAudioCodec = probe.failure.audioCodec
                    runCatching { controller?.stop() }
                    terminalFailure = true
                    _state.value = PlaybackState.Error(probe.failure.reason, probe.failure)
                }
                is FormatProbe.Result.Playable -> {
                    probedContainer = probe.container
                    probedVideoCodec = probe.videoCodec
                    probedAudioCodec = probe.audioCodec
                    applyQueue(mediaItemsOf(items), safeIndex, startPositionMs)
                }
                FormatProbe.Result.Unknown -> applyQueue(mediaItemsOf(items), safeIndex, startPositionMs)
            }
        }
    }

    /** setQueue 成功路径的装载体（探测通过后调用），保持与探测前的行为一致 */
    private fun applyQueue(
        mediaItems: List<MediaItem>,
        safeIndex: Int,
        startPositionMs: Long,
    ) {
        val c = controller ?: return
        _queue.value.let { if (it.isNotEmpty()) _currentUri.value = it[safeIndex].uri }
        c.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
    }

    private fun mediaItemsOf(items: List<com.noneplayer.core.media.MediaItem>): List<MediaItem> =
        items.map { it.toMediaItem() }

    override fun selectQueueIndex(index: Int) {
        val c = controller ?: return
        val items = _queue.value
        if (index !in items.indices) return
        _currentIndex.value = index
        // seekToDefaultPosition(index) 由 media3 负责装载目标项；
        // 预载源命中时这一步几乎无缓冲（L2 的收益点）
        c.seekToDefaultPosition(index)
        c.play()
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

    /** 队列项 → media3 MediaItem（标题/封面/字幕一并带上） */
    private fun com.noneplayer.core.media.MediaItem.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
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
                setTextTracksEnabled(c, false)
            uri != null && uri == mountedSubtitleUri ->
                setTextTracksEnabled(c, true)
            // 更换字幕文件：唯一需要重建媒体项的分支（探测未完成时由 applyMedia 带上）
            media == appliedUri -> applyMedia(media)
            else -> Unit
        }
    }

    /** 文本轨开关（TrackSelectionParameters 对当前与后续媒体项生效） */
    private fun setTextTracksEnabled(c: MediaController, enabled: Boolean) {
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
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
    }

    override fun release() {
        released = true
        scope.cancel()
        // 注意：会话侧播放器由 PlaybackService 持有，后台播放继续；
        // 这里只解除 UI 与会话的连接
        controller?.release()
        controller = null
    }

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
        setTextTracksEnabled(c, true)
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
