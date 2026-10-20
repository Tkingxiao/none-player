package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A17 收尾：错误后的重探必须绕过 `FormatProbe` 结果缓存。
 *
 * 钉住两件事：
 * 1. **首次/常规装载**（没有失败历史）照旧走缓存——命中的 0.5–1.5s 探测不能白白丢掉；
 * 2. **错误面板「重试」**（引擎里还留着失败闩 / 状态停在 Error）与 **A18 解码器受控回退**
 *    的同媒体 re-probe 必须 `bypassCache = true`：否则缓存里那条"容器/编码不支持"的
 *    确定性结论会把用户直接送回同一条错误，重试永远没有机会生效。
 *
 * 判定放在 [ExoVideoPlayer.probeThenLoad] 入口、`terminalFailure` 复位**之前**捕获，
 * 由本纯函数决定，因此可在无 Robolectric 的纯 JVM 下半测。
 */
class ProbeBypassOnRetryTest {

    @Test
    fun normalFirstLoadUsesCache() {
        assertFalse(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Idle,
                retryingAfterFailure = false,
            ),
        )
    }

    @Test
    fun normalQueueSwitchUsesCache() {
        assertFalse(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Paused,
                retryingAfterFailure = false,
            ),
        )
    }

    @Test
    fun normalLoadWhileBufferingUsesCache() {
        assertFalse(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Buffering,
                retryingAfterFailure = false,
            ),
        )
    }

    /** 用户点错误面板「重试」：终态闩还没放下时装载，必须现场重探。 */
    @Test
    fun userRetryAfterTerminalFailureBypassesCache() {
        assertTrue(
            shouldBypassProbeCache(
                terminalFailure = true,
                state = PlaybackState.Idle,
                retryingAfterFailure = false,
            ),
        )
    }

    /** 状态已落在 Error、但闩还没置（两处判定的另一半）同样必须绕过。 */
    @Test
    fun errorStateWithoutLatchBypassesCache() {
        assertTrue(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Error("无法播放此文件"),
                retryingAfterFailure = false,
            ),
        )
    }

    /** A18：解码器初始化失败后的同媒体 re-probe——此时 _state 可能还停在 Playing。 */
    @Test
    fun decoderFallbackRetryBypassesCache() {
        assertTrue(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Playing,
                retryingAfterFailure = true,
            ),
        )
    }

    /** 播完重播不是"失败后的重试"：没有理由丢掉缓存（E80 的探测成本仍然要省）。 */
    @Test
    fun endedReplayUsesCache() {
        assertFalse(
            shouldBypassProbeCache(
                terminalFailure = false,
                state = PlaybackState.Ended,
                retryingAfterFailure = false,
            ),
        )
    }
}
