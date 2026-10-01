package com.noneplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.kernel.BootState
import com.noneplayer.kernel.Kernel
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.generated.ExoPlayerPluginIndex
import com.noneplayer.kernel.generated.LibraryPluginIndex
import com.noneplayer.kernel.generated.LocalScannerPluginIndex
import com.noneplayer.kernel.generated.PlayerUiPluginIndex
import com.noneplayer.kernel.generated.ProfilePluginIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 宿主 Application——只做一件事：装配并引导内核（设计文档 §1）。
 *
 * 插件清单由 kernel-ksp 编译期生成的 PluginIndex 收集；
 * 新增插件时在此追加一个索引即可，无需改动内核。
 */
class NonePlayerApplication : Application(), ImageLoaderFactory {

    lateinit var kernel: Kernel
        private set

    /**
     * E10：Coil 装载器提到 Application 级单例——此前由 LibraryScreen 在
     * composable 内 remember 创建且从不释放（每次进页重建、缓存不共享）。
     * 内存/磁盘缓存用 Coil 默认配置，进程内所有图片请求共享。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    /**
     * E69 修复：B9 原实现用 `by lazy { runBlocking { ... } }`，首次访问（在
     * MainActivity.setContent 的主线程组合期间）会阻塞主线程等 DataStore 读盘——
     * 低端设备/首次冷启动创建 DataStore 文件/扫描抢占存储 I/O 时没有硬上限保证，
     * 是潜在的 ANR 诱因。改为 Application.onCreate() 内用后台调度器预热：
     * boot() 刚启动即可读（services 在 bootInternal 的第一步就同步注册完毕），
     * 结果写入 StateFlow；MainActivity 侧只做无阻塞读取，读不到时退默认主题
     * （代价是极端情况下首帧可能短暂用默认主题，换来主线程绝不阻塞）。
     */
    private val _initialThemeMode = MutableStateFlow<ThemeMode?>(null)
    val initialThemeMode: ThemeMode get() = _initialThemeMode.value ?: ThemeMode.SYSTEM

    private val appScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        kernel = Kernel.create(
            context = this,
            pluginIndexes = listOf(
                LibraryPluginIndex(),
                LocalScannerPluginIndex(),
                ExoPlayerPluginIndex(),
                PlayerUiPluginIndex(),
                ProfilePluginIndex(),
            ),
        )
        kernel.boot()
        // 服务在 bootInternal() 的第一步（registerKernelServices）同步注册，
        // 早于插件安装/启动——等到不再是 Idle 即可安全取 AppSettings，不必等 Running。
        appScope.launch {
            kernel.bootState.filter { it != BootState.Idle }.first()
            runCatching {
                _initialThemeMode.value = kernel.services.get<AppSettings>().themeMode.first()
            }
        }
    }
}
