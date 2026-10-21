package com.noneplayer.plugins.player.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.database.PlaybackHistoryStore
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.NpDecoding
import com.noneplayer.core.media.cardTitle
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerConnection
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.VideoPlayerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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
@Immutable
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
    /** §8.3 持久层：断点/历史的唯一写入方（DataStore 只留设置项，E11）。
     *  R2-1/REV-06：只依赖 [PlaybackHistoryStore] 契约，写失败以降级返回值呈现，不抛进播放链路。 */
    private val history: PlaybackHistoryStore,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            // R2-1/REV-06：后台任务异常不再无人接——记录后隔离，避免整个播放会话被拖垮。
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "会话后台任务异常（已隔离）", error) },
    )
    private var startJob: Deferred<SessionStart>? = null

    /** 在途装载的**完整意图**（A9）；与 [startJob] 同生共死，stop() 时一并清空。 */
    private var startingIntent: StartIntent? = null
    private val appVisible = MutableStateFlow(true)
    private val _sleepTimerEndsAtMs = MutableStateFlow(0L)
    val sleepTimerEndsAtMs: StateFlow<Long> = _sleepTimerEndsAtMs.asStateFlow()
    private var sleepJob: Job? = null

    fun toggleSleepTimer() {
        sleepJob?.cancel()
        if (_sleepTimerEndsAtMs.value > 0L) {
            _sleepTimerEndsAtMs.value = 0L
        } else {
            _sleepTimerEndsAtMs.value = System.currentTimeMillis() + 30 * 60_000L
            sleepJob = scope.launch {
                delay(30 * 60_000L)
                pause()
                _sleepTimerEndsAtMs.value = 0L
            }
        }
    }

    // Loading belongs to the session: moving its surface from detail to pane must not cancel restoration.
    internal suspend fun startPlayback(
        item: MediaItem,
        restorePosition: Boolean = true,
        autoPlay: Boolean = true,
        prepareOnly: Boolean = false,
        audioOnlyMode: Boolean? = null,
    ): SessionStart {
        val intent = startIntentOf(item, restorePosition, autoPlay, prepareOnly, audioOnlyMode)
        val pending = startJob
        val previous = startingIntent
        when (startDisposition(previous, pending?.isActive == true, intent)) {
            StartDisposition.JOIN_PENDING -> {
                // 同一意图：旧任务要做的和这次完全一样，等它落地即可。
                // audioOnlyMode 相同，旧任务自己会应用；这里再补一次是幂等的，
                // 只为覆盖"旧任务看到 accepted=true 之后、应用模式之前"的极窄时序。
                val result = awaitStart(pending!!)
                if (result.accepted && intent.audioOnlyMode != null) setAudioOnly(intent.audioOnlyMode)
                return result
            }
            StartDisposition.SUPERSEDE_PENDING -> {
                // A9：旧任务作废、按新意图重跑。cancel() 打在它的挂起点上
                // （awaitReady / 历史读盘等），取消由协程内部逐层抛出，**不会**
                // 落成 UI 可读的错误状态；旧 awaiter 也不会收到这个取消（见 awaitStart）。
                //
                // 但若它已经跑过 session.open()（会话队列已指向这条媒体），引擎里
                // 那份装载是按**旧参数**定下的 startPositionMs / playOnLoad——必须清掉，
                // 否则旧探测落地时会把新意图（从头播 / 只装载）原样盖回去。
                // 换媒体时不需要：canReusePlayback 本来就不成立，新任务必然重新装载。
                if (pending != null && previous != null &&
                    supersedeNeedsEngineReset(previous, intent, _queue.value.getOrNull(_index.value))
                ) {
                    _player.value?.clearQueue()
                }
                pending?.cancel()
            }
            StartDisposition.START_NEW -> pending?.cancel()
        }
        startingIntent = intent
        val job = scope.async { ensureStart(intent) }
        startJob = job
        return awaitStart(job)
    }

    /**
     * 按 [intent] 装载一次。
     *
     * 引擎由会话持有（[acquire] 复用/换代），这里被取消时**不需要**释放引擎：
     * 它留给下一个意图、或 [stop] 之后的会话继续复用，不随单次装载的生死走
     * （会话级对象的既有设计，见 [stop] 的 KDoc）。真正耗时的起播探测跑在引擎
     * 自己的 scope 里，靠装载代际与 [VideoPlayer.clearQueue] 打断，不挂在本协程树下。
     */
    private suspend fun ensureStart(intent: StartIntent): SessionStart {
        val item = intent.item
        val player = acquire(item.mimeType) ?: return SessionStart(accepted = false)
        return ensureSessionPlaying(
            context, registry, settings, history, player, this, item,
            restorePosition = intent.restorePosition,
            autoPlay = intent.autoPlay,
            prepareOnly = intent.prepareOnly,
            audioOnlyMode = intent.audioOnlyMode,
            onSubtitleMatched = { key, hit -> scope.launch { settings.setSubtitleUri(key, hit) } },
        )
    }

    /**
     * 等待在途装载，但**不把"被取代"当成播放失败抛给 UI**（A9）。
     *
     * 旧任务被 cancel 时 `Deferred.await()` 会抛 CancellationException。对调用方
     * 来说这不是错误，而是"有更新的意图接管了这次装载"：返回一个中性的未接受结果
     * 即可（调用方只读 resumedFromMs / coverUri / subtitleMissing，缺失时各自回退）。
     *
     * 先 `ensureActive()` 分辨取消来源：若是**调用方自己**被取消（页面销毁 / stop），
     * 照旧向上抛出——取消的任务必须保持取消，别把页面取消吞成一次"装载完成"。
     */
    private suspend fun awaitStart(job: Deferred<SessionStart>): SessionStart = try {
        job.await()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        SessionStart(accepted = false)
    }

    init {
        scope.launch {
            // Process STOP is delayed across configuration changes and does not fire for visible PiP.
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> appVisible.value = true
                    Lifecycle.Event.ON_STOP -> {
                        persistPosition()
                        appVisible.value = false
                    }
                    else -> Unit
                }
            })
            appVisible.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            combine(settings.backgroundPlay, appVisible, _queue) { background, visible, queue ->
                shouldStopInBackground(background, visible, queue.isNotEmpty())
            }.distinctUntilChanged().collect { shouldStop -> if (shouldStop) stop() }
        }
        // §8.3 写入时机：播放中每 15s 落一次断点；暂停/切集/退页另有显式调用。
        // A24：2h 片子的周期写从约 1440 次降到约 480 次；显式时机的实时性不变。
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
        observeDecodingVideo()
    }

    // §9.4「播放中自动降一档」的输入。此前这条管道是**空转的**：主题层的
    // `forPlayback()` 与 `autoMotionQuality(decodingVideo)` 都写好了，但 app 侧
    // 拿不到会话，只能恒传 false，于是解码期的装饰动画从来没降过档。
    // 判据是"视频正在播"：仅听音频态没有视频解码器在跑，不该拿它去削别的动画。
    // flatMapLatest 仍是实验 API；init 块不能挂注解，故下沉到这里单独 opt-in。
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDecodingVideo() {
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
    private var audioModeRevision = 0L
    private var subtitleRevision = 0L

    /** 仅听音频模式状态（UI collectAsState 用） */
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _subtitleOffsetMs = MutableStateFlow(0L)
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs.asStateFlow()

    /** All player surfaces share the session offset; changing presentation must not reset it. */
    fun setSubtitleOffsetMs(value: Long) {
        val offset = clampSubtitleOffsetMs(value)
        _subtitleOffsetMs.value = offset
        _player.value?.setSubtitleOffsetMs(offset)
    }

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
        audioModeRevision++
        val item = _queue.value.getOrNull(_index.value)
        val target = resolveAudioOnlyMode(value, item?.kind == MediaKind.AUDIO)
        _audioOnly.value = target
        player?.setVideoTracksEnabled(!target)
        val key = currentMediaKey
        if (key.isNotEmpty()) {
            scope.launch { settings.setAudioOnlyPreferred(key, target) }
        }
    }

    /**
     * 切下标后重挂**该集自己的**外挂字幕（A4）。
     *
     * 引擎在切下标时一定会清掉字幕（新一集不许继承上一集的字幕），而字幕记忆是按
     * `sourceId:mediaId` 逐集存的——所以**每一次切下标**都要重新解析一遍：先读该集的
     * 记忆，没有再走同目录同名自动匹配，命中即写回记忆（与起播路径 `SessionBootstrap`
     * 同一套）。此前这两个落点只恢复仅听模式，于是连播/选集跳到第 2 集之后，
     * 第 2 集记忆过的字幕与自动匹配全部失效，只剩内嵌轨。
     *
     * 姿势照 [restoreAudioMode]：改序号 → 异步读 → 落地前核条目。异步期间用户又切了一次
     * （或手动挂了别的文件），旧结果不许盖到新集上。
     */
    private fun restoreSubtitle(item: MediaItem) {
        val revision = ++subtitleRevision
        player?.setSubtitle(null)
        if (item.id.isEmpty()) return
        scope.launch {
            val key = mediaMemoryKey(item.sourceId, item.id)
            val source = settings.subtitleUri(key).first()
                ?: resolveSiblingSubtitle(context, item.uri, settings.scanFolderUris.first())
                    ?.also { hit -> settings.setSubtitleUri(key, hit) }
                ?: return@launch
            val prepared = prepareSubtitleUri(context, source.toUri())
            // 读记忆与转码都在 IO 上，两段各核一次条目身份
            if (revision != subtitleRevision) return@launch
            val current = _queue.value.getOrNull(_index.value)
            if (current?.id != item.id || current.uri != item.uri) return@launch
            player?.setSubtitle(prepared.uri, prepared.format)
            player?.setTextTracksEnabled(settings.subtitleEnabled.first())
        }
    }

    private fun restoreAudioMode(item: MediaItem, restoredMode: Boolean? = null) {
        val revision = ++audioModeRevision
        _audioOnly.value = resolveAudioOnlyMode(restoredMode, item.kind == MediaKind.AUDIO)
        player?.setVideoTracksEnabled(!_audioOnly.value)
        if (restoredMode != null || item.id.isEmpty()) return
        scope.launch {
            val stored = settings.audioOnlyPreference(mediaMemoryKey(item.sourceId, item.id)).first()
            val current = _queue.value.getOrNull(_index.value)
            if (revision == audioModeRevision && current?.id == item.id && current.uri == item.uri) {
                _audioOnly.value = resolveAudioOnlyMode(stored, item.kind == MediaKind.AUDIO)
                player?.setVideoTracksEnabled(!_audioOnly.value)
            }
        }
    }

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    /**
     * 装载代际（REV-04）：每次"会话里即将不再是当前这条媒体"的动作**之前**自增——
     * 换队列/换下标（[open]、[selectQueueIndex]）、清理会话（[stop]）。
     *
     * 用途：给断点快照打标记，日志里能看出"这一条进度属于哪一代"，测试也能断言
     * "这个窗口里确实发生过换代"。**它不是写盘门**——是否落盘由引擎回报的已生效
     * 身份（`VideoPlayer.appliedMediaKey`）判定；拿代际当门会在"下标已变、引擎还没切"
     * 的窗口里把合法落点一并丢掉（引擎切集是异步的，代际和身份不同步）。
     */
    @Volatile
    private var loadGeneration: Long = 0L

    /** 当前装载代际（测试/诊断用；生产逻辑内部只读 [loadGeneration]） */
    internal val loadGenerationNow: Long get() = loadGeneration

    /**
     * 会话是否**真的开始播过**——"有当前项"与"这是一个播放会话"的区别。
     *
     * 竖屏播放页的**未播放态**（路由 `play=0`）也会把媒体装载进会话：`queue`/`index`
     * 都在，但引擎停在就绪态、用户一次都没按过播放。此时占用语义必须与"在播/暂停"
     * 区分开：类 PC 右栏是"陪着你浏览的画面"，未播放态占住它，横屏就只剩一个停在
     * 封面的播放面板，而用户其实只是在看剧集详情（2026-10-15 真机实报"竖屏未播放
     * 转横屏直接打开右侧栏"）。
     *
     * 只装载不起播由 `open(startPlaying = false)` 落下；之后任何人让引擎真的播起来
     * （用户按播放、通知栏 ▶、播完自动连播）都算"播过"——所以这里**只认引擎状态**，
     * 不只认会话自己的那几个入口：播放页与通知栏都会直接驱动引擎。
     */
    private val _playbackStarted = MutableStateFlow(false)
    val playbackStarted: StateFlow<Boolean> = _playbackStarted.asStateFlow()

    /** 上一次 [acquire] 失败的原因（null = 无）；UI 据此渲染体面失败面板（E88 留尾） */
    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    init {
        observeEngineIndex()
        observePlaybackStarted()
    }

    /**
     * "播过"只认**引擎状态**：播放页底栏、通知栏、播完自动连播都会直接驱动引擎，
     * 会话侧的 `play()`/`open()` 不是唯一入口——只挂在那几处，"用户按了播放、
     * 转屏却依然不算播放会话"会漏。状态一旦到 [PlaybackState.Playing]，未播放态结束。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePlaybackStarted() {
        scope.launch {
            _player.flatMapLatest { it?.state ?: flowOf(PlaybackState.Idle) }
                .collect { state ->
                    if (state == PlaybackState.Playing) _playbackStarted.value = true
                }
        }
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
                        restoreAudioMode(_queue.value[idx])
                        restoreSubtitle(_queue.value[idx])
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

    /**
     * 已创建引擎的**身份**（[com.noneplayer.kernel.api.PlayerEngineProvider.engineId]）。
     *
     * 复用判据必须是引擎身份而不是 mimeType 字符串：同一个 exo 引擎既接 `video/mp4`
     * 也接 `video/x-matroska`，按字符串比会在换集（容器不同）时误判成"换代"——
     * 释放旧实例 + 重建 MediaController + 重连会话，几秒全落在用户等的那段时间里。
     * 引擎内部本来就按**每个条目各自的 mimeType** 选解封装器，无需换代。
     */
    private var engineId: String? = null

    /** 当前引擎实例（供播放页直接驱动） */
    val player: VideoPlayer? get() = _player.value

    /**
     * 与**承载当前引擎的播放服务**的连接状态（REV-10）。
     *
     * 真实连接态由引擎维护（media3 `MediaController` 的 connected/disconnected），
     * 会话只做透传；换代时经 `flatMapLatest` 自动改订阅新引擎——不能只订阅一次，
     * 否则旧引擎 release 时抛出的 DISCONNECTED 会把新会话永久染成"断开"。
     *
     * 无引擎（还没装载过任何媒体）按 [PlayerConnection.CONNECTED] 处理：没有服务
     * 可断，播放页的横幅不应在"还没开始播"时就亮起来。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connection: StateFlow<PlayerConnection> =
        _player.flatMapLatest { engine ->
            engine?.connection ?: flowOf(PlayerConnection.CONNECTED)
        }.stateIn(scope, SharingStarted.Eagerly, PlayerConnection.CONNECTED)

    /**
     * 重连播放服务（REV-10 横幅的「重连」/「重试」按钮）。
     *
     * 幂等、不阻塞画面：只把重连请求转给引擎（引擎内部重新 bind `MediaController`），
     * 结果由 [connection] 自己流回来；无引擎时是空操作。
     */
    fun retryConnection() {
        val engine = _player.value ?: return
        Log.d(TAG, "retryConnection: engine=$engineId")
        runCatching { engine.reconnect() }
            .onFailure { Log.d(TAG, "retryConnection failed: ${it.message}") }
    }

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
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

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
        // 目标 mimeType 归哪个引擎：同引擎（哪怕容器不同）直接复用既有实例
        val targetEngineId = runCatching { factory.engineIdFor(mimeType) }.getOrNull()
        if (existing != null && targetEngineId != null && targetEngineId == engineId) {
            // 复用时顺手清掉上一次失败的原因：引擎其实好着，面板不该继续挂着
            _engineError.value = null
            return existing
        }
        // A23：**先建新、再放旧**。反过来的话 create 失败时旧引擎已经被释放，
        // 而它可能还在正常播放（从"播视频"切到需要另一个引擎的条目时），
        // 表现为黑屏/无声，用户手里那条正在播的内容被一次失败的切换带走。
        val created = runCatching { factory.create(context, mimeType) }
            .onFailure { _engineError.value = it.message ?: it.javaClass.simpleName }
            .getOrNull()
        if (created == null) {
            // 失败收口：旧引擎（若有）**保持原样可用**，队列/下标不动——不能把 _player
            // 置空，否则 nowPlaying 丢掉在播条目、迷你条凭空消失，而旧控制器其实还活着。
            // 只有本来就没有引擎时才回到"无引擎"干净态。
            if (existing == null) {
                _player.value = null
                engineId = null
                _queue.value = emptyList()
                _index.value = -1
                _playbackStarted.value = false
            }
            return null
        }
        // 新引擎已就绪，此时才换代：先切会话侧引用，再释放旧实例——旧引擎 release
        // 期间的最后几次状态回流因此不会落到新会话上（连接状态、下标回调都会漏过）。
        engineId = targetEngineId
        _engineError.value = null
        _player.value = created
        created.setSubtitleOffsetMs(_subtitleOffsetMs.value)
        created.setVideoTracksEnabled(!_audioOnly.value)
        existing?.release()
        return created
    }

    /**
     * 打开媒体并建立队列（同文件夹连播的来源，§8.2）。
     *
     * 队列写入 media3 会话后，**通知栏 ⏮⏭ 与播完自动下一集由框架免费提供**。
     *
     * [startPlaying] = false：**只装载不起播**（竖屏播放页的"未播放态"落点）。
     * 这条意图同时落到两处：引擎的 `setQueue(playOnLoad = false)`（装载落地不起播）
     * 与 [playbackStarted]（未播放态不占右栏，见那里的说明）。
     */
    fun open(
        item: MediaItem,
        queueOfFolder: List<MediaItem>,
        /** 起始位置（断点续播）：经 `setQueue` 的 startPositionMs 下发——
         *  它由引擎在 prepare 时套用，不依赖"控制器已连接"这一时序假设
         *  （单独的 seekTo 在控制器未就绪时会被静默丢弃，模拟器实测续播丢失）。 */
        startPositionMs: Long = 0L,
        audioOnlyMode: Boolean? = null,
        startPlaying: Boolean = true,
    ) {
        // REV-04：换媒体前先把**上一条**的断点落盘。页退时的 persist 只保得住
        // "当时那一条"：从迷你条/详情页直接打开另一条时，旧媒体会丢掉最后一段进度
        // （周期落点最多差 15s）。必须放在 acquire/替换队列之前——此刻 _queue/_index
        // 与引擎都还指向旧媒体，身份核对才会通过。
        persistPosition()
        // 换代标记：此后队列/下标不再代表"引擎里正在放的那条"
        loadGeneration++
        // 无引擎：会话保持干净（不动队列/下标），原因已写入 engineError 由 UI 呈现
        val player = acquire(item.mimeType) ?: return
        val (list, index) = resolveQueueStart(queueOfFolder, item)
        _queue.value = list
        _index.value = index
        _playbackStarted.value = startPlaying
        restoreAudioMode(list[index], audioOnlyMode)
        player.setQueue(list, index, startPositionMs.coerceAtLeast(0L), playOnLoad = startPlaying)
        events.publish(KernelEvent.PlaybackStarted(list[index].id))
        // play_count 在"进入某项播放"时 +1（周期/暂停落点不算，避免被落点次数灌水）
        // R2-1/REV-06：写失败只降级为可观察日志，绝不让持久层异常打断起播。
        scope.launch {
            withContext(Dispatchers.IO) {
                val persisted = history.markPlayed(list[index])
                if (!persisted) Log.w(TAG, "markPlayed 落库失败（存储降级），本次播放计数丢失")
            }
        }
    }

    /** 切换播放/暂停（迷你条 ▶/⏸ 直接控制会话） */
    fun toggle() {
        val p = _player.value ?: return
        if (p.state.value == PlaybackState.Playing) p.pause() else play()
    }

    /**
     * 播放（A23）。**停在 [PlaybackState.Ended] 上时先归零再播**：media3 在 ENDED
     * 状态下 `play()` 是空操作（播放位置已在末尾、没有"下一项"可推进），用户按
     * 播放键毫无反应，只能自己去拖进度条。
     *
     * "同媒体重进播放页"那条路径由 `SessionBootstrap` 单独兜底（那里还要区分
     * 断点续播与从头播），两条都保留、互不冲突：这里只管用户主动按播放键。
     */
    fun play() {
        val p = _player.value ?: return
        if (p.state.value == PlaybackState.Ended) p.seekTo(0L)
        p.play()
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
        // 换代标记（REV-04）：从这一行起"当前下标"不再等于"引擎里正在放的那条"，
        // 直到引擎回报的 appliedMediaKey 跟上为止，落盘都会因身份不符被跳过。
        loadGeneration++
        if (_index.value != index) {
            _index.value = index
            restoreAudioMode(_queue.value[index])
            restoreSubtitle(_queue.value[index])
        }
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
        sleepJob?.cancel()
        _sleepTimerEndsAtMs.value = 0L
        startJob?.cancel()
        startJob = null
        startingIntent = null
        val p = _player.value
        p?.pause()
        // 清队列前先把当前项断点落盘：周期落点最多差 15s，显式关闭不该丢掉这一截
        // （persistPosition 自行短路，队列已空/引擎未就绪时是空操作）。
        persistPosition()
        // 清理前换代（REV-04）：此后"当前项"已不存在，任何迟到的落点都必须先过身份核对
        loadGeneration++
        p?.seekTo(0L)
        // 清底层队列：引擎不支持时为空操作，这里仍继续清会话侧状态。
        p?.clearQueue()
        _queue.value = emptyList()
        _index.value = -1
        _playbackStarted.value = false
        audioModeRevision++
        subtitleRevision++
        _audioOnly.value = false
        setSubtitleOffsetMs(0L)
    }

    /**
     * 记住当前项进度（周期/暂停/切集/退页时调用），供断点续播与历史。
     *
     * 门槛与"看完"语义收在 [HistoryRepository.shouldPersist]（E12：<10s 且未完看不写）——
     * 此前页面侧的门槛是 1s，切集瞬间的 0 附近位置会把「继续观看」污染成 0:00。
     *
     * REV-04：**落盘前先核对身份**。会话下标是引擎异步回写的（`observeEngineIndex`），
     * 在"引擎已切集、_index 还没跟上"（或反过来）的窗口里，用"当前项 + 引擎位置"落盘
     * 会把 A 的进度写进 B 的历史键，表现为点开 B 直接跳到 A 看到的位置。判定与取值
     * 全部收在纯函数 [resolvePersistSnapshot]（可单测），这里只负责日志与写库。
     */
    fun persistPosition() {
        // 目标 = 会话**当前下标**对应的条目；引擎里真正生效的是哪条，由 appliedMediaKey 核对
        val target = _queue.value.getOrNull(_index.value) ?: return
        val p = _player.value ?: return
        if (target.id.isEmpty()) return
        val generation = loadGeneration
        val snapshot = resolvePersistSnapshot(
            target = target,
            appliedMediaKey = p.appliedMediaKey,
            appliedPositionMs = p.appliedPositionMs,
            appliedDurationMs = p.appliedDurationMs,
            controllerPositionMs = p.positionMs.value,
            controllerDurationMs = p.durationMs.value,
            generation = generation,
        )
        if (snapshot == null) {
            // 身份不符：引擎里生效的还不是这条（切集/换媒体的异步窗口）。宁可这次不写，
            // 等下一次落点——写错键的代价是"点开另一集直接跳到别的位置"，比丢 15s 大得多。
            Log.d(
                TAG,
                "persistPosition skipped: identity mismatch target=${keyOf(target)} " +
                    "applied=${p.appliedMediaKey} gen=$generation",
            )
            return
        }
        if (!HistoryRepository.shouldPersist(snapshot.positionMs, snapshot.durationMs)) return
        Log.d(
            TAG,
            "persistPosition: ${snapshot.mediaKey} pos=${snapshot.positionMs} " +
                "dur=${snapshot.durationMs} gen=${snapshot.generation}",
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                val persisted = history.persistProgress(
                    target,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                )
                // R2-1/REV-06：失败不抛，只记诊断；下一次周期落点（15s）会继续尝试自愈。
                if (!persisted) {
                    Log.w(TAG, "断点落盘失败（存储降级），下次周期落点将重试 ${snapshot.mediaKey}")
                }
            }
        }
    }

    private companion object {
        /** Logcat 前缀（会话级诊断：断点落盘、引擎换代、连接状态） */
        const val TAG = "PlaybackSession"

        /** §8.3 / A24：播放中每 15s 落一次断点（暂停/切集/退页另有显式落盘）。 */
        const val PERIODIC_PERSIST_MS = 15_000L
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

internal fun clampSubtitleOffsetMs(value: Long): Long = value.coerceIn(-1_000L, 1_000L)

internal fun shouldStopInBackground(
    backgroundPlay: Boolean,
    appVisible: Boolean,
    hasSession: Boolean,
): Boolean = hasSession && !appVisible && !backgroundPlay

/**
 * 决定某个媒体此刻该处于哪种呈现模式（E129 / 审查 #1、#2）——纯函数，便于单测。
 *
 * Pure audio always uses audio mode. Video restores its preference, defaulting to video.
 *
 * **默认值不需要记忆键**：`storedPreference` 传 null 时这条同样给出类型默认，于是
 * "深链没带 id"的入口（外部 ACTION_VIEW / 通知回跳）也能拿到正确形态——那一路没有键
 * 可读（E109 的孤儿桶正是不该读它），但"音频不该露视频舞台"与"有没有键"无关。
 * 是否落盘由 [PlaybackSession.setAudioOnly] 自己判空键，不在这里分叉。
 *
 * 之所以提成纯函数而不是留在 composable 里：这两条都是**分支取值**错误，
 * 编译器与 UI 走查都抓不到，只有把决策从 Compose 里拆出来才钉得住
 * （同 [resolveQueueStart] 的取舍）。
 */
internal fun resolveAudioOnlyMode(
    storedPreference: Boolean?,
    isAudioMedia: Boolean,
): Boolean = isAudioMedia || storedPreference == true

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

/**
 * 一次 [PlaybackSession.startPlayback] 的**完整意图**（A9）。
 *
 * 会话同一时刻只有一条在途装载（[PlaybackSession.startJob]），后到的调用只有
 * 意图完全一致才能搭旧任务的车（[startDisposition] → JOIN）。此前只比较 uri+id，
 * 于是「同一条正在装载时，后到的调用要求从头播 / 打开但保持暂停 / 只装载」全被丢掉：
 * 旧任务已经按旧参数算好了起始位置、装载后播不播、视频轨开不开，落地时不会再回头看
 * 调用方一眼。
 *
 * 身份判据只取 uri / id / mimeType：标题、封面这些是展示元数据，不同入口
 * （深链还原、选集面板）拼出来的值可能不同，却指向同一次装载，不该为此重跑。
 */
internal data class StartIntent(
    /** 装载目标；身份字段见 [sameStartIntent] 的说明 */
    val item: MediaItem,
    val restorePosition: Boolean,
    val autoPlay: Boolean,
    val prepareOnly: Boolean,
    val audioOnlyMode: Boolean?,
)

/** 构造一次意图（纯函数，便于单测直接覆盖判据）。 */
internal fun startIntentOf(
    item: MediaItem,
    restorePosition: Boolean,
    autoPlay: Boolean,
    prepareOnly: Boolean,
    audioOnlyMode: Boolean?,
): StartIntent = StartIntent(item, restorePosition, autoPlay, prepareOnly, audioOnlyMode)

/**
 * 两次调用是否**完全同意图**（A9 的去重判据）。
 *
 * 同一条媒体（uri + id + mimeType）+ 同一组装载参数 = 旧任务要做的和这次一模一样，
 * 直接 join 即可；任何一项不同都必须取消旧任务、按新参数重跑。
 */
internal fun sameStartIntent(a: StartIntent, b: StartIntent): Boolean =
    a.item.uri == b.item.uri &&
        a.item.id == b.item.id &&
        a.item.mimeType == b.item.mimeType &&
        a.restorePosition == b.restorePosition &&
        a.autoPlay == b.autoPlay &&
        a.prepareOnly == b.prepareOnly &&
        a.audioOnlyMode == b.audioOnlyMode

/** 在途装载的处置：搭旧任务的车 / 取消它重跑 / 新起一条。 */
internal enum class StartDisposition { JOIN_PENDING, SUPERSEDE_PENDING, START_NEW }

/**
 * 有在途任务时，这次调用该怎么处置（A9 的纯决策函数）。
 *
 * 没有在途任务（[previousActive] = false）时一律新起；有在途任务时只有
 * 意图完全一致（[sameStartIntent]）才 join，其余一律取代——宁可贵一次重装载，
 * 也不能让后到的意图被旧参数吞掉。
 */
internal fun startDisposition(
    previous: StartIntent?,
    previousActive: Boolean,
    requested: StartIntent,
): StartDisposition = when {
    previous == null || !previousActive -> StartDisposition.START_NEW
    sameStartIntent(previous, requested) -> StartDisposition.JOIN_PENDING
    else -> StartDisposition.SUPERSEDE_PENDING
}

/**
 * 被取代的旧任务是否可能已经把**按旧意图定下的装载**交给引擎，需要先清掉（A9）。
 *
 * 两个条件同时成立才需要：
 * ① 新旧是**同一条媒体**——换媒体时 `canReusePlayback` 本来就不成立，新任务必然
 *    重新装载，不需要额外清；
 * ② 变的是**装载参数**：`restorePosition`（从头播还是续播）与 `prepareOnly`
 *    （装载后播不播）。这两个值由 ensureSessionPlaying 在装载那一刻算好写进
 *    setQueue 的 `startPositionMs` / `playOnLoad`，旧探测落地时会把它们盖回来。
 *
 * `autoPlay` / `audioOnlyMode` 不进这个判据：它们由"同媒体"分支当场应用，
 * 是播放状态属性，不需要重新装载。
 *
 * [sessionItem] 是会话此刻队列里的当前项：只有它已指向这条媒体，才说明旧任务的
 * `session.open()` 已经执行过（写会话队列与调 `setQueue` 在同一段同步代码里），
 * 引擎里才可能存在按旧参数发起的探测。
 */
internal fun supersedeNeedsEngineReset(
    previous: StartIntent,
    requested: StartIntent,
    sessionItem: MediaItem?,
): Boolean {
    val sameMedia = previous.item.uri == requested.item.uri &&
        previous.item.id == requested.item.id
    if (!sameMedia) return false
    if (previous.restorePosition == requested.restorePosition &&
        previous.prepareOnly == requested.prepareOnly
    ) {
        return false
    }
    return sessionItem != null &&
        sessionItem.uri == previous.item.uri &&
        (previous.item.id.isEmpty() || sessionItem.id == previous.item.id)
}
