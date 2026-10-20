package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.PlayerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-10：会话连接状态机。
 *
 * 钉的是"断连 → 有界重连 → 成功/失败"的迁移与退避节奏（500ms/1s/2s，≤3 次），
 * 以及并发去重（多个 UI 入口同时点重试只允许一条重连在跑）。
 */
class PlayerConnectionTrackerTest {

    @Test
    fun initialStateIsConnecting() {
        val tracker = PlayerConnectionTracker()
        assertEquals(PlayerConnection.CONNECTING, tracker.state)
        assertEquals(0, tracker.attempt)
    }

    @Test
    fun onConnectedMovesToConnected() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        assertEquals(PlayerConnection.CONNECTED, tracker.state)
    }

    @Test
    fun onInitialFailedIsTerminalUntilManualReconnect() {
        val tracker = PlayerConnectionTracker()
        tracker.onInitialFailed()
        assertEquals(PlayerConnection.FAILED, tracker.state)
        // 初始失败后用户点重试：允许从 FAILED 进入重连
        assertEquals(500L, tracker.beginReconnect())
        assertEquals(PlayerConnection.RECONNECTING, tracker.state)
    }

    @Test
    fun onDisconnectedResetsAttempts() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        tracker.onDisconnected()
        assertEquals(PlayerConnection.DISCONNECTED, tracker.state)
        assertEquals(0, tracker.attempt)
    }

    @Test
    fun beginReconnectFromDisconnectedReturnsFirstBackoff() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        tracker.onDisconnected()
        assertEquals(500L, tracker.beginReconnect())
        assertEquals(PlayerConnection.RECONNECTING, tracker.state)
        assertEquals(1, tracker.attempt)
    }

    @Test
    fun beginReconnectIsDeduplicatedWhileReconnecting() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        tracker.onDisconnected()
        assertEquals(500L, tracker.beginReconnect())
        // 并发第二次调用：已有重连在跑，返回 null 且不重置进度
        assertNull(tracker.beginReconnect())
        assertEquals(1, tracker.attempt)
    }

    @Test
    fun beginReconnectRejectedWhileConnectedOrConnecting() {
        val connecting = PlayerConnectionTracker()
        assertNull(connecting.beginReconnect())
        assertEquals(PlayerConnection.CONNECTING, connecting.state)

        val connected = PlayerConnectionTracker()
        connected.onConnected()
        assertNull(connected.beginReconnect())
        assertEquals(PlayerConnection.CONNECTED, connected.state)
    }

    @Test
    fun attemptFailuresBackOffThenFailAfterThreeAttempts() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        tracker.onDisconnected()
        assertEquals(500L, tracker.beginReconnect())

        // 第 1 次尝试失败 → 等 1s 后第 2 次
        assertEquals(1_000L, tracker.onAttemptFailed())
        // 第 2 次失败 → 等 2s 后第 3 次
        assertEquals(2_000L, tracker.onAttemptFailed())
        // 第 3 次失败 → 重试耗尽，终态 FAILED
        assertNull(tracker.onAttemptFailed())
        assertEquals(PlayerConnection.FAILED, tracker.state)
    }

    @Test
    fun customMaxAttemptsIsRespected() {
        val tracker = PlayerConnectionTracker(maxAttempts = 1)
        tracker.onConnected()
        tracker.onDisconnected()
        assertEquals(500L, tracker.beginReconnect())
        assertNull(tracker.onAttemptFailed())
        assertEquals(PlayerConnection.FAILED, tracker.state)
    }

    @Test
    fun successfulAttemptReturnsToConnectedAndAllowsFutureReconnect() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        tracker.onDisconnected()
        tracker.beginReconnect()
        tracker.onAttemptSucceeded()
        assertEquals(PlayerConnection.CONNECTED, tracker.state)
        assertEquals(0, tracker.attempt)

        // 重连成功后再断一次：重试计数已归零，重新从 500ms/第 1 次开始
        tracker.onDisconnected()
        assertEquals(500L, tracker.beginReconnect())
        assertEquals(1, tracker.attempt)
    }

    @Test
    fun onAttemptFailedOutsideReconnectIsIgnored() {
        val tracker = PlayerConnectionTracker()
        tracker.onConnected()
        assertNull(tracker.onAttemptFailed())
        assertEquals(PlayerConnection.CONNECTED, tracker.state)
    }

    // ---- REV-10：断连后是否有可恢复目标（handleDisconnected / restoreSessionState 共用）----

    @Test
    fun restoreIsNeededWhenQueueHasItems() {
        // 队列路径：只要登记过队列就必须恢复，URI 是否为空无关
        assertTrue(shouldRestoreAfterDisconnect(queueSize = 12, singleMediaUri = null))
        assertTrue(shouldRestoreAfterDisconnect(queueSize = 1, singleMediaUri = ""))
    }

    @Test
    fun restoreIsNeededForSingleMediaTarget() {
        assertTrue(shouldRestoreAfterDisconnect(queueSize = 0, singleMediaUri = "file:///movies/a.mkv"))
    }

    @Test
    fun restoreIsNotNeededWithoutAnyTarget() {
        // 空队列 + 无单媒体 URI：重连后没有可装载目标，恢复必须提前返回
        assertFalse(shouldRestoreAfterDisconnect(queueSize = 0, singleMediaUri = null))
        assertFalse(shouldRestoreAfterDisconnect(queueSize = 0, singleMediaUri = ""))
        assertFalse(shouldRestoreAfterDisconnect(queueSize = 0, singleMediaUri = "   "))
    }
}
