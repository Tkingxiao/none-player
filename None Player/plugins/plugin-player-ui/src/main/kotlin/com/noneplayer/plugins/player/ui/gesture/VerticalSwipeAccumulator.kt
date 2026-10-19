package com.noneplayer.plugins.player.ui.gesture

import kotlin.math.roundToInt

/** 竖滑一帧的结果：回写的整数百分比 + 这一帧是否顶到端点（供一次性 Reject 触感）。 */
internal data class VerticalSwipeStep(val percent: Int, val edgeHit: Boolean)

/**
 * 竖滑亮度/音量**增量累加器**（E144-2）。
 *
 * 旧实现每帧 `(current - deltaPercent).roundToInt()` 直接算目标值，而 `current`
 * 每帧读的是同一个已生效状态：慢滑时单帧增量往往只有 0.1%–0.3%，取整后恒为 0，
 * 手指滑完整块屏幕数值纹丝不动（真机复现：300px/3000ms 慢滑亮度覆盖值 0.5 → 0.5）。
 *
 * 这里保留浮点累计值：每帧把增量累加上去，取整只发生在回写目标值时；回写后累计值
 * 不重置（否则"每帧取整"的洞会以另一种形式回来）。夹取在累计值上做，顶到端点时
 * 额外报告 [VerticalSwipeStep.edgeHit]，由调用方决定是否响一次 Reject 触感。
 */
internal class VerticalSwipeAccumulator {
    private var value = 0f

    /** 起手时用**平台当前值**播种（竖滑起手会先 refreshFromPlatform）。 */
    fun start(currentPercent: Int) {
        value = currentPercent.toFloat()
    }

    /**
     * 累加一帧增量（pointer y 向下为正 → 用减实现"上滑变大"），返回回写目标。
     *
     * [floor]/[ceil] 是当前模式的取值范围（亮度 1..100、音量 0..100）。
     */
    fun onDelta(deltaPercent: Float, floor: Int, ceil: Int): VerticalSwipeStep {
        val candidate = value - deltaPercent
        val edgeHit = candidate < floor || candidate > ceil
        value = candidate.coerceIn(floor.toFloat(), ceil.toFloat())
        return VerticalSwipeStep(value.roundToInt(), edgeHit)
    }

    /** 当前累计值（测试/调试用）。 */
    fun current(): Float = value
}
