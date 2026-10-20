package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.PlayerEngineProvider
import com.noneplayer.kernel.api.VideoPlayer

/**
 * 假引擎 Provider。
 *
 * 按 [mimeTypes] 声明支持的格式，[create] 时把 `create:<engineId>` 写进共享的
 * [lifecycleLog]，引擎 [FakeVideoPlayer.release] 时写 `release:<engineId>`——
 * 「不同 mime 换代时新引擎 create 先于旧引擎 release」的时序断言就比对这份日志。
 */
internal class FakePlayerEngineProvider(
    override val engineId: String,
    override val priority: Int = 0,
    private val mimeTypes: Set<String> = setOf("video/mp4"),
    private val lifecycleLog: MutableList<String> = mutableListOf(),
) : PlayerEngineProvider {

    /** 本 provider 创建过的引擎实例（按创建顺序） */
    val created: MutableList<FakeVideoPlayer> = mutableListOf()

    var createCount: Int = 0
        private set

    override fun supports(mimeType: String): Boolean = mimeType in mimeTypes

    override fun create(context: Any): VideoPlayer {
        createCount++
        lifecycleLog += "create:$engineId"
        return FakeVideoPlayer(engineId, lifecycleLog).also { created += it }
    }
}
