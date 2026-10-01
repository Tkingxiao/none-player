package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.PlayerEngineProvider
import com.noneplayer.kernel.api.VideoPlayer

/**
 * ExoPlayer 引擎——默认播放内核，支持 media3 能覆盖的全部格式。
 *
 * priority 取 50；未来 FFmpeg 插件以更高优先级接入即可覆盖本引擎
 * （设计文档 §6.2：切换引擎由调用方释放旧实例，防止解码器泄漏）。
 */
class ExoEngineProvider : PlayerEngineProvider {

    override val engineId: String = "exo"
    override val priority: Int = 50

    override fun create(context: Any): VideoPlayer =
        ExoVideoPlayer(context as android.content.Context)

    override fun supports(mimeType: String): Boolean = mimeType.startsWith("video/")
}
