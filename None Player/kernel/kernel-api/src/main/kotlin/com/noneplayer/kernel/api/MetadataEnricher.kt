package com.noneplayer.kernel.api

import com.noneplayer.core.media.MediaItem

/**
 * 元数据富化扩展点——为媒体项补充封面、简介等信息。
 *
 * 实现方：TMDB 插件、豆瓣插件等。富化按注册顺序串行执行，
 * 后一个富化器看到的是前一个的输出；实现必须幂等。
 */
interface MetadataEnricher : ExtensionPoint {
    val enricherId: String

    /** 返回富化后的 [item]；无信息可补时原样返回 */
    suspend fun enrich(item: MediaItem): MediaItem
}
