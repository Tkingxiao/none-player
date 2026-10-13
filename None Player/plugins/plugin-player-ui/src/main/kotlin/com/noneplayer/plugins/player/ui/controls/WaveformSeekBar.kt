package com.noneplayer.plugins.player.ui.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
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
    onPreviewPosition: (Long?) -> Unit = {},
    onSeek: (Long) -> Unit,
) {
    val amplitudes = remember(mediaKey, barCount) { waveformAmplitudes(mediaKey, barCount * 2) }
    val ink = LocalNpInk.current
    val maxPosition = durationMs.coerceAtLeast(1L).toDouble()
    var scrub by remember(mediaKey, durationMs) { mutableStateOf<WaveformScrub?>(null) }
    var pending by remember(mediaKey, durationMs) { mutableStateOf<WaveformScrub?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val preview by rememberUpdatedState(onPreviewPosition)
    // 位置是 250ms 一跳的轮询值，直接用它播放头会一格一格地蹦。补一段线性推进
    // （§3.8 线性例外之一：波形的连续推进），让它按节拍滑过去——时长与迷你条
    // 进度线同档，正好接住轮询间隔。
    val progress by animateFloatAsState(
        targetValue = (scrub?.fraction ?: pending?.fraction ?: (positionMs / maxPosition)).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = if (scrub != null) snap() else npMotion(Motion.MiniProgress, easing = LinearEasing),
        label = "waveformProgress",
    )
    val displayedFraction = scrub?.fraction ?: pending?.fraction ?: progress.toDouble()
    val currentFraction by rememberUpdatedState(displayedFraction)
    LaunchedEffect(positionMs, pending, progress) {
        val requested = pending ?: return@LaunchedEffect
        // Keep the released preview until both the engine and playback animation catch up.
        if (abs(positionMs - requested.positionMs(durationMs)) < 1_000L &&
            abs(progress - requested.fraction) * maxPosition < 50.0
        ) pending = null
    }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(1_500L)
            pending = null
        }
    }
    LaunchedEffect(scrub, pending) {
        preview((scrub ?: pending)?.positionMs(durationMs))
    }
    val buffered = bufferedFraction.coerceIn(0f, 1f)
    val playedColor = ink.accent
    // 跟字色走，不写死白：亮色幕布上白条会消失。透明度只负责「未播放 / 已缓冲」的层次。
    val idleColor = ink.fg.copy(alpha = 0.28f)
    val bufferedColor = ink.fg.copy(alpha = 0.16f)
    val haptics = rememberNpHaptics()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NpDimens.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                // E129（审查 #11）：自绘波形此前**完全没有语义**——仅听音频页的主进度
                // 在读屏里等于不存在。这里补齐与 [NpSeekBar] 同一套：范围读数 +
                // 时间读数 + `setProgress` 可调节动作，让无障碍路径复用同一条
                // `onSeek`，不另开一份只改 UI 的假 seek。
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        displayedFraction.toFloat(),
                        0f..1f,
                    )
                    stateDescription = if (durationMs > 0L) {
                        "${formatTimestamp((scrub ?: pending)?.positionMs(durationMs) ?: positionMs)} / ${formatTimestamp(durationMs)}"
                    } else {
                        formatTimestamp(positionMs)
                    }
                    setProgress { target ->
                        if (durationMs <= 0L || !target.isFinite()) {
                            false
                        } else {
                            val requested = WaveformScrub(target.toDouble().coerceIn(0.0, 1.0))
                            pending = requested
                            seek(requested.positionMs(durationMs))
                            true
                        }
                    }
                }
                .pointerInput(mediaKey, durationMs) {
                    if (durationMs <= 0L) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            scrub = WaveformScrub(currentFraction)
                            pending = null
                            haptics.performHaptic(NpHaptic.Long)
                        },
                        onDragEnd = {
                            scrub?.let { requested ->
                                pending = requested
                                seek(requested.positionMs(durationMs))
                            }
                            scrub = null
                            haptics.performHaptic(NpHaptic.Tick)
                        },
                        onDragCancel = { scrub = null },
                    ) { change, dragAmount ->
                        change.consume()
                        scrub = scrub?.dragBy(dragAmount, size.width * 2f)
                    }
                },
        ) {
            val n = amplitudes.size
            if (n == 0) return@Canvas
            // Drawing and dragging share the same timeline scale; the reference never moves.
            val trackWidth = size.width * 2f
            val pitch = trackWidth / (n - 1).coerceAtLeast(1)
            val drawnBarW = minOf(NpDimens.waveBarWidth.toPx(), pitch * 0.6f)
            val startX = waveformOriginPx(size.width, trackWidth, displayedFraction)
            val radius = CornerRadius(drawnBarW / 2f, drawnBarW / 2f)
            val centerY = size.height / 2f
            val maxBarH = size.height * 0.82f
            val playheadX = size.width / 2f

            for (i in 0 until n) {
                val left = startX + i * pitch - drawnBarW / 2f
                if (left + drawnBarW < 0f || left > size.width) continue
                val h = (amplitudes[i] * maxBarH).coerceAtLeast(drawnBarW)
                val topLeft = Offset(left, centerY - h / 2f)
                val color = when {
                    left + drawnBarW <= playheadX -> playedColor
                    i.toFloat() / (n - 1).coerceAtLeast(1) <= buffered -> bufferedColor
                    else -> idleColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(drawnBarW, h),
                    cornerRadius = radius,
                )
            }
            drawLine(
                color = ink.fg,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
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
