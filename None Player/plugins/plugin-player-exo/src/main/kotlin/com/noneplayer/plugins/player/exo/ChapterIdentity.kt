package com.noneplayer.plugins.player.exo

/**
 * REV-09：章节归属判定（纯逻辑，便于 JVM 单测）。
 *
 * 旧实现 `if (entries.isNotEmpty()) _chapters.value = entries` 有两处硬伤：
 * - **永不清空**：切到没有章节的媒体后，上一集的章节面板照旧列着（点进去还会 seek
 *   到别的媒体的时间点）；`clearQueue()` 也不清，退出播放后章节残留到下一次播放。
 * - **旧媒体的迟到回调会盖回来**：切集瞬间上一集的 `onMetadata` / `onTracksChanged`
 *   可能还在路上，非空条目会覆盖新媒体的章节。
 *
 * 所以章节必须带"归属键"（[keyOf]）：媒体身份优先（"sourceId:mediaId"），单媒体
 * prepare 路径没有 id 时退回 uri。切换媒体时由播放器清空章节并把键切到新媒体；
 * 回调只有在键一致时才允许落地，且同媒体的空回调不许清掉已有效章节（章节元数据
 * 有时晚于 tracks 到达，空回调不代表"没有章节"）。
 */
internal object ChapterIdentity {

    /**
     * 章节归属键：优先媒体身份（[mediaKey]）；没有身份信息（单媒体 prepare、无 id
     * 条目）时退回 [mediaUri]——uri 至少能区分"两条不同的媒体"。
     */
    fun keyOf(mediaKey: String?, mediaUri: String?): String? =
        mediaKey?.takeIf { it.isNotBlank() } ?: mediaUri?.takeIf { it.isNotBlank() }

    /**
     * 这次章节回调该不该落到 `_chapters`：
     * - 回调没有归属键 → 丢弃（无主章节无从判断归属）；
     * - 回调键与当前键不同 → 丢弃（旧媒体的迟到回调，不许盖掉新媒体的章节）；
     * - 同键且条目非空 → 应用；
     * - 同键且条目为空 → 仅当当前本来就没有章节时才应用空集；否则保留已有效章节
     *   （同媒体空回调不清已有效章节）。
     */
    fun shouldApplyChapters(
        currentKey: String?,
        callbackKey: String?,
        incomingCount: Int,
        existingCount: Int,
    ): Boolean {
        if (callbackKey.isNullOrBlank()) return false
        if (currentKey != null && currentKey != callbackKey) return false
        return incomingCount > 0 || existingCount == 0
    }
}
