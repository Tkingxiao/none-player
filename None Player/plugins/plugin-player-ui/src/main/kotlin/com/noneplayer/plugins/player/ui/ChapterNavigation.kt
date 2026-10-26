package com.noneplayer.plugins.player.ui

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * E235：章节入口三件套的纯函数——进度条点按吸附与面板上/下一章。
 *
 * 吸附只在**点击**路径生效（拖动保持精确 seek，不被章节"吸"走）：点到离刻度
 * 一定比例（由调用方按 12dp/轨宽换算）以内时，落点归到章节起点，否则按点按
 * 位置精确 seek。时长未知（<=0）不做任何吸附。
 */
internal fun chapterSnapPositionMs(
    tapFraction: Float,
    durationMs: Long,
    chapterStartsMs: List<Long>,
    snapFraction: Float,
): Long {
    if (durationMs <= 0L) return 0L
    val raw = (tapFraction.coerceIn(0f, 1f) * durationMs).roundToLong().coerceIn(0L, durationMs)
    if (chapterStartsMs.isEmpty() || snapFraction <= 0f) return raw
    val nearest = chapterStartsMs
        .filter { it in 0L..durationMs }
        .minByOrNull { abs(it - raw) }
        ?: return raw
    val snapMs = (snapFraction * durationMs).toLong().coerceAtLeast(1L)
    return if (abs(nearest - raw) <= snapMs) nearest else raw
}

/**
 * 面板「上一章 / 下一章」的目标下标；没有可去的章返回 null（按钮保持可点但无动作）。
 * 当前下标来自播放位置派生，可能为 -1（位置在所有章之前）——按第 0 章处理。
 */
internal fun chapterStepIndex(currentIndex: Int, chapterCount: Int, forward: Boolean): Int? {
    if (chapterCount <= 0) return null
    val from = currentIndex.coerceIn(0, chapterCount - 1)
    val target = if (forward) from + 1 else from - 1
    return target.takeIf { it in 0 until chapterCount }
}