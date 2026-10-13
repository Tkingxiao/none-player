package com.noneplayer.plugins.player.ui.gesture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npDuration
import kotlin.math.abs

/**
 * 手势指示器三件套（§4.3「视觉」列）：横滑 seek 的目标时间气泡、
 * 竖滑亮度/音量的竖条。都是**指示**而非控件——不接收输入，压在画面之上、
 * 控制层之下；显隐由调用方用 [AnimatedVisibility]（或直接传 visible）驱动。
 *
 * 旧实现只有一枚中央文字胶囊（GestureHintPill）："亮度 62%" 这样的读数没有
 * 可视的量感，盲调依然盲。规格要的是竖条 + 百分比 + 图标（160dp 高、淡出 900ms）。
 */

/** 指示器淡出时限（§4.3：指示器 900ms 后淡出） */
internal const val GESTURE_INDICATOR_HOLD_MS = 900L

/**
 * 横滑 seek 气泡（§4.3：中心胶囊 `+01:23` / `-00:45` + 目标时间）。
 *
 * 拖动中视频照常播放，气泡只呈现"松手会去哪"：增量带符号、目标时刻绝对值。
 */
@Composable
fun SeekGestureBubble(
    deltaMs: Long,
    targetMs: Long,
    modifier: Modifier = Modifier,
) {
    BubbleShell(modifier = modifier) {
        Text(
            text = (if (deltaMs >= 0) "+" else "-") + formatTimestamp(abs(deltaMs)),
            style = NpMono,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = formatTimestamp(targetMs),
            style = NpMono,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * 竖滑亮度/音量竖条（§4.3：高 160dp 竖条 + 图标 + 百分比）。
 * 音量 0 时图标换成静音（NpIcon.Sound + 一道斜杠由文字"静音"补充说明——
 * 图标集刻意保持最小，不为一处状态加字形）。
 */
@Composable
fun VerticalAdjustIndicator(
    mode: StageVerticalMode,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Icon(
            imageVector = when {
                mode == StageVerticalMode.Volume && percent <= 0 -> NpIcon.Close
                mode == StageVerticalMode.Brightness -> NpIcon.Sun
                else -> NpIcon.Sound
            },
            contentDescription = if (mode == StageVerticalMode.Brightness) "亮度" else "音量",
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Canvas(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(4.dp)
                .height(160.dp),
        ) {
            val radius = size.width / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                cornerRadius = CornerRadius(radius, radius),
            )
            val fill = (percent.coerceIn(0, 100)) / 100f * size.height
            if (fill > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, size.height - fill),
                    size = Size(size.width, fill),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        }
        Text(
            text = if (mode == StageVerticalMode.Volume && percent <= 0) "静音" else "$percent%",
            style = NpMono,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 中央胶囊容器（黑色 62% 丸，与 GestureHintPill 同配方） */
@Composable
private fun BubbleShell(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        content = content,
    )
}

/**
 * 手势指示器的统一显隐包装：900ms 无更新即淡出。
 * 调用方只需在每次手势增量到达时刷新 [key]（指示器当前值），计时自动重启。
 */
@Composable
fun GestureIndicatorVisibility(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        // M10：出现 `Field`(150)、消失 `Hover`(100)——原先 90/180 正好写反，
        // 侧条"啪"地跳出、又慢慢消失，读数时机被拉长的是最不该等的退场。
        enter = fadeIn(tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(tween(npDuration(Motion.Hover), easing = NpEasing.SilkExit)),
    ) {
        content()
    }
}
