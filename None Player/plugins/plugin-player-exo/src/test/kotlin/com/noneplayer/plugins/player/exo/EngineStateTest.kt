package com.noneplayer.plugins.player.exo

import androidx.media3.common.Player
import com.noneplayer.kernel.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A5：引擎对外状态映射。
 *
 * 钉的是"`STATE_READY` 不再产出独立的 `Ready`"——就绪态只能是"该播的"（Playing）
 * 或"停着的"（Paused）。这条判据存在的理由：暂停后拖进度条会 BUFFERING → READY，
 * 而 `isPlaying` 全程为 false、`onIsPlayingChanged` 不再回调，旧映射会把状态永久
 * 停在 `Ready` 上，播放钮/常亮/降档/弹幕一起错。
 */
class EngineStateTest {

    @Test
    fun readyWhilePlayWhenReadyIsPlaying() {
        assertEquals(PlaybackState.Playing, npEngineStateOf(Player.STATE_READY, playWhenReady = true))
    }

    @Test
    fun readyWhilePausedStaysPaused() {
        // 用户暂停后 seek 回到 READY，与 play=0 的"只装载不起播"是同一条：都读成 Paused
        assertEquals(PlaybackState.Paused, npEngineStateOf(Player.STATE_READY, playWhenReady = false))
    }

    @Test
    fun bufferingAndEndedIgnorePlayWhenReady() {
        assertEquals(PlaybackState.Buffering, npEngineStateOf(Player.STATE_BUFFERING, playWhenReady = true))
        assertEquals(PlaybackState.Buffering, npEngineStateOf(Player.STATE_BUFFERING, playWhenReady = false))
        assertEquals(PlaybackState.Ended, npEngineStateOf(Player.STATE_ENDED, playWhenReady = true))
        assertEquals(PlaybackState.Idle, npEngineStateOf(Player.STATE_IDLE, playWhenReady = false))
    }

    /**
     * A23：关闭自动连播时服务端 `setPauseAtEndOfMediaItems(true)`，一集播完停在
     * `READY + playWhenReady=false`，但变化原因是 END_OF_MEDIA_ITEM——这是"播完了"，
     * 必须译成 Ended（UI 才显示重播/下一集）。
     */
    @Test
    fun readyPausedAtEndOfMediaItemIsEnded() {
        assertEquals(
            PlaybackState.Ended,
            npEngineStateOf(
                Player.STATE_READY,
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
            ),
        )
    }

    /** 同样是 READY + 暂停，但原因是用户/焦点等：仍是 Paused，不能误判成播完 */
    @Test
    fun readyPausedByUserRequestStaysPaused() {
        assertEquals(
            PlaybackState.Paused,
            npEngineStateOf(
                Player.STATE_READY,
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ),
        )
        assertEquals(
            PlaybackState.Paused,
            npEngineStateOf(
                Player.STATE_READY,
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ),
        )
        // playWhenReady 为 true 时永远优先读成 Playing，与变化原因无关
        assertEquals(
            PlaybackState.Playing,
            npEngineStateOf(
                Player.STATE_READY,
                playWhenReady = true,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
            ),
        )
    }
}
