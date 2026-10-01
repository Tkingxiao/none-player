package com.noneplayer.kernel.api

import com.noneplayer.core.media.MediaItem

/**
 * 上下文菜单扩展点——向媒体项长按菜单注入动作。
 *
 * 实现方：投屏插件、分享插件、字幕插件等。
 * 菜单 UI 由展示层聚合渲染，本契约只负责"可见性 + 动作"。
 */
interface ContextMenuItem : ExtensionPoint {
    val itemId: String
    val label: String

    /** 图标由 UI 层解释；纯 Kotlin 契约下允许为 null（仅文字项） */
    val icon: Any? get() = null

    /** 对 [item] 是否可见（如投屏项仅在支持投屏的设备上显示） */
    fun isVisible(item: MediaItem): Boolean

    /** 菜单项被点击 */
    suspend fun onClick(item: MediaItem)
}
