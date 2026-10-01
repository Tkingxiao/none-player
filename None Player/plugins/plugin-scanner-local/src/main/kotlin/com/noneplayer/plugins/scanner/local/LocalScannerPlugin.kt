package com.noneplayer.plugins.scanner.local

import android.content.Context
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
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
        source = LocalMediaSource(context, dispatchers, eventBus, settings)
    }

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(MediaSourceProvider::class.java, source)
        registry.register(PermissionProvider::class.java, LocalMediaPermissions())
        registry.register(SettingsProvider::class.java, LocalScannerSettings(source))
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
