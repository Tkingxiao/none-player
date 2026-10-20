package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * R1 验收：REV-10（断连 / 重连一致性）。
 *
 * 直接引用 `PlaybackSession.connection` / `retryConnection()`——生产契约已落地，
 * 让编译器帮忙钉住 API 形状（不再用反射兜底）。
 */
internal class R1SessionConnectionTest : SessionRobolectricTest() {

    @Test
    fun `REV-10 会话 connection 反映引擎 DISCONNECTED`() {
        val a = testItem("a")
        session.open(a, listOf(a))

        val connection = session.connection
        assertEquals("初始应已连接", PlayerConnection.CONNECTED, connection.value)

        fakePlayer.simulateDisconnect()
        awaitCondition { connection.value == PlayerConnection.DISCONNECTED }
        assertEquals(
            "REV-10：引擎断连后会话 connection 必须反映 DISCONNECTED",
            PlayerConnection.DISCONNECTED,
            connection.value,
        )
    }

    @Test
    fun `REV-10 retryConnection 触发引擎 reconnect 并跟随状态恢复`() {
        val a = testItem("a")
        session.open(a, listOf(a))
        fakePlayer.simulateDisconnect()
        awaitCondition { session.connection.value == PlayerConnection.DISCONNECTED }

        session.retryConnection()

        assertEquals("retryConnection 必须把重连请求转给引擎", 1, fakePlayer.reconnectCount)
        assertEquals(
            "重连请求发出后引擎应进入 RECONNECTING",
            PlayerConnection.RECONNECTING,
            fakePlayer.connection.value,
        )
        awaitCondition { session.connection.value == PlayerConnection.RECONNECTING }
        assertEquals(
            "会话 connection 必须跟随引擎进入 RECONNECTING",
            PlayerConnection.RECONNECTING,
            session.connection.value,
        )

        fakePlayer.simulateReconnect()
        awaitCondition { session.connection.value == PlayerConnection.CONNECTED }
        assertEquals(
            "重连成功后会话 connection 必须回到 CONNECTED",
            PlayerConnection.CONNECTED,
            session.connection.value,
        )

        // 恢复后命令重新生效（不再被静默丢弃）。
        fakePlayer.calls.clear()
        session.play()
        assertEquals("重连后 play 必须生效", PlaybackState.Playing, fakePlayer.state.value)
        assertFalse(
            "重连后不得再有被丢弃的命令，实际=${fakePlayer.labels}",
            fakePlayer.calls.any { it is PlayerCall.Rejected },
        )
    }

    @Test
    fun `REV-10 断连期间命令不得伪造成功`() {
        val a = testItem("a")
        session.open(a, listOf(a))
        fakePlayer.simulateProgress(10_000, 60_000)
        fakePlayer.setPlaybackState(PlaybackState.Paused)
        awaitCondition { session.nowPlaying.value?.isPlaying == false }

        fakePlayer.simulateDisconnect()
        fakePlayer.calls.clear()
        session.toggle()

        assertEquals(
            "REV-10：断连期间会话不得把引擎状态伪造成 Playing",
            PlaybackState.Paused,
            fakePlayer.state.value,
        )
        assertFalse(
            "REV-10：断连期间 nowPlaying 不得宣称在播",
            session.nowPlaying.value?.isPlaying == true,
        )
        assertEquals(
            "REV-10：断连期间不得伪造成已连接",
            PlayerConnection.DISCONNECTED,
            fakePlayer.connection.value,
        )
    }
}
