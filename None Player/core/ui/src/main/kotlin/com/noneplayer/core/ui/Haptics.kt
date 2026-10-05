package com.noneplayer.core.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * None Player 触觉体系——语义到平台常量的唯一映射表。
 *
 * 规格《界面设计路线.md》§7.5 要的是**语义**（`Tap`/`Reject`），不是常量。
 * 组件只说"这是一次拒绝"，由这里决定它落在哪个 `HapticFeedbackConstants` 上、
 * 以及老系统上有没有对应的震动效果。
 *
 * 平台分岔（R14，minSdk 26 让这条分岔不是可选的）：`CONTEXT_CLICK`/`CLOCK_TICK`/
 * `SEGMENT_TICK` 这类语义常量是 API 31 才有的，而 31 之前同一段号位是**别的效果**
 * （`KEYBOARD_PRESS` 就改过号），所以必须按版本取用、低版本回 `VIRTUAL_KEY`，
 * 不能指望平台替我们忽略越界编号。`SEGMENT_TICK` 没有等价回退——档位感做不出来
 * 就**不出声**，不许拿单击顶替。
 *
 * `View.performHapticFeedback` 默认读全局设置：系统"关闭触摸反馈"时全静默
 * （§7.5 规则），这里不传 `FLAG_IGNORE_GLOBAL_SETTING` 去绕过它。
 */

/** 触觉语义。命名按**用户感知**，不按平台常量。 */
enum class NpHaptic {
    /** 常规点按（M1 卡片、M6 播放/暂停）。 */
    Tap,

    /** 轻点：一层出现/收起的回执（M3 迷你条）。 */
    LightTap,

    /** 刻度：切换、松手、触发（M4 Tab、M7 松手、M8 双击、M14 刷新）。 */
    Tick,

    /** 吸附档位（M11 Sheet detent）；API<31 静默。 */
    Segment,

    /** 按住（M7 拖动开始、M9 长按）。 */
    Long,

    /** 被拒绝：撞到边界、出错（M10 边界、M18 错误）。 */
    Reject,

    /** 危险操作确认（§5.5 `NpConfirm` 危险变体）。 */
    Warning,
}

/**
 * 触觉入口。用 [rememberNpHaptics] 取，不要自己 new。
 *
 * 与动效**同帧**触发（Apple §13）：本类在调用点立刻下发，不排程、不等动画。
 */
class NpHaptics internal constructor(
    private val view: View,
    private val quality: NpMotionQuality,
) {

    private var lastAtMs = 0L

    /** 下发一次语义触觉。性能档位关掉触觉、或系统关闭触摸反馈时静默。 */
    fun performHaptic(haptic: NpHaptic) {
        if (!quality.haptics) return
        val now = SystemClock.uptimeMillis()
        // **一次交互只响一次**：按钮自己给 `Tap`、它引起的层显隐又给 `LightTap`
        // 是必然出现的成对调用（§7.5「一屏一次以上即算滥用」）。同一时间窗内的
        // 第二次请求丢弃——M7/M9 那种"按下与松开各一次"相隔远超这个窗，不受影响。
        if (now - lastAtMs < minGapMs) return
        val id = resolveId(haptic)
        if (id == NO_HAPTIC) return
        lastAtMs = now
        view.performHapticFeedback(id)
    }

    /**
     * 拖动途中的档位触感：[NpHaptic.Segment] 的连续调用入口。
     *
     * 拖一条连续量每帧都在回调，这里靠 [minGapMs] 时间窗把"每帧一次"压成
     * "每 90ms 最多一次"，一次拖动才不会糊成一片震动。API<31 静默。
     */
    fun performHardwareTick() = performHaptic(NpHaptic.Segment)

    /**
     * §7.5 映射表落位。版本判定写成**外层闸门**：31 之前那批语义常量的号位
     * 与旧效果重叠，读错号位不是"没反应"而是"震错东西"。
     */
    private fun resolveId(haptic: NpHaptic): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) when (haptic) {
            NpHaptic.Tap -> HapticFeedbackConstants.KEYBOARD_TAP
            NpHaptic.LightTap -> HapticFeedbackConstants.CONTEXT_CLICK
            NpHaptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
            NpHaptic.Segment -> HapticFeedbackConstants.SEGMENT_TICK
            NpHaptic.Long -> HapticFeedbackConstants.LONG_PRESS
            NpHaptic.Reject -> HapticFeedbackConstants.REJECT
            // §7.5 写的 `VIBRATE_LONG` 不是公开常量（API 29 起即非 public）。
            // 公开常量里 `CONFIRM` 是唯一"比单击重、又不是拒绝"的形。
            NpHaptic.Warning -> HapticFeedbackConstants.CONFIRM
        } else when (haptic) {
            // 回退列：`LONG_PRESS` 与 `VIRTUAL_KEY` 是全版本安全形。
            // Segment 没有等价回退——宁可不出声。
            NpHaptic.Long -> HapticFeedbackConstants.LONG_PRESS
            NpHaptic.Segment -> NO_HAPTIC
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }

    internal companion object {
        /** 对齐 `HapticFeedbackConstants.NO_HAPTICS`：没有可用形时的记号。 */
        const val NO_HAPTIC = -1

        /** 两次触感的最小间隔（毫秒）；同时是 [performHardwareTick] 的节流窗。 */
        const val minGapMs = 90L
    }
}

/**
 * 当前视图的触觉入口。
 *
 * 读 [npQuality]：档位变化时重新 remember，`haptics` 开关才即时生效。
 */
@Composable
fun rememberNpHaptics(): NpHaptics {
    val view = LocalView.current
    val quality = npQuality()
    return remember(view, quality) { NpHaptics(view, quality) }
}
