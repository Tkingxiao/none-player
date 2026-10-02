package com.noneplayer.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * None Player 材质系统（第 2 层配方）——由令牌组合出的"玻璃 / 高光 / 边框"。
 *
 * 核心取舍（采 ECHOAndroid `EchoLiquidGlass` 的做法并补齐 SDK 分级思路）：**不依赖真实高斯模糊**。
 * `半透明填充 + 顶边高光 + 1px 内描边 rim + 竖向渐变` 这四件已经把"层次感/玻璃感"表达完了，
 * 且 `API 26` 起全通吃、列表滚动时不触发每帧离屏重绘。真实模糊只作为高性能档的可选增强。
 */

/**
 * 玻璃面板：半透明渐变填充 + 左上高光 + 发丝级描边 + 顶边高光线。
 * 贴在内容之上（控制层、Sheet、迷你条、面板），不作页面底。
 *
 * @param strength 夹在 0.45–1.4：越大越"实"，用于层级更高的浮层。
 */
@Composable
fun NpGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NpRadius.sheet),
    strength: Float = 1f,
    elevation: Dp = 16.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val ink = LocalNpInk.current
    val dark = ink.isDark
    val s = strength.coerceIn(0.45f, 1.4f)
    val fill = if (dark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.16f * s),
            0.42f to Color.White.copy(alpha = 0.08f * s),
            1f to Color.Black.copy(alpha = 0.20f * s),
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.80f),
            1f to Color.White.copy(alpha = 0.56f),
        )
    }
    val specular = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.20f * s else 0.30f),
        0.48f to Color.Transparent,
    )
    val rim = if (dark) Color.White.copy(alpha = 0.22f * s) else Color.White.copy(alpha = 0.88f)
    Box(
        modifier = modifier
            .then(
                if (elevation <= 0.dp) Modifier else Modifier.shadow(
                    elevation = elevation * s,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.34f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.22f else 0.08f),
                )
            )
            .clip(shape)
            .background(ink.surface2.copy(alpha = NpAlpha.panel * s.coerceAtMost(1f)))
            .background(fill)
            .background(specular)
            .border(BorderStroke(1.dp, rim), shape),
        contentAlignment = contentAlignment,
    ) {
        // 顶边 1px 高光线——玻璃感的核心（ECHO 的 inset top hairline 等效物）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = if (dark) 0.42f * s else 0.70f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        content()
    }
}

/**
 * 卡片表面：surface1 实底 + accent 派生描边 + 顶边高光。用于封面卡、分组卡这类"内容承载面"。
 */
fun Modifier.npCardSurface(
    ink: InkTokens,
    shape: Shape,
): Modifier = this
    .clip(shape)
    .background(ink.surface1)
    .border(BorderStroke(1.dp, ink.border), shape)
    .npTopHairline(ink)

/**
 * 顶边 1px 高光——任何表面上边缘内侧铺满的发丝亮线。
 * 不用 `Modifier.border`（四边等亮，玻璃味立刻消失），只画顶边。
 */
fun Modifier.npTopHairline(ink: InkTokens): Modifier = drawWithContent {
    drawContent()
    val hairColor = if (ink.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.60f)
    val inset = 12.dp.toPx()
    val y = 0.6f.dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to hairColor,
                1f to Color.Transparent,
            ),
            startX = inset,
            endX = size.width - inset,
        ),
        start = Offset(inset, y),
        end = Offset(size.width - inset, y),
        strokeWidth = 1.2f.dp.toPx(),
    )
}
