package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerConnection
import com.noneplayer.kernel.api.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R1 会话级回归用的假播放引擎。
 *
 * 只实现 [PlaybackSession] 真正依赖的那部分契约，其余成员走接口默认实现。
 * 设计要点：
 * - 可编程 state / position / duration / applied 身份 / connection；
 * - **慢探测**：`slowLoad = true` 时 [setQueue] 只记请求、不切换 applied 身份
 *   （模拟起播探测 + 建表的异步窗口），必须显式 [finishLoad] 才落地。REV-04 的
 *   「慢探测窗口内引擎位置仍属于上一条媒体」就靠它复现；
 * - [calls] 按顺序记录带参数的调用，供「先 seekTo(0) 再 play」「新引擎 create 先于
 *   旧引擎 release」这类时序断言；
 * - 断连后命令被静默丢弃并记一条 [PlayerCall.Rejected]，**不伪造成功**。
 */
internal class FakeVideoPlayer(
    val engineId: String = "fake",
    private val lifecycleLog: MutableList<String>? = null,
) : VideoPlayer {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs

    private val _currentUri = MutableStateFlow<String?>(null)
    override val currentUri: StateFlow<String?> = _currentUri

    private val _appliedMediaKey = MutableStateFlow<String?>(null)
    override val appliedMediaKey: String? get() = _appliedMediaKey.value

    private val _appliedPositionMs = MutableStateFlow<Long?>(null)
    override val appliedPositionMs: Long? get() = _appliedPositionMs.value

    private val _appliedDurationMs = MutableStateFlow(0L)
    override val appliedDurationMs: Long get() = _appliedDurationMs.value

    private val _connection = MutableStateFlow(PlayerConnection.CONNECTED)
    override val connection: StateFlow<PlayerConnection> = _connection

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    override val queue: StateFlow<List<MediaItem>> = _queue

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex

    /** 按调用顺序记录的全部调用（断言用） */
    val calls: MutableList<PlayerCall> = mutableListOf()

    /** 慢探测开关：true 时 [setQueue] 只挂起装载，applied 身份保持上一条媒体 */
    var slowLoad: Boolean = false

    /** 慢探测窗口内在途的装载请求；[finishLoad] 消费它 */
    var pendingLoad: PendingLoad? = null
        private set

    var releaseCount: Int = 0
        private set

    var reconnectCount: Int = 0
        private set

    /** 便于失败信息阅读的调用标签序列 */
    val labels: List<String> get() = calls.map { it.describe() }

    fun indexOfCall(predicate: (PlayerCall) -> Boolean): Int = calls.indexOfFirst(predicate)

    fun seekToIndex(positionMs: Long): Int =
        indexOfCall { it is PlayerCall.SeekTo && it.positionMs == positionMs }

    fun lastPlayIndex(): Int = calls.indexOfLast { it is PlayerCall.Play }

    fun setQueueCalls(): List<PlayerCall.SetQueue> = calls.filterIsInstance<PlayerCall.SetQueue>()

    // ------------------------------------------------------------------
    // 编程接口：驱动假引擎
    // ------------------------------------------------------------------

    /** 模拟播放进度（位置/时长同时同步到 applied 身份，前提是当前有已生效媒体） */
    fun simulateProgress(positionMs: Long, durationMs: Long = _durationMs.value) {
        _positionMs.value = positionMs
        _durationMs.value = durationMs
        if (_appliedMediaKey.value != null) {
            _appliedPositionMs.value = positionMs
            _appliedDurationMs.value = durationMs
        }
    }

    /** 模拟播到结尾：位置停在 duration，state = Ended */
    fun simulateEnded() {
        val duration = _durationMs.value
        simulateProgress(duration, duration)
        _state.value = PlaybackState.Ended
    }

    /** 模拟引擎自己推进到队列第 [index] 项（自动连播/通知栏 ⏭） */
    fun simulateTransition(index: Int) {
        val items = _queue.value
        val item = items.getOrNull(index) ?: return
        _currentIndex.value = index
        _currentUri.value = item.uri
        _appliedMediaKey.value = fakeMediaKey(item)
        _appliedPositionMs.value = 0L
        _appliedDurationMs.value = item.durationMs
        _positionMs.value = 0L
        _durationMs.value = item.durationMs
        _state.value = PlaybackState.Playing
    }

    /** 模拟承载服务断连（REV-10）：命令从此静默丢弃 */
    fun simulateDisconnect() {
        _connection.value = PlayerConnection.DISCONNECTED
    }

    /**
     * 模拟「引擎里已生效的媒体已切换、但 controller 的 currentIndex 回调还没到达
     * 会话侧」的窗口（REV-04 反向竞态：引擎自动连播到 B，会话下标仍是 A）。
     *
     * 与 [simulateTransition] 的区别：**不动** `_queue/_currentIndex`——会话的
     * `observeEngineIndex` 因此不会把下标追上来，窗口保持打开。
     */
    fun simulateAppliedMedia(item: MediaItem, positionMs: Long) {
        _appliedMediaKey.value = fakeMediaKey(item)
        _appliedPositionMs.value = positionMs
        _appliedDurationMs.value = item.durationMs
        _positionMs.value = positionMs
        _durationMs.value = item.durationMs
    }

    /** 模拟重连失败 */
    fun simulateFailed() {
        _connection.value = PlayerConnection.FAILED
    }

    /** 模拟重连成功 */
    fun simulateReconnect() {
        _connection.value = PlayerConnection.CONNECTED
    }

    fun setPlaybackState(value: PlaybackState) {
        _state.value = value
    }

    /**
     * 模拟 legacy 引擎：未实现 applied 身份（`appliedMediaKey == null`）。
     * 此时会话必须退回旧行为——按 [positionMs] 落盘，而不是跳过写入。
     */
    fun simulateLegacyIdentity() {
        _appliedMediaKey.value = null
        _appliedPositionMs.value = null
        _appliedDurationMs.value = 0L
    }

    /** 让慢探测窗口内的装载落地：applied 身份切到新条目 */
    fun finishLoad(): Boolean {
        val load = pendingLoad ?: return false
        pendingLoad = null
        applyLoad(load)
        return true
    }

    // ------------------------------------------------------------------
    // VideoPlayer 实现
    // ------------------------------------------------------------------

    override fun prepare(uri: String, mimeType: String, title: String, artworkUri: String?) {
        calls += PlayerCall.Prepare(uri, mimeType)
    }

    override fun setQueue(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        playOnLoad: Boolean,
    ) {
        val load = PendingLoad(items, startIndex, startPositionMs, playOnLoad)
        calls += PlayerCall.SetQueue(items.map { it.id }, startIndex, startPositionMs, playOnLoad)
        if (slowLoad) {
            pendingLoad = load
            return
        }
        applyLoad(load)
    }

    override fun selectQueueIndex(index: Int) {
        calls += PlayerCall.SelectQueueIndex(index)
        if (!commandsAccepted()) {
            calls += PlayerCall.Rejected("selectQueueIndex")
            return
        }
        simulateTransition(index)
    }

    override fun play() {
        calls += PlayerCall.Play
        if (!commandsAccepted()) {
            calls += PlayerCall.Rejected("play")
            return
        }
        // 注意：Ended 后直接 play 不从 0 重播（位置不动），A23 的「先 seekTo(0)」因此可断言
        _state.value = PlaybackState.Playing
    }

    override fun pause() {
        calls += PlayerCall.Pause
        if (!commandsAccepted()) {
            calls += PlayerCall.Rejected("pause")
            return
        }
        if (_state.value == PlaybackState.Playing || _state.value == PlaybackState.Buffering) {
            _state.value = PlaybackState.Paused
        }
    }

    override fun seekTo(positionMs: Long) {
        calls += PlayerCall.SeekTo(positionMs)
        if (!commandsAccepted()) {
            calls += PlayerCall.Rejected("seekTo")
            return
        }
        _positionMs.value = positionMs
        if (_appliedMediaKey.value != null) {
            _appliedPositionMs.value = positionMs
        }
    }

    override fun playNext() {
        calls += PlayerCall.PlayNext
    }

    override fun playPrevious() {
        calls += PlayerCall.PlayPrevious
    }

    override fun clearQueue() {
        calls += PlayerCall.ClearQueue
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentUri.value = null
        _appliedMediaKey.value = null
        _appliedPositionMs.value = null
        _appliedDurationMs.value = 0L
        _positionMs.value = 0L
        _durationMs.value = 0L
        pendingLoad = null
        _state.value = PlaybackState.Idle
    }

    override fun reconnect() {
        calls += PlayerCall.Reconnect
        reconnectCount++
        if (_connection.value == PlayerConnection.DISCONNECTED ||
            _connection.value == PlayerConnection.FAILED
        ) {
            _connection.value = PlayerConnection.RECONNECTING
        }
    }

    override fun release() {
        calls += PlayerCall.Release
        releaseCount++
        lifecycleLog?.add("release:$engineId")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun commandsAccepted(): Boolean = _connection.value == PlayerConnection.CONNECTED

    private fun applyLoad(load: PendingLoad) {
        _queue.value = load.items
        _currentIndex.value = load.startIndex
        val item = load.items.getOrNull(load.startIndex)
        _currentUri.value = item?.uri
        _appliedMediaKey.value = item?.let { fakeMediaKey(it) }
        _appliedPositionMs.value = if (item == null) null else load.startPositionMs
        _appliedDurationMs.value = item?.durationMs ?: 0L
        _positionMs.value = load.startPositionMs
        _durationMs.value = item?.durationMs ?: 0L
        _state.value = if (load.playOnLoad) PlaybackState.Playing else PlaybackState.Paused
    }
}

/** 与 `HistoryEntry.keyOf` / `PlaybackProgress.keyOf` 同一条约定：`sourceId:mediaId`。 */
internal fun fakeMediaKey(item: MediaItem): String = "${item.sourceId}:${item.id}"

/** 慢探测窗口内挂起的一次装载请求 */
internal data class PendingLoad(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val playOnLoad: Boolean,
)

/** 假引擎记录的一次调用（顺序即调用顺序，参数原样保留） */
internal sealed interface PlayerCall {
    data class Prepare(val uri: String, val mimeType: String) : PlayerCall
    data class SetQueue(
        val itemIds: List<String>,
        val startIndex: Int,
        val startPositionMs: Long,
        val playOnLoad: Boolean,
    ) : PlayerCall
    data class SelectQueueIndex(val index: Int) : PlayerCall
    data class SeekTo(val positionMs: Long) : PlayerCall

    /** 断连窗口内被丢弃的命令（不伪造成功） */
    data class Rejected(val command: String) : PlayerCall
    data object Play : PlayerCall
    data object Pause : PlayerCall
    data object PlayNext : PlayerCall
    data object PlayPrevious : PlayerCall
    data object ClearQueue : PlayerCall
    data object Reconnect : PlayerCall
    data object Release : PlayerCall
}

internal fun PlayerCall.describe(): String = when (this) {
    is PlayerCall.Prepare -> "prepare($uri, $mimeType)"
    is PlayerCall.SetQueue -> "setQueue($itemIds, index=$startIndex, pos=$startPositionMs, playOnLoad=$playOnLoad)"
    is PlayerCall.SelectQueueIndex -> "selectQueueIndex($index)"
    is PlayerCall.SeekTo -> "seekTo($positionMs)"
    is PlayerCall.Rejected -> "rejected($command)"
    PlayerCall.Play -> "play"
    PlayerCall.Pause -> "pause"
    PlayerCall.PlayNext -> "playNext"
    PlayerCall.PlayPrevious -> "playPrevious"
    PlayerCall.ClearQueue -> "clearQueue"
    PlayerCall.Reconnect -> "reconnect"
    PlayerCall.Release -> "release"
}
