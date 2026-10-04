package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.BottomChromeProvider
import com.noneplayer.kernel.ui.api.InlinePlaybackProvider
import com.noneplayer.kernel.ui.api.ScreenProvider

/**
 * 播放页插件——注册 `player` 路由 + 底部迷你条，播放页动态发现
 * PlaybackOverlay 扩展点（字幕/弹幕未来零侵入挂载）。
 */
@PluginMarker
class PlayerUiPlugin : Plugin {

    override val id: String = "ui.player"
    override val name: String = "播放页"
    override val version: String = "1.0.0"

    /**
     * 播放会话在此建立（进程级单例）。
     *
     * 放在 `onBind` 而不是首次进播放页：迷你条**在任意 Tab 上**都要能显示"正在播什么"，
     * 若等播放页才创建会话，冷启动直达首页时迷你条永远不知道有东西在播
     * （后台服务的播放器还在放，UI 却无数据源）。
     */
    override fun onBind(services: ServiceLocator) {
        SessionHolder.of(sessionFactoryOf(services))
    }

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(ScreenProvider::class.java, PlayerScreenProvider())
        registry.register(BottomChromeProvider::class.java, MiniPlayerChrome())
        // 内嵌播放（详情页封面区直接播，全屏键才进独占播放页）
        registry.register(InlinePlaybackProvider::class.java, InlinePlayerSurface())
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}
