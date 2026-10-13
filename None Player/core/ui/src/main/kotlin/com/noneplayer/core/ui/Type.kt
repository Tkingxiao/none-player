package com.noneplayer.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * None Player 字号刻度——对齐设计稿 `none-player.html` 的实测 px 值。
 *
 * 设计稿的三条字体族：Display（大标题 / 数字）、Body（正文与 UI）、Mono（一切
 * 时间码、时长、大小、百分比）。Android 上没有 Segoe UI Variable / SF Pro，
 * 系统 sans-serif 是等价替代；**Mono 是真实存在的**（`Monospace`），
 * 所以时间码用等宽，位数变化时整行不会左右抖动。
 *
 * 设计稿刻度 → M3 槽位映射（px 会随设备密度放大，这里按 1dp ≈ 1px 的 Web 习惯给值）：
 *   27   → displayLarge（.topbar h1）
 *   22   → headlineMedium / titleLarge（.listhead h2 / .audio-info h2）
 *   19   → headlineSmall（.sec-head h2）
 *   17   → titleMedium（.sheet h3）
 *   15   → titleSmall / bodyLarge（.row-item .lbl b / .btnp 基底）
 *   14   → bodyMedium（.listrow .lr-title）
 *   13   → bodySmall（次要说明、.chip）
 *   11   → labelSmall（.rail-btn span / .eyebrow 下限）
 *
 * 全部使用 sp，随系统字体大小缩放。
 */
val NpTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 27.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        letterSpacing = (-0.02).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
        letterSpacing = (-0.015).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 14.sp,
    ),
)

/**
 * 圆角体系：与 [NpRadius] 同源，投影到 M3 `Shapes` 供 Material 组件使用。
 */
val NpShapes = Shapes(
    extraSmall = RoundedCornerShape(NpRadius.cover),
    small = RoundedCornerShape(NpRadius.tile),
    medium = RoundedCornerShape(NpRadius.card),
    large = RoundedCornerShape(NpRadius.sheet),
    extraLarge = RoundedCornerShape(NpRadius.hero),
)

/**
 * 等宽时间码（设计稿 `.mono`）——一切时长 / 位置 / 大小 / 百分比。
 *
 * `FontFamily.Monospace` 保证数字等宽，位数变化时不抖动；
 * `tabular-nums` 对等宽族是冗余的，故不再设 fontFeatureSettings。
 */
val NpMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 17.sp,
)

val NpMonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 15.sp,
)

val NpMonoLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 20.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeight = 24.sp,
)

/** 设计稿 `.eyebrow`：等宽小号 + 字距展开 + accent 色的小标题。 */
val NpEyebrow = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 15.sp,
    letterSpacing = 1.sp,
)
