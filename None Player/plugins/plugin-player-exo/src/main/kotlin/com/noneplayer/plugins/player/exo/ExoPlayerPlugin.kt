package com.noneplayer.plugins.player.exo

import android.Manifest
import android.os.Build
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.PlayerEngineProvider
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.register

/**
 * ExoPlayer 播放内核插件——注册默认播放引擎与后台播放所需权限。
 */
@PluginMarker
class ExoPlayerPlugin : Plugin {

    override val id: String = "player.exo"
    override val name: String = "ExoPlayer 内核"
    override val version: String = "1.0.0"

    override fun onBind(services: ServiceLocator) {
        // E209：PlaybackService 的 onPlaybackResumption 需要内核级历史仓库；服务在
        // 本进程内启动，onBind 早于播放服务创建。取不到时恢复入口返回空表。
        PlaybackResumeHistory.repository =
            runCatching { services.get<HistoryRepository>() }.getOrNull()
    }

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(PlayerEngineProvider::class.java, ExoEngineProvider())
        registry.register(PermissionProvider::class.java, PlaybackPermissions())
    }

    override suspend fun onStart() = Unit

    override suspend fun onStop() = Unit
}

/** 后台播放的媒体通知需要通知权限（Android 13+） */
class PlaybackPermissions : PermissionProvider {

    override val permissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    override val rationale: String =
        "后台播放时，None Player 会在通知栏显示播放控制，需要通知权限。"
}

/** E209：PlaybackService 与内核 HistoryRepository 的薄桥（进程内单例）。 */
internal object PlaybackResumeHistory {
    @Volatile
    var repository: HistoryRepository? = null
}
