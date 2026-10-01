package com.noneplayer.kernel.api

/**
 * 导航器——插件页面间的跳转抽象（纯 Kotlin，无 Android 依赖）。
 *
 * 内核在 UI 层（`kernel-ui-api` 的 KernelNavHost）提供基于 NavController 的实现，
 * 并通过 ServiceLocator 以 Navigator 接口共享给插件。
 *
 * 路由约定（设计文档 §7 踩坑 1）：route 必须全局唯一，建议以插件 id 为前缀；
 * 内核在组装导航图时校验重复，冲突即崩溃报错。
 */
interface Navigator {
    /** 跳转到 [route]，可携带附加参数 */
    fun navigate(route: String, args: Map<String, Any> = emptyMap())

    /** 返回上一级 */
    fun popBackStack()
}
