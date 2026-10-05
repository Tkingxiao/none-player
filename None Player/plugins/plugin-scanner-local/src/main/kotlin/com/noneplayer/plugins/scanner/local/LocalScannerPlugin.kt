package com.noneplayer.plugins.scanner.local

import android.content.Context
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
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
class LocalScannerPlugin : Plugin {

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
    }

    override suspend fun onStart() {
        source.startObserving()
        if (source.isAvailable()) {
            val result = source.scan()
            eventBus.publish(KernelEvent.MediaScanned(sourceId = result.sourceId, count = result.added))
        }
    }

    override suspend fun onStop() {
        source.stopObserving()
    }
}
