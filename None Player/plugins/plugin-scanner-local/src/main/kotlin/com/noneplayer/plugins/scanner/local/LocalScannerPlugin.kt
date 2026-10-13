package com.noneplayer.plugins.scanner.local

import android.content.Context
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.StartupTaskPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.SettingsProvider

/**
 * 本地媒体扫描插件——设备视频进入媒体库的入口。
 *
 * 注册三个扩展点：
 * - [MediaSourceProvider]：MediaStore 视频数据源（门控：用户选定目录后才扫描）；
 * - [PermissionProvider]：声明所需权限，由宿主统一申请；
 * - [SettingsProvider]：设置页"扫描目录 + 重新扫描"功能。
 */
@PluginMarker
class LocalScannerPlugin : StartupTaskPlugin {

    override val id: String = "scanner.local"
    override val name: String = "本地媒体"
    override val version: String = "1.0.0"

    private lateinit var source: LocalMediaSource
    private lateinit var eventBus: EventBus

    override fun onBind(services: ServiceLocator) {
        val context: Context = services.get()
        val dispatchers: DispatcherProvider = services.get()
        eventBus = services.get()
        val settings: AppSettings = services.get()
        // 注册表在 onBind 即可用（内核把它也登记成了服务，见 Kernel.registerKernelServices）
        val registry: ExtensionRegistry = services.get()
        source = LocalMediaSource(
            context = context,
            dispatchers = dispatchers,
            eventBus = eventBus,
            settings = settings,
            registry = registry,
            history = services.get(),
            // E25：观察者跟随进程前台/后台，后台不再无谓持有 ContentObserver
            processLifecycle = services.get(),
        )
    }

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(MediaSourceProvider::class.java, source)
        registry.register(PermissionProvider::class.java, LocalMediaPermissions())
        registry.register(SettingsProvider::class.java, LocalScannerSettings(source))
        // 批次 7「插件清缓存」：缩略图缓存的清理入口交给插件管理页
        registry.register(CacheProvider::class.java, source)
        // 媒体库页的「文件夹管理」界面经由它增删扫描目录：释放持久授权、重扫、
        // 移除最后一个目录时清列表——副作用归来源所有，UI 不直接改 DataStore。
        // 注意：它使 source 以多个接口键注册——`app/proguard-rules.pro` 的
        // "扩展点接口禁止 R8 类合并"keep 列表必须涵盖本接口（2026-10-12 闪退根因，
        // 注册表按 Class 分桶，合并后即双份注册）。
        registry.register(com.noneplayer.kernel.api.ScanFolderController::class.java, source)
    }

    /**
     * 首扫走**有界启动预算**（内核侧 [com.noneplayer.kernel.api.StartupTaskPlugin] 契约）：
     * 观察者先挂上（它必须早于任何一次扫描回调），**先恢复上次扫描的落盘快照**——
     * 大库冷启动时媒体列表首帧即有内容，扫描退居后台做增量刷新
     * （旧行为是"不加载好不进去"：一整轮全量扫描完成前，媒体库页只有占位/空态）。
     */
    override fun startup(scope: CoroutineScope): Deferred<Unit> = scope.async {
        source.startObserving()
        source.restoreSnapshot()
        onStart()
    }

    override suspend fun onStart() {
        if (source.isAvailable()) {
            val result = source.scan()
            eventBus.publish(KernelEvent.MediaScanned(sourceId = result.sourceId, count = result.added))
        }
    }

    override suspend fun onStop() {
        source.stopObserving()
    }
}
