package com.noneplayer.kernel

import android.content.Context
import android.util.Log
import com.noneplayer.core.common.DefaultDispatcherProvider
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginIndex
import com.noneplayer.kernel.api.ProcessLifecycle
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.StartupTaskPlugin
import com.noneplayer.kernel.api.VideoPlayerFactory
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.KernelHost
import com.noneplayer.kernel.ui.api.ScreenProvider
import com.noneplayer.kernel.ui.api.TabProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    private val extensionRegistry = DefaultExtensionRegistry()
    override val registry: ExtensionRegistry get() = extensionRegistry
    override val events: EventBus = FlowEventBus()
    private val serviceLocator = DefaultServiceLocator()
    override val services: ServiceLocator get() = serviceLocator

    // E25：进程前台/后台状态。在本对象构造（= Application.onCreate，任何 Activity
    // 存在之前）时就装好 ActivityLifecycleCallbacks，计数不漏首个 Activity 的 onStart。
    private val processLifecycle: ProcessLifecycle =
        DefaultProcessLifecycle(context as? android.app.Application)

    private val _bootState = MutableStateFlow<BootState>(BootState.Idle)
    val bootState: StateFlow<BootState> = _bootState.asStateFlow()

    private val installMutex = Mutex()
    private var installed: List<Plugin> = emptyList()
    private val backgroundStartupJobs = mutableListOf<Job>()

    /** boot() 的一次性闸门（见 [boot] 注释）；[shutdown] 复位，允许重新引导。 */
    private val bootGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 已安装插件（供设置页"插件管理"展示） */
    override val plugins: List<Plugin> get() = installed

    /**
     * 引导内核。立即返回，过程见 [bootState]；
     * UI 层在 Running 之前不应渲染依赖内核的界面。
     *
     * 幂等：重复调用（宿主误用、或未来接 Activity 重入）直接忽略。此前判重是
     * bootInternal 里的一句 `check(_bootState.value == Idle)`，属于 check-then-act，
     * 且抛在 try/catch **之外**——第二次 boot 会以未捕获异常收场，而不是"已引导"。
     */
    fun boot() {
        if (!bootGate.compareAndSet(false, true)) return
        // 核心服务**同步**注册，必须留在协程之前：它们是 UI 首帧就可能读到的
        // （MainActivity 的组合在 Running 之前就读 AppSettings 的主题/动效档），
        // 放进协程会与主线程的组合赛跑——内核引导稍慢的那一次就撞出
        // "内核服务未注册: com.noneplayer.core.data.AppSettings" 闪退
        // （2026-10-08 实测一次）。这几行只登记句柄（DataStore / Room 都是惰性打开，
        // 不碰磁盘），放主线程安全；宿主契约因此成立：`boot()` 返回即可读服务。
        registerKernelServices()
        _bootState.value = BootState.Booting
        scope.launch { bootInternal() }
    }

    private suspend fun bootInternal() {
        try {
            // A2-8：per-media 记忆键的一次性重排（`subtitle_ms-68` → `subtitle_local:ms-68`，
            // 并清空后缀孤儿桶）。放这里而不是扫描回调里：深链冷进会在**首次扫描完成之前**
            // 就读字幕/画面/仅音频记忆，搬迁必须早于任何一次读取。
            // 失败不致命（标志位不置位，下次启动重试），但那次启动的 per-media 记忆读不到——
            // 如实记日志，不静默。
            runCatching {
                services.get<AppSettings>().migratePerMediaKeyNamespaceOnce()
            }.onFailure {
                Log.w(TAG, "per-media 键命名空间搬迁失败，本次启动的记忆可能读不到旧值（下次启动重试）", it)
            }
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
            // E59 修复：普通 onStart 逐插件隔离；显式声明 [StartupTaskPlugin] 的插件
            //（首次 MediaStore 扫描是典型）**只把任务安排下去**，不在这里同步等它跑完——
            // 否则"扫完 81 项媒体"这段时间用户一直盯着启动画面。
            val startupTasks = mutableListOf<Pair<String, Deferred<Unit>>>()
            sorted.forEach { plugin ->
                if (plugin is StartupTaskPlugin) {
                    runCatching {
                        val task = plugin.startup(scope)
                        startupTasks += plugin.id to task
                        // 与后台任务同寿：`shutdown()` 取消它们（Deferred 也是 Job）
                        backgroundStartupJobs += task
                    }.onFailure { cause ->
                        Log.w(TAG, "插件 ${plugin.id} 启动任务未能安排，已隔离", cause)
                        events.publish(KernelEvent.PluginStartFailed(plugin.id, cause))
                    }
                } else {
                    runCatching { plugin.onStart() }
                        .onFailure { cause ->
                            Log.w(TAG, "插件 ${plugin.id} onStart 异常，已隔离，其余插件不受影响", cause)
                            events.publish(KernelEvent.PluginStartFailed(plugin.id, cause))
                        }
                }
            }

            registerBuiltins()
            // Room 建库/首查对页面可用性不是前置条件；在首页进入骨架态时并行预热。
            backgroundStartupJobs += scope.launch(Dispatchers.IO) {
                runCatching { services.get<HistoryRepository>().prime() }
                    .onFailure { Log.w(TAG, "历史库预热失败（UI 首次收集时会再试）", it) }
            }
            // 播放偏好预热：渲染器模式在 ExoPlayer 构建期定型，`PlaybackService.onCreate`
            // 必须同步拿到"硬解优先"值。这里先在 IO 上读一次写进进程内缓存，服务侧就不必
            // 在主线程上 runBlocking 等首次读盘（那一段正落在"点播放"的关键路径上）。
            backgroundStartupJobs += scope.launch(Dispatchers.IO) {
                runCatching { AppSettings.warmHardwareDecodePreferred(context) }
                    .onFailure { Log.w(TAG, "播放偏好预热失败（服务侧会自行回退读取）", it) }
            }
            // 启动任务给一个**有界预算**：扫得快就带着内容进首页（常见于热启动/小库），
            // 扫得慢就先让 UI 走、内容随后补齐。超时**不取消**任务——它本来就不在
            // 页面可用的关键路径上，取消等于把媒体列表丢掉。
            //
            // **异常必须在这里就地隔离**：任务跑在 `scope.async` 里，异常会随 `await()`
            // 重抛；若不接住，一次插件侧失败就会冒到本方法的 catch 里把整个引导打成
            // `BootState.Failed`——那正是 E59 明令禁止的"单插件故障连累全应用"。
            // `CancellationException` 原样重抛：它承载超时与停机两种控制流，吞掉会让
            // `withTimeoutOrNull` 失效。
            if (startupTasks.isNotEmpty()) {
                val finished = withTimeoutOrNull(STARTUP_SCAN_BUDGET_MS) {
                    startupTasks.forEach { (pluginId, task) ->
                        try {
                            task.await()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (cause: Throwable) {
                            Log.w(TAG, "插件 $pluginId 启动任务异常，已隔离，其余插件不受影响", cause)
                            events.publish(KernelEvent.PluginStartFailed(pluginId, cause))
                        }
                    }
                    true
                }
                if (finished == null) {
                    Log.i(TAG, "启动任务未在 ${STARTUP_SCAN_BUDGET_MS}ms 内完成，先放行 UI，后台继续")
                }
            }
            _bootState.value = BootState.Running
            Log.i(TAG, "内核引导完成，共 ${sorted.size} 个插件: ${sorted.map { it.id }}")
        } catch (t: Throwable) {
            Log.e(TAG, "内核引导失败", t)
            _bootState.value = BootState.Failed(t)
        }
    }

    /**
     * 停机：逆序 onStop 释放资源（设计文档 §15.5）。
     *
     * 契约：停机后允许重新 [boot]，且重建的是一套**新**的服务与扩展，不允许沿用停机前的
     * 实例。因此 onStop 全部结束后要清空 [serviceLocator] 与 [extensionRegistry]：
     *   - 不清服务：重引导时 `registerKernelServices()` 会撞上 `DefaultServiceLocator`
     *     的重复注册检查，直接崩溃；
     *   - 不清扩展：旧插件实例已被 onStop，但仍在注册表里，新插件实例追加进去，
     *     查询会同时拿到已停机与在役的两份。
     *
     * 只由宿主在进程级收尾时调用；不要与 [boot] 并发调用（本方法把 bootGate 的复位放到
     * 最后，允许下一次 boot 发生在清空完成之后，但开机与停机本身不串行）。
     */
    suspend fun shutdown() {
        backgroundStartupJobs.forEach(Job::cancel)
        backgroundStartupJobs.clear()
        installMutex.withLock {
            installed.reversed().forEach { plugin ->
                runCatching { plugin.onStop() }
                    .onFailure { Log.w(TAG, "插件 ${plugin.id} onStop 异常", it) }
            }
            installed = emptyList()
        }
        serviceLocator.clear()
        extensionRegistry.clear()
        _bootState.value = BootState.Idle
        bootGate.set(false)
    }

    private fun registerKernelServices() {
        services.register<Context>(context)
        services.register<ProcessLifecycle>(processLifecycle)
        services.register<DispatcherProvider>(DefaultDispatcherProvider())
        services.register<EventBus>(events)
        services.register<AppSettings>(AppSettings(context.applicationContext))
        // §8.3 持久层：播放历史/收藏（Room）。内核级服务——库/播放/我的三个插件都读它
        services.register<HistoryRepository>(HistoryRepository(context.applicationContext))
        services.register<VideoPlayerFactory>(VideoPlayerFactory(registry))
        // 扩展注册表本身也是内核级服务：插件在 onBind 阶段只能拿到 ServiceLocator
        // （onInstall 的 registry 参数要等安装期才来），而 `VideoPlayerFactory` 之类的
        // 构造需要它。播放会话层要在 onBind 就建起来（迷你条可能在首个 Tab 组合时就
        // 需要它），因此把 registry 一并登记，避免插件各建一份互不相认的注册表。
        services.register<ExtensionRegistry>(registry)
    }

    /**
     * 内核内置扩展：聚合设置页（设计文档 §8 的内核实现）。
     *
     * 按 **`TabProvider`** 注册而不是 `ScreenProvider`：设置是第四个顶层标签，
     * 与首页/文件夹/我的同级（有选中态、标签栏常驻、无返回箭头）。
     * 注册成 ScreenProvider 会让它成为压在栈上的二级页——打开时盖住标签栏，
     * 顶部多一个返回箭头，读起来像"从设置里出去了"而不是"切到了设置"。
     */
    private fun registerBuiltins() {
        registry.register(TabProvider::class.java, KernelSettingsScreenProvider(this))
        // 设置的两个下钻详情页（只读的应用自身信息）
        registry.register(ScreenProvider::class.java, KernelAboutScreenProvider(this))
        registry.register(ScreenProvider::class.java, KernelPluginsScreenProvider(this))
    }

    companion object {
        private const val TAG = "NonePlayerKernel"
        private const val STARTUP_SCAN_BUDGET_MS = 1_200L

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
