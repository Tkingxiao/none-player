package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.ScreenProvider

/**
 * 播放页插件——注册 `player` 路由，播放页动态发现
 * PlaybackOverlay 扩展点（字幕/弹幕未来零侵入挂载）。
 */
@PluginMarker
class PlayerUiPlugin : Plugin {

    override val id: String = "ui.player"
    override val name: String = "播放页"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(ScreenProvider::class.java, PlayerScreenProvider())
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}
