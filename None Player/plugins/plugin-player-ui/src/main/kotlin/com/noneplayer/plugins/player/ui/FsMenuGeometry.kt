package com.noneplayer.plugins.player.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

internal data class FsMenuGeometry(
    val width: Int,
    val maxHeight: Int,
    val preferAbove: Boolean,
)

internal fun fsMenuGeometry(
    window: IntSize,
    anchor: IntRect,
    aboveLimit: Int?,
    preferAbove: Boolean,
    preferredWidth: Int,
    minimumRowHeight: Int,
    gap: Int,
    margin: Int,
): FsMenuGeometry {
    val width = window.width.coerceAtLeast(1)
    val height = window.height.coerceAtLeast(1)
    val horizontalMargin = margin.coerceIn(0, (width - 1) / 2)
    val verticalMargin = margin.coerceIn(0, (height - 1) / 2)
    val above = ((aboveLimit ?: anchor.top) - gap - verticalMargin).coerceAtLeast(0)
    val below = (height - anchor.bottom - gap - verticalMargin).coerceAtLeast(0)
    val preferredSpace = if (preferAbove) above else below
    val alternateSpace = if (preferAbove) below else above
    val useAbove = if (preferredSpace < minimumRowHeight && alternateSpace > preferredSpace) {
        !preferAbove
    } else {
        preferAbove
    }
    val available = if (useAbove) above else below
    return FsMenuGeometry(
        width = min(preferredWidth.coerceAtLeast(1), width - horizontalMargin * 2),
        maxHeight = min(available.coerceAtLeast(1), (height * 0.72f).toInt().coerceAtLeast(1)),
        preferAbove = useAbove,
    )
}

internal fun fsMenuPosition(
    window: IntSize,
    content: IntSize,
    anchor: IntRect,
    aboveLimit: Int?,
    alignEnd: Boolean,
    preferAbove: Boolean,
    gap: Int,
    margin: Int,
): IntOffset {
    val maxX = (window.width - content.width).coerceAtLeast(0)
    val maxY = (window.height - content.height).coerceAtLeast(0)
    val marginX = margin.coerceIn(0, maxX / 2)
    val marginY = margin.coerceIn(0, maxY / 2)
    val x = if (alignEnd) anchor.right - content.width else anchor.left
    val y = if (preferAbove) (aboveLimit ?: anchor.top) - gap - content.height else anchor.bottom + gap
    return IntOffset(
        x.coerceIn(marginX, maxX - marginX),
        y.coerceIn(marginY, maxY - marginY),
    )
}
