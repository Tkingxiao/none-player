package com.noneplayer.kernel.api

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * **同步快照**：已经拿到过的列表（没有就 null）。
     *
     * 给页面**首帧**用：`observeMedia()` 的收集是异步的，页面重进时首帧必然拿到空，
     * 先渲染一帧骨架/"0 个视频/0 个文件夹"，叠上转场淡出就成了用户看到的
     * "切界面闪一帧模板页"。实现方若用 StateFlow 持有列表，这里直接回当前值即可；
     * 首扫完成之前回 null（＝"确实还没数据"，交给骨架）。
     */
    fun mediaSnapshot(): List<MediaItem>? = null

    /** 触发一次扫描 */
    suspend fun scan(): ScanResult

    /**
     * A25：扫描进度（可选能力）。来源不支持时恒为 idle。
     *
     * UI 不再用本地 `refreshing` + 15s 超时猜扫描何时结束，直接跟这条流；
     * [total] 为 0 表示还在准备（尚未拿到本次范围）。
     */
    fun observeScanProgress(): Flow<ScanProgress> = flowOf(ScanProgress())

    /**
     * A25：取消当前扫描（可选能力）。未在扫描 / 不支持时无操作；取消后
     * [observeScanProgress] 应回到 idle。
     */
    fun cancelScan() {}
}

/** 一次扫描的进度快照。 */
data class ScanProgress(
    val active: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
)

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
