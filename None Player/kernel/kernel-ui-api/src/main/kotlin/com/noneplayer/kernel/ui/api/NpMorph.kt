package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration

/**
 * 形态切换（横屏三栏 ↔ 竖屏播放页）的**整屏 morph**。
 *
 * 参考实现 `player-orientation-sim.html` 的做法：换版式与"东西飞到位"是**同一段**——
 * 每个 actor 从旧版式里它所在的位置（按容器转过的角折进新坐标系）插值到新版式里自己的
 * 落点，位置与尺寸同步，形状不变。这里照做，只是把"容器"换成真实窗口：
 *
 * · **起点**：旧窗口里同名部件的矩形，按这一下**显示旋转角**刚体转进新窗口
 *   （[rotateIntoWindow]）。不是"按窗口归一化位置搬"——那个映射会把竖屏顶部满宽的画面
 *   搬到横屏窗口正中央，读起来正是用户实报的"先全屏、再跳成右侧栏"。
 * · **落点**：新版式里同名部件自己的矩形。位置与尺寸都只是"相对落点的偏移"，
 *   所以右栏宽度即便晚几帧才稳，已经在飞的部件也不会被顶跳。
 * · **节拍**：新形态的几何**量到之后**才开始补间（[geometryReady]）。转屏那一轮窗口
 *   在几百毫秒里不出新版式的帧，若从 `onConfigurationChanged` 起算，480ms 会在
 *   "用户还看不见"的时候走完——真机录屏逐帧核对到的就是"完全没有动画、直接换版式"。
 *
 * 用法：两侧同一部件用**同一个 key**（[NpMorphKey]）挂 [npMorphPart]，并声明自己属于
 * 哪个形态（`desktop`）。节拍器是 [NpMorphDriver]，挂在窗口原点级的宿主里。
 */
object NpMorph {

    /** 当前形态里每个部件自己的矩形（窗口坐标）。节点每次布局刷新。 */
    private var current by mutableStateOf<Map<String, Rect>>(emptyMap())

    /** 上一形态的矩形快照，[begin] 时冻结；飞行结束清空。 */
    private var previous by mutableStateOf<Map<String, Rect>>(emptyMap())

    /** [previous] 那批矩形所在的窗口像素尺寸——搬进新窗口要用。 */
    private var previousWindow by mutableStateOf(IntSize.Zero)

    /** 本形态的窗口尺寸，由宿主每次布局登记。 */
    private var windowSize by mutableStateOf(IntSize.Zero)

    /** 本轮的目标形态（true = 类 PC 横屏）。过渡期间只认这一形态的登记。 */
    private var targetDesktop by mutableStateOf(false)

    /** 本轮旧窗口的内容是**顺时针**转了多少度进新窗口（+90 / -90 / 180 / 0）。 */
    var turnDegrees by mutableStateOf(0)
        private set

    /** 每一轮 morph 递增：驱动者以它为 key。 */
    var generation by mutableIntStateOf(0)
        private set

    /** 0 = 还在旧位置，1 = 已落位。不在飞时为 1。 */
    var progress by mutableFloatStateOf(1f)
        private set

    /**
     * 新形态的几何（窗口尺寸 + 各部件的落点）是否已经量到。
     *
     * 这是补间的**开始闸门**：几何没量到就不许动，否则动画会在"新窗口还没排好"的
     * 那几帧里空跑完。
     */
    var geometryReady by mutableStateOf(false)
        private set

    /** 起飞时刻（[NpHandoffTime]，与静音窗同一口径），用于给"演不完"兜底。 */
    internal var startedAtMs = 0L
        private set

    /**
     * 在飞 = 有部件该画在旧位置上。整页转场与右栏宽度动画都要为此让路。
     *
     * 读 [progress] 所以可被组合观测；再加一道天花板：驱动者若被中途拆掉，
     * 不能让 `isFlying` 永远压着转场。
     */
    val isFlying: Boolean get() = progress < 0.999f &&
        NpHandoffTime.now() - startedAtMs < FLIGHT_CEILING_MS

    /**
     * 位移是否允许（Reduce Motion / 轻量档为 false）。
     *
     * 由 [NpMorphDriver] 在组合期同步进来，好让 [npMorphPart] 保持**非组合**函数——
     * 修饰符链里读不到 CompositionLocal。
     */
    var displacementAllowed by mutableStateOf(true)

    /**
     * 这次登记要不要收下。
     *
     * 过渡那几帧里新旧两棵树可能同时在场（横转竖时左栏要留到播放页盖上来为止），
     * 旧树在新窗口里量出来的矩形**不是落点**，收下就会把落点顶掉。
     * `desktop == null` 表示"任何形态都收"（不参与交接的部件）。
     */
    private fun accepts(desktop: Boolean?): Boolean =
        desktop == null || !isFlying || desktop == targetDesktop

    fun recordWindow(size: IntSize, desktop: Boolean? = null) {
        if (size.width <= 0 || size.height <= 0) return
        if (!accepts(desktop)) return
        if (windowSize != size) windowSize = size
        if (!geometryReady) geometryReady = true
    }

    fun record(key: String, rect: Rect, desktop: Boolean? = null) {
        if (rect.width <= 0f || rect.height <= 0f) return
        if (!accepts(desktop)) return
        if (current[key] != rect) current = current + (key to rect)
    }

    /**
     * 起飞：把当前这批矩形冻成"上一形态"。
     *
     * 必须在**换版式之前**调用（`NpFormHandoff` 的档位翻转里）——晚一步登记上来的
     * 就是新形态的矩形，旧位置再也拿不到，morph 退化成"什么都不动"。
     *
     * [desktop] 是**目标**形态；[turn] 是这一下显示转过的角度（+90 = 顺时针），
     * 起点按它把旧矩形折进新坐标系。
     */
    fun begin(desktop: Boolean = false, turn: Int = 0) {
        previous = current
        previousWindow = windowSize
        current = emptyMap()
        targetDesktop = desktop
        turnDegrees = turn
        geometryReady = false
        progress = 0f
        startedAtMs = NpHandoffTime.now()
        generation++
        android.util.Log.d(
            "NpRot",
            "morph.begin desktop=" + desktop + " turn=" + turn + " parts=" + previous.size +
                " prevWindow=" + previousWindow + " window=" + windowSize,
        )
    }

    internal fun advance(value: Float) {
        progress = value
    }

    internal fun end() {
        progress = 1f
        // 落定 = 几何这件事不再欠着。不清它，`keepsDesktopChrome` 会把外壳的左栏
        // 一直按类 PC 版留着（Reduce Motion 下驱动直接落位，走的就是这条）。
        geometryReady = true
        previous = emptyMap()
    }

    /** 用例之间复位：进程级单例，Robolectric 会把状态跨用例留着。 */
    internal fun resetForTests() {
        current = emptyMap()
        previous = emptyMap()
        previousWindow = IntSize.Zero
        windowSize = IntSize.Zero
        targetDesktop = false
        turnDegrees = 0
        generation = 0
        progress = 1f
        geometryReady = true
        startedAtMs = 0L
        displacementAllowed = true
    }

    /** 上一形态里同一个部件的位置，已经搬进本次窗口坐标系；没有则 null（只淡入）。 */
    internal fun sourceRect(key: String): Rect? {
        val from = previous[key] ?: return null
        val window = windowSize
        if (window.width <= 0 || window.height <= 0) return from
        return carryIntoWindow(from, previousWindow, window)
    }

    internal fun targetRect(key: String): Rect? = current[key]
}

/** 一个部件在某一帧的绘制位姿：相对它自己的落点做平移与缩放。 */
data class MorphTransform(
    val translationX: Float,
    val translationY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val alpha: Float,
)

/**
 * 位姿插值（纯函数）。`e = 0` 严丝合缝压在旧位置上，`e = 1` 回到版式本来的落点。
 *
 * [scale] 只该给**画面**这类"整块缩放的图像"：`graphicsLayer` 的 scale 是画布变换，
 * 文字与按钮被缩放会拉糊字形。所以文字、按钮、行只跟着**平移**，尺寸差由淡入淡出补。
 *
 * [from] 为 null 表示这个部件只在新形态存在（例如竖屏才有的元信息块）——不位移，只淡入。
 * 反过来"只在旧形态存在"的部件此刻已经不在组合里，演不了退场：交给落位后的静态版式。
 */
internal fun npMorphTransform(
    from: Rect?,
    to: Rect,
    progress: Float,
    displacement: Boolean,
    scale: Boolean,
): MorphTransform {
    val e = progress.coerceIn(0f, 1f)
    if (!displacement) return MorphTransform(0f, 0f, 1f, 1f, if (from == null) e else 1f)
    val t = 1f - e
    if (from == null || from.width <= 0f || from.height <= 0f || to.width <= 0f || to.height <= 0f) {
        return MorphTransform(0f, 0f, 1f, 1f, e)
    }
    return MorphTransform(
        translationX = (from.center.x - to.center.x) * t,
        translationY = (from.center.y - to.center.y) * t,
        scaleX = if (scale) 1f + (from.width / to.width - 1f) * t else 1f,
        scaleY = if (scale) 1f + (from.height / to.height - 1f) * t else 1f,
        alpha = 1f,
    )
}

/**
 * 把**旧窗口**里的一个矩形搬进新窗口：**尺寸照旧**，中心按窗口归一化位置搬。
 *
 * 两件事分开看，是这个函数存在的全部理由：
 *
 * · **尺寸不许动**。转屏前后这块画面在物理屏上一样大（竖屏顶部的 16:9 与横屏右栏的
 *   16:9 本来就差不多大），开局把它按窗口比例缩一下，第一帧就会出现一次"先缩一下再
 *   长回去"的假动作。旧的刚体旋转映射则更糟：它把矩形转了 90°，16:9 的起点变成 9:16，
 *   逐轴缩放把画面拉成竖条——用户读到的正是"在固定位置直接展开"。
 * · **位置按归一化搬**。画面在屏上的方位就是它的身份：竖屏在顶部 → 横屏就在上部，
 *   然后自己滑到右栏。位移量才是这一下"看得见的动"，把它按角度折回旋转坐标系
 *   （刚体映射）会让终点几乎落在起点上，行程趋近于零。
 *
 * 中心夹在窗口内（贴边也允许半个画面在外，但不让整块飞出去）。
 */
internal fun carryIntoWindow(src: Rect, srcWindow: IntSize, dstWindow: IntSize): Rect {
    if (srcWindow.width <= 0 || srcWindow.height <= 0) return src
    if (dstWindow.width <= 0 || dstWindow.height <= 0) return src
    if (src.width <= 0f || src.height <= 0f) return src
    val halfW = src.width / 2f
    val halfH = src.height / 2f
    val cx = (src.center.x / srcWindow.width) * dstWindow.width
    val cy = (src.center.y / srcWindow.height) * dstWindow.height
    val x = if (src.width >= dstWindow.width) {
        dstWindow.width / 2f
    } else {
        cx.coerceIn(halfW, dstWindow.width - halfW)
    }
    val y = if (src.height >= dstWindow.height) {
        dstWindow.height / 2f
    } else {
        cy.coerceIn(halfH, dstWindow.height - halfH)
    }
    return Rect(x - halfW, y - halfH, x + halfW, y + halfH)
}

/** 参与 morph 的部件名。两侧必须同名才会互相衔接（对齐参考实现的 `data-part`）。 */
object NpMorphKey {
    const val Video = "video"
    const val Title = "title"
    const val Sub = "sub"
    const val Meta = "meta"
    const val Play = "play"
    const val Seek = "seek"
    const val Time = "time"
    const val Dismiss = "dismiss"
    const val Full = "full"
    const val ListPlay = "list-play"
    const val Fav = "fav"
    const val Audio = "audio"
    const val Info = "info"

    /** 选集行与它的封面：下标由调用方给，同一集在两侧必须是同一个下标。 */
    fun episode(index: Int) = "ep" + index
    fun cover(index: Int) = "epCover" + index
}

/**
 * 让这个部件参与 morph。
 *
 * 只做两件事：布局时登记自己的窗口矩形，绘制时按当前位姿加一层 `graphicsLayer`。
 * **不参与测量与摆放**——落点就是版式本来的位置，动画只是把它画在别处。
 * 因此不在飞时（绝大多数帧）这里一个动画状态都不读，layer 保持恒等，零开销。
 *
 * [scale] 给"整块缩放的图像"（画面本体）；文字与按钮保持 false，只跟着平移。
 * [desktop] 声明这个部件属于哪个形态：过渡期间旧树量出来的矩形不是落点，不能被收下。
 * [rotate] 给**画面**：整段里从"转屏前那一侧倒着"回正（±90°），让人读得出这一下是
 *   转过来了，而不是凭空在落点上展开。文字与按钮不许转——转了就没法读。
 */
fun Modifier.npMorphPart(
    key: String,
    scale: Boolean = false,
    desktop: Boolean? = null,
    rotate: Boolean = false,
): Modifier = this
    .onGloballyPositioned { NpMorph.record(key, it.boundsInWindow(), desktop) }
    .graphicsLayer {
        val to = if (NpMorph.isFlying) NpMorph.targetRect(key) else null
        if (to == null) {
            // 不在飞（或这一帧还没量到自己的落点）：**显式回到恒等**。
            // 只写 `return@graphicsLayer` 会把上一轮的半程位姿留在层上——天花板掐断一次
            // 没跑完的 morph，这些节点就会永远歪着画。
            transformOrigin = TransformOrigin.Center
            alpha = 1f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            rotationZ = 0f
            return@graphicsLayer
        }
        val transform = npMorphTransform(
            from = NpMorph.sourceRect(key),
            to = to,
            progress = NpMorph.progress,
            displacement = NpMorph.displacementAllowed,
            scale = scale,
        )
        transformOrigin = TransformOrigin.Center
        alpha = transform.alpha
        translationX = transform.translationX
        translationY = transform.translationY
        scaleX = transform.scaleX
        scaleY = transform.scaleY
        // 画面在飞行里**翻个身**：从转屏方向上倒过来的一小段角度回正，落位时正好 0°。
        // 不做满 90°——显示本身已经把整块玻璃转过一次了，再倒满 90° 就成了"转两遍"，
        // 开头那一下看着像卡住；留四成是"这个框是转着飞过去的"刚刚好。
        rotationZ = if (rotate) {
            NpMorph.turnDegrees * ROTATION_SHARE * (1f - NpMorph.progress)
        } else {
            0f
        }
    }

/**
 * morph 的节拍器。一轮 [NpMorph.generation] 演一次 [Motion.FormMorph]。
 *
 * 三条纪律，缺一条用户就看不到动画：
 * 1. **等几何**：[NpMorph.geometryReady] 之前一帧都不动。转屏那一轮新窗口要过几百毫秒
 *    才排出第一帧，从 `onConfigurationChanged` 起算的 480ms 会在"用户还看不见"的时候走完。
 * 2. **按合成帧钟推**：`withFrameNanos` 一帧一步，步长就是这一帧真正过去的时间。
 *    窗口不出帧时它不会假装时间在走——动画跟着画面走，不跟着墙钟走。
 * 3. **e=0 那一帧先画出去**：闸门打开的这一帧 `progress` 仍是 0，部件画在旧位置上；
 *    补间从**下一帧**才开始，所以第一帧新版式不会先闪一下落点。
 *
 * Reduce Motion / 轻量档直接落位。
 */
@Composable
fun NpMorphDriver() {
    val generation = NpMorph.generation
    val ready = NpMorph.geometryReady
    val duration = npDuration(Motion.FormMorph)
    val displacement = npDisplacementAllowed()
    SideEffect { NpMorph.displacementAllowed = displacement }
    LaunchedEffect(generation, ready, duration, displacement) {
        if (generation == 0) return@LaunchedEffect
        if (!displacement) {
            NpMorph.end()
            return@LaunchedEffect
        }
        if (!ready) return@LaunchedEffect
        // 进出对称：见 [NpEasing.Morph] 的取舍说明（Silk 会把行程堆在开头 150ms 里）。
        val easing = NpEasing.Morph
        var startedAt = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (startedAt == 0L) {
                // **等平台让开**：新形态的几何量到了，但窗口这头几百毫秒里放的是系统自己的
                // 转屏动画（snapshot 旋转 + 黑底），我们的补间若从这一刻起算，会在"用户还
                // 看不见"的时候把 Silk 的前段走完——那条曲线的 87% 行程都在前 30% 时间里，
                // 剩下的尾巴读起来就是"没有动画、直接换版式"（用户实报的两种说法）。
                // 停在 e=0（各部件仍在旧位置）等它演完，代价是零：这段时间本来就看不见。
                val holdMs = NpMorph.startedAtMs + MORPH_SETTLE_MS - NpHandoffTime.now()
                if (holdMs > 0L) {
                    NpMorph.advance(0f)
                    continue
                }
                startedAt = frameNanos
                NpMorph.advance(0f)
                continue
            }
            val elapsedMs = (frameNanos - startedAt) / 1_000_000f
            val raw = if (duration <= 0) 1f else elapsedMs / duration
            val e = raw.coerceIn(0f, 1f)
            NpMorph.advance(easing.transform(e))
            if (e >= 1f) break
        }
        NpMorph.end()
        android.util.Log.d("NpRot", "morph.end generation=" + generation + " duration=" + duration)
    }
}

/**
 * 一轮 morph 的天花板：480ms 的补间 + 一两帧余量。
 *
 * 驱动者若被中途拆掉（极端情况下整页换掉），`progress` 会停在半路；没有这道天花板，
 * `isFlying` 就会永久压住整页转场与右栏宽度动画。
 */
private const val FLIGHT_CEILING_MS = 1_500L

/**
 * 平台转屏动画的让位窗（ms，从 [NpMorph.begin] 起算）。
 *
 * 取值来自真机录屏：`ROTATION_ANIMATION_JUMPCUT` 在部分 ROM（本机 HyperOS / Android 14）
 * 上不生效，系统仍会拿旧窗口的 snapshot 转 90° 并压一层黑底，约 250~350ms。
 * 我们的补间必须从**它演完之后**起算——Silk 的 87% 行程都在前 30% 时间里，
 * 若这 260ms 被系统动画盖住，用户看到的就只剩缓入的尾巴，读作"没有动画"。
 */
private const val MORPH_SETTLE_MS = 260L

/** 画面在飞行中的自转幅度（占显示旋转角的比例）：见 [npMorphPart] 的 `rotate`。 */
private const val ROTATION_SHARE = 0.4f
