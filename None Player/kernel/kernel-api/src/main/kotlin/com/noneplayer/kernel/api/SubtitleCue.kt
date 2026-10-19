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
@Immutable
data class SubtitleCue(
    val text: String? = null,
    val bitmap: Any? = null,
    val placement: CuePlacement? = null,
)

/** 归一化摆放（0..1，相对视频画面），用于位图字幕等需要按画面定位的场景 */
@Immutable
data class CuePlacement(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)
