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

    /**
     * E203：请求为指定条目**预热封面**（可选能力）。
     *
     * 扫描不再阻塞在封面上（[ScanPhase.COVERS] 只在来源仍有同步抽帧时上报），
     * 封面由来源在后台按需求补齐。UI 在渲染前把自己「真正要看」的条目 id 交过来：
     * 可见窗口 + 滚动方向上的预备窗口（急速滚动时提前要几屏）。
     *
     * 约定：
     * - 实现方应把这些条目排在后台封面队列**最前面**，命中缓存/否定缓存的直接跳过；
     * - 该方法**不阻塞**、不在调用线程做 IO，重复请求应幂等；
     * - 未实现（默认空实现）时无任何副作用，UI 继续走自己的现场抽帧兜底。
     */
    fun requestCovers(itemIds: List<String>) {}
}

/** 一次扫描的进度快照。 */
data class ScanProgress(
    val active: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    /**
     * 当前所处阶段（可选）。[total] 为 0 时 UI 用阶段名代替「已处理 x / y」，
     * 避免长耗时的准备/遍历段只显示一句「正在准备」。
     */
    val phase: ScanPhase? = null,
)

/**
 * 扫描阶段。来源自行决定粒度，未上报时 UI 退回通用文案。
 *
 * 约定：[ScanPhase.HIDDEN_WALK] 期间 [ScanProgress.total] 仍可能为 0（目录树深度
 * 未知），此时 [ScanProgress.processed] 上报**已发现条目数**，UI 按「已发现 N 项」渲染。
 */
enum class ScanPhase {
    /** 准备：解析扫描范围、权限、基线 */
    PREPARING,

    /** 查询 MediaStore（视频/音频索引） */
    MEDIA_STORE,

    /** 遍历 SAF 目录树（含隐藏目录），逐目录 IPC 最慢的一段 */
    HIDDEN_WALK,

    /** 生成/刷新封面缩略图 */
    COVERS,

    /** 补读时长等逐文件头部信息 */
    DURATION,

    /** 提交结果（写库、快照） */
    COMMIT,
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
