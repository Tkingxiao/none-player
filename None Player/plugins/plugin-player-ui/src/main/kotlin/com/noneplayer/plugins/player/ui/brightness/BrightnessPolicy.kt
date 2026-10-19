package com.noneplayer.plugins.player.ui.brightness

import kotlin.math.abs
import kotlin.math.roundToInt

/** `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`：窗口没有亮度覆盖。 */
internal const val BRIGHTNESS_NONE = -1f

/**
 * 平台亮度读不到时的中性兜底百分比。
 *
 * A8 红线：旧实现兜底写死 62，把"未知"伪装成"62%"——第一次进页面时还没滑，
 * 亮度就已经被覆盖成 62%（假值），HUD 与实感都对不上。兜底必须是一个
 * **不改变观感预期**的中性值，且只在平台真的读不到时使用。
 */
internal const val BRIGHTNESS_FALLBACK_PERCENT = 50

/** 亮度覆盖的合法百分比区间（0 在 Android 上表示"灭屏"，不作为用户可调档位）。 */
internal const val BRIGHTNESS_MIN_PERCENT = 1
internal const val BRIGHTNESS_MAX_PERCENT = 100

/**
 * 窗口亮度读写的平台边界（`Window.attributes.screenBrightness`）。
 *
 * 抽成接口是为了让 [BrightnessPolicy] 成为纯策略：单测用 fake 记录读写次数，
 * 不需要真实 Window。
 */
internal interface WindowBrightnessTarget {
    /** 读窗口亮度覆盖；没有覆盖时必须返回 [BRIGHTNESS_NONE]（-1f）。 */
    fun readWindowBrightness(): Float

    /** 写窗口亮度覆盖；写 [BRIGHTNESS_NONE] 表示清除覆盖。 */
    fun writeWindowBrightness(value: Float)
}

/** 系统亮度来源（`Settings.System.SCREEN_BRIGHTNESS`，原始 0..255）。 */
internal fun interface SystemBrightnessSource {
    /** 读系统亮度原始值；读不到（权限/异常/缺失）返回 null。 */
    fun readSystemBrightnessRaw(): Int?
}

/**
 * 窗口亮度纯策略（A8）：捕获 / 恢复 / 当前百分比 / 写百分比。
 *
 * 四条不变量：
 * 1. [capture] 原样返回窗口现值，**包含 [BRIGHTNESS_NONE]**——"没有覆盖"本身就是
 *    需要还原的原值，不能当成"没有原值"而丢掉；
 * 2. [restore] 精确写回捕获值（NONE → NONE，override → 原 override），绝不再写 0.62；
 * 3. [currentPercent] 优先读窗口 override，其次读系统亮度，都读不到才用中性兜底；
 * 4. [setPercent] 先 clamp、再读平台现值、真的变了才写（逐帧竖滑不空转 binder）。
 */
internal class BrightnessPolicy(
    private val target: WindowBrightnessTarget,
    private val system: SystemBrightnessSource,
) {
    /** 捕获进入播放页前的窗口亮度（-1f = 无覆盖）。 */
    fun capture(): Float = target.readWindowBrightness()

    /**
     * 离开播放页时精确写回捕获值。
     *
     * 平台现值已经等于原值时跳过写入——避免无意义的 WindowManager 更新，
     * 但**不会**因为"原值是 NONE"就跳过：NONE 也要明确写回成 NONE，
     * 这样离开全屏/内嵌播放器后窗口不再残留本次的亮度覆盖。
     */
    fun restore(original: Float) {
        if (target.readWindowBrightness() != original) target.writeWindowBrightness(original)
    }

    /**
     * 当前亮度百分比。
     *
     * 有窗口覆盖（0.01..1）→ 覆盖值；否则读系统亮度（0..255 转百分比）；
     * 系统也读不到 → [BRIGHTNESS_FALLBACK_PERCENT]。
     * 任何路径都不会伪造 62%：HUD 第一帧与竖滑起手读的都是真实值。
     */
    fun currentPercent(): Int {
        val override = target.readWindowBrightness()
        if (override in 0.01f..1f) {
            return (override * 100f).roundToInt().coerceIn(BRIGHTNESS_MIN_PERCENT, BRIGHTNESS_MAX_PERCENT)
        }
        val raw = system.readSystemBrightnessRaw()
        if (raw != null && raw in 0..255) {
            return (raw / 255f * 100f).roundToInt().coerceIn(BRIGHTNESS_MIN_PERCENT, BRIGHTNESS_MAX_PERCENT)
        }
        return BRIGHTNESS_FALLBACK_PERCENT
    }

    /**
     * 写窗口亮度覆盖（百分比 clamp 到 1..100）。
     *
     * 返回 clamp 后实际生效的百分比，供 UI 状态对齐。
     * 窗口现值与目标相同的时候不写：逐帧指针事件里"没变"是常态。
     */
    fun setPercent(percent: Int): Int {
        val clamped = percent.coerceIn(BRIGHTNESS_MIN_PERCENT, BRIGHTNESS_MAX_PERCENT)
        val targetValue = clamped / 100f
        if (abs(target.readWindowBrightness() - targetValue) < 0.001f) return clamped
        target.writeWindowBrightness(targetValue)
        return clamped
    }
}
