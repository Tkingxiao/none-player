package com.noneplayer.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * None Player 字号刻度——对齐 Apple HIG iOS 字体规格（typography.md）。
 *
 * iOS 刻度 → Material 3 槽位映射：
 *   Large Title 34   → displayLarge
 *   Title 1     28   → headlineMedium
 *   Title 2     22   → titleLarge
 *   Title 3     20   → headlineSmall
 *   Headline    17/600 → titleMedium
 *   Body        17   → bodyLarge
 *   Subheadline 15   → bodyMedium
 *   Footnote    13   → bodySmall
 *   Caption 1   12   → labelMedium
 *   Caption 2   11   → labelSmall（最小 11pt，HIG 红线）
 *
 * 全部使用 sp，随系统字体大小缩放（对应 Dynamic Type）；
 * 小字号不使用细体（HIG accessibility.md）。
 */
val NpTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 13.sp),
)

/**
 * 圆角体系：小 10 / 中 14 / 大 20，近似 iOS 连续圆角的观感。
 */
val NpShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
