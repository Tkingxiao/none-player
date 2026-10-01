package com.noneplayer.kernel.ui.api

import androidx.navigation.NavGraphBuilder
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator

/**
 * 页面扩展点——插件向导航图贡献页面的唯一方式。
 *
 * 采用 `NavGraphBuilder.() -> Unit` 形态（设计文档 §7 踩坑 2 的解决方案）：
 * 插件在自己的 register 内调用 `composable(route) { ... }`，
 * 自行声明 navArgument 并解析参数，内核不感知具体路由结构。
 *
 * 约束：
 * - [route] 全局唯一，建议以插件 id 为前缀；内核组装时校验，冲突即崩溃。
 * - [deepLinks] 由宿主在未来接入深层链接时使用；P0 仅声明。
 */
interface ScreenProvider : ExtensionPoint {
    val route: String
    val deepLinks: List<String> get() = emptyList()

    /** 把页面挂到导航图上；实现内调用 `builder.composable(route) { ... }`，可访问 [Navigator] 完成页面内跳转 */
    fun register(builder: NavGraphBuilder, navigator: Navigator)
}
