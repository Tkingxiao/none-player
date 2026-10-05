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

    // E88（批次 8 实测暴露）：此前只认领 video/*——MediaStore.Audio 出的 mime
    // （audio/x-wav 等）全部无引擎，库里的音频文件点开即 IllegalStateException。
    // 仅听音频此前能活是因为它播的还是 video/* 的文件本体。ExoPlayer 的 Extractor
    // 链本就认领音频容器（§12.1 ① 裸音频全表支持），audio/* 一并接下。
    override fun supports(mimeType: String): Boolean =
        mimeType.startsWith("video/") || mimeType.startsWith("audio/")
}
