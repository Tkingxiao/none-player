package com.noneplayer.core.ui

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
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
 * None Player 动效体系——曲线、时长令牌与 spring 换算的唯一事实源。
 *
 * 设计稿 `none-player.html` 给了三组 `cubic-bezier` 与一批毫秒数；Compose 用 spring，
 * 是两套真相的根源。本文件用 [silkStiffness] 把"时长令牌(ms)"换算成 spring 刚度，
 * 使设计稿的毫秒数与代码里的 spring 参数是**同一个真相**。
 *
 * 组件不许写裸 `tween(...)` / `CubicBezierEasing(...)` / 裸 ms——只能引用这里的曲线
 * 与 [Motion] 时长表。
 */

/**
 * 设计稿 `.np-ico` 与整份 CSS 里出现过的全部缓动曲线，一一对应。
 *
 * 设计稿变量名与这里的常量名不同（`--ease-out` / `--ease-in-out` / `--ease-drawer`），
 * 因为这里的命名描述的是**用途**：同一根曲线在进场、退场、抽屉里各用一次，
 * 按用途命名才不会被"换个动画就不知道选哪条"困住。
 */
object NpEasing {
    /** 设计稿 `--ease-out: cubic-bezier(.23,1,.32,1)`——进场、可交互反馈的主力曲线。 */
    val Silk: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** 设计稿 `--ease-in-out: cubic-bezier(.77,0,.175,1)`——双向切换（如折叠展开）。 */
    val SilkExit: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** 设计稿 `--ease-drawer: cubic-bezier(.32,.72,0,1)`——抽屉 / 面板侧向滑入。 */
    val Drawer: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 设计稿 `.switch i { cubic-bezier(.34,1.3,.6,1) }`——物理开关的轻微过冲。 */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.3f, 0.6f, 1f)

    /** 设计稿 `.sheet { cubic-bezier(.34,1.1,.5,1) }`——底部面板上滑。 */
    val Sheet: Easing = CubicBezierEasing(0.34f, 1.1f, 0.5f, 1f)

    /** 设计稿 `.tonearm { cubic-bezier(.34,1.3,.5,1) }`——唱臂落针。 */
    val Tonearm: Easing = CubicBezierEasing(0.34f, 1.3f, 0.5f, 1f)

    /** 强进场：只用于共享元素与主播放键。 */
    val Emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 小手势：chip、开关。 */
    val Snappy: Easing = CubicBezierEasing(0.2f, 0.82f, 0.18f, 1f)
}

/**
 * 时长令牌——唯一可写的毫秒数字。组件不许写裸 ms。
 *
 * 数值逐条取自设计稿：`.16s` 的按压反馈、`.18s` 的卡片位移、`.26s` 的侧栏、
 * `.32s` 的 Sheet 与主题插值、`.5s` 的唱臂。命名描述用途而非数值。
 *
 * 红线：任何 >480ms 的界面动效都需单独说明理由；用户可感知的"反馈延迟"必须 ≤150ms。
 */
object Motion {
    const val Hover = 100       // hover、图标态颜色（按压反馈走 Press）
    const val Press = 160       // 按压缩放（设计稿 --press: .16s）
    const val Field = 150       // 输入框聚焦、chip 展开
    const val Panel = 180       // 卡片位移、行 hover（设计稿 .18s）
    const val Switch = 220      // 开关滑块（设计稿 .22s 过冲）
    const val Drawer = 260       // 播放器侧栏滑入（设计稿 .26s）
    const val Page = 460        // 路由 push（退场 300）
    const val PageExit = 300
    const val Tab = 320         // Tab 选中（设计稿 .chip/.tabbar 的过渡段）
    const val Expand = 360      // 迷你条 ↔ 播放页
    const val Overlay = 480     // 播放控制层淡入（退场 340）
    const val OverlayExit = 340
    const val MiniPlayer = 320  // 迷你条显隐与圆角动画
    const val MiniProgress = 250 // 迷你条进度线（设计稿 .mini-prog width .25s linear）
    const val Scheme = 320      // 明暗主题插值（设计稿 .32s）
    const val Sheet = 320       // 底部面板上滑（设计稿 .32s）
    const val Tonearm = 500     // 唱臂落针（设计稿 .5s）
    const val Spin = 22000      // 唱片旋转一圈（设计稿 22s）
    const val Toast = 240       // Toast 出入场
    const val ToastHold = 1900  // Toast 停留（设计稿 setTimeout 1900）
    const val Stagger = 62      // 列表错峰入场步长（× index，6 项封顶）
}

/**
 * 把"时长令牌(ms)"换算成 spring 刚度：想要多快就给 [Motion] 里对应的值。
 *
 * 例：Hover 100→夹到 140→≈3265；Tab 380→≈443；Page 460→≈302。
 */
fun silkStiffness(durationMs: Int): Float {
    val clamped = durationMs.coerceAtLeast(140).toFloat()
    val k = 8000f / clamped
    return k * k
}

/**
 * Silk spring：固定 [Spring.DampingRatioNoBouncy]（不弹），刚度由时长令牌换算。
 *
 * 返回 [FiniteAnimationSpec] 而非宽泛的 [AnimationSpec]：这样它可以直接喂给
 * `fadeIn` / `slideInVertically` / `AnimatedContent.transitionSpec` 等要求有限时长的
 * API——否则调用点会因类型上界不足而编译失败。
 */
@Suppress("UNCHECKED_CAST")
fun <T> silkSpring(durationMs: Int): FiniteAnimationSpec<T> =
    spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = silkStiffness(durationMs),
    )

/** 轻微过冲 spring：仅用于开关 / 滑块 thumb，表达"物理开关"而非"电子开关"。 */
fun <T> bouncySpring(durationMs: Int): FiniteAnimationSpec<T> =
    spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = silkStiffness(durationMs),
    )

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

/** 错峰入场延迟：62ms × index，6 项封顶（上限 372ms）。仅用于首次进入。 */
fun staggerDelayMs(index: Int): Int = index.coerceAtMost(6) * Motion.Stagger

/**
 * Reduce Motion：系统动画时长缩放为 0 时，所有位移/缩放一律砍到 0（只保留 opacity）。
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
