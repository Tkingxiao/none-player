package com.noneplayer.core.ui

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * None Player 动效体系（界面设计路线 §6）——曲线、时长令牌与 spring 换算的唯一事实源。
 *
 * 设计稿给 ms、代码用 spring，是两套真相的根源。本文件用 [silkStiffness] 把"时长令牌(ms)"
 * 换算成 spring 刚度，使设计稿的毫秒数与代码里的 spring 参数是**同一个真相**。
 *
 * 组件不许写裸 `tween(...)` / `CubicBezierEasing(...)` / 裸 ms——只能引用这里的四条曲线
 * 与 [Motion] 时长表（§11.3 lint 会拦）。
 */

/** §3.9 唯一允许的四条缓动曲线。任何位移/缩放动画都必须引用其中之一。 */
object NpEasing {
    /** 默认进场：快起慢收，顺滑而不拖（ECHOAndroid 命名来源，参照源里出现最多）。 */
    val Silk: Easing = CubicBezierEasing(0.22f, 1.00f, 0.36f, 1.00f)

    /** 退场：先慢后快离场，不拖尾。 */
    val SilkExit: Easing = CubicBezierEasing(0.32f, 0f, 0.18f, 1f)

    /** 强进场：只用于共享元素与主播放键。 */
    val Emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 小手势：chip、开关。 */
    val Snappy: Easing = CubicBezierEasing(0.2f, 0.82f, 0.18f, 1f)
}

/**
 * §3.8 时长令牌——唯一可写的毫秒数字。组件不许写裸 ms。
 *
 * 红线：任何 >480ms 的界面动效都需单独说明理由；用户可感知的"反馈延迟"必须 ≤150ms
 * （[Hover] / [Field] 覆盖）。
 */
object Motion {
    const val Hover = 100       // 按压、hover、图标态
    const val Field = 150       // 输入框聚焦、chip 展开
    const val Panel = 160       // 面板高度、折叠
    const val Page = 460        // 路由 push（退场 300）
    const val PageExit = 300
    const val Tab = 380         // Tab 胶囊滑动
    const val Expand = 360      // 迷你条 ↔ 播放页
    const val Overlay = 480     // 播放控制层淡入（退场 340）
    const val OverlayExit = 340
    const val MiniPlayer = 320  // 迷你条显隐与圆角动画
    const val Scheme = 320      // 明暗主题插值
    const val Stagger = 62      // 列表错峰入场步长（× index，6 项封顶）
}

/**
 * 把"时长令牌(ms)"换算成 spring 刚度：想要多快就给 [Motion] 里对应的值。
 *
 * ECHOAndroid 的解法——让"设计稿 ms"和"代码 spring"成为同一个真相。
 * 例：Hover 100→夹到 140→≈3265；Tab 380→≈443；Page 460→≈302。
 */
fun silkStiffness(durationMs: Int): Float {
    val clamped = durationMs.coerceAtLeast(140).toFloat()
    val k = 8000f / clamped
    return k * k
}

/** Silk spring：固定 [Spring.DampingRatioNoBouncy]（不弹），刚度由时长令牌换算。 */
fun <T> silkSpring(durationMs: Int): AnimationSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = silkStiffness(durationMs))

/** 轻微过冲 spring：仅用于开关 / 滑块 thumb（§5.4 / §5.5），表达"物理开关"而非"电子开关"。 */
fun <T> bouncySpring(durationMs: Int): AnimationSpec<T> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = silkStiffness(durationMs))

@Composable
fun silkFloat(target: Float, durationMs: Int, label: String = "silkFloat"): State<Float> =
    animateFloatAsState(targetValue = target, animationSpec = silkSpring(durationMs), label = label)

@Composable
fun silkDp(target: Dp, durationMs: Int, label: String = "silkDp"): State<Dp> =
    animateDpAsState(targetValue = target, animationSpec = silkSpring(durationMs), label = label)

@Composable
fun silkOffset(target: IntOffset, durationMs: Int, label: String = "silkOffset"): State<IntOffset> =
    animateIntOffsetAsState(targetValue = target, animationSpec = silkSpring(durationMs), label = label)

/** DpSize 没有内置 VectorConverter，手动桥接到二维动画向量。 */
private val DpSizeConverter: TwoWayConverter<DpSize, AnimationVector2D> = TwoWayConverter(
    convertToVector = { AnimationVector2D(it.width.value, it.height.value) },
    convertFromVector = { DpSize(it.v1.dp, it.v2.dp) },
)

@Composable
fun silkSize(target: DpSize, durationMs: Int, label: String = "silkSize"): State<DpSize> =
    animateValueAsState(
        targetValue = target,
        typeConverter = DpSizeConverter,
        animationSpec = silkSpring(durationMs),
        label = label,
    )

/** §6.5 错峰入场延迟：62ms × index，6 项封顶（上限 372ms）。仅用于首次进入。 */
fun staggerDelayMs(index: Int): Int = index.coerceAtMost(6) * Motion.Stagger

/**
 * §6.6 Reduce Motion：系统动画时长缩放为 0 时，所有位移/缩放一律砍到 0（只保留 opacity）。
 * 返回 true 表示应禁用位移类动效。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
