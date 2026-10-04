package com.noneplayer.plugins.player.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 自绘进度条（§11.3 S5 + 批次 2「SeekBar 自绘」），替代 M3 `Slider`：
 *
 * - **三段式轨道**：已播 accent / 已缓冲 白 45% / 未缓冲 白 25%（数据来自
 *   `VideoPlayer.bufferedPositionMs`）——用户能看出"拖过去还有多远要加载"，
 *   这是"seek 为什么卡"的唯一可见解释；
 * - **拖动膨胀**：轨道 3→4dp、滑块 12→18dp（沿用既有拖动放大语言）；
 * - **点击定位**：整条 32dp 触控带（视觉仍是细线），点哪儿定位到哪儿；
 * - 拖动采用**绝对定位**（滑块跟手到触点），与 M3 Slider 行为一致，
 *   `onScrubChange` 的预览限流与松手精确定位仍由调用方（PlayerScreen）承担。
 *
 * 不用 `Slider` 的原因：它的 track 槽位拿不到"缓冲段"这第三种状态，
 * 而 progress 三态是本组件存在的全部理由。
 */
@Composable
internal fun NpSeekBar(
    durationMs: Long,
    displayPositionMs: Float,
    bufferedPositionMs: Long,
    dragging: Boolean,
    onScrubStart: () -> Unit,
    onScrubChange: (positionMs: Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
    val fraction = (displayPositionMs / maxPosition).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs / maxPosition).coerceIn(0f, 1f)
    val playedColor = MaterialTheme.colorScheme.primary
    val bufferedColor = Color.White.copy(alpha = 0.45f)
    val remainingColor = Color.White.copy(alpha = 0.25f)
    val trackHeight = if (dragging) 4.dp else 3.dp
    val knobRadius = if (dragging) 9.dp else 6.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxPosition) {
                    detectTapGestures { offset ->
                        onScrubStart()
                        onScrubChange(
                            offset.x.coerceIn(0f, size.width.toFloat()) / size.width * maxPosition,
                        )
                        onScrubEnd()
                    }
                }
                .pointerInput(maxPosition) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onScrubStart()
                            onScrubChange(offset.x / size.width * maxPosition)
                        },
                        onDragEnd = onScrubEnd,
                        onDragCancel = onScrubEnd,
                    ) { change, _ ->
                        change.consume()
                        onScrubChange(
                            change.position.x.coerceIn(0f, size.width.toFloat()) / size.width *
                                maxPosition,
                        )
                    }
                },
        ) {
            val trackH = trackHeight.toPx()
            val centerY = size.height / 2f
            val topLeft = Offset(0f, centerY - trackH / 2f)
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            // 未缓冲段（底）
            drawRoundRect(
                color = remainingColor,
                topLeft = topLeft,
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // 已缓冲段（中）：只有真的领先于播放位置才画，否则是一段假缓冲
            if (bufferedFraction > fraction) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = topLeft,
                    size = Size(size.width * bufferedFraction, trackH),
                    cornerRadius = radius,
                )
            }
            // 已播段（顶）
            drawRoundRect(
                color = playedColor,
                topLeft = topLeft,
                size = Size(size.width * fraction, trackH),
                cornerRadius = radius,
            )
            drawCircle(
                color = playedColor,
                radius = knobRadius.toPx(),
                center = Offset(size.width * fraction, centerY),
            )
        }
    }
}
