package com.noneplayer.kernel.ui.api

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 落点的兜底时限（沿用旧覆盖层的 1200ms）：换页/拆栏被吞掉也不能让这一层永远压着。
 * 正常路径由落点侧现量出矩形提前收，这里只防赖着不走。
 */
private const val FLIGHT_TARGET_MS = 1_200L

/**
 * 交接飞行时长。比共享封面 [Motion.Shared]（300）略长：这一段要走完"整块画面从一栏/一屏
 * 搬到另一处"。取值 420：位移现在按**墙钟**推（不再按合成帧钟），所以转屏那段不出帧的时间
 * 也计入行程——[PREROLL_MAX] 把露脸后的可见段固定在七成左右，即约 300ms，落在
 * 《设计文档》§3.9 转场类区间内。
 */
private const val FLIGHT_MS = 420

/**
 * 揭开窗口时位移最多允许已经走到这一档（见 `PlayerPaneMotionOverlay` 的预滚）。
 *
 * 转屏期间窗口不出新帧，墙钟却一直在走；若按真实墙钟取值，等旋转演完（约 300~500ms）
 * 画面往往已经飞到终点，露脸即完成——那还是"转完就位"。所以露脸的第一帧把位移**压回**
 * 这一档，剩下的 70% 留在露脸之后演：读感从"转完再跟过来"变成"揭开的瞬间已经在飞"。
 * 0.3 是实测折中——再高会把露脸后的可见位移压得太短，又读成"闪一下"。
 */
private const val PREROLL_MAX = 0.3f

/**
 * [predictTarget] 在"还没有历史落点"时用的几何估计（只差几十像素，实测值一到就收敛）：
 * 右栏画面占窗口宽度的六成、右侧留 1.7% 边距、垂直居中；竖屏舞台满宽、顶对齐并给顶栏留 7.5%。
 */
private const val PANE_PREDICT_W = 0.62f
private const val PANE_PREDICT_MARGIN = 0.017f
private const val STAGE_PREDICT_TOP = 0.075f

/**
 * 「这台设备上只有一个画面」的**交接曲线**：把交接前那一帧从源矩形补间到落点矩形。
 *
 * 三类交接共用同一条曲线：用户点返回（播放页→右栏）、转横屏（同一条）、转竖屏（右栏→播放页）。
 * 后两者是**跨窗口**的交接，源矩形与落点矩形分处两个窗口坐标系（转屏前后尺寸互换、原点各自
 * 在左上）：把两串绝对坐标直接插值，中间帧会飞出屏幕，读起来是"从屏幕外斜着甩进来"。
 * 所以源矩形先按 [mapPictureRect] 映射到本次窗口再补间——归一化方位不变、统一缩放不拉伸，
 * 两帧之间只剩连续的平移与缩放。
 */
object NpPlayerPaneMotion {
    /**
     * 右栏里的**画面**矩形（窗口坐标；是画面本体，不含承载它的栏与黑边）。
     *
     * 必须是**可观测状态**：交接那一侧只负责把它登记进来，飞行层靠
     * `snapshotFlow { target(request) }` 等它出现——普通字段不会触发重放，
     * 等待会一直到超时，表现为"落点永远等不到、这一层停在起点"（真机实报的
     * "只有黑色背景、画面不生硬转换但也不动"就是这么来的）。
     */
    private var paneFrame by mutableStateOf<Rect?>(null)
    /** 竖屏播放页里的**画面**矩形。同样必须是可观测状态（理由同上）。 */
    private var stageFrame by mutableStateOf<Rect?>(null)
    /** 外壳最近一次登记的窗口像素尺寸——映射要拿**转屏之前**的那一个。 */
    private var windowSize: IntSize = IntSize.Zero

    private var paneUri by mutableStateOf<String?>(null)

    /** 一侧落点的历史值（矩形 + 当时的窗口尺寸），供下一轮起步时预测，见 [predictTarget]。 */
    private class Spot(val rect: Rect, val window: IntSize)
    private var lastPane: Spot? = null
    private var lastStage: Spot? = null

    internal var flight by mutableStateOf<Flight?>(null)
        private set

    /**
     * 一次交接。落点矩形不在这里：转屏那一轮它要等新形态量完才有值（右栏要等拆栏，
     * 播放页要等换页那几百毫秒），由 [target] 现取——所以下面两侧登记在 [begin] 时作废。
     */
    internal class Flight(
        val frame: Bitmap?,
        val from: Rect?,
        /** 源矩形所在的窗口尺寸；同一窗口内起飞（用户点返回）时为零，表示不必映射。 */
        val srcWindow: IntSize,
        /** 往右栏飞还是往播放页飞——决定 [target] 取哪一侧的登记。 */
        val towardsPane: Boolean,
        /**
         * 起跑时刻（`uptimeMillis`）。位移按**墙钟**推，不按合成帧钟推——转屏期间系统拿
         * 上一形态的快照去做旋转动画，应用窗口不出新帧，合成帧钟是停的；按帧钟推的话
         * 位移要等旋转演完才起步，读感就是"转完视频才跟过来"（用户实报）。
         */
        val startedAt: Long,
    )

    /**
     * 右栏每次布局刷新自己**画面**的矩形。旧版登记的是整栏（含工具栏与元数据行的黑底），
     * 补间两端各差一圈黑框——交接后的可见画面会与落点差出一截。
     */
    fun recordPane(uri: String, bounds: Rect) {
        if (bounds.width > 0f && bounds.height > 0f && paneFrame != bounds) {
            android.util.Log.d("NpRot", "paneMotion.recordPane $bounds uri=$uri")
            paneUri = uri
            paneFrame = bounds
            lastPane = Spot(bounds, windowSize)
        }
    }

    fun clearPane() {
        android.util.Log.d("NpRot", "paneMotion.clearPane")
        paneUri = null
        paneFrame = null
    }

    /** 竖屏播放页每次布局刷新自己画面的矩形（转竖屏那一轮的落点）。 */
    fun recordStage(bounds: Rect) {
        if (bounds.width > 0f && bounds.height > 0f && stageFrame != bounds) {
            android.util.Log.d("NpRot", "paneMotion.recordStage $bounds")
            stageFrame = bounds
            lastStage = Spot(bounds, windowSize)
        }
    }

    /**
     * 外壳每次布局刷新窗口像素尺寸（转屏映射要拿转屏之前的那一个）。
     *
     * 这里**只记尺寸、不作废登记**：登记本来就是每帧重量的最新值，而作废要赶在落点侧
     * 重量之前——那是 [begin]，由交接侧在拆栏/退页之前调。放在这里反而会踩顺序：父节点
     * 的 `onGloballyPositioned` 可能后于子节点的回调触发，把刚落下的新矩形一并抹掉。
     */
    fun recordWindow(size: IntSize) {
        if (size.width > 0 && size.height > 0) windowSize = size
    }

    fun paneBounds(): Rect? = paneFrame

    fun stageBounds(): Rect? = stageFrame

    /**
     * 交接起飞（[NpFormHandoff] 在拆栏/退页**之前**调用）。两侧登记随即作废：
     * 落点必须在新形态下重新量一次，拿旧形态的矩形当落点会飞到上一档的位置上。
     *
     * **空起飞不得顶掉正在飞的那一轮**：没有帧也没有源矩形的起飞，飞行层只能画一层
     * 整屏黑（见 `PlayerPaneMotionOverlay` 的兜底分支），它若替换掉带帧的真实飞行，
     * 用户看到的就是"黑屏 + 生硬切换"。这条兜底与 [NpFormHandoff] 的轮次判重各管一段：
     * 判重拦"同一轮的第二次上报"，这里拦"任何来晚一步的空起飞"。
     */
    internal fun begin(frame: Bitmap?, from: Rect?, towardsPane: Boolean) {
        val active = flight
        if (frame == null && from == null && active != null) {
            android.util.Log.d("NpRot", "paneMotion.begin SKIPPED(empty over active) towardsPane=$towardsPane")
            return
        }
        android.util.Log.d(
            "NpRot",
            "paneMotion.begin from=$from srcWindow=$windowSize towardsPane=$towardsPane hasFrame=${frame != null}",
        )
        paneFrame = null
        stageFrame = null
        paneUri = null
        flight = Flight(frame, from, windowSize, towardsPane, SystemClock.uptimeMillis())
    }

    internal fun target(request: Flight): Rect? = if (request.towardsPane) paneFrame else stageFrame

    /**
     * 预测落点，**只为让飞行立刻起步**：实测落点要等新形态量完（真机 ~520ms 后才登记上来），
     * 那段时间若停在源位置不动，用户读到的就是"冻住半秒再闪一下"——实报的"几乎看不到动效"
     * 有一半来自这里（另一半是飞得太短）。
     *
     * 首选**上一次同侧实测的矩形**按窗口比例搬过来：版式是确定的，转过一次就完全吻合。
     * 没有缓存时退到几何估计，也只差几十像素；实测值一到就换成真值，切换时只剩这点位移。
     */
    internal fun predictTarget(from: Rect, window: Rect, towardsPane: Boolean): Rect {
        if (from.width <= 0f || from.height <= 0f || window.width <= 0f || window.height <= 0f) return from
        val cached = (if (towardsPane) lastPane else lastStage)
            ?.let { mapPictureRect(it.rect, it.window, window) }
            ?.takeIf { it.width > 0f && it.height > 0f }
        if (cached != null) return cached
        val aspect = from.width / from.height
        // 满宽估出来的高度可能超出窗口（竖版视频），先按窗口夹一下，别让落点探到屏外。
        val w = min(if (towardsPane) window.width * PANE_PREDICT_W else window.width, window.width)
        val h = min(w / aspect, window.height)
        return if (towardsPane) {
            val right = window.right - window.width * PANE_PREDICT_MARGIN
            val left = right - w
            val top = window.top + (window.height - h) / 2f
            Rect(left, top, left + w, top + h)
        } else {
            val left = window.left + (window.width - w) / 2f
            val top = window.top + window.height * STAGE_PREDICT_TOP
            Rect(left, top, left + w, top + h)
        }
    }

    /**
     * 同一窗口内起飞（用户点返回 / 「返回浏览」）：源与落点同处一套坐标系，
     * 所以 [Flight.srcWindow] 记零、不做映射。
     *
     * 落点用**右栏画面**自己的矩形（[paneFrame]，与起飞点同为"画面本体"）：
     * 一端是整屏里的画面、另一端是右栏里的画面，补间才是纯粹的"缩小 + 滑移"。
     * 只有"两条边都是画面本体"这一条前置：不再要求落点被起飞点包住——横屏里
     * 画面贴满整窗时右栏的画面可能探出它的右缘，那个包含式会误判成"不能飞"。
     */
    fun returnToPane(uri: String, frame: Bitmap, from: Rect): Boolean {
        val to = paneFrame ?: return false
        if (paneUri != uri || from.width <= 0f || from.height <= 0f ||
            to.width <= 0f || to.height <= 0f
        ) return false
        flight = Flight(frame, from, IntSize.Zero, towardsPane = true, startedAt = SystemClock.uptimeMillis())
        return true
    }

    internal fun finish(request: Flight) {
        if (flight === request) flight = null
    }
}

/**
 * 交接要带走的那一帧：**哪条媒体**的画面 + 画面本体 + 它此刻在窗口里的矩形。
 *
 * 矩形必须是**画面本体**（不含承载它的栏/页的留白与黑边），否则补间两端各差一截：
 * 端点处飞行帧的可见画面会与真人落点差出一圈黑框，落位那一下就读成"跳了一下"。
 */
class NpPicture(val uri: String, val frame: Bitmap?, val rect: Rect)

internal fun playerPaneFrame(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * p
    return Rect(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom))
}

/**
 * 跨窗口搬一个矩形：中心按**窗口归一化位置**搬过来，尺寸按窗口比例统一缩放。
 *
 * 转屏前后两个窗口尺寸互换，"归一化位置"就是这块画面在屏幕上的方位（贴右的转过来还贴右，
 * 居中的转过来还居中），用户读到的连续感来自这里；"统一缩放"保证画面不被拉伸——两个窗口
 * 长短边比例不同，逐轴缩放会把 16:9 的画面中间帧挤成方形。
 *
 * 搬过来后还要夹进本次窗口：源矩形贴边时允许被窗口裁掉的部分收敛掉，免得开局就缺一角。
 * 源窗口尺寸为零（同一窗口内起飞）时原样返回，不做映射。
 */
internal fun mapPictureRect(src: Rect, srcWindow: IntSize, dst: Rect): Rect {
    if (srcWindow.width <= 0 || srcWindow.height <= 0) return src
    if (src.width <= 0f || src.height <= 0f || dst.width <= 0f || dst.height <= 0f) return src
    val scale = min(dst.width / srcWindow.width, dst.height / srcWindow.height)
    val w = src.width * scale
    val h = src.height * scale
    val cx = dst.left + (src.center.x / srcWindow.width) * dst.width
    val cy = dst.top + (src.center.y / srcWindow.height) * dst.height
    val left = (cx - w / 2f).coerceIn(dst.left, (dst.right - w).coerceAtLeast(dst.left))
    val top = (cy - h / 2f).coerceIn(dst.top, (dst.bottom - h).coerceAtLeast(dst.top))
    return Rect(left, top, left + w, top + h)
}

/**
 * 露脸那一刻位移**已经允许走到的比例**（见 [PREROLL_MAX]）：按墙钟从 [startedAt] 算，
 * 但封顶在 [PREROLL_MAX]。
 *
 * 两个调用点必须给同一个值：`remember` 拿它当 [androidx.compose.animation.core.Animatable]
 * 的初值（露脸第一帧就在这一档），`LaunchedEffect` 拿它算剩余行程。真机实测转屏起跑
 * （系统旋转动画开始）与露脸第一帧只差 7~17ms，所以这一档就是"旋转开始时画面已经在飞"
 * 的读感来源。
 */
private fun prerollBase(startedAt: Long, flightDuration: Int): Float {
    if (flightDuration <= 0) return PREROLL_MAX
    val raw = (SystemClock.uptimeMillis() - startedAt).toFloat() / flightDuration
    return min(raw, PREROLL_MAX).coerceIn(0f, 1f)
}

/**
 * 交接期间压在**整窗**之上的那一层：黑底 + 那一帧，从源矩形补间到落点矩形，落位后淡出。
 *
 * 为什么要盖：转屏那一帧要把整棵媒体库树重测一遍，`navigate()` 到 NavHost 真的换页隔了
 * 几百毫秒（真机实测 570ms），这段空档里屏幕上躺着的是**旧形态的壳**（且外壳本身也在换
 * 版式：竖屏底部条 ↔ 横屏左 Rail）。旧版只在这里盖一张静止的帧，用户实报"只有黑色背景
 * + 播放内容的全屏生硬转换"；这一版让它**动**：同一帧画面从它原来在的位置缩/移到它要
 * 去的位置，与真人落点同形同位，其余外壳变化被这层挡住。
 *
 * 挂载点必须是**窗口原点**上的节点（外壳的最外层 Box），两端矩形都来自
 * `boundsInWindow()`，原点一致才能直接拿坐标摆放。
 */
@Composable
internal fun PlayerPaneMotionOverlay() {
    val request = NpPlayerPaneMotion.flight ?: return
    var target by remember(request) { mutableStateOf(NpPlayerPaneMotion.target(request)) }
    val flightDuration = npDuration(FLIGHT_MS)
    // 初值就是**起跑预滚**（见 [PREROLL_MAX]），而不是 0：转屏期间本层要等系统旋转演完
    // 才出画，那时离 [Flight.startedAt] 已过了几百毫秒。若从 0 起步，露脸的**第一帧**还画
    // 在源位置、要到第二帧才跳到预滚值——真机逐帧看就是"旋转中画面先定住、再弹一下"。
    // 起手就落在预滚值上，露脸第一帧已经是"在飞"的姿态。
    val progress = remember(request) {
        Animatable(prerollBase(request.startedAt, flightDuration))
    }
    val opacity = remember(request) { Animatable(1f) }
    val fadeDuration = npDuration(Motion.ReduceFade)
    val displacement = npDisplacementAllowed()
    val source = request.from
    val hasSource = source != null && source.width > 0f && source.height > 0f
    LaunchedEffect(request) {
        val t0 = android.os.SystemClock.uptimeMillis()
        android.util.Log.d(
            "NpRot",
            "overlay start from=$source srcWindow=${request.srcWindow} hasFrame=${request.frame != null} " +
                "lag=${t0 - request.startedAt}ms",
        )
        try {
            // **起飞与等落点并发**：等落点要 ~520ms（换页 + 播放页首帧版式），若串行地先等
            // 再飞，这段时间这一层就冻在源位置——真机据此读成"没有动效"。现在渲染目标取
            // "实测落点 ?: [predictTarget] 的预测落点"，所以第一帧起就有连续位移；
            // 实测值带来时换成真值，差值只有几十像素。两者都等齐了才收尾淡出。
            coroutineScope {
                val landing = launch {
                    if (target == null) {
                        target = withTimeoutOrNull(FLIGHT_TARGET_MS) {
                            snapshotFlow { NpPlayerPaneMotion.target(request) }
                                .filterNotNull()
                                .first()
                        }
                        android.util.Log.d(
                            "NpRot",
                            "overlay targetArrived +${android.os.SystemClock.uptimeMillis() - t0}ms target=$target",
                        )
                    }
                }
                val moving = launch {
                    if (displacement && hasSource) {
                        // 转屏期间系统拿上一形态的快照演旋转，应用窗口出帧很稀（且不是每帧都
                        // 轮到这一层重画；真机实测本层第一帧出现在转屏起跑后 7~200ms）。所以
                        // "位移何时起跑"不由 tween 决定，而由露脸那一帧距 [Flight.startedAt]
                        // 过了多久决定：若从 0 起步，读感就是"转完了视频才跟过来"（用户实报）。
                        //
                        // 这里分两段：
                        //   ① 露脸第一帧按**墙钟**算出"这一趟已经走了多少"，直接落在那儿
                        //      （[PREROLL_MAX] 封顶）——画面以"已经在飞"的姿态出现；
                        //   ② 剩下的行程交回**帧钟** tween 补完。这一段不用墙钟：露脸前后
                        //      那几百毫秒帧很稀（首帧与次帧实测隔了 446ms），墙钟推的话
                        //      第二帧就已经走满、看着是跳一下；帧钟会自己把行程摊到可用的
                        //      那几帧上，读着是连续滑移。
                        val base = prerollBase(request.startedAt, flightDuration)
                        progress.snapTo(base)
                        val rest = ((1f - base) * flightDuration).roundToInt().coerceAtLeast(1)
                        progress.animateTo(1f, tween(rest, easing = NpEasing.Emphasized))
                        android.util.Log.d(
                            "NpRot",
                            "overlay flightDone +${SystemClock.uptimeMillis() - t0}ms " +
                                "revealAt=${t0 - request.startedAt}ms base=$base rest=$rest",
                        )
                    } else {
                        android.util.Log.d(
                            "NpRot",
                            "overlay NO motion hasSource=$hasSource displacement=$displacement",
                        )
                    }
                }
                landing.join()
                moving.join()
            }
            opacity.animateTo(0f, tween(fadeDuration, easing = NpEasing.SilkExit))
        } finally {
            NpPlayerPaneMotion.finish(request)
            android.util.Log.d("NpRot", "overlay finish +${android.os.SystemClock.uptimeMillis() - t0}ms")
        }
    }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                // 本层的可用空间就是**本次窗口**（挂在窗口原点的节点上）。约束是这一帧
                // 现算的，所以转屏那一帧拿到的已经是新窗口尺寸；若去读 WindowInfo 会慢一拍。
                val window = Rect(
                    0f,
                    0f,
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat(),
                )
                // 跨窗口那一轮源矩形与新窗口不同坐标系（转屏前后尺寸互换），先映射过来：
                // 归一化方位不变 + 统一缩放不拉伸，中间帧才不会"从屏幕外斜着甩进来"。
                val from = if (hasSource) mapPictureRect(source, request.srcWindow, window) else null
                // 落点必须落在**本次窗口**内：尺寸变化时登记会作废并重测，这里再夹一道，
                // 万一来的是上一套坐标系的矩形，宁可在起点等收尾，也别飞到屏幕外。
                val landed = target?.takeIf {
                    it.width > 0f && it.height > 0f &&
                        it.left >= -1f && it.top >= -1f &&
                        it.right <= window.right + 1f && it.bottom <= window.bottom + 1f
                }
                // 实测落点还没量出来时先朝**预测落点**飞（见 [NpPlayerPaneMotion.predictTarget]）；
                // 实测一到就换成真值，两端只差几十像素，切换那一下看不出来。
                val to = landed
                    ?: from?.let { NpPlayerPaneMotion.predictTarget(it, window, request.towardsPane) }
                // 量不到源矩形（右栏没在播、抓帧被拒）就没有"从哪来"可言：退回旧封面形态
                // ——整屏 Fit 顶对齐，不飞。
                val rect = when {
                    from != null && to != null -> playerPaneFrame(from, to, progress.value)
                    from != null -> from
                    else -> null
                }
                val child = if (rect != null) {
                    measurable.measure(
                        Constraints.fixed(
                            rect.width.roundToInt().coerceAtLeast(1),
                            rect.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                } else {
                    measurable.measure(constraints)
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (rect != null) {
                        child.place(rect.left.roundToInt(), rect.top.roundToInt())
                    } else {
                        child.place(0, 0)
                    }
                }
            }
            .graphicsLayer { alpha = opacity.value }
            .background(Color.Black),
    ) {
        val frame = request.frame
        if (frame != null && !frame.isRecycled) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // 飞行中居中（两端同形，居中即贴合）；没有源矩形时顶对齐——竖屏落点的
                // 舞台贴着顶栏，居中会让这一帧在接手时往下跳半屏。
                alignment = if (hasSource) Alignment.Center else Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
