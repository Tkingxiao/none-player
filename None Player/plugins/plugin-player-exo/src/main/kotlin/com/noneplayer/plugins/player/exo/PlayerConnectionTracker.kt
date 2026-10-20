package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.PlayerConnection

/**
 * REV-10：会话连接状态机（纯逻辑，便于 JVM 单测）。
 *
 * ExoPlayer 引擎的播放器驻留在后台 `MediaSessionService` 进程侧，客户端只持有
 * `MediaController`。服务被系统回收/进程崩溃时 binder 断开，media3 会**自动释放**
 * 旧控制器（见 MediaControllerImplBase.onServiceDisconnected），旧实例不可复用：
 * 必须重新 `buildAsync()` 一条新连接。旧实现在断连后什么都没做——
 * `MediaController` 的命令与读取全部静默空转（`seekTo`/`play` 被忽略、
 * `currentPosition` 停在上一次快照），UI 以为还在播 = REV-10 的"假成功"。
 *
 * 这个状态机只负责"连接状态怎么迁移、重试几次、每次等多久"，不碰 Android API：
 * - 初始 [PlayerConnection.CONNECTING]，连上 → CONNECTED；
 * - 断连 → DISCONNECTED（重试计数归零）；
 * - [beginReconnect] 从 DISCONNECTED/FAILED 进入 RECONNECTING，返回第一次尝试前的
 *   退避时长；已在 RECONNECTING 时返回 null（并发去重：多个调用方只会有一次重连）；
 * - 每次尝试失败 → [onAttemptFailed] 返回下一次退避时长，重试耗尽返回 null 并把
 *   状态置 FAILED（有界：默认最多 3 次，退避 500ms/1s/2s）；
 * - 尝试成功 → CONNECTED，计数归零（之后可再次断连重连）。
 */
internal class PlayerConnectionTracker(
    private val maxAttempts: Int = 3,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {

    var state: PlayerConnection = PlayerConnection.CONNECTING
        private set

    /** 当前是第几次重连尝试（0 = 不在重连）；仅供日志/单测断言 */
    var attempt: Int = 0
        private set

    /** 初始连接成功（或重连成功后的统一入口） */
    fun onConnected() {
        state = PlayerConnection.CONNECTED
        attempt = 0
    }

    /** 初始连接失败（首次 buildAsync 就失败）：直接终态，等用户手动重连 */
    fun onInitialFailed() {
        state = PlayerConnection.FAILED
        attempt = 0
    }

    /** 连接断开：进入 DISCONNECTED，重试计数归零（下一次重连重新从第 1 次算） */
    fun onDisconnected() {
        state = PlayerConnection.DISCONNECTED
        attempt = 0
    }

    /**
     * 开始重连（并发去重）。返回**第一次尝试前**的退避时长；null = 拒绝：
     * - 已在 RECONNECTING：已有重连在跑，不要重复起；
     * - 状态不是 DISCONNECTED/FAILED：连接正常或初始连接还在进行，无需重连。
     */
    fun beginReconnect(): Long? {
        if (state == PlayerConnection.RECONNECTING) return null
        if (state != PlayerConnection.DISCONNECTED && state != PlayerConnection.FAILED) return null
        state = PlayerConnection.RECONNECTING
        attempt = 1
        return backoffMs.firstOrNull() ?: 0L
    }

    /**
     * 第 [attempt] 次尝试失败。返回下一次尝试前的退避时长；null = 重试耗尽，
     * 状态已置 FAILED（调用方应把 `connection` 同步为 FAILED 并让等待方快速失败）。
     */
    fun onAttemptFailed(): Long? {
        if (state != PlayerConnection.RECONNECTING) return null
        if (attempt >= maxAttempts) {
            state = PlayerConnection.FAILED
            return null
        }
        val delay = backoffMs.getOrElse(attempt) { backoffMs.lastOrNull() ?: 0L }
        attempt += 1
        return delay
    }

    /** 重连尝试成功：回到 CONNECTED */
    fun onAttemptSucceeded() {
        state = PlayerConnection.CONNECTED
        attempt = 0
    }

    private companion object {
        /** 有界退避：500ms / 1s / 2s（与任务要求的 ≤3 次尝试配套） */
        val DEFAULT_BACKOFF_MS = listOf(500L, 1_000L, 2_000L)
    }
}

/**
 * REV-10：断连后是否还有可恢复的播放目标（纯逻辑，便于单测）。
 *
 * [ExoVideoPlayer.handleDisconnected] 与 `restoreSessionState` 必须共用这一个判定：
 * 只要引擎里登记过队列（`_queue`）或单媒体目标 URI，就要把"恢复意图"钉住，重连
 * 成功后按它重新装载；两者都没有时什么都不装，避免空 setQueue / 空探测。
 *
 * 之所以需要这个函数：正常播放中发生断连时没有走任何装载路径，`pendingSessionRestore`
 * 仍是 false——不显式置位的话，重连成功后的恢复会直接返回，新会话里没有
 * setMediaItems，表现就是"重连成功但画面永远停住"。
 */
internal fun shouldRestoreAfterDisconnect(queueSize: Int, singleMediaUri: String?): Boolean =
    queueSize > 0 || !singleMediaUri.isNullOrBlank()

/**
 * 会话连接不可用（初始连接失败，或断连后重连重试耗尽）。
 *
 * 从 [ExoVideoPlayer.awaitReady] 抛出：断连后**不再**像旧实现那样立刻"成功返回"，
 * 调用方（装载链）要么等到重连成功，要么收到这个可捕获异常。捕获方应读取
 * `VideoPlayer.connection` 判断是显示"已断开，点击重试"还是真正的装载失败。
 */
internal class PlayerConnectionUnavailableException(message: String) : IllegalStateException(message)
