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
 * None Player 设计令牌。
 *
 * 遵循 Apple HIG 的色彩原则（color.md）：
 * - 语义化角色命名，明暗两套完整定义，跟随系统外观，不提供应用内切换；
 * - 一种颜色只表达一种含义：琥珀橙 accent 只用于主操作与品牌时刻，
 *   其余界面保持中性灰阶；
 * - 对比度按 WCAG AA 校验：小字 ≥ 4.5:1，大字/粗体 ≥ 3:1。
 */
private object NpTokens {
    // ---- 明亮外观 ----
    val BackgroundLight = Color(0xFFF2F2F7)      // 分组列表底色（iOS systemGroupedBackground）
    val SurfaceLight = Color(0xFFFFFFFF)         // 分组卡片
    val LabelLight = Color(0xFF000000)           // 4.5:1+ 参照黑
    val LabelSecondaryLight = Color(0xFF6C6C70)  // 次要文字，白底对比 5.2:1
    val SeparatorLight = Color(0xFFDCDCE2)
    val AccentLight = Color(0xFFC24E00)          // 主橙，白底对比 4.8:1，可直接作文字色
    val OnAccentLight = Color(0xFFFFFFFF)        // 橙底白字 4.8:1
    val AccentContainerLight = Color(0xFFC24E00) // 选中态容器：与 accent 同义（一色一义）
    val OnAccentContainerLight = Color(0xFFFFFFFF)
    val DestructiveLight = Color(0xFFD70015)     // 白底对比 5.4:1

    // ---- 暗色外观 ----
    val BackgroundDark = Color(0xFF000000)       // iOS systemBackground（base）
    val SurfaceDark = Color(0xFF1C1C1E)          // elevated 分组卡片
    val SurfaceElevatedDark = Color(0xFF2C2C2E)
    val LabelDark = Color(0xFFFFFFFF)
    val LabelSecondaryDark = Color(0xFF9E9EA6)   // 黑底对比 7.2:1
    val SeparatorDark = Color(0xFF38383C)
    val AccentDark = Color(0xFFFF9F0A)           // 播放器的"放映灯"，黑底对比 10:1
    val OnAccentDark = Color(0xFF1C1C1E)         // 橙底深字 8.3:1
    val AccentContainerDark = Color(0xFFFF9F0A)  // 选中态容器：与 accent 同义
    val OnAccentContainerDark = Color(0xFF1C1C1E)
    val DestructiveDark = Color(0xFFFF453A)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = NpTokens.AccentLight,
    onPrimary = NpTokens.OnAccentLight,
    secondary = NpTokens.AccentLight,
    onSecondary = NpTokens.OnAccentLight,
    secondaryContainer = NpTokens.AccentContainerLight,
    onSecondaryContainer = NpTokens.OnAccentContainerLight,
    tertiary = NpTokens.AccentLight,
    onTertiary = NpTokens.OnAccentLight,
    background = NpTokens.BackgroundLight,
    onBackground = NpTokens.LabelLight,
    surface = NpTokens.SurfaceLight,
    onSurface = NpTokens.LabelLight,
    surfaceVariant = NpTokens.SurfaceLight,
    onSurfaceVariant = NpTokens.LabelSecondaryLight,
    surfaceContainer = NpTokens.SurfaceLight,
    surfaceContainerHigh = NpTokens.SurfaceLight,
    surfaceContainerHighest = NpTokens.SurfaceLight,
    outlineVariant = NpTokens.SeparatorLight,
    error = NpTokens.DestructiveLight,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = NpTokens.AccentDark,
    onPrimary = NpTokens.OnAccentDark,
    secondary = NpTokens.AccentDark,
    onSecondary = NpTokens.OnAccentDark,
    secondaryContainer = NpTokens.AccentContainerDark,
    onSecondaryContainer = NpTokens.OnAccentContainerDark,
    tertiary = NpTokens.AccentDark,
    onTertiary = NpTokens.OnAccentDark,
    background = NpTokens.BackgroundDark,
    onBackground = NpTokens.LabelDark,
    surface = NpTokens.SurfaceDark,
    onSurface = NpTokens.LabelDark,
    surfaceVariant = NpTokens.SurfaceElevatedDark,
    onSurfaceVariant = NpTokens.LabelSecondaryDark,
    surfaceContainer = NpTokens.SurfaceDark,
    surfaceContainerHigh = NpTokens.SurfaceElevatedDark,
    surfaceContainerHighest = NpTokens.SurfaceElevatedDark,
    outlineVariant = NpTokens.SeparatorDark,
    error = NpTokens.DestructiveDark,
)

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
            content = content,
        )
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

private const val SCHEME_ANIMATION_MS = 320

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
