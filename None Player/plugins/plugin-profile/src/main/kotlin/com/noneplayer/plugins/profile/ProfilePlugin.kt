package com.noneplayer.plugins.profile

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.TabProvider

/**
 * "我的" Tab 插件——B 站式个人中心骨架：
 * 应用信息头 + 设置入口 + 已加载插件清单 + 关于。
 * Tab 提供方与页面实现见 ProfileScreen.kt。
 *
 * 注意：Tab 以 TabProvider 类型键注册（注册表按精确类型存取）。
 */
@PluginMarker
class ProfilePlugin : Plugin {

    override val id: String = "ui.profile"
    override val name: String = "我的"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(TabProvider::class.java, ProfileTabProvider())
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}
