package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.SessionKeyProvider
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.BottomChromeProvider
import com.noneplayer.kernel.ui.api.DetailPaneProvider
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
        val kernelServices = sessionFactoryOf(services)
        SessionHolder.of(kernelServices)
        subtitleCache = SubtitleCacheProvider(services.get())
        // E99：非播放页形态（三栏浏览态等）的会话快捷键，窗口层兜底派发
        browseKeys = BrowseSessionKeys(services.get()) { SessionHolder.of(kernelServices) }
    }

    private var subtitleCache: SubtitleCacheProvider? = null

    private var browseKeys: BrowseSessionKeys? = null

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(ScreenProvider::class.java, PlayerScreenProvider())
        registry.register(BottomChromeProvider::class.java, MiniPlayerChrome())
        // 内嵌播放（详情页封面区直接播，全屏键才进独占播放页）
        registry.register(InlinePlaybackProvider::class.java, InlinePlayerSurface())
        // §8.2 路径 A：类 PC 档的右栏播放栏（有会话时取代底部迷你条）
        registry.register(DetailPaneProvider::class.java, PlaybackPaneChrome())
        // 批次 7「插件清缓存」：字幕转码副本（cache_dir/subs）的清理入口
        subtitleCache?.let { registry.register(CacheProvider::class.java, it) }
        browseKeys?.let { registry.register(SessionKeyProvider::class.java, it) }
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}
