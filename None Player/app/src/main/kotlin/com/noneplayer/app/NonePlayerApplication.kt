package com.noneplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.kernel.Kernel
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.generated.ExoPlayerPluginIndex
import com.noneplayer.kernel.generated.LibraryPluginIndex
import com.noneplayer.kernel.generated.LocalScannerPluginIndex
import com.noneplayer.kernel.generated.PlayerUiPluginIndex
import com.noneplayer.kernel.generated.ProfilePluginIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
     * B9 修复：启动时同步读取一次持久化主题，让首帧组合即用正确外观，
     * 避免"系统亮色 + 应用强制暗色"时首帧闪白。
     * （一次性 ~毫秒级 DataStore 读取，换取首帧色彩正确）
     */
    val initialThemeMode: ThemeMode by lazy {
        runBlocking {
            kernel.services.get<AppSettings>().themeMode.first()
        }
    }

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
    }
}
