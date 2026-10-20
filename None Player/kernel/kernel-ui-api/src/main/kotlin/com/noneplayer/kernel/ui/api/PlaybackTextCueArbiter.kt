package com.noneplayer.kernel.ui.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一条被覆盖层认领的文本轨：同一批 Cue 由认领者渲染，字幕层让位。 */
data class TextCueClaim(val mediaUri: String, val trackId: String)

/**
 * 文本轨 Cue 的归属仲裁（A12）。
 *
 * 内嵌弹幕轨与字幕层吃的是**同一批** `player.cues`：用户把某条内嵌轨选成弹幕来源后，
 * 弹幕层按新 Cue spawn 滚动弹幕，字幕层同时还会把同一句画在底部——同一条字幕出现两次。
 * 两边没有共同作用域，也不允许插件互相依赖，因此把"谁认领了这条轨"放在内核的
 * 进程级对象里，由字幕层读取后让位。
 *
 * 只影响**文本** Cue；位图字幕（PGS/VobSub）不走这条通路。
 */
object PlaybackTextCueArbiter {

    private val _claim = MutableStateFlow<TextCueClaim?>(null)
    val claim: StateFlow<TextCueClaim?> = _claim.asStateFlow()

    fun claim(mediaUri: String, trackId: String, owner: String) {
        _claim.value = TextCueClaim(mediaUri, trackId)
        lastOwner = owner
    }

    fun release(mediaUri: String, trackId: String) {
        val current = _claim.value ?: return
        if (current.mediaUri == mediaUri && current.trackId == trackId) {
            _claim.value = null
            lastOwner = null
        }
    }

    /** 排查用：最近一次认领的覆盖层 id。 */
    @Volatile
    var lastOwner: String? = null
        private set

    fun isClaimed(mediaUri: String, trackId: String): Boolean {
        val current = _claim.value ?: return false
        return current.mediaUri == mediaUri && current.trackId == trackId
    }
}
