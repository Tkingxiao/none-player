package com.noneplayer.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * None Player 动效体系——曲线、时长令牌与 spring 换算的唯一事实源。
 *
 * 设计稿 `none-player.html` 给了三组 `cubic-bezier` 与一批毫秒数；Compose 用 spring，
 * 是两套真相的根源。本文件用 [silkStiffness] 把"时长令牌(ms)"换算成 spring 刚度，
 * 使设计稿的毫秒数与代码里的 spring 参数是**同一个真相**。
 *
 * 组件不许写裸 `tween(...)` / `CubicBezierEasing(...)` / 裸 ms——只能引用这里的曲线
 * 与 [Motion] 时长表。
 *
 * 规格：《设计文档.md》§3.8/§3.9（曲线与令牌）、§9.3（Reduce Motion）、
 * §9.4（播放中降一档）、§9.6（三档性能模式 [NpMotionQuality]）。
 */

/**
 * 设计稿 `.np-ico` 与整份 CSS 里出现过的缓动曲线，按**用途**命名。
 *
 * 设计稿变量名与这里的常量名不同（`--ease-out` / `--ease-in-out` / `--ease-drawer`），
 * 因为同一根曲线在进场、退场、抽屉里各用一次，按用途命名才不会被"换个动画就不知道选哪条"困住。
 *
 * **E119 清了零调用点**（§14 #2 的口径）：设计稿另有 `--ease-drawer`(.32,.72,0,1)、
 * `.switch i`(.34,1.3,.6,1)、`.tonearm`(.34,1.3,.5,1) 三根，施工期它们的天然消费方全部
 * 改成了 spring——抽屉收尾用 `silkSpring(Motion.Drawer)`、开关用 `bouncySpring(Motion.Switch)`、
 * 唱臂落针用 bouncy spring。三条曲线自落地起 0 调用点，故删除而不是"留着以后用"：
 * 留着就会有人拿它凑数，而 spring 与贝塞尔的差别是手感级的，换回去是可感知的回归。
 */
object NpEasing {
    /** 设计稿 `--ease-out: cubic-bezier(.23,1,.32,1)`——进场、可交互反馈的主力曲线。 */
    val Silk: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** 设计稿 `--ease-in-out: cubic-bezier(.77,0,.175,1)`——双向切换（如折叠展开）。 */
    val SilkExit: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** 设计稿 `.sheet { cubic-bezier(.34,1.1,.5,1) }`——底部面板上滑。 */
    val Sheet: Easing = CubicBezierEasing(0.34f, 1.1f, 0.5f, 1f)

    /** 强进场：只用于共享元素与主播放键。 */
    val Emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 小手势：chip、开关。 */
    val Snappy: Easing = CubicBezierEasing(0.2f, 0.82f, 0.18f, 1f)

    /**
     * 整屏 morph（横竖屏形态切换）的**进出对称**曲线：`cubic-bezier(.6,0,.4,1)`。
     *
     * 为什么这里不用 [Silk]（参考实现用的就是它）：Silk 的 87% 行程都在前 30% 时间里，
     * 480ms 的飞行里真正"看得见在动"的只有开头约 150ms——用户两次实报"太快了 / 像闪现"
     * 都是它。转屏是**整屏重排**，不是一次进场反馈：进出对称才读得出"每一块都在移动"，
     * 也才受得住把时长往上调。
     */
    val Morph: Easing = CubicBezierEasing(0.6f, 0f, 0.4f, 1f)
}

/**
 * 时长令牌——唯一可写的毫秒数字。组件不许写裸 ms。
 *
 * 数值逐条取自设计稿：`.16s` 的按压反馈、`.18s` 的卡片位移、`.26s` 的侧栏、
 * `.32s` 的 Sheet 与主题插值、`.5s` 的唱臂。命名描述用途而非数值。
 *
 * 红线（《设计文档》§3.9）：反馈类 ≤150ms；转场类 ≤460ms；错峰总长 ≤372ms；
 * 除 `Tonearm`/`Spin` 这两个装饰性周期外，不许有 >480ms 的界面动效。
 */
object Motion {
    const val Hover = 100       // hover、图标态颜色（按压反馈走 Press）
    const val Press = 160       // 按压缩放（设计稿 --press: .16s）

    /**
     * 短时反馈档。名字叫 Field 是历史遗留——现网 20 个调用点里它主要用在
     * **chip 展开、锚定菜单、图标切换**上，与输入框无关。语义是"比 Panel 更急的
     * 一次性状态变化"，选档看时长不看名字（《设计文档》§3.9）。
     */
    const val Field = 150
    const val Panel = 180       // 卡片位移、行 hover、迷你条显隐（设计稿 .18s）
    const val Switch = 220      // 开关滑块（设计稿 .22s 过冲）
    const val Drawer = 260      // 播放器侧栏 / 右抽屉滑入（设计稿 .26s）
    const val Tab = 220         // Tab 选中 + 内容淡移（批次 5 由 320 收下来：选中态是反馈，不是转场）
    const val Overlay = 280     // 控制层进场（批次 5 由 480 改小：480ms 会让用户以为"卡了一下"）
    const val OverlayExit = 220 // 控制层退场（原 340；退场要"加速离开"，比进场更短）
    const val MiniProgress = 250 // 迷你条进度线（设计稿 .mini-prog width .25s linear）
    const val Scheme = 320      // 明暗主题插值（设计稿 .32s）
    const val Sheet = 320       // 底部面板上滑（设计稿 .32s）
    const val Tonearm = 500     // 唱臂落针（设计稿 .5s）
    const val Spin = 22000      // 唱片旋转一圈（设计稿 22s）
    const val Shimmer = 1200    // 骨架屏流光一圈（M13；§3.8 允许 LinearEasing 的四类之一）
    const val Toast = 240       // Toast 出入场
    const val ToastHold = 1900  // Toast 停留（设计稿 setTimeout 1900）
    const val Stagger = 62      // 列表错峰入场步长（× index，6 项封顶）

    /**
     * 纯淡入淡出的统一时长（§9.3：≤120ms）。
     *
     * 两处语义其实是同一条规则：**没有位移的短退场**——Reduce Motion / 轻量档下位移退化成 fade 用它，
     * 缓冲指示这类"只淡不挪"的小件退场也用它（M22 的 out 120），不再另开令牌。
     */
    const val ReduceFade = 120

    // 路由转场（批次 5 新增；取代已删除的 Page / PageExit，并把壳层三处裸 tween 收编进来）
    const val NavEnter = 280    // push 进场
    const val NavFade = 240     // 交叉淡入
    const val NavExit = 180     // pop / 退场

    // 装饰（批次 5 新增）
    const val Bubble = 500      // 中央播放键水波（High 档装饰，见 §9.6）

    // M2 共享封面（批次 5 唯一允许顺延项，2026-10-08 落地）
    const val Shared = 300      // 卡片 → 播放页共享封面（曲线只用 Emphasized）
    const val AccentShift = 400 // 封面主色切集过渡

    /**
     * 横竖屏整屏 morph（E133）：平板旋转时，画面、标题、按钮、选集行与封面在同一段里
     * 各自飞到落点。480ms 取自参考实现 `player-orientation-sim.html` 的 `FLIGHT`，
     * 曲线用 `NpEasing.Silk`（那份实现里的 `silk` 与设计稿 `--ease-out` 是同一条）。
     */
    const val FormMorph = 560
}

/**
 * 把"时长令牌(ms)"换算成 spring 刚度：想要多快就给 [Motion] 里对应的值。
 *
 * 例：Hover 100→夹到 140→≈3265；Overlay 280→≈816；Sheet 320→≈625。
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
 *
 * **`internal` 是有意的**（E119）：它收的是**裸毫秒**，不吃 [npDuration] 的档位倍率、
 * 也不看 Reduce Motion。此前 24 处组件写的是 `silkSpring(Motion.Hover)`——
 * 令牌数字进了管道、却没经过管道，"降到轻量档 / 系统移除动画"对那 24 处完全无效。
 * 现在跨模块只能走 [npMotion]（或 `silkFloat/silkDp/silkOffset/silkSize`），
 * 由编译器而不是注释来保证"唯一入口"。模块内确需在**非组合上下文**（协程、
 * 手势收尾）里造 spring 的，先 `npDuration()` 取值再喂进来——见 `NpSheet` 的拖拽收尾。
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> silkSpring(durationMs: Int): FiniteAnimationSpec<T> =
    spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = silkStiffness(durationMs),
    )

/**
 * 轻微过冲 spring：仅用于开关 / 滑块 thumb，表达"物理开关"而非"电子开关"。
 *
 * 同样 `internal`，理由见 [silkSpring]。
 */
internal fun <T> bouncySpring(durationMs: Int): FiniteAnimationSpec<T> =
    spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = silkStiffness(durationMs),
    )

// ================= 三档性能模式（§9.6 / R13） =================

/**
 * 性能档位——**倍率与开关**的集合，不是令牌的替代品。
 *
 * 用户可见设置项名为「性能模式」（设置 → 外观与关于，三档「轻量 / 平衡 / 全效」）：
 * 它管的不止动效，还有图像模糊、阴影高度、波形密度、封面取色这些非动效项，
 * 所以不叫"动效级别"（R13 命名理由见《设计文档》§2）。
 *
 * 红线（§9.6）：本类**只**改变时长倍率与装饰开关，**不得**改变任何令牌的语义——
 * `Motion.Overlay` 在三个档位里都还是"控制层进场"。
 * backdrop-blur 不在这里出现：R1 全局禁止，三档恒为 0dp，没有可开关的余地。
 *
 * @param id 持久化标识（设置页写入 DataStore 的字符串）
 * @param level 档位序（0 轻量 / 1 平衡 / 2 全效），用于"取低档"比较
 * @param durationFactor 时长倍率；小于 1 时结果夹进 [lightDurationFloorMs, lightDurationCeilMs]
 * @param displacementAsFade 位移/缩放类动效是否整体退化为纯 fade（轻量档 true）
 * @param imageBlurRadiusDp 音频模式底的图像高斯半径（R1 唯一的模糊允许点，§9.5）
 * @param imageBlurDownsample 模糊前的降采样倍数（成本 ≈ 1/n²；轻量档 0 → 走 8×8 马赛克近似）
 * @param shadowFactor `npShadow` 的 elevation 倍率
 * @param waveformBars 装饰波形 bar 根数（§5.6）
 * @param haptics 触觉开关——三档都为 true，且 §9.4 降档**不**关它：
 *   触觉不耗电，且它是"动效被削掉之后剩下的那条反馈兜底"。
 */
data class NpMotionQuality(
    val id: String,
    val level: Int,
    val durationFactor: Float,
    val displacementAsFade: Boolean,
    val imageBlurRadiusDp: Float,
    val imageBlurDownsample: Int,
    val shadowFactor: Float,
    val waveformBars: Int,
    val stagger: Boolean,
    val shimmer: Boolean,
    val sharedElement: Boolean,
    val centerBubble: Boolean,
    val tonearm: Boolean,
    val accentGradient: Boolean,
    val emptyStroke: Boolean,
    val haptics: Boolean,
) {

    /** 把令牌时长折算成本档位下的实际时长（毫秒）——所有动画时长只经这一个出口。 */
    fun scaled(durationMs: Int): Int {
        if (durationFactor >= 1f) return durationMs
        // 轻量档：×0.20 之后夹进 [45,120]ms——下限保证"还看得见"，上限保证"不再拖沓"。
        return (durationMs * durationFactor).roundToInt()
            .coerceIn(lightDurationFloorMs, lightDurationCeilMs)
    }

    /**
     * §9.4 播放中降一档：**只降装饰类列**，反馈类（M1/M3/M5/M7）与触觉一律不动。
     *
     * 倍率与位移开关不在这里改——把按压反馈也削成 45ms 会被感知成"卡"，
     * 而"卡"和"省电"是两回事。
     */
    fun forPlayback(): NpMotionQuality = copy(
        stagger = false,
        shimmer = false,
        centerBubble = false,
        waveformBars = Lightweight.waveformBars,
    )

    companion object {
        const val lightDurationFloorMs = 45
        const val lightDurationCeilMs = 120

        /** 轻量：老机型 / 低内存。倍率压到 0.20，位移全退成 fade，模糊与装饰全关。 */
        val Lightweight = NpMotionQuality(
            id = "lightweight",
            level = 0,
            durationFactor = 0.20f,
            displacementAsFade = true,
            imageBlurRadiusDp = 0f,
            imageBlurDownsample = 8,
            shadowFactor = 0.5f,
            waveformBars = 28,
            stagger = false,
            shimmer = false,
            sharedElement = false,
            centerBubble = false,
            tonearm = false,
            accentGradient = false,
            emptyStroke = false,
            haptics = true,
        )

        /** 平衡（默认）：全量动效，但中央水波与渐变叠加这类"高开销装饰"仍不开。 */
        val Balanced = NpMotionQuality(
            id = "balanced",
            level = 1,
            durationFactor = 1f,
            displacementAsFade = false,
            imageBlurRadiusDp = 24f,
            imageBlurDownsample = 2,
            shadowFactor = 1f,
            waveformBars = 44,
            stagger = true,
            shimmer = true,
            sharedElement = true,
            centerBubble = false,
            tonearm = true,
            accentGradient = false,
            emptyStroke = true,
            haptics = true,
        )

        /** 全效：需要用户显式选择（自动判定只在桌面形态 / 无解码时给到这一档）。 */
        val High = NpMotionQuality(
            id = "high",
            level = 2,
            durationFactor = 1f,
            displacementAsFade = false,
            imageBlurRadiusDp = 60f,
            imageBlurDownsample = 4,
            shadowFactor = 1f,
            waveformBars = 56,
            stagger = true,
            shimmer = true,
            sharedElement = true,
            centerBubble = true,
            tonearm = true,
            accentGradient = true,
            emptyStroke = true,
            haptics = true,
        )

        private val ordered by lazy { listOf(Lightweight, Balanced, High) }

        /** 档位序 → 实例（设置页存的是 0/1/2，读回时越界夹回默认档）。 */
        fun fromLevel(level: Int): NpMotionQuality = ordered[level.coerceIn(0, ordered.lastIndex)]

        /**
         * 生效档 = 参与比较的各档里的**低者**。
         *
         * 这是"设置覆盖 > 自动判定"与"默认平衡"之间的落点：用户显式选的那档是
         * **上限**，自动判定与设备实况只能往下压（见 §9.6 的施工期澄清）。
         */
        fun lowerOf(a: NpMotionQuality, b: NpMotionQuality): NpMotionQuality =
            if (a.level <= b.level) a else b
    }
}

/** 当前生效的性能档位，由 [NonePlayerTheme] 单点算好下发（组件不许自己判设备）。 */
val LocalNpMotionQuality: ProvidableCompositionLocal<NpMotionQuality> =
    staticCompositionLocalOf { NpMotionQuality.Balanced }

@Composable
fun npQuality(): NpMotionQuality = LocalNpMotionQuality.current

/** 本档位下的实际时长：令牌值 × 倍率（轻量档再夹 [45,120]）。 */
@Composable
fun npDuration(durationMs: Int): Int = npQuality().scaled(durationMs)

/**
 * 位移/缩放类动效当前是否允许。
 *
 * Reduce Motion 与轻量档都返回 false——**9.3 优先于 9.6**，两者任一生效即退化为 fade。
 * 它是 @Composable，而 `slideInVertically` 的 lambda 在布局期执行、读不到组合上下文，
 * 所以调用点必须先在组合期 hoist 一个值再用：
 * `val slideAllowed = npDisplacementAllowed()` → `slideInVertically(spec) { if (slideAllowed) it else 0 }`。
 * 位移量为 0 时只剩淡入淡出，正是规格要的"更少更柔，不是零反馈"。
 */
@Composable
fun npDisplacementAllowed(): Boolean =
    !LocalNpReduceMotion.current && !npQuality().displacementAsFade

/**
 * 确定性淡入淡出时长：`tween` 而不是 spring——退化路径不该带过冲。
 *
 * 命名里的 "OrFade" 说的是**用途**：Reduce Motion / 轻量档下所有位移动画改走这里，
 * 由调用点把位移量归零，只留 alpha。
 */
fun <T> tweenOrFade(durationMs: Int): FiniteAnimationSpec<T> =
    tween(durationMs, easing = NpEasing.Silk)

/**
 * 动画规格的**唯一入口**：令牌时长 → 档位倍率 → Reduce Motion 覆盖。
 *
 * 组件一律走它（或下面的 `silkFloat/silkDp/silkOffset/silkSize`，它们内部已接好），
 * 这样"改一根令牌的数值"在全应用只有一处真相，而"降到轻量档 / 系统移除动画"
 * 不需要碰任何组件就能全局生效。
 *
 * 默认给 spring（Silk 手感）。规格里点名**贝塞尔曲线**的场合——§5.5 锚定菜单的
 * `Snappy`、§7.2 路由的 `Silk`/`SilkExit`——传 [easing] 换确定性 tween，
 * 这样走曲线的动画同样吃档位倍率和 Reduce Motion，不再是令牌管道的漏网点。
 */
@Composable
fun <T> npMotion(durationMs: Int, bouncy: Boolean = false, easing: Easing? = null): FiniteAnimationSpec<T> {
    val scaled = npDuration(durationMs)
    if (LocalNpReduceMotion.current) return tweenOrFade(npDuration(Motion.ReduceFade))
    if (easing != null) return tween(scaled, easing = easing)
    return if (bouncy) bouncySpring(scaled) else silkSpring(scaled)
}

@Composable
fun silkFloat(target: Float, durationMs: Int, label: String = "silkFloat"): State<Float> =
    animateFloatAsState(targetValue = target, animationSpec = npMotion(durationMs), label = label)

@Composable
fun silkDp(target: Dp, durationMs: Int, label: String = "silkDp"): State<Dp> =
    animateDpAsState(targetValue = target, animationSpec = npMotion(durationMs), label = label)

@Composable
fun silkOffset(target: IntOffset, durationMs: Int, label: String = "silkOffset"): State<IntOffset> =
    animateIntOffsetAsState(targetValue = target, animationSpec = npMotion(durationMs), label = label)

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
        animationSpec = npMotion(durationMs),
        label = label,
    )

/** §3.10 红线 4：错峰总时长 ≤ `6 × 62 = 372ms`，第 7 项起与第 6 项同时入场。 */
private const val StaggerMaxItems = 6

/** 错峰入场延迟：62ms × index，6 项封顶（上限 372ms）。仅用于首次进入。 */
fun staggerDelayMs(index: Int): Int = index.coerceAtMost(StaggerMaxItems) * Motion.Stagger

/** 分档后的错峰延迟：轻量档 / 播放中降档返回 0（整组一次淡入，§9.6 stagger 列）。 */
@Composable
fun npStaggerDelay(index: Int): Int = if (npQuality().stagger) staggerDelayMs(index) else 0

/**
 * M24 列表项增删的淡入淡出规格：`Switch`(220)。
 *
 * `Modifier.animateItem()` 不带参数时**只做位移**——删除的那一行是"瞬间消失"的，
 * 读起来像列表抖了一下。fade 补上后才是"这一条被拿走了，剩下的补位"。
 */
@Composable
fun npItemFadeSpec(): DurationBasedAnimationSpec<Float> =
    tween(npDuration(Motion.Switch), easing = NpEasing.Silk)

/**
 * 错峰组的**首屏窗口**截止时刻（`SystemClock.uptimeMillis`），在 Lazy 容器**外层**取一次。
 *
 * 窗口 = 封顶延迟（`Stagger × 6` = 372ms）+ 单项时长（`Switch`）。窗口之后才进入组合的
 * 项是滚动带进视野的，不该再错峰——§3.10 红线 4 写的是"仅首屏，不阻塞交互"，
 * 让每张滚进来的卡片先等 372ms 才出现，用户读到的是"卡片总是慢半拍"。
 *
 * [key] 变化时重新起窗：搜索页把 query 传进来，每次新结果集都值得再错峰入场一次。
 */
@Composable
fun npStaggerWindow(key: Any? = Unit): Long = remember(key) { staggerWindowFor(key) }

/**
 * 错峰窗口的**进程级登记**：同一个 [key] 的"首屏入场"只认**第一次**。
 *
 * 之前窗口是每次组合现算的，于是**切 Tab / 进出页面**拿到的是全新窗口——整页卡片
 * 每次都重播一遍入场（淡入 + 12dp 上移），用户看到的就是"每切一次界面闪一帧空模板"
 * （2026-10-08 用户实报；同页复用直接给终态是本项目在 M15 空态上已经立过的规矩，
 * 错峰窗口此前没照做）。
 *
 * 窗口只增不删是有意的：key 就那几个（版式 / 查询词 / 目录路径），不为省几十字节
 * 引入淘汰逻辑与它自己的边界情况。
 */
private val staggerWindows = mutableMapOf<Any?, Long>()

private fun staggerWindowFor(key: Any?): Long = synchronized(staggerWindows) {
    staggerWindows.getOrPut(key) {
        SystemClock.uptimeMillis() + Motion.Stagger * StaggerMaxItems + Motion.Switch
    }
}

/**
 * M12 网格/列表入场：第 [index] 项延迟 `Stagger×min(index,6)`，从下方 12dp 上移并淡入，
 * 每项时长 `Switch`(220)。
 *
 * Lazy 列表里传 [window]（容器外层 [npStaggerWindow] 取一次），并给项配稳定 key——
 * 否则同一项滚出再滚回会重播一次入场，列表看起来在跳舞。
 * 非 Lazy 的一小组（chips、按钮组）不传 window，整组按首屏错峰。
 * 轻量档 / Reduce Motion 下延迟归零、位移归零，退化成整组短淡入——
 * 反馈还在，但不再占用主线程做逐帧位移。
 */
@Composable
fun Modifier.npStaggeredEntry(index: Int, window: Long = 0L): Modifier {
    val firstScreen = window == 0L || SystemClock.uptimeMillis() <= window
    // E128：首屏窗口过期后**直接短路**。此前这里仍会给每项建三个状态（`shown` 的
    // remember、`animateFloatAsState`、`LaunchedEffect`）再得到一个恒为终态的结果——
    // 列表滚动时每一项进入组合都要付这笔账，而按 §3.10 红线 4（错峰仅首屏），
    // 滚动带进来的项本来就不该播入场动画。短路后：滚动帧里只有"普通内容"。
    if (!firstScreen) return this
    val delayMs = npStaggerDelay(index)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        shown = true
    }
    val shift = if (npDisplacementAllowed()) 12.dp else 0.dp
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = npMotion(Motion.Switch),
        label = "npStaggerEntry",
    )
    // 位移和透明度读在绘制层：逐帧只重绘，不重组整张卡片。
    // `toPx()` 也放在这里——GraphicsLayerScope 自己就是 Density，组合期不需要再取一个。
    return this.then(
        Modifier.graphicsLayer {
            alpha = progress.coerceIn(0f, 1f)
            translationY = (1f - progress) * shift.toPx()
        },
    )
}

/**
 * Reduce Motion：系统动画时长缩放为 0 时，所有位移/缩放一律砍到 0（只保留 opacity）。
 * 返回 true 表示应禁用位移类动效。
 *
 * **值是全局的、每帧不变的**，所以由 [NonePlayerTheme] 读取一次经
 * [LocalNpReduceMotion] 下发，而不是让每个卡片各自 `remember` 一次
 * （此前每次调用都要 `ContentResolver` 读一遍系统设置——列表项滚出屏幕再滚回来
 * 会重新组合、重新读一次，是滚动期一笔纯粹浪费的开销）。
 */
val LocalNpReduceMotion: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean = LocalNpReduceMotion.current

/** 读一次系统"动画时长缩放"，供 [NonePlayerTheme] 下发（仅此一处读系统设置）。 */
fun readSystemReduceMotion(context: android.content.Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}

/**
 * 自动判档（§9.6）：设备实况能跑多花的动效。**只在用户没显式选档时决定基线**，
 * 且经 [NpMotionQuality.lowerOf] 与用户档位取低者——自动判定永远只能往下压，
 * 不能把"轻量"设备上用户选的"全效"抬上去。
 *
 * 三条判据逐字照规格：
 * - 低内存机或 Android 10 以下 → 轻量；
 * - Android 12+ 且非低内存且（当前无视频解码 或 桌面形态）→ 全效；
 * - 其余 → 平衡。
 *
 * `decodingVideo` 是"此刻有没有在解视频"的一等输入而不是可有可无的装饰：解码期
 * 加一层 60dp 高斯就是掉帧的第一现场。桌面形态（外接键鼠的大屏）例外——那里没有
 * 列表滚动的 jank 预算压力，用户看得见也受得住开销。
 */
fun autoMotionQuality(
    context: Context,
    isDesktopLike: Boolean,
    decodingVideo: Boolean,
): NpMotionQuality {
    val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice == true
    return autoMotionQualityFor(
        lowRam = lowRam,
        sdkInt = Build.VERSION.SDK_INT,
        isDesktopLike = isDesktopLike,
        decodingVideo = decodingVideo,
    )
}

/**
 * [autoMotionQuality] 的判据本体——四个输入全是值、不碰 Android 框架，因此可在
 * JVM 单测里逐条覆盖 §9.6 的三条分支（§14 第 22 条的"性能档位判据"）。
 */
fun autoMotionQualityFor(
    lowRam: Boolean,
    sdkInt: Int,
    isDesktopLike: Boolean,
    decodingVideo: Boolean,
): NpMotionQuality = when {
    lowRam || sdkInt < 29 -> NpMotionQuality.Lightweight
    sdkInt >= 31 && (!decodingVideo || isDesktopLike) -> NpMotionQuality.High
    else -> NpMotionQuality.Balanced
}
