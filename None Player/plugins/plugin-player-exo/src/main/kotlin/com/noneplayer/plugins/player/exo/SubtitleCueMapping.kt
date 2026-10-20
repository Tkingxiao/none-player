package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.SubtitleAlignment

/**
 * A12 第一期：media3 `Cue` → kernel `SubtitleCue` 的映射算式（纯函数，可离线单测）。
 *
 * 这些算式原本内联在 `ExoVideoPlayer.toKernelCue()` 里，抽出来是为了能在纯 JVM 上
 * 钉死第一批验收的三件事：Cue 自带对齐、主色（ARGB）、位图归一化摆放。**行为必须与
 * 抽取前逐位一致**——缺省底部居中、非法 DIMEN 回落 1、坐标钳进 0..1。
 */
internal fun npCueAlignment(isStartAligned: Boolean, isEndAligned: Boolean): SubtitleAlignment = when {
    isStartAligned -> SubtitleAlignment.LEFT
    isEndAligned -> SubtitleAlignment.RIGHT
    else -> SubtitleAlignment.CENTER
}

/** Cue 的 0..1 比例值统一钳位；null 表示 Cue 没给（由渲染层按用户设置兜底）。 */
internal fun npCueFraction(raw: Float?): Float? = raw?.coerceIn(0f, 1f)

/** Cue 的颜色是带符号 Int；契约里是无符号 ARGB（Long），必须清掉符号扩展。 */
internal fun npCueColorArgb(argb: Int?): Long? = argb?.toLong()?.and(0xFFFFFFFFL)

/**
 * 位图 Cue 的归一化摆放（画面内 0..1）。
 *
 * - [heightFraction]：Cue 给的高度比例；非法值（≤0、>1、非有限）回落整屏高；
 * - 宽度按位图宽高比推：`heightFraction * width / height`，上限 1；
 * - [anchorX]：水平中心点（缺省 0.5），[baselineY]：底边位置（缺省 1 = 贴底）；
 * - left/top 钳进 0..1（抽取前的 inline 行为如此，超出部分直接切掉）。
 */
internal fun npCuePlacement(
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
    heightFraction: Float?,
    anchorX: Float?,
    baselineY: Float?,
): CuePlacement {
    val height = heightFraction
        ?.takeIf { it.isFinite() && it > 0f && it <= 1f }
        ?: 1f
    // 解码器给 0/负高度时按 1:1 兜底，避免除零
    val aspect = if (bitmapHeightPx > 0) bitmapWidthPx.toFloat() / bitmapHeightPx else 1f
    val width = (height * aspect).coerceAtMost(1f)
    val anchor = anchorX ?: 0.5f
    val baseline = baselineY ?: 1f
    return CuePlacement(
        left = (anchor - width / 2f).coerceIn(0f, 1f),
        top = (baseline - height).coerceIn(0f, 1f),
        width = width,
        height = height,
    )
}
