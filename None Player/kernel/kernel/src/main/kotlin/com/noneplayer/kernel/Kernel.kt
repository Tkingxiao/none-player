package com.noneplayer.kernel

import android.content.Context
import android.util.Log
import com.noneplayer.core.common.DefaultDispatcherProvider
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginIndex
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.VideoPlayerFactory
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.KernelHost
import com.noneplayer.kernel.ui.api.ScreenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 内核引导状态；UI 在 Running 前显示启动画面，Failed 时展示错误 */
sealed interface BootState {
    data object Idle : BootState
    data object Booting : BootState
    data object Running : BootState
    data class Failed(val cause: Throwable) : BootState
}

/**
 * 微内核门面——装配一切的中心。
 *
 * 职责（设计文档 §4）：
 * 1. 从 [PluginIndex]（KSP 编译期生成）收集全部插件；
 * 2. 按依赖拓扑排序后依次 onInstall / onStart；
 * 3. 注册内核级服务与内核内置扩展（设置页）。
 *
 * 插件间通信只允许扩展点 + 事件总线；[Kernel] 自身通过 [KernelHost]
 * 暴露给 UI 层。`Navigator` 不进 ServiceLocator——它与 NavController
 * 生命周期绑定，由 KernelNavHost 经 ScreenProvider.register 注入。
 *
 * 装配错误（插件 id 冲突、依赖缺失、循环依赖）一律启动即失败，
 * 通过 [bootState] 的 [BootState.Failed] 暴露。
 */
class Kernel private constructor(
    private val context: Context,
    private val pluginIndexes: List<PluginIndex>,
    private val scope: CoroutineScope,
) : KernelHost {

    override val registry: ExtensionRegistry = DefaultExtensionRegistry()
    override val events: EventBus = FlowEventBus()
    override val services: ServiceLocator = DefaultServiceLocator()

    private val _bootState = MutableStateFlow<BootState>(BootState.Idle)
    val bootState: StateFlow<BootState> = _bootState.asStateFlow()

    private val installMutex = Mutex()
    private var installed: List<Plugin> = emptyList()

    /** 已安装插件（供设置页"插件管理"展示） */
    override val plugins: List<Plugin> get() = installed

    /**
     * 引导内核。立即返回，过程见 [bootState]；
     * UI 层在 Running 之前不应渲染依赖内核的界面。
     */
    fun boot() {
        scope.launch { bootInternal() }
    }

    private suspend fun bootInternal() {
        check(_bootState.value == BootState.Idle) { "Kernel 已引导，不能重复 boot" }
        _bootState.value = BootState.Booting
        try {
            registerKernelServices()

            val allPlugins = pluginIndexes.flatMap { it.plugins() }
            check(allPlugins.isNotEmpty()) {
                "未收集到任何插件。请确认 :app 装配了至少一个 PluginIndex（kernel-ksp 生成）。"
            }

            val sorted = PluginSorter.sort(allPlugins)
            installMutex.withLock {
                sorted.forEach { plugin ->
                    installed = installed + plugin
                    plugin.onBind(services)
                    plugin.onInstall(registry)
                    events.publish(KernelEvent.PluginInstalled(plugin.id))
                }
            }
            sorted.forEach { it.onStart() }

            registerBuiltins()
            _bootState.value = BootState.Running
            Log.i(TAG, "内核引导完成，共 ${sorted.size} 个插件: ${sorted.map { it.id }}")
        } catch (t: Throwable) {
            Log.e(TAG, "内核引导失败", t)
            _bootState.value = BootState.Failed(t)
        }
    }

    /** 停机：逆序 onStop 释放资源（设计文档 §15.5） */
    suspend fun shutdown() {
        installMutex.withLock {
            installed.reversed().forEach { plugin ->
                runCatching { plugin.onStop() }
                    .onFailure { Log.w(TAG, "插件 ${plugin.id} onStop 异常", it) }
            }
            installed = emptyList()
        }
        _bootState.value = BootState.Idle
    }

    private fun registerKernelServices() {
        services.register<Context>(context)
        services.register<DispatcherProvider>(DefaultDispatcherProvider())
        services.register<EventBus>(events)
        services.register<AppSettings>(AppSettings(context.applicationContext))
        services.register<VideoPlayerFactory>(VideoPlayerFactory(registry))
    }

    /** 内核内置扩展：聚合设置页（设计文档 §8 的内核实现） */
    private fun registerBuiltins() {
        registry.register(ScreenProvider::class.java, KernelSettingsScreenProvider(this))
    }

    companion object {
        private const val TAG = "NonePlayerKernel"

        /**
         * 装配内核。[context] 当前仅保留引用（WorkManager、权限触发在后续阶段接入），
         * [pluginIndexes] 由宿主 `:app` 从各插件模块的 KSP 产物收集。
         */
        fun create(
            context: Context,
            pluginIndexes: List<PluginIndex>,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): Kernel = Kernel(context.applicationContext, pluginIndexes, scope)
    }
}
