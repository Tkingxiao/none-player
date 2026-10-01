package com.noneplayer.kernel.api

import com.noneplayer.core.media.SearchResult

/**
 * 搜索扩展点——提供一路搜索结果。
 *
 * 实现方：本地搜索插件、在线搜索插件等；结果按 providerId 标记来源，
 * 聚合方原样呈现，不去重、不排序（各来源自排序）。
 */
interface SearchProvider : ExtensionPoint {
    val providerId: String

    /** 执行搜索；实现方自行处理超时与异常，失败返回空列表 */
    suspend fun search(query: String): List<SearchResult>
}
