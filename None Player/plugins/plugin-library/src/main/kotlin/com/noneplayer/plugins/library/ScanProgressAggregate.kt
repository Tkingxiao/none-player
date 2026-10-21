package com.noneplayer.plugins.library

import com.noneplayer.kernel.api.ScanProgress

/**
 * A25：多来源扫描进度的聚合。只把 active 的来源计入；全部 idle 时回到 idle。
 *
 * 大库/多来源时 UI 显示的是“所有正在扫的来源合起来处理了多少”，取消时逐个来源
 * 调 cancelScan。单来源时等同于该来源自己的进度。
 */
internal fun aggregateScanProgress(values: List<ScanProgress>): ScanProgress {
    val active = values.filter { it.active }
    if (active.isEmpty()) return ScanProgress()
    return ScanProgress(
        active = true,
        processed = active.sumOf { it.processed },
        total = active.sumOf { it.total },
    )
}
