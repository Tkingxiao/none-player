package com.noneplayer.kernel.api

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 媒体来源扩展点——提供一路媒体库数据。
 *
 * 实现方：本地扫描插件、网盘插件、DLNA 插件等。
 * 多个来源可同时注册，媒体库 UI 聚合渲染。
 */
interface MediaSourceProvider : ExtensionPoint {
    /** 来源唯一 id（如 `local`、`webdav.home`），同时是 [MediaItem.sourceId] */
    val sourceId: String
    val displayName: String

    /** 该来源是否可用（权限、网络、登录状态） */
    suspend fun isAvailable(): Boolean

    /** 观察该来源的媒体列表（增量推送） */
    fun observeMedia(): Flow<List<MediaItem>>

    /** 触发一次扫描 */
    suspend fun scan(): ScanResult
}

/** 一次扫描的结果统计 */
data class ScanResult(
    val sourceId: String,
    /** 新增条数 */
    val added: Int,
    /** 更新条数 */
    val updated: Int,
    /** 失效移除条数 */
    val removed: Int,
    /** 扫描耗时（毫秒） */
    val durationMs: Long,
)
