package com.noneplayer.kernel.api

import androidx.compose.runtime.Immutable

/**
 * 一条字幕 Cue——内核无关的字幕出口模型。
 *
 * 引擎把外挂字幕（srt/ass/vtt…）与内嵌轨道（MKV 的 S_TEXT 系、PGS 位图等）
 * 统一映射为本模型，UI 层的字幕浮层只面向本模型渲染，
 * 不感知 media3 或具体字幕格式。
 *
 * - 文本字幕：[text] 非空，多行以 `\n` 分隔；
 * - 位图字幕（PGS/VobSub）：[bitmap] 非空（Android 侧为 `android.graphics.Bitmap`），
 *   [placement] 给出相对视频画面的归一化摆放。
 */
/** 文本 Cue 的水平对齐（A12 第一期：按容器已有的对齐摆放，缺省居中） */
enum class SubtitleAlignment { LEFT, CENTER, RIGHT }

@Immutable
data class SubtitleCue(
    val text: String? = null,
    val bitmap: Any? = null,
    val placement: CuePlacement? = null,
    /** 容器声明的水平对齐；缺省居中 */
    val alignment: SubtitleAlignment = SubtitleAlignment.CENTER,
    /**
     * 容器声明的垂直位置（0 = 画面顶端，1 = 底端）；null = 用用户设置/底部默认。
     * 来自 media3 `Cue.line`（fraction 型）——顶部对齐、双语上下分置的 ASS 靠它摆放。
     */
    val lineFraction: Float? = null,
    /** 容器声明的水平中心位置（0..1）；null = 按 [alignment] 铺满可用宽度 */
    val positionFraction: Float? = null,
    /**
     * Cue 自带的主色（ARGB，取自文本 span，A12 第一期只取首个前景色）。
     * null = 容器没给颜色；用户显式选过颜色时以用户设置为准。
     */
    val textColorArgb: Long? = null,
)

/** 归一化摆放（0..1，相对视频画面），用于位图字幕等需要按画面定位的场景 */
@Immutable
data class CuePlacement(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)
