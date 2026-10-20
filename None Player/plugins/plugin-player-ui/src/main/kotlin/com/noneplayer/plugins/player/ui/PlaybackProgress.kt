package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem

/**
 * 一次断点落盘的**身份快照**（REV-04）——回答"这条进度到底属于哪条媒体"。
 *
 * 修的是一个会**静默写坏历史**的竞态：`PlaybackSession.persistPosition()` 过去只按
 * "会话当前队列下标"取条目、再拿引擎的 `positionMs` 直接落盘。但引擎切集是异步的
 * （`observeEngineIndex` 要等 `currentIndex` 回调才回写 `_index`），在这段窗口里
 * "会话当前项"已经是 B、"引擎位置"还属于 A —— A 的进度被写进 B 的历史键，
 * 表现为"点开 B 直接跳到 A 看到的位置"。
 *
 * 因此落盘前必须核对**身份**，而不是只核对下标。身份由五个字段共同决定：
 * `sourceId + mediaId + uri + clipStartUs + clipEndUs`。后三个不能省：
 * CUE 分轨的 N 轨共享同一个音频文件（`uri` 相同），只有 `clipStartUs/clipEndUs`
 * 窗口能区分"专辑第 2 轨"与"第 5 轨"；反过来同一条媒体被重扫换号时 `uri` 也可能变。
 * 只比其一会把分轨的进度串在一起。
 *
 * [generation] 是装载代际（见 `PlaybackSession.loadGeneration`）：每次换队列/换下标/
 * 清理会话前自增，只用于日志与测试断言——**是否写盘以身份判定为主**，代际不单独当门。
 */
internal data class PlaybackProgressSnapshot(
    val sourceId: String,
    val mediaId: String,
    val uri: String,
    val clipStartUs: Long,
    val clipEndUs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val generation: Long,
) {
    /** `sourceId:mediaId`，与 Room 的 `media_key`（`HistoryEntry.keyOf`）同一条约定 */
    val mediaKey: String get() = keyOf(sourceId, mediaId)
}

/**
 * 历史键（`sourceId:mediaId`）——与 [com.noneplayer.core.database.HistoryEntry.keyOf]
 * 和引擎侧 `VideoPlayer.appliedMediaKey` 同一个约定。
 *
 * 注意与 [mediaMemoryKey] 的区别：后者是**设置项**的 per-media 键，路由条目缺
 * `sourceId` 时会补 `LOCAL_MEDIA_SOURCE_ID`；历史键不补，空就是空——两边一旦混用，
 * 引擎回报的 `appliedMediaKey` 与这里算出的键就对不上，落盘会被误判成"身份不符"。
 */
internal fun keyOf(item: MediaItem): String = keyOf(item.sourceId, item.id)

/** 由身份两段直接拼历史键（引擎侧回报的也是这个形状） */
internal fun keyOf(sourceId: String, mediaId: String): String = "$sourceId:$mediaId"

/** 从会话侧的**目标条目**构造快照（位置/时长由调用方给定，代际用于日志与断言） */
internal fun snapshotOf(
    item: MediaItem,
    positionMs: Long,
    durationMs: Long,
    generation: Long,
): PlaybackProgressSnapshot = PlaybackProgressSnapshot(
    sourceId = item.sourceId,
    mediaId = item.id,
    uri = item.uri,
    clipStartUs = item.clipStartUs,
    clipEndUs = item.clipEndUs,
    positionMs = positionMs,
    durationMs = durationMs,
    generation = generation,
)

/**
 * 两个快照是否指向**同一条媒体**（不看位置/时长/代际）。
 *
 * 五个身份字段全比：`sourceId + mediaId + uri + clipStartUs + clipEndUs`。
 * 只比 `uri` 会把 CUE 分轨串成一条（同一文件 N 轨）；只比 `sourceId:mediaId`
 * 在来源重扫换号/重授权后会把"同一位置的另一条记录"当成同一条。
 */
internal fun isSameTarget(a: PlaybackProgressSnapshot, b: PlaybackProgressSnapshot): Boolean =
    a.sourceId == b.sourceId &&
        a.mediaId == b.mediaId &&
        a.uri == b.uri &&
        a.clipStartUs == b.clipStartUs &&
        a.clipEndUs == b.clipEndUs

/** [snapshot] 是否就是 [target] 这条媒体的进度（落盘前的最终身份门） */
internal fun isSameTarget(snapshot: PlaybackProgressSnapshot, target: MediaItem): Boolean =
    snapshot.sourceId == target.sourceId &&
        snapshot.mediaId == target.id &&
        snapshot.uri == target.uri &&
        snapshot.clipStartUs == target.clipStartUs &&
        snapshot.clipEndUs == target.clipEndUs

/**
 * 这份快照能不能写进 [target] 的历史键（REV-04 的最终门）。
 *
 * 三条同时成立才放行：
 * ① 快照存在（引擎/队列尚未就绪时调用方拿不到快照）；
 * ② 条目有 id（无 id 的条目没有历史键，写进去只会生成孤儿行）；
 * ③ 快照身份与 [target] 完全一致（[isSameTarget]）。
 */
internal fun canPersist(snapshot: PlaybackProgressSnapshot?, target: MediaItem?): Boolean {
    if (snapshot == null || target == null) return false
    if (snapshot.mediaId.isEmpty() || target.id.isEmpty()) return false
    return isSameTarget(snapshot, target)
}

/**
 * 把"引擎此刻的位置"折算成**属于 [target] 的**可落盘快照；身份不符时返回 null。
 *
 * 这是 REV-04 的唯一判定入口（[PlaybackSession.persistPosition] 直接调它），
 * 提成纯函数是为了把三条容易写反的分支钉在单测里：
 *
 * - **引擎有身份**（`appliedMediaKey != null`，新引擎）：只认"已真正生效"的那条媒体。
 *   引擎回报的身份与 [target] 不一致，说明会话下标已指向 B、引擎里还是 A（或反之），
 *   此刻落盘会把 A 的进度写进 B 的键——返回 null 跳过，等下一次落点。
 * - **位置/时长取值**：优先用 `appliedPositionMs/appliedDurationMs`（只随已生效媒体
 *   更新，切换窗口内不会串到上一条媒体），缺失时退回控制器值。
 * - **引擎无身份**（legacy 引擎 / 尚未装载）：退回改造前的旧行为——队列当前项 +
 *   控制器位置，保证旧引擎插件不被这次修复弄丢断点。
 */
internal fun resolvePersistSnapshot(
    target: MediaItem,
    appliedMediaKey: String?,
    appliedPositionMs: Long?,
    appliedDurationMs: Long,
    controllerPositionMs: Long,
    controllerDurationMs: Long,
    generation: Long,
): PlaybackProgressSnapshot? {
    val appliedKey = appliedMediaKey?.takeIf { it.isNotEmpty() }
    val snapshot = if (appliedKey != null) {
        // 引擎已生效媒体 ≠ 会话目标条目：下标回写窗口内，跳过而不是"就近写一条"
        if (appliedKey != keyOf(target)) return null
        snapshotOf(
            item = target,
            positionMs = appliedPositionMs ?: controllerPositionMs,
            durationMs = appliedDurationMs.takeIf { it > 0L } ?: controllerDurationMs,
            generation = generation,
        )
    } else {
        snapshotOf(
            item = target,
            positionMs = controllerPositionMs,
            durationMs = controllerDurationMs,
            generation = generation,
        )
    }
    return snapshot.takeIf { canPersist(it, target) }
}
