package com.noneplayer.kernel.ui.api

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
