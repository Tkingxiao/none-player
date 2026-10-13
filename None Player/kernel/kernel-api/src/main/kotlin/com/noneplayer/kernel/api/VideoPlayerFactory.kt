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

    /**
     * 目标 mimeType 会落到哪个引擎（[PlayerEngineProvider.engineId]）；无引擎支持时返回 null。
     *
     * 调用方据此判断"换一个媒体还需不需要重建引擎"：同一引擎能接的两种 mimeType
     * （如 `video/mp4` 与 `video/x-matroska` 都归 exo）之间切换时复用既有实例即可——
     * 按 mimeType **字符串**比较会把每次换集都判成"换代"，重建 MediaController 要重连
     * 会话，用户看到的是切集卡在转圈。引擎内部本就按条目各自的 mimeType 选解封装器。
     */
    fun engineIdFor(mimeType: String): String? =
        registry.query<PlayerEngineProvider>()
            .filter { it.supports(mimeType) }
            .maxByOrNull { it.priority }
            ?.engineId
}
