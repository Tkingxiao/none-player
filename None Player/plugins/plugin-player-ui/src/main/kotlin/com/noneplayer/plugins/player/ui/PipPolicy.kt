package com.noneplayer.plugins.player.ui

import kotlin.math.roundToInt

// Stay inside the standard PiP range after Rational's 1/1000 rounding.
internal fun pipAspectRatio(value: Float): Float =
    (value.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)).coerceIn(0.419f, 2.39f)

/**
 * A14：画面在 View 内的内接矩形尺寸（px），用于 PiP `setSourceRectHint`。
 *
 * FIT 档按视频比例内接，避免把黑边收进小窗；CROP/STRETCH 铺满 View。
 * 返回 width to height；非法尺寸返回 0 to 0，由调用方放弃 hint。
 */
internal fun pipContentSize(
    viewWidth: Int,
    viewHeight: Int,
    aspectRatio: Float,
    fit: Boolean,
): Pair<Int, Int> {
    if (viewWidth <= 0 || viewHeight <= 0) return 0 to 0
    if (!fit || !aspectRatio.isFinite() || aspectRatio <= 0f) return viewWidth to viewHeight
    return if (viewWidth.toFloat() / viewHeight > aspectRatio) {
        val width = (viewHeight * aspectRatio).roundToInt().coerceIn(1, viewWidth)
        width to viewHeight
    } else {
        val height = (viewWidth / aspectRatio).roundToInt().coerceIn(1, viewHeight)
        viewWidth to height
    }
}
