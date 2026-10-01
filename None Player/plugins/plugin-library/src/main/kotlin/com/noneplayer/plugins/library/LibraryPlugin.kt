package com.noneplayer.plugins.library

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.TabProvider

/**
 * 媒体库插件——提供"首页"与"文件夹"两个底部 Tab，验证扩展点全链路：
 * `@PluginMarker` → KSP 生成 [com.noneplayer.kernel.api.PluginIndex] →
 * 宿主装配 → 内核引导 → TabProvider 注入主界面。
 *
 * 注意：Tab 以 TabProvider 类型键注册（注册表按精确类型存取）。
 */
@PluginMarker
class LibraryPlugin : Plugin {

    override val id: String = "ui.library"
    override val name: String = "媒体库"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(TabProvider::class.java, LibraryTabProvider())
        registry.register(TabProvider::class.java, FoldersTabProvider())
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}
