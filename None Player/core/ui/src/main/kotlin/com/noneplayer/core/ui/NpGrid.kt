package com.noneplayer.core.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自适应网格每格最小宽度：把用户的「列数」偏好重新诠释为**密度档**——
 * 2 列=舒适（大卡）、3 列=紧凑（小卡）。真实列数随窗口宽度连续变化（E47），
 * 平板/折叠展开后自动变多列，不再被 `GridCells.Fixed` 锁死在手机的 2/3 列。
 */
fun npGridMinCell(gridColumns: Int): Dp =
    if (gridColumns <= 2) 176.dp else 120.dp

/**
 * 网格列数（§5.7）：**列数偏好只在紧凑档生效**——紧凑档没有自适应可言，用户说几列就几列
 * （默认 2 列，与设计稿 `.tier-compact .grid.v` 同值）；`Medium/Expanded` 的列数由窗口宽度
 * 决定（E47）。媒体库、搜索、个人页共用同一份口径，避免各写一份后漂移。
 *
 * 入参在这里再夹一次 1..3：旧 DataStore 值或调用方误传 0 不能让 Fixed(0) 崩掉。
 */
fun npGridColumnsFor(compact: Boolean, gridColumns: Int): GridCells {
    val columns = gridColumns.coerceIn(1, 3)
    return if (compact) GridCells.Fixed(columns) else GridCells.Adaptive(npGridMinCell(columns))
}
