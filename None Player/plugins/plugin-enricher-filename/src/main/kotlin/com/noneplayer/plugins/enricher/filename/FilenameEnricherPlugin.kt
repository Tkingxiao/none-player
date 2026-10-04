package com.noneplayer.plugins.enricher.filename

import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.MetadataEnricher
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register

/**
 * 文件名解析插件（E42）——`MetadataEnricher` 扩展点的**首个实现**（E34 的一格）。
 *
 * 扫描期由 `LocalMediaSource` 按注册顺序串行调用：把
 * `[字幕组] 标题 - 01 [1080p][HEVC]` 变成 短标题 + 季/集 + 编码，
 * 卡片于是显示「标题 · 第 1 集」而不是整条文件名（§10 批次 4 验收项）。
 *
 * 无 UI、无状态：纯数据富化（幂等——已富化的条目再跑一遍结果不变）。
 */
@PluginMarker
class FilenameEnricherPlugin : Plugin {

    override val id: String = "enricher.filename"
    override val name: String = "文件名解析"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(MetadataEnricher::class.java, FilenameMetadataEnricher())
    }

    override suspend fun onStart() = Unit
    override suspend fun onStop() = Unit
}

/** 把文件名解析结果写进 [MediaItem] 的展示字段；解析不出原样返回 */
class FilenameMetadataEnricher : MetadataEnricher {

    override val enricherId: String = "filename"

    override suspend fun enrich(item: MediaItem): MediaItem {
        val parsed = FilenameParser.parse(item.title) ?: return item
        return item.copy(
            // 短标题与原名一致时不写入（UI 的 cardTitle 自会退回 title）
            displayTitle = parsed.title.takeIf { it.isNotBlank() && it != item.title },
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            videoCodec = parsed.codec,
        )
    }
}
