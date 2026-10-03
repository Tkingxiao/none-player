package com.noneplayer.plugins.library

import androidx.navigation.compose.composable
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.SearchProvider
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.ScreenProvider
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
        registry.register(ScreenProvider::class.java, SearchScreenProvider())
        // 搜索扩展点的本地实现（此前是"已定义但零实现"的悬空扩展点）
        registry.register(SearchProvider::class.java, LocalSearchProvider(registry))
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}

/** 搜索页路由挂载（顶栏搜索钮 → `navigator.navigate(SEARCH_ROUTE)`）。 */
class SearchScreenProvider : ScreenProvider {

    override val route: String = SEARCH_ROUTE
    override val deepLinks: List<String> = listOf("noneplayer://search")

    override fun register(
        builder: androidx.navigation.NavGraphBuilder,
        navigator: com.noneplayer.kernel.api.Navigator,
    ) {
        builder.composable(route) {
            SearchScreen(navigator = navigator)
        }
    }
}
