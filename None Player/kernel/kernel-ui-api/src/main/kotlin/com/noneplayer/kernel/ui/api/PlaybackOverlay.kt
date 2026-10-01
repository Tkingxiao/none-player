package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.VideoPlayer

/**
 * 播放页覆盖层扩展点——向播放页注入字幕、弹幕等浮层内容。
 *
 * 播放页插件动态发现本扩展点并逐个渲染（设计文档 §6.3），
 * 覆盖层之间互不感知。
 */
interface PlaybackOverlay : ExtensionPoint {
    val overlayId: String

    /** 渲染覆盖层；[context] 提供当前媒体、播放器与导航器 */
    @Composable
    fun Render(context: PlaybackContext)
}

/** 播放页传给覆盖层的上下文 */
data class PlaybackContext(
    val media: MediaItem,
    val player: VideoPlayer,
    val navigator: Navigator,
)
