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
    /**
     * 控制层（顶/底控制栏）当前是否可见。
     *
     * 覆盖层里的**开关类**控件（弹幕 pill 等）要跟着控制层一起显隐——
     * 否则控制栏隐去后它还常驻在画面上，用户找不到也关不掉（实报）。
     * 默认 true：不关心显隐的覆盖层（字幕、纯画布弹幕）不受影响。
     */
    val controlsVisible: Boolean = true,
)
