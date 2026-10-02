package com.noneplayer.core.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * None Player 设计令牌 → Material ColorScheme 的映射。
 *
 * 色板的唯一事实源是 [InkTokens]（`DesignTokens.kt`）：美学主张「暗室与放映灯」——
 * 中性面是带琥珀色相的暖黑/暖米，accent 只用于主操作与"正在播放"。这里把 Ink 令牌
 * 投影到 M3 槽位，使所有读 `MaterialTheme.colorScheme` 的既有界面**自动**获得暖化分层；
 * 需要更精细材质（玻璃/高光/选中底）的组件再经 [LocalNpInk] 取用原始令牌。
 */
private fun ColorScheme.applyInk(ink: InkTokens): ColorScheme = copy(
    primary = ink.accent,
    onPrimary = ink.onAccent,
    secondary = ink.accent,
    onSecondary = ink.onAccent,
    secondaryContainer = ink.accentBg,
    onSecondaryContainer = ink.accentStrong,
    tertiary = ink.accentStrong,
    onTertiary = ink.onAccent,
    background = ink.bg,
    onBackground = ink.label,
    surface = ink.surface1,
    onSurface = ink.label,
    surfaceVariant = ink.surface2,
    onSurfaceVariant = ink.label2,
    surfaceContainerLowest = ink.void,
    surfaceContainerLow = ink.bg,
    surfaceContainer = ink.surface1,
    surfaceContainerHigh = ink.surface2,
    surfaceContainerHighest = ink.surface3,
    outline = ink.label3,
    outlineVariant = ink.separator,
    error = ink.destructive,
    scrim = ink.scrim,
)

private val LightColors: ColorScheme = lightColorScheme().applyInk(NpInkLight)

private val DarkColors: ColorScheme = darkColorScheme().applyInk(NpInkDark)

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
 */
@Composable
fun NonePlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 当前已生效的外观：颜色只在切换瞬间改变一次
    var appliedDark by remember { mutableStateOf(darkTheme) }
    // 旧外观冻结帧：切换瞬间抓取，淡出结束后释放
    var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val frozenAlpha = remember { Animatable(0f) }

    LaunchedEffect(darkTheme) {
        if (darkTheme == appliedDark) return@LaunchedEffect
        val window = (context as? Activity)?.window
        val captured = window?.let { captureWindow(it) }
        if (captured != null) {
            frozenFrame = captured.asImageBitmap()
            frozenAlpha.snapTo(1f)
        }
        appliedDark = darkTheme
        if (captured != null) {
            frozenAlpha.animateTo(0f, tween(SCHEME_ANIMATION_MS, easing = LinearEasing))
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
            Bitmap.createBitmap(
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
