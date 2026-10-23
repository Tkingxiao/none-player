package com.noneplayer.plugins.library

import com.noneplayer.kernel.api.ScanPhase
import com.noneplayer.kernel.api.ScanProgress

/**
 * A25：多来源扫描进度的聚合。只把 active 的来源计入；全部 idle 时回到 idle。
 *
 * 大库/多来源时 UI 显示的是“所有正在扫的来源合起来处理了多少”，取消时逐个来源
 * 调 cancelScan。单来源时等同于该来源自己的进度。
 *
 * 阶段（[ScanProgress.phase]）不参与求和：多个来源各扫各的目录，「阶段之和」没有
 * 对应物。取 active 来源里**第一个非 null** 的阶段（即列表顺序上最先上报的那个）：
 * 一个来源已进入遍历段、另一个还在准备时，UI 显示出更靠后的那段，比把整条聚合
 * 进度按回「正在准备」更接近用户实际看到的耗时（契约 §5）。
 */
internal fun aggregateScanProgress(values: List<ScanProgress>): ScanProgress {
    val active = values.filter { it.active }
    if (active.isEmpty()) return ScanProgress()
    return ScanProgress(
        active = true,
        processed = active.sumOf { it.processed },
        total = active.sumOf { it.total },
        phase = active.firstNotNullOfOrNull { it.phase },
    )
}

/**
 * 阶段中文文案。未上报阶段（老来源 / 尚未进入阶段机）时返回 null，
 * 由调用方退回通用文案「正在准备扫描…」。
 */
internal fun scanPhaseLabel(phase: ScanPhase?): String? = when (phase) {
    null -> null
    ScanPhase.PREPARING -> "正在准备"
    ScanPhase.MEDIA_STORE -> "查询媒体库"
    ScanPhase.HIDDEN_WALK -> "遍历隐藏目录"
    ScanPhase.COVERS -> "生成封面"
    ScanPhase.DURATION -> "补读时长"
    ScanPhase.COMMIT -> "写入结果"
}

/** 扫描中主文案：有阶段报阶段名，无阶段保持既有「正在准备扫描…」。 */
internal fun scanProgressHeadline(progress: ScanProgress): String =
    scanPhaseLabel(progress.phase) ?: "正在准备扫描…"

/**
 * 扫描中明细文案。
 *
 * - 已知总数（`total > 0`）：确定进度，「已处理 x / y 项」。
 * - [ScanPhase.HIDDEN_WALK] 且总数未知：目录树深度事先不可知，此时 `processed`
 *   上报的是**已发现条目数**，渲染成「已发现 N 项」（契约 §0）。
 * - 其余情况无明细可报，返回 null（调用方不占行）。
 */
internal fun scanProgressDetail(progress: ScanProgress): String? = when {
    progress.total > 0 -> "已处理 ${progress.processed} / ${progress.total} 项"
    progress.phase == ScanPhase.HIDDEN_WALK -> "已发现 ${progress.processed} 项"
    else -> null
}

/** 确定进度（0f..1f）；总数未知时返回 null，调用方改走 indeterminate。 */
internal fun scanProgressFraction(progress: ScanProgress): Float? =
    if (progress.total > 0) {
        (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
