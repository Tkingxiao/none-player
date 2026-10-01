package com.noneplayer.core.media

/**
 * 搜索结果——[com.noneplayer.kernel.api.SearchProvider] 的返回模型。
 *
 * [MediaItem] 字段非空时表示结果可直接播放；
 * 否则仅为一条可跳转的搜索建议（如在线词条）。
 */
data class SearchResult(
    val providerId: String,
    val title: String,
    val subtitle: String? = null,
    val media: MediaItem? = null,
)
