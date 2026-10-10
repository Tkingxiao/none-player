package com.noneplayer.plugins.player.ui

import android.content.Context
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.NpDecoding
import com.noneplayer.core.media.cardTitle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    // E42：展示名统一用 cardTitle（富化短标题）——迷你条/通知/点回播放页的路由标题共用
    val title: String get() = item.cardTitle
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
    /** §8.3 持久层：断点/历史的唯一写入方（DataStore 只留设置项，E11） */
    private val history: HistoryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // §8.3 写入时机：播放中每 5s 落一次断点；暂停/切集/退页另有显式调用。
        // 引擎/队列未就绪时 persistPosition 自行短路，这里不做状态判断之外的事。
        scope.launch {
            while (true) {
                delay(PERIODIC_PERSIST_MS)
                if (_player.value?.state?.value == PlaybackState.Playing) persistPosition()
            }
        }
        // L1：「自动连播下一集」设置的真实接线（此前只有存储与展示、无消费端——关掉后照样
        // 连播）。用 combine 而不是只 collect 设置：**引擎换代**（切到不同 mimeType 时重建）
        // 后新实例回到默认值，只订阅设置变化会漏掉这一次补发。
        scope.launch {
            combine(settings.autoplayNext, _player) { autoplay, player -> autoplay to player }
                .collect { (autoplay, player) -> player?.setAutoplayNext(autoplay) }
        }
        // §9.4「播放中自动降一档」的输入。此前这条管道是**空转的**：主题层的
        // `forPlayback()` 与 `autoMotionQuality(decodingVideo)` 都写好了，但 app 侧
        // 拿不到会话，只能恒传 false，于是解码期的装饰动画从来没降过档。
        // 判据是"视频正在播"：仅听音频态没有视频解码器在跑，不该拿它去削别的动画。
        scope.launch {
            combine(_player.flatMapLatest { it?.state ?: flowOf(PlaybackState.Idle) }, _audioOnly) { state, audioOnly ->
                state == PlaybackState.Playing && !audioOnly
            }.collect { NpDecoding.set(it) }
        }
    }

    /**
     * 仅听音频模式（设计稿 `.audio` 整屏页的开关）。
     *
     * 放会话而不是路由参数：迷你条的耳机钮在播放页**之外**，它要能把整个 App
     * 带进/带出该模式；路由参数方案会让"迷你条切仅听"变成一次压栈。
     * 进程级状态与播放会话同寿——退页后台播放时模式保持。
     *
     * **§5.1 的 per-media 记忆由这里一并写**（[toggleAudioOnly]/[setAudioOnly] 是唯一写路径）。
     * 此前只有播放页那两处写：迷你条走无记忆的切换，于是"从迷你条进音频态 → 杀进程 →
     * 重进同一文件"落回视频形态，落盘只剩旧键（A1-1）。写路径下沉后三个入口天然同源，
     * 也杜绝了"页内两处各写一份、漂一份"的老问题。
     */
    private val _audioOnly = MutableStateFlow(false)

    /** 仅听音频模式状态（UI collectAsState 用） */
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /**
     * 当前条目的**记忆键**（`sourceId:mediaId`，A2-8）；空 = 尚未装载或条目无 id。
     *
     * per-media 记忆的写键用它，而不是调用方手里的路由 id：深链/通知回跳的路由 id
     * 可能为空（A1-2），而队列条目是 `session.open` 里经 E90「先 id 再 uri」选定的那一条。
     */
    val currentMediaKey: String
        get() = _queue.value.getOrNull(_index.value)
            ?.takeIf { it.id.isNotEmpty() }
            ?.let { mediaMemoryKey(it.sourceId, it.id) }
            .orEmpty()

    /** 切换仅听音频并写 per-media 记忆（迷你条耳机钮 / 播放页 pactions / 音频页「切回视频」共用） */
    fun toggleAudioOnly() {
        setAudioOnly(!_audioOnly.value)
    }

    /** 置位并写 per-media 记忆（§5.1 恢复路径重复写同值 = 幂等，不多一条语义） */
    fun setAudioOnly(value: Boolean) {
        _audioOnly.value = value
        val key = currentMediaKey
        if (key.isNotEmpty()) {
            scope.launch { settings.setAudioOnlyPreferred(key, value) }
        }
    }

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    /** 上一次 [acquire] 失败的原因（null = 无）；UI 据此渲染体面失败面板（E88 留尾） */
    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

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
                    if (idx != _index.value && idx in _queue.value.indices) {
                        _index.value = idx
                        // 引擎推进（自动连播 / 通知栏 ⏭）也广播给全仓库：
                        // 详情页等 UI 侧订阅方靠它把"正在看的条目"跟上队列，
                        // 否则右栏已在放下一集、详情页还停在播完的那一集。
                        events.publish(KernelEvent.PlaybackStarted(_queue.value[idx].id))
                    }
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

    /**
     * **位置无关**的会话摘要——只用来渲染"在播什么"，进度请自己订阅
     * [VideoPlayer.positionMs]。
     *
     * 为什么要这份投影（E120）：[nowPlaying] 里含 `positionMs`，而引擎每 250ms 轮询
     * 一次进度，于是它**每秒发 4 个新值**。页面只要 `by session.nowPlaying` 解包，
     * 整页就跟着进度重组——仅听音频页实测 **39 次/12s**（两个 `radialGradient`、
     * 唱片、6 枚 chip 全部重跑），类 PC 外壳的 `isOccupied` 同理会把失效广播到
     * 整个骨架（含媒体库那一栏）。
     *
     * 实现是"把 positionMs 归零再 distinctUntilChanged"，所以
     * **这份投影的 `progress` 恒为 0**——它不是"少更新一点的 nowPlaying"，
     * 而是"只回答在播什么"的另一份真相。需要进度的叶子（迷你条、进度行）
     * 继续用 [nowPlaying] 或直接订引擎。
     */
    val nowPlayingSummary: StateFlow<NowPlaying?> =
        nowPlaying.map { snap -> snap?.copy(positionMs = 0L) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

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
     *
     * 无引擎（插件未启用 / 未知 mime）时返回 null 并把原因写进 [engineError]，
     * **不再主线程硬抛**（E88 留尾：此前 `factory.create` 的 `error(...)` 会以
     * IllegalStateException 闪退）——UI 侧渲染体面失败面板。
     */
    fun acquire(mimeType: String): VideoPlayer? {
        val existing = _player.value
        if (existing != null && engineMimeType == mimeType) return existing
        existing?.release()
        val created = runCatching { factory.create(context, mimeType) }
            .onFailure { _engineError.value = it.message ?: it.javaClass.simpleName }
            .getOrNull()
        if (created == null) {
            // 失败收口：旧引擎在上面已经 release，实例字段不能继续指向它——否则
            // nowPlaying 会继续订阅一个已释放的控制器（位置/状态冻在最后一帧、
            // 迷你条像卡住），player getter 也会把死对象交给 UI。会话回到"无引擎"干净态。
            _player.value = null
            engineMimeType = null
            _queue.value = emptyList()
            _index.value = -1
            return null
        }
        engineMimeType = mimeType
        _engineError.value = null
        _player.value = created
        return created
    }

    /**
     * 打开媒体并建立队列（同文件夹连播的来源，§8.2）。
     *
     * 队列写入 media3 会话后，**通知栏 ⏮⏭ 与播完自动下一集由框架免费提供**。
     */
    fun open(
        item: MediaItem,
        queueOfFolder: List<MediaItem>,
        /** 起始位置（断点续播）：经 `setQueue` 的 startPositionMs 下发——
         *  它由引擎在 prepare 时套用，不依赖"控制器已连接"这一时序假设
         *  （单独的 seekTo 在控制器未就绪时会被静默丢弃，模拟器实测续播丢失）。 */
        startPositionMs: Long = 0L,
    ) {
        // 无引擎：会话保持干净（不动队列/下标），原因已写入 engineError 由 UI 呈现
        val player = acquire(item.mimeType) ?: return
        val (list, index) = resolveQueueStart(queueOfFolder, item)
        _queue.value = list
        _index.value = index
        player.setQueue(list, index, startPositionMs.coerceAtLeast(0L))
        events.publish(KernelEvent.PlaybackStarted(list[index].id))
        // play_count 在"进入某项播放"时 +1（周期/暂停落点不算，避免被落点次数灌水）
        scope.launch { withContext(Dispatchers.IO) { history.markPlayed(list[index]) } }
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
     *
     * L8：底层 media3 的 media items 也必须清（[VideoPlayer.clearQueue]），
     * 否则通知栏会残留一条"已暂停"的媒体通知——迷你条与通知应当同生命周期。
     */
    fun stop() {
        val p = _player.value
        p?.pause()
        // 清队列前先把当前项断点落盘：周期落点最多差 5s，显式关闭不该丢掉这一截
        // （persistPosition 自行短路，队列已空/引擎未就绪时是空操作）。
        persistPosition()
        p?.seekTo(0L)
        // 清底层队列：引擎不支持时为空操作，这里仍继续清会话侧状态。
        p?.clearQueue()
        _queue.value = emptyList()
        _index.value = -1
    }

    /**
     * 记住当前项进度（周期/暂停/切集/退页时调用），供断点续播与历史。
     *
     * 门槛与"看完"语义收在 [HistoryRepository.shouldPersist]（E12：<10s 且未完看不写）——
     * 此前页面侧的门槛是 1s，切集瞬间的 0 附近位置会把「继续观看」污染成 0:00。
     */
    fun persistPosition() {
        val item = _queue.value.getOrNull(_index.value) ?: return
        val p = _player.value ?: return
        val position = p.positionMs.value
        val duration = p.durationMs.value
        if (item.id.isEmpty()) return
        if (!HistoryRepository.shouldPersist(position, duration)) return
        scope.launch {
            withContext(Dispatchers.IO) { history.record(item, positionMs = position, durationMs = duration) }
        }
    }

    private companion object {
        /** §8.3：播放中每 5s 落一次断点 */
        const val PERIODIC_PERSIST_MS = 5_000L
    }
}

/**
 * per-media 记忆的 DataStore 键（A2-8）：`sourceId:mediaId`，与 Room 的 `media_key`
 * （[com.noneplayer.core.database.HistoryEntry.keyOf]）同一条约定。
 *
 * 路由条目没有 sourceId（深链契约只带 id）时按当前唯一来源补前缀——
 * 与 [com.noneplayer.core.data.AppSettings.migratePerMediaKeyNamespaceOnce]
 * 对旧键的推断**必须是同一个**，否则搬完家反而读不到。
 */
internal fun mediaMemoryKey(sourceId: String, mediaId: String): String =
    "${sourceId.ifEmpty { LOCAL_MEDIA_SOURCE_ID }}:$mediaId"

/**
 * 队列定位（E18 提纯为普通函数以便单测）：**先 id 再 uri**——来源重扫后
 * MediaStore._id 可能换号，只认 id 会让队列退化成单项且**静默**。
 * 两者都不命中（例如来源刚重扫过）时退化为单项队列、下标 0，而不是把
 * 下标硬指到 0——那会让用户点到 A 却播 B。
 */
internal fun resolveQueueStart(
    queueOfFolder: List<MediaItem>,
    item: MediaItem,
): Pair<List<MediaItem>, Int> {
    val startIndex = queueOfFolder.indexOfFirst { it.id == item.id }
        .takeIf { it >= 0 }
        ?: queueOfFolder.indexOfFirst { it.uri == item.uri }
    return if (startIndex >= 0) {
        queueOfFolder to startIndex
    } else {
        listOf(item) to 0
    }
}
