package com.noneplayer.kernel.ui.api

import kotlin.math.abs

/**
 * 窗口档位**判定与翻转**的纯函数层。
 *
 * 把这两件事从 Android / Compose 里剥出来，是为了能在 JVM 单测里钉死两条真机踩过的路径：
 *
 * ① 类 PC 档的判据不得依赖 `WindowSizeClass`。它是 Material3 的断点表（构造器还是 private），
 * 与转屏回调拿到的 `Configuration.screenWidthDp/screenHeightDp` 不等价。旧写法让
 * `onConfigurationChanged`（硬编码 840/480/1.4）与外壳 `SideEffect`（Expanded + heightClass）
 * 各算一套，同一次物理旋转被判成**两次**档位变化：第一次带着帧起飞并把两侧登记清空，
 * 第二次反向交接抓不到帧、`beginFlight` 不成立、连 `mark()` 都不打，整页淡入淡出与右栏
 * 宽度动画被放回画面正在飞的那几百毫秒里——用户读到"转横屏直接全屏、退出瞬间跳回右栏"。
 *
 * ② "算不算一次形态变化"必须由**配置**决定，而不是由调用者身份决定。归约见 [npReduceFormFlip]：
 * 同一份窗口配置只允许翻转一次，第二条路径来打架时被拒。
 */

/**
 * 一次档位判定的全部输入。
 *
 * [displayRotation] 取 [android.view.Surface] 口径（0/1/2/3 = 0°/90°/180°/270°），
 * 由 [NpDevicePose.displayRotation] 提供；它和窗口尺寸一起唯一确定"同一份配置"。
 *
 * 公开是因为 [NpWindowInfo.formConfig] 把它带过了模块边界；判定与归约的**函数**仍然留在
 * 模块内（internal），外部只能拿到配置本身，不能各算一套。
 */
data class NpFormConfig(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val displayRotation: Int,
)

/** 类 PC 档的**唯一**判据：宽 ≥840dp、高 ≥480dp（手机横屏因此被排除）、横向、宽高比 > 1.4。 */
internal fun npDesktopLikeFromSize(screenWidthDp: Int, screenHeightDp: Int): Boolean {
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return false
    val aspect = screenWidthDp.toFloat() / screenHeightDp
    return screenWidthDp >= DESKTOP_LIKE_WIDTH_DP &&
        screenHeightDp >= DESKTOP_LIKE_HEIGHT_DP &&
        screenWidthDp > screenHeightDp &&
        aspect > DESKTOP_LIKE_ASPECT
}

/** [npDesktopLikeFromSize] 的配置版：档位判定只看尺寸，旋转角只参与"是不是同一份配置"。 */
internal fun npIsDesktopLike(config: NpFormConfig): Boolean =
    npDesktopLikeFromSize(config.screenWidthDp, config.screenHeightDp)

/**
 * 同一份窗口配置的比较。
 *
 * 容差是给两条上报路径留的：Activity 的 `newConfig` 与组合里的 `LocalConfiguration` 正常是
 * 同一个对象，但自由窗口/分屏下的舍入差到 1dp 量级；判成"两份配置"就等于把守卫关掉。
 */
internal fun npSameFormConfig(a: NpFormConfig, b: NpFormConfig, toleranceDp: Int = 2): Boolean =
    a.displayRotation == b.displayRotation &&
        abs(a.screenWidthDp - b.screenWidthDp) <= toleranceDp &&
        abs(a.screenHeightDp - b.screenHeightDp) <= toleranceDp

/** 档位翻转的可观测状态。 */
internal data class NpFormFlipState(
    val desktop: Boolean,
    /** 上一次**被接受**的翻转发生在哪份配置上；null = 还没翻过。 */
    val flippedAt: NpFormConfig? = null,
    /** 每次接受的翻转递增，供"同一轮只起飞一次"的判重使用。 */
    val generation: Int = 0,
)

/**
 * 翻转归约：返回新状态，以及"这次要不要当成一次形态变化来处理（交接）"。
 *
 * 两条否决：
 * · 档位没变 → 不是交接（同一档位会被反复上报，先判这条）；
 * · 档位变了，但**上一次被接受的翻转就在同一份配置上** → 也只可能是第二条上报路径
 *   （或两套判据自相矛盾），拒绝。真实的用户转屏必然带来新配置，不会被这条挡掉。
 */
internal fun npReduceFormFlip(
    state: NpFormFlipState,
    config: NpFormConfig,
): Pair<NpFormFlipState, Boolean> {
    val nowDesktop = npIsDesktopLike(config)
    if (nowDesktop == state.desktop) return state to false
    if (state.flippedAt?.let { npSameFormConfig(it, config) } == true) return state to false
    return state.copy(desktop = nowDesktop, flippedAt = config, generation = state.generation + 1) to true
}

/**
 * 交接时钟。生产实现是 [android.os.SystemClock.uptimeMillis]（与飞行 `startedAt` 同一口径），
 * 单测注入假时钟，才能不靠 `Thread.sleep` 覆盖"秒表还没过期 / 已经过期"两条分支。
 */
internal fun interface NpHandoffClock {
    fun now(): Long
}

/** [NpHandoffClock] 的生产实现。 */
internal val NpSystemHandoffClock = NpHandoffClock { android.os.SystemClock.uptimeMillis() }

/**
 * 交接与飞行**共用**的时钟。
 *
 * 只留一个注入点：飞行 `startedAt` 与静音窗必须同口径（`uptimeMillis`），
 * 两边各读各的时钟会让"这一轮飞行还在不在"的判断随深度睡眠漂移。
 * 行为级单测把 [clock] 换成假时钟，就能不靠 `Thread.sleep` 覆盖到期/未到期两条分支。
 */
internal object NpHandoffTime {
    var clock: NpHandoffClock = NpSystemHandoffClock
    fun now(): Long = clock.now()
}

/** 静音窗的全部输入——只含可观测快照，不含系统时钟。 */
internal data class NpHandoffGate(
    /** 幽灵帧是否还在飞（含 [ceilingMs] 天花板）。 */
    val flightActive: Boolean,
    /** 这一轮飞行已经持续多久；没有飞行时为 [Long.MAX_VALUE]。 */
    val flightAgeMs: Long,
    /** 秒表还剩多久；`<= 0` 表示已失效。 */
    val silentRemainingMs: Long,
    /**
     * 这一次 `mark()` 是否属于某轮飞行。绑定过就不许在飞行结束后继续静音——
     * 否则秒表会把手工翻页的转场也一起吞掉（最长 [silentRemainingMs]）。
     */
    val silentBoundToFlight: Boolean,
    val ceilingMs: Long,
)

/**
 * 转场静音判据：**以飞行快照为准，秒表只兜没有画面可飞的形态变化**。
 *
 * 三条分支：
 * · 在飞 → 静音（直到飞行结束或撞 [ceilingMs] 天花板，防异常滞留）；
 * · 不在飞、且秒表绑定的那轮飞行已经结束 → 放开。这就是"秒表没过期但 flight 已 null"
 *   那条误触发路径，旧实现会在这里继续吃掉整页转场；
 * · 不在飞、且本来就没有飞行可绑（抓不到帧的形态变化）→ 尊重秒表，只压掉交叉淡入。
 */
internal fun npHandoffSilent(gate: NpHandoffGate): Boolean {
    if (gate.flightActive) return gate.flightAgeMs < gate.ceilingMs
    if (gate.silentBoundToFlight) return false
    return gate.silentRemainingMs > 0L
}

/** 类 PC 档判据的三个阈值（§13.2 / §13.4）。 */
private const val DESKTOP_LIKE_WIDTH_DP = 840
private const val DESKTOP_LIKE_HEIGHT_DP = 480
internal const val DESKTOP_LIKE_ASPECT = 1.4f
