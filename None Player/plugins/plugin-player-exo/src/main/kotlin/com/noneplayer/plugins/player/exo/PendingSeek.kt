package com.noneplayer.plugins.player.exo

import kotlin.math.abs

/**
 * REV-11：一次"目标媒体还没有真正生效时到达的 seek"。
 *
 * 旧实现把挂起 seek 记成裸 `Long?`，于是三个问题互相牵连：
 * - **认不出是谁的 seek**：装载窗口内用户切到另一集，上一条媒体的 seek 目标会被
 *   当成新媒体的起播位置落实（拖到 30 分钟 → 下一集从 30 分钟开始）；
 * - **清不掉**：轮询只看"位置接近目标"，媒体切换后位置永远不接近，挂起值一直留到
 *   下一次重建（切字幕等），被当成恢复位置把已播到的位置拉回去；
 * - **清太早**：旧媒体还在报位置时就清，真正的 seek 落到新媒体上时已无处可查。
 *
 * [mediaKey] 是目标媒体的身份（"sourceId:mediaId"，见 [mediaKeyOf]）；没有身份
 * 概念的路径（单媒体 prepare、无 id 的临时条目）为 null——此时只能按 null==null
 * 的旧行为处理，无法区分两条媒体。
 *
 * [generation] 是单调递增序号：同一媒体上后写的 seek 覆盖先写的（用户最后一次
 * 拖动才是意图），单测见 PendingSeekTest。
 */
internal data class PendingSeek(
    val positionMs: Long,
    val mediaKey: String?,
    val generation: Long,
)

/** 记录一次新的挂起 seek：同一媒体后写覆盖先写，序号 +1 */
internal fun nextPendingSeek(
    previous: PendingSeek?,
    positionMs: Long,
    mediaKey: String?,
): PendingSeek = PendingSeek(
    positionMs = positionMs.coerceAtLeast(0L),
    mediaKey = mediaKey,
    generation = (previous?.generation ?: 0L) + 1L,
)

/**
 * 装载落地时该用的起播位置（纯函数）。
 *
 * 只有"挂起 seek 的媒体就是这次要装载的媒体"才采信它；否则一律用调用方给的
 * [fallbackMs]——切集时旧媒体的 seek 目标不许落进新媒体。挂起目标为 0 视为
 * "从头播"，与没有挂起值等价（避免把 0 当成一次有效的续播位置）。
 */
internal fun resolveStartPosition(
    pending: PendingSeek?,
    targetMediaKey: String?,
    fallbackMs: Long,
): Long {
    val fallback = fallbackMs.coerceAtLeast(0L)
    if (pending == null || pending.positionMs <= 0L) return fallback
    if (pending.mediaKey != targetMediaKey) return fallback
    return pending.positionMs
}

/** 这次装载是否"消费掉"了挂起 seek（媒体身份一致才允许清空） */
internal fun shouldConsumePendingSeek(pending: PendingSeek?, targetMediaKey: String?): Boolean =
    pending != null && pending.mediaKey == targetMediaKey

/**
 * 轮询是否该清掉挂起 seek（纯函数）。
 *
 * 必须同时满足：挂起 seek 属于**当前已生效媒体**（[appliedMediaKey]），且引擎位置
 * 已经收敛到目标附近（[settleMs] 容差）。只判位置不判身份，就是旧实现"切集后挂起
 * 值永远清不掉"的成因；只判身份不判位置，则会在 seek 还没落实时就清掉。
 */
internal fun shouldClearPendingSeekAfterPoll(
    pending: PendingSeek?,
    appliedMediaKey: String?,
    positionMs: Long,
    settleMs: Long,
): Boolean {
    if (pending == null) return false
    if (pending.mediaKey != appliedMediaKey) return false
    return abs(positionMs.coerceAtLeast(0L) - pending.positionMs) <= settleMs
}

/**
 * 媒体身份键："sourceId:mediaId"。
 *
 * 必须与会话侧（`PlaybackProgress.keyOf` / 历史记忆键）**逐字一致**：那边就是
 * `"${item.sourceId}:${item.id}"`，对空 sourceId 也**不做** "local" 回退。这里若
 * 自作主张补位，深链/路由临时条目（sourceId 为空）在引擎侧会算成 "local:id"、
 * 会话侧算成 ":id"，appliedMediaKey 与目标键永不相等 → 进度持久化被静默跳过
 * （REV-04 对该媒体整体失效）。所以只做一件事：没有 mediaId 时返回 null
 * （无身份），sourceId 原样拼接。
 */
internal fun mediaKeyOf(sourceId: String, mediaId: String): String? {
    if (mediaId.isBlank()) return null
    return "$sourceId:$mediaId"
}
