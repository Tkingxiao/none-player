package com.noneplayer.plugins.player.ui.gesture

import kotlin.math.abs

/**
 * 舞台手势的死区/边缘参数（单位 dp）。
 *
 * A7：边缘宽度必须跟随**系统手势区域**，不再写死一个 24dp——
 * 左右 = max(24dp, systemGestures 左右 inset)，顶部固定 48dp（状态栏呼出），
 * 底部 = max(48dp, systemGestures bottom inset)（导航条）。
 *
 * 这里只装数据；实际像素换算由手势层按 `Density` 完成，
 * 具体宽度由版式层（PlayerScreen）读 WindowInsets 后算好传入。
 */
internal data class StageGestureZones(
    val leftDp: Float = STAGE_EDGE_MIN_DP,
    val rightDp: Float = STAGE_EDGE_MIN_DP,
    val topDp: Float = STAGE_TOP_DEAD_ZONE_DP,
    val bottomDp: Float = STAGE_BOTTOM_DEAD_ZONE_DP,
) {
    companion object {
        /** 拿不到系统 inset 时的保守默认值。 */
        val Default = StageGestureZones()
    }
}

/** 已换算成 px 的死区宽度，纯几何判定用。 */
internal data class StageGestureZonePx(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

/** 左右边缘下限：小于这个宽度会与系统返回手势抢事件。 */
internal const val STAGE_EDGE_MIN_DP = 24f

/** 顶部死区：留给状态栏呼出。 */
internal const val STAGE_TOP_DEAD_ZONE_DP = 48f

/** 底部死区下限：留给导航条/手势导航。 */
internal const val STAGE_BOTTOM_DEAD_ZONE_DP = 48f

/** 起手判定阈值：位移超过它才分派轴。 */
internal const val STAGE_SLOP_DP = 10f

/** 横滑 seek 满幅基准：整屏宽 = ±90s。 */
internal const val STAGE_SEEK_FULL_SPAN_MS = 90_000f

/** 拖动族实际执行的轴。 */
internal enum class StageDragKind { Seek, Brightness, Volume }

/** 边缘宽度取「系统手势 inset」与固定下限的较大者。 */
internal fun stageDpAtLeast(systemInsetDp: Float, minDp: Float): Float = maxOf(minDp, systemInsetDp)

/**
 * 点按族死区：左右边缘 + 顶部。
 *
 * 点按层**不消费**死区内的事件，直接返回，让系统手势原样收到；
 * 底部不设点按死区（控制层/解锁钮的唤出需要点到底部）。
 */
internal fun isTapDeadZone(
    x: Float,
    y: Float,
    width: Float,
    zones: StageGestureZonePx,
): Boolean = x < zones.left || x > width - zones.right || y < zones.top

/**
 * 拖动族死区：四边。
 *
 * A7#3：死区判定必须**先于分轴**——旧实现先按横向判 Seek、再查上下死区，
 * 于是从底部导航条起手的横滑会变成一次 seek。
 */
internal fun isDragDeadZone(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    zones: StageGestureZonePx,
): Boolean = isTapDeadZone(x, y, width, zones) || y > height - zones.bottom

/** 双击落点分区：左右 1/3 快退/快进，中间播放/暂停。 */
internal fun tapZoneForX(x: Float, width: Float): StageTapZone = when {
    x < width / 3f -> StageTapZone.Back
    x > width * 2f / 3f -> StageTapZone.Forward
    else -> StageTapZone.Center
}

/** 竖滑分区：左 1/3 亮度、右 1/3 音量、中 1/3 不接管。 */
internal fun verticalModeForX(x: Float, width: Float): StageVerticalMode? = when {
    x < width / 3f -> StageVerticalMode.Brightness
    x > width * 2f / 3f -> StageVerticalMode.Volume
    else -> null
}

/**
 * 起手分轴（过 slop 后调用一次）。
 *
 * 顺序钉死：**先死区、后分轴**；横向优先（abs(x) >= abs(y) → Seek）；
 * 竖滑再按左右 1/3 分亮度/音量，中 1/3 返回 null（不接管、不消费）。
 */
internal fun decideDragKind(
    totalX: Float,
    totalY: Float,
    startX: Float,
    startY: Float,
    width: Float,
    height: Float,
    zones: StageGestureZonePx,
): StageDragKind? {
    if (isDragDeadZone(startX, startY, width, height, zones)) return null
    if (abs(totalX) >= abs(totalY)) return StageDragKind.Seek
    return when (verticalModeForX(startX, width)) {
        StageVerticalMode.Brightness -> StageDragKind.Brightness
        StageVerticalMode.Volume -> StageDragKind.Volume
        null -> null
    }
}

/**
 * 双击窗口：第二次按下必须晚于 `doubleTapMinTime` 且早于 `doubleTapTimeout`
 * （与 `ViewConfiguration` 语义一致）。
 */
internal fun isSecondTapValid(
    firstUpUptimeMs: Long,
    secondDownUptimeMs: Long,
    doubleTapTimeoutMs: Long,
    doubleTapMinTimeMs: Long,
): Boolean {
    val elapsed = secondDownUptimeMs - firstUpUptimeMs
    return elapsed >= doubleTapMinTimeMs && elapsed < doubleTapTimeoutMs
}

/**
 * 长按加速的正向守卫（A7#5）：
 * - 触发前：位移越过 slop 就不许进入长按（先按后滑不是"长按 + 3x"）；
 * - 触发后：位移再越过 slop 立即取消加速（不是"按住不动才生效，动了继续 3x"）。
 */
internal fun movementCancelsLongPress(distancePx: Float, slopPx: Float): Boolean = distancePx > slopPx

/** 横滑增量 → seek 增量（满幅 ±90s）。 */
internal fun seekDeltaMs(deltaX: Float, width: Float): Float =
    if (width <= 0f) 0f else deltaX / width * STAGE_SEEK_FULL_SPAN_MS

/** 竖滑增量 → 百分比增量（满幅 100%；pointer y 向下为正，调用方取负实现"上滑变大"）。 */
internal fun verticalDeltaPercent(deltaY: Float, height: Float): Float =
    if (height <= 0f) 0f else deltaY / height * 100f

/**
 * 回调执行时夹取 seek 目标（A7#7）。
 *
 * `durationMs` 必须是**回调执行时**读到的值：AVI 时长回填、切集瞬间组合期
 * 快照会过期，把旧的 duration 当上限会把横滑永远夹在旧片长里。
 * 时长未知（<=0）时不设上限，只保证非负。
 */
internal fun clampSeekTargetMs(rawTargetMs: Float, durationMs: Long): Float =
    if (durationMs <= 0L) rawTargetMs.coerceAtLeast(0f) else rawTargetMs.coerceIn(0f, durationMs.toFloat())
