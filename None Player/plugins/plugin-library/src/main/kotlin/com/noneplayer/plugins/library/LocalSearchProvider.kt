package com.noneplayer.plugins.library

import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.SearchResult
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.SearchProvider
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.flow.first

/**
 * 本地搜索扩展点实现——把媒体库（各 MediaSourceProvider 聚合结果）暴露给
 * `kernel-api SearchProvider` 契约。
 *
 * 设计稿的匹配域是"标题 + 文件夹名"（`searchView` 1146），这里逐字对齐；
 * 结果按媒体来源顺序原样返回（不排序——排序是结果页的排序控件的事）。
 *
 * SearchScreen 自己也直接对内存里的媒体列表做同一份匹配：扩展点的存在是为了
 * **其他方**（未来的全局搜索、语音入口、在线搜索插件并列展示）能拿到本地一路结果，
 * 而不是为了给本页绕一次远路。
 */
class LocalSearchProvider(private val registry: ExtensionRegistry) : SearchProvider {

    override val providerId: String = "local-media"

    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val items: List<MediaItem> = registry.query<MediaSourceProvider>()
            .map { runCatching { it.observeMedia().first() }.getOrDefault(emptyList()) }
            .flatten()
        return items
            .filter { item ->
                // 与搜索页同一匹配域（标题/短标题/文件夹/扩展名 + 拼音），
                // 共用 `PinyinSearch.matchesAll`：扩展名分支不再各写一份
                // （单个 "." 曾命中全库，见审查批次 A1）。
                PinyinSearch.matchesAll(item, q)
            }
            .map { item ->
                SearchResult(
                    providerId = providerId,
                    title = item.title,
                    subtitle = item.folder,
                    media = item,
                )
            }
    }
}
