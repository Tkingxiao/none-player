package com.noneplayer.kernel.api

/**
 * 播放器工厂——内核级服务，从已注册的 [PlayerEngineProvider] 中
 * 挑选引擎并创建播放器。
 *
 * 选择规则：过滤支持目标格式的引擎，按 priority 降序取第一个。
 *
 * ⚠️ 引擎生命周期（设计文档 §6.2 踩坑）：调用方持有返回的 [VideoPlayer]，
 * 切换引擎或退出播放时必须调用 [VideoPlayer.release]，
 * 否则底层解码器泄漏。
 */
class VideoPlayerFactory(private val registry: ExtensionRegistry) {

    fun create(context: Any, mimeType: String): VideoPlayer {
        val engine = registry.query<PlayerEngineProvider>()
            .filter { it.supports(mimeType) }
            .maxByOrNull { it.priority }
            ?: error("没有播放引擎支持格式 $mimeType（请确认已启用相应的播放内核插件）")
        return engine.create(context)
    }
}
