package com.noneplayer.plugins.player.ui.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.rememberNpHaptics
import kotlin.math.PI
import kotlin.math.sin

/**
 * 装饰波形进度条（§5.6）——"仅听音频"模式的主视觉。
 *
 * 形状由 [mediaKey] 唯一决定（FNV-1a 播种 + xorshift32 推进），**不读任何音频数据**：
 * 零解码成本，且同一文件任何时候看起来都是同一根波形。种子不许掺时间戳/帧号/`Random()`，
 * 否则它就是一台每帧重掷的果冻机（§14 第 12 条把这点写进了单测）。
 */
@Composable
internal fun WaveformSeekBar(
    mediaKey: String,
    durationMs: Long,
    positionMs: Long,
    bufferedFraction: Float,
    barCount: Int,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    val amplitudes = remember(mediaKey, barCount) { waveformAmplitudes(mediaKey, barCount) }
    val ink = LocalNpInk.current
    val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
    // 位置是 250ms 一跳的轮询值，直接用它播放头会一格一格地蹦。补一段线性推进
    // （§3.8 线性例外之一：波形的连续推进），让它按节拍滑过去——时长与迷你条
    // 进度线同档，正好接住轮询间隔。
    val progress by animateFloatAsState(
        targetValue = (positionMs / maxPosition).coerceIn(0f, 1f),
        animationSpec = npMotion(Motion.MiniProgress, easing = LinearEasing),
        label = "waveformProgress",
    )
    val buffered = bufferedFraction.coerceIn(0f, 1f)
    val playedColor = ink.accent
    // §5.6 指定值：音频模式的底是封面图（永远是不均匀的亮面），色板的 fgSoft
    // 压在上面会被封面吃掉，所以这里按规格用白的两个透明度而不是 ink。
    val idleColor = Color.White.copy(alpha = 0.18f)
    val bufferedColor = Color.White.copy(alpha = 0.10f)
    val haptics = rememberNpHaptics()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NpDimens.seekTouchBand),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // E129（审查 #11）：自绘波形此前**完全没有语义**——仅听音频页的主进度
                // 在读屏里等于不存在。这里补齐与 [NpSeekBar] 同一套：范围读数 +
                // 时间读数 + `setProgress` 可调节动作，让无障碍路径复用同一条
                // `onSeek`，不另开一份只改 UI 的假 seek。
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        (positionMs.toFloat() / maxPosition).coerceIn(0f, 1f),
                        0f..1f,
                    )
                    stateDescription = if (durationMs > 0L) {
                        "${formatTimestamp(positionMs)} / ${formatTimestamp(durationMs)}"
                    } else {
                        formatTimestamp(positionMs)
                    }
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * maxPosition).toLong())
                        true
                    }
                }
                .pointerInput(maxPosition, barCount) {
                    fun seekTo(x: Float) {
                        onSeek(
                            (x.coerceIn(0f, size.width.toFloat()) / size.width * maxPosition)
                                .toLong(),
                        )
                    }
                    detectTapGestures { offset ->
                        haptics.performHaptic(NpHaptic.Tick)
                        seekTo(offset.x)
                    }
                }
                .pointerInput(maxPosition, barCount) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragged = offset.x
                            haptics.performHaptic(NpHaptic.Long)
                            onSeek((dragged / size.width * maxPosition).toLong())
                        },
                        onDragEnd = {
                            haptics.performHaptic(NpHaptic.Tick)
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragged += dragAmount
                        onSeek(
                            (dragged.coerceIn(0f, size.width.toFloat()) / size.width * maxPosition)
                                .toLong(),
                        )
                    }
                },
        ) {
            val n = amplitudes.size
            if (n == 0) return@Canvas
            val barW = NpDimens.waveBarWidth.toPx()
            val gap = NpDimens.waveBarGap.toPx()
            val groupW = n * barW + (n - 1) * gap
            // 组宽小于画布时居中；大于画布时按画布宽摊平（宁可挤也不裁掉尾巴）
            val pitch = if (groupW <= size.width) (barW + gap) else size.width / n
            val drawnBarW = if (groupW <= size.width) barW else pitch * (barW / (barW + gap))
            val startX = if (groupW <= size.width) (size.width - groupW) / 2f else 0f
            val radius = CornerRadius(drawnBarW / 2f, drawnBarW / 2f)
            val centerY = size.height / 2f
            val maxBarH = size.height
            val playheadX = startX + progress * (n * pitch - gap)

            for (i in 0 until n) {
                val left = startX + i * pitch
                val h = (amplitudes[i] * maxBarH).coerceAtLeast(drawnBarW)
                val topLeft = Offset(left, centerY - h / 2f)
                val color = when {
                    left + drawnBarW <= playheadX -> playedColor
                    left + drawnBarW <= startX + buffered * (n * pitch - gap) -> bufferedColor
                    else -> idleColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(drawnBarW, h),
                    cornerRadius = radius,
                )
            }
            // 缓冲段只覆盖"还没播到"的那部分；已播区再叠一层白会糊掉主色
            if (buffered > progress) {
                val from = startX + progress * (n * pitch - gap)
                val to = startX + buffered * (n * pitch - gap)
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(from, centerY - 1.dp.toPx() / 2f),
                    size = Size((to - from).coerceAtLeast(0f), 1.dp.toPx()),
                    cornerRadius = CornerRadius(0.5.dp.toPx(), 0.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * 波形柱高（§5.6 生成规则，常数照抄勿改）。
 *
 * `0.28` 底 + `r*0.52` 随机纹理 + 两个正弦：`sin(i*0.23)` 制造细密起伏，
 * `sin(i/n*PI)` 制造"整段有起有落"的乐句感（两端低、中间高）。
 */
internal fun waveformAmplitudes(mediaKey: String, barCount: Int): FloatArray {
    if (barCount <= 0) return FloatArray(0)
    var state = hashFnv1a(mediaKey)
    if (state == 0) state = 0x9E3779B9.toInt() // xorshift32 的 0 是不动点，会把整条波形压平
    return FloatArray(barCount) { i ->
        state = xorshift32(state)
        val r = ((state ushr 8) and 0xFFFFFF) / 16777216f
        val amp = 0.28f + r * 0.52f + sin(i * 0.23f) * 0.18f + sin(i.toFloat() / barCount * PI.toFloat()) * 0.28f
        amp.coerceIn(0.18f, 1f)
    }
}

/** FNV-1a（32 位）：够散、够快、跨版本结果稳定。 */
private fun hashFnv1a(text: String): Int {
    var hash = FNV_OFFSET_BASIS
    for (char in text) {
        hash = hash xor char.code
        hash *= FNV_PRIME
    }
    return hash
}

private fun xorshift32(x: Int): Int {
    var s = x
    s = s xor (s shl 13)
    s = s xor (s ushr 17)
    s = s xor (s shl 5)
    return s
}

private const val FNV_OFFSET_BASIS = 0x811C9DC5.toInt()
private const val FNV_PRIME = 0x01000193
