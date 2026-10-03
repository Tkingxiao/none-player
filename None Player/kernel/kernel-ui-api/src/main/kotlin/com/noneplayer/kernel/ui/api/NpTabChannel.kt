package com.noneplayer.kernel.ui.api

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 「切到某个 Tab 并携带一次性意图」的通道。
 *
 * **为什么它属于内核**：`LocalNpTabSwitcher` 只解决"切到哪个 Tab"，
 * 解决不了"切过去之后打开什么"——比如"我的"页的「最近播放 · 全部」要跳到
 * **首页**并打开「播放历史」聚合。而 Tab 页面之间的这种意图无法用路由参数传递
 * （Tab 路由不带参数，拼参数会变成新路由、连带毁掉 `saveState/restoreState`
 * 的状态保留语义，见 `KernelTabScaffold` / `FolderNav` 的同类说明）。
 *
 * 而"我的"与"首页"分属两个插件，插件之间禁止互相依赖（§5 铁律），
 * 所以这个共享点只能落在双方都依赖的 `kernel-ui-api`。
 *
 * 用法（发起方）：
 * ```
 * NpTabChannel.request("home", "history")
 * switchTab("home")
 * ```
 * 用法（接收方，在目标 Tab 的页面里）：
 * ```
 * LaunchedEffect(viewMode, mediaLoaded) { NpTabChannel.consumeFor("home")?.let { ... } }
 * ```
 * **消费即清空**：同一请求不会被消费两次，也不会在下次进页面时"诈尸"。
 */
object NpTabChannel {

    data class Entry(val tabRoute: String, val payload: String)

    private val pending = MutableStateFlow<Entry?>(null)

    /** 请求 [tabRoute] 这个 Tab 在下次（或首次）进入时处理 [payload] */
    fun request(tabRoute: String, payload: String) {
        pending.value = Entry(tabRoute, payload)
    }

    /** 目标 Tab 取用一次；只对匹配的 tabRoute 生效，取到即清空 */
    fun consumeFor(tabRoute: String): String? {
        val entry = pending.value ?: return null
        if (entry.tabRoute != tabRoute) return null
        pending.value = null
        return entry.payload
    }

    /** 丢弃未消费的请求（例如用户又点了别的入口） */
    fun clear() {
        pending.value = null
    }
}

/**
 * Rail 底部「主题」按钮的通道（设计稿 `.rail` 底部的二态开关，B6）。
 *
 * **为什么由宿主注入**：主题状态（三态 ThemeMode + DataStore 持久化）属于 app / core-ui 层，
 * 而 Rail 在 kernel-ui-api 层——后者不能依赖前者（§13.2）。所以内核只声明"这里有个
 * 主题入口"，由宿主经本通道提供"当前是否暗色 + 切换"；未提供（null）时 Rail 不渲染该按钮，
 * 例如压栈页或未来的无主题宿主。
 */
data class NpThemeToggle(
    val isDark: Boolean,
    val onToggle: () -> Unit,
)

val LocalNpThemeToggle: androidx.compose.runtime.ProvidableCompositionLocal<NpThemeToggle?> =
    androidx.compose.runtime.staticCompositionLocalOf { null }
