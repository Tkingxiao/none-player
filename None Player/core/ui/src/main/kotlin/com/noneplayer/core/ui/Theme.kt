package com.noneplayer.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.drawable.toDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * None Player 设计令牌 → Material ColorScheme 的映射。
 *
 * 色板的唯一事实源是 [InkTokens]（`DesignTokens.kt`），来自设计稿 `none-player.html`。
 * 这里把 Ink 令牌投影到 M3 槽位，使所有读 `MaterialTheme.colorScheme` 的既有界面
 * **自动**获得同一套配色；需要更精细材质（玻璃/高光/选中底）的组件再经 [LocalNpInk]
 * 取用原始令牌。
 *
 * 设计稿的 `--surface` 与 `--bg` 在亮色下同为纯白——这意味着 M3 的
 * "surface 比 background 亮一档"惯例在这里不成立。投影时刻意保留这个相等关系：
 * 亮色下的层次全靠发丝描边与投影，改动它会同时改掉整套卡片的读法。
 */
private fun ColorScheme.applyInk(ink: InkTokens): ColorScheme = copy(
    primary = ink.accent,
    onPrimary = if (ink.isDark) Color(0xFF1C150E) else Color.White,
    secondary = ink.accent,
    onSecondary = if (ink.isDark) Color(0xFF1C150E) else Color.White,
    secondaryContainer = ink.accentSoft,
    onSecondaryContainer = ink.accent,
    tertiary = ink.accent,
    onTertiary = ink.bg,
    background = ink.bg,
    onBackground = ink.fg,
    surface = ink.surface,
    onSurface = ink.fg,
    surfaceVariant = ink.surface2,
    onSurfaceVariant = ink.muted,
    surfaceContainerLowest = ink.bg,
    surfaceContainerLow = ink.surface,
    surfaceContainer = ink.surface,
    surfaceContainerHigh = ink.surface,
    surfaceContainerHighest = ink.surface2,
    outline = ink.muted,
    outlineVariant = ink.border,
    error = ink.danger,
    onError = ink.bg,
    scrim = ink.scrim,
)

private val LightColors: ColorScheme = lightColorScheme().applyInk(NpInkLight)

private val DarkColors: ColorScheme = darkColorScheme().applyInk(NpInkDark)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * None Player 主题。
 *
 * 明暗切换采用**冻结帧交叉淡化**：
 * 1. 切换请求到达时，先用 [PixelCopy] 抓取当前（旧外观）整帧画面，
 *    再**一次性**切换到目标配色——全树只重组一次（MaterialTheme 的
 *    LocalColorScheme 是 staticCompositionLocal，逐帧插值会让整棵子树
 *    逐帧重组，正是此前"主题切换卡顿"的根因）；
 * 2. 冻结帧作为最上层以 alpha 320ms 线性淡出——动画只发生在绘制层
 *    （GPU 合成单张位图），期间零重组、零布局，背景色与全部控件同步平滑过渡。
 *
 * 选择 PixelCopy 而非 GraphicsLayer 录制：Compose 的延迟图层机制会让
 * `drawContent()` 录制漏掉走独立 RenderNode 的内容（卡片/面板等），抓帧不完整；
 * PixelCopy 直接取合成后的窗口像素，所见即所得（降采样，淡化期间肉眼无差）。
 *
 * 性能档位（[motionQuality]）传的是**用户显式选的那一档**，null 表示"跟随自动判定"：
 * 实际生效值 = 用户档与设备自动档的**低者**，再叠加播放中降档（§9.6/§9.4）。
 * 这条规则的唯一落点就在这里，组件侧一律只读 [LocalNpMotionQuality]。
 */
@Composable
fun NonePlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    motionQuality: NpMotionQuality? = null,
    isDesktopLike: Boolean = false,
    decodingVideo: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 当前已生效的外观：颜色只在切换瞬间改变一次
    var appliedDark by remember { mutableStateOf(darkTheme) }
    // 旧外观冻结帧：切换瞬间抓取，淡出结束后释放
    var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val frozenAlpha = remember { Animatable(0f) }
    // Reduce Motion：全局读一次系统设置，经 CompositionLocal 下发。
    // 不下发的话，每个卡片节点各自 remember 一次 ContentResolver 读取——
    // 列表项滚出/滚回会重新组合、重新读，是滚动期一笔纯浪费。
    val reduceMotion = remember(context) { readSystemReduceMotion(context) }
    // 性能档位：设备实况 → 用户上限 → 播放中降档，三步在一个地方算完再下发。
    // 组件只读 [LocalNpMotionQuality]，不许自己判 SDK / 内存 / 播放状态。
    val effectiveQuality = remember(motionQuality, isDesktopLike, decodingVideo) {
        val auto = autoMotionQuality(context, isDesktopLike, decodingVideo)
        val base = motionQuality?.let { NpMotionQuality.lowerOf(it, auto) } ?: auto
        if (decodingVideo) base.forPlayback() else base
    }

    // A13：系统栏图标明暗跟**应用主题**，不是只跟系统深色；windowBackground 同理。
    // 放在每次 appliedDark 落定之后，主题切换与冷启首帧后的状态都由同一处收口。
    val view = LocalView.current
    SideEffect {
        val activity = context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !appliedDark
            isAppearanceLightNavigationBars = !appliedDark
        }
        activity.window.setBackgroundDrawable(
            (if (appliedDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE).toDrawable(),
        )
    }

    LaunchedEffect(darkTheme) {
        // 已经切过了（例如快速连点后 effect 重跑）：把可能残留的冻结帧摘掉再退出。
        // 不摘的话，上一次被取消的淡出会把一张旧画面的位图永久留在界面上。
        if (darkTheme == appliedDark) {
            frozenFrame = null
            return@LaunchedEffect
        }
        val window = (context as? Activity)?.window
        val captured = window?.let { captureWindow(it) }
        try {
            if (captured != null) {
                frozenFrame = captured.asImageBitmap()
                frozenAlpha.snapTo(1f)
            }
            appliedDark = darkTheme
            if (captured != null) {
                // 这里在协程里，调不到 @Composable 的 npDuration，就按同一份 effectiveQuality
                // 折算——不折的话"移除动画"设备上切主题仍会淡出 300ms。
                val ms = if (reduceMotion) 0 else effectiveQuality.scaled(SCHEME_ANIMATION_MS)
                frozenAlpha.animateTo(0f, tween(ms, easing = LinearEasing))
            }
        } finally {
            // **必须在 finally 里摘帧**：`animateTo` 在 effect 被取消时会抛
            // CancellationException（快速连点、配置变更、进程回收都算），
            // 此前把 `frozenFrame = null` 写在动画之后，取消时就跳过了这一句——
            // 冻结帧以当时的 alpha 永久盖在界面上，表现为"切一次主题后整个界面变暗一层"，
            // 且因为新外观已经生效，重进页面也不会自愈。
            frozenFrame = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MaterialTheme(
            colorScheme = if (appliedDark) DarkColors else LightColors,
            typography = NpTypography,
            shapes = NpShapes,
        ) {
            // 语义色令牌与 ColorScheme 同源同步切换：需要玻璃/高光/选中底等材质的组件
            // 经 LocalNpInk 取用原始令牌（ColorScheme 只投影得到 M3 槽位，损失了 alpha 阶梯）。
            CompositionLocalProvider(
                LocalNpInk provides if (appliedDark) NpInkDark else NpInkLight,
                LocalNpReduceMotion provides reduceMotion,
                LocalNpMotionQuality provides effectiveQuality,
                content = content,
            )
        }
        frozenFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frozenAlpha.value },
            )
        }
    }
}

private const val SCHEME_ANIMATION_MS = Motion.Scheme

/** 冻结帧降采样：面积取 1/4，抓帧更快、瞬时内存更小，淡化期间肉眼无差 */
private const val CAPTURE_SCALE = 2

/** 抓取窗口当前合成画面（旧外观）；失败返回 null，此时退化为瞬时切换 */
private suspend fun captureWindow(window: Window): Bitmap? =
    suspendCancellableCoroutine { cont ->
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val target = try {
            createBitmap(
                width / CAPTURE_SCALE,
                height / CAPTURE_SCALE,
                Bitmap.Config.ARGB_8888,
            )
        } catch (_: OutOfMemoryError) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            PixelCopy.request(
                window,
                Rect(0, 0, width, height),
                target,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(target)
                    } else {
                        target.recycle()
                        cont.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: IllegalArgumentException) {
            target.recycle()
            cont.resume(null)
        }
    }
