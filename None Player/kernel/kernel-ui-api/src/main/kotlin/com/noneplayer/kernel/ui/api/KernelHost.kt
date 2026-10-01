package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.staticCompositionLocalOf
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.ServiceLocator

/**
 * UI 层对内核的只读视图。
 *
 * 由 `:kernel` 的 [com.noneplayer.kernel.Kernel] 实现，在 Activity 的
 * setContent 中通过 [LocalKernel] 注入；插件页面只面向本接口，
 * 不感知内核实现细节。
 */
interface KernelHost {
    val registry: com.noneplayer.kernel.api.ExtensionRegistry
    val events: com.noneplayer.kernel.api.EventBus
    val services: com.noneplayer.kernel.api.ServiceLocator

    /** 已安装的插件清单（"我的"页展示插件列表） */
    val plugins: List<com.noneplayer.kernel.api.Plugin>
}

/**
 * 内核的 CompositionLocal。
 *
 * 未提供时读取即抛错——内核未引导完成就渲染依赖内核的页面属于编程错误，
 * 应尽早失败（设计文档的启动校验原则）。
 */
val LocalKernel: androidx.compose.runtime.ProvidableCompositionLocal<KernelHost> =
    staticCompositionLocalOf { error("LocalKernel 未提供：请在宿主 Activity 中通过 CompositionLocalProvider 注入 KernelHost") }
