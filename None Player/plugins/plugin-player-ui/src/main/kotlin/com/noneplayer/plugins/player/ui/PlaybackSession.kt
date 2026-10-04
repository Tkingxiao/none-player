package com.noneplayer.plugins.player.ui

import android.content.Context
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.VideoPlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 正在播放的快照——迷你条与通知的数据源（§8.1 `NowPlaying`）。
 *
 * 只带 UI 需要展示的字段，不暴露播放器内部细节。
 */
data class NowPlaying(
    val item: MediaItem,
    val index: Int,
    val queueSize: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
) {
    val title: String get() = item.title
    val coverUri: String? get() = item.coverUri

    /** 进度 0f..1f；时长未知时为 0 */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 播放会话层（§8.1，P0 的架构核心）——**进程级单例，插件自己持有，不污染内核**。
 *
 * **解决的问题**：此前"正在播放什么"只存在于 `PlayerScreen` 的组合内
 * （`remember { factory.create(...) }`）——一旦退页，App 内没有任何组件知道会话状态，
 * 于是迷你条、连播、队列全都无从实现。
 *
 * **L0（§11.2，前置不是优化项）**：`ExoPlayer` 实例本就归 `PlaybackService` 独占，
 * 页面只经 `MediaController` 驱动。因此**退页不再 `release()`**——会话跨页存活，
 * L1（LoadControl 调参）/L2（下一集预加载）才有落点；否则页面 dispose 会把预载源
 * 一起释放，表现为"开了预加载但没有任何收益"。
 *
 * 生命周期：与进程同寿（[stop] 只停播并清空队列，不销毁实例）；
 * 引擎实例按 mimeType 变化时重建（不同格式可能选中不同引擎插件）。
 */
class PlaybackSession(
    private val context: Context,
    private val factory: VideoPlayerFactory,
    private val registry: ExtensionRegistry,
    private val settings: AppSettings,
    private val events: EventBus,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 仅听音频模式（设计稿 `.audio` 整屏页的开关）。
     *
     * 放会话而不是路由参数：迷你条的耳机钮在播放页**之外**，它要能把整个 App
     * 带进/带出该模式；路由参数方案会让"迷你条切仅听"变成一次压栈。
     * 进程级状态与播放会话同寿——退页后台播放时模式保持。
     */
    private val _audioOnly = MutableStateFlow(false)

    /** 仅听音频模式状态（UI collectAsState 用） */
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** 切换仅听音频（迷你条耳机钮 / 播放页 pactions / 音频页「切回视频」共用） */
    fun toggleAudioOnly() {
        _audioOnly.value = !_audioOnly.value
    }

    /** 直接置位（§5.1 per-media 记忆恢复：进同一文件时直接进音频形态） */
    fun setAudioOnly(value: Boolean) {
        _audioOnly.value = value
    }

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    init {
        observeEngineIndex()
    }

    /**
     * 队列下标以**引擎**为真相：底栏 ⏮⏭、播完自动续播都由 media3 推进播放项，
     * 会话若只在自己调 [selectQueueIndex] 时写下标，页头 / 选集高亮 / 迷你条
     * 会停在旧集数上。引擎换代时经 `flatMapLatest` 自动重新订阅新实例的下标流。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeEngineIndex() {
        scope.launch {
            _player.flatMapLatest { p -> p?.currentIndex ?: flowOf(-1) }
                .collect { idx ->
                    if (idx != _index.value && idx in _queue.value.indices) _index.value = idx
                }
        }
    }

    /**
     * 当前播放引擎实例。`null` 表示尚未装载过任何媒体。
     *
     * 作为 StateFlow 暴露是为了让 [nowPlaying] 能在"引擎被创建/换代"时
     * 自动重新订阅新实例的位置/状态流（`flatMapLatest`）——否则引擎换代后
     * 迷你条会永远停在旧引擎的最后一次快照上。
     */
    private val _player = MutableStateFlow<VideoPlayer?>(null)

    /** 已创建引擎对应的 mimeType，用于判断能否复用 */
    private var engineMimeType: String? = null

    /** 当前引擎实例（供播放页直接驱动） */
    val player: VideoPlayer? get() = _player.value

    /**
     * 迷你条数据源：队列 + 下标 + 引擎实时状态三者组合。
     *
     * 位置轮询本身由引擎每 250ms 写一次 StateFlow（见 `ExoVideoPlayer.pollPosition`），
     * 这里只做组合，不再另起轮询（避免两份轮询节奏打架）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<NowPlaying?> =
        combine(_queue, _index, _player.flatMapLatest { engineSnapshotFlow(it) }) { queue, index, snapshot ->
            val item = queue.getOrNull(index) ?: return@combine null
            NowPlaying(
                item = item,
                index = index,
                queueSize = queue.size,
                positionMs = snapshot.positionMs,
                durationMs = if (snapshot.durationMs > 0) snapshot.durationMs else item.durationMs,
                isPlaying = snapshot.isPlaying,
                hasNext = index in 0 until queue.lastIndex,
                hasPrevious = index > 0,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private data class EngineSnapshot(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private fun engineSnapshotFlow(player: VideoPlayer?) = if (player == null) {
        flowOf(EngineSnapshot())
    } else {
        combine(player.state, player.positionMs, player.durationMs) { state, position, duration ->
            EngineSnapshot(
                isPlaying = state == PlaybackState.Playing,
                positionMs = position,
                durationMs = duration,
            )
        }
    }

    /**
     * 取得（必要时创建）播放引擎。**页面退出不释放**——由本会话持有。
     *
     * 引擎按 mimeType 选择（不同格式可能落到不同 `PlayerEngineProvider`）；
     * mimeType 变化且已有实例时才重建。
     */
    fun acquire(mimeType: String): VideoPlayer {
        val existing = _player.value
        if (existing != null && engineMimeType == mimeType) return existing
        existing?.release()
        val created = factory.create(context, mimeType)
        engineMimeType = mimeType
        _player.value = created
        return created
    }

    /**
     * 打开媒体并建立队列（同文件夹连播的来源，§8.2）。
     *
     * 队列写入 media3 会话后，**通知栏 ⏮⏭ 与播完自动下一集由框架免费提供**。
     */
    fun open(item: MediaItem, queueOfFolder: List<MediaItem>) {
        val player = acquire(item.mimeType)
        // 定位起点同样"先 id 再 uri"：来源重扫后 id 可能已换号（MediaStore._id 会变），
        // 只认 id 会让队列退化成单项且**静默**——播放正常，却悄悄丢掉连播与选集。
        val startIndex = queueOfFolder.indexOfFirst { it.id == item.id }
            .takeIf { it >= 0 }
            ?: queueOfFolder.indexOfFirst { it.uri == item.uri }
        val list: List<MediaItem>
        val index: Int
        if (startIndex >= 0) {
            list = queueOfFolder
            index = startIndex
        } else {
            // 当前项不在给定队列里（例如来源刚重扫过）：退化为单项队列，
            // 而不是把下标硬指到 0 ——那会让用户点到 A 却播 B。
            list = listOf(item)
            index = 0
        }
        _queue.value = list
        _index.value = index
        player.setQueue(list, index, 0L)
        events.publish(KernelEvent.PlaybackStarted(list[index].id))
    }

    /** 切换播放/暂停（迷你条 ▶/⏸ 直接控制会话） */
    fun toggle() {
        val p = _player.value ?: return
        if (p.state.value == PlaybackState.Playing) p.pause() else p.play()
    }

    fun play() {
        _player.value?.play()
    }

    fun pause() {
        _player.value?.pause()
    }

    /** 下一集（无下一集时为空操作） */
    fun next() {
        persistPosition()
        _player.value?.playNext()
    }

    fun previous() {
        persistPosition()
        _player.value?.playPrevious()
    }

    private val _loopMode = MutableStateFlow(NpLoopMode.OFF)

    /** 循环模式（fs-panel / more sheet 驱动；会话为 UI 侧单一真相，切换时同步给引擎） */
    val loopMode: StateFlow<NpLoopMode> = _loopMode.asStateFlow()

    fun setLoopMode(mode: NpLoopMode) {
        _loopMode.value = mode
        _player.value?.setLoopMode(mode)
    }

    /** 选集面板：跳到队列指定项 */
    fun selectQueueIndex(index: Int) {
        if (index !in _queue.value.indices) return
        // 切集前把**离开的那一项**的进度落盘——否则从选集/底栏跳集会把断点丢掉
        // （页退落盘只记得住当前项）。
        persistPosition()
        _index.value = index
        _player.value?.selectQueueIndex(index)
        events.publish(KernelEvent.PlaybackStarted(_queue.value[index].id))
    }

    /**
     * 停止播放并清空会话（迷你条 ✕）。
     *
     * 只停播 + 清空队列，**不 release 引擎**——会话是进程级复用对象，
     * 引擎释放交给进程结束或引擎换代，避免"关掉迷你条下次进页要重建整条会话"。
     */
    fun stop() {
        val p = _player.value
        p?.pause()
        p?.seekTo(0L)
        _queue.value = emptyList()
        _index.value = -1
    }

    /** 记住当前项进度（退页/切集时调用），供断点续播 */
    fun persistPosition() {
        val item = _queue.value.getOrNull(_index.value) ?: return
        val position = _player.value?.positionMs?.value ?: return
        if (item.id.isEmpty() || position <= MIN_PERSIST_POSITION_MS) return
        scope.launch {
            withContext(Dispatchers.IO) { settings.savePlaybackPosition(item.id, position) }
        }
    }

    private companion object {
        /** 与 E12 语义一致：太靠前的进度不算"看过"，不写断点 */
        const val MIN_PERSIST_POSITION_MS = 1_000L
    }
}
