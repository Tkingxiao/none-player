package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.rememberNpHaptics
import com.noneplayer.kernel.api.PlayerConnection

/**
 * 播放服务连接状态横幅（REV-10）——**轻提示，不阻塞画面**。
 *
 * Android 随时可能回收承载媒体会话的进程（后台内存压力、厂商省电、播放服务崩溃）。
 * 那之后画面会冻住/黑掉，而改造前用户只会看到"卡了"：既没有解释，也没有恢复入口。
 * 这里只在三种异常态浮一条顶部横幅：
 *
 * - [PlayerConnection.DISCONNECTED]：已断开 + 「重连」（引擎会重新 bind 控制器）
 * - [PlayerConnection.RECONNECTING]：正在重连——不给按钮，避免重复请求
 * - [PlayerConnection.FAILED]：重连失败 + 「重试」
 *
 * [PlayerConnection.CONNECTING]（正常启动过程）与 [PlayerConnection.CONNECTED]
 * 不显示。横幅挂在根 Box 顶层、不参与布局，只有按钮可点，画面交互不受影响。
 */
@Composable
internal fun ConnectionBanner(
    connection: PlayerConnection,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (connection) {
        PlayerConnection.DISCONNECTED -> "播放服务已断开（可能被系统回收）"
        PlayerConnection.RECONNECTING -> "正在重连播放服务…"
        PlayerConnection.FAILED -> "重连失败"
        PlayerConnection.CONNECTING, PlayerConnection.CONNECTED -> ""
    }
    val actionLabel = when (connection) {
        PlayerConnection.DISCONNECTED -> "重连"
        PlayerConnection.FAILED -> "重试"
        else -> null
    }
    val ink = LocalNpInk.current
    AnimatedVisibility(
        visible = text.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(animationSpec = npMotion(Motion.Field)),
        exit = fadeOut(animationSpec = npMotion(Motion.Field)),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(NpRadius.chip))
                .padding(
                    start = 12.dp,
                    end = if (actionLabel != null) 6.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                style = NpMonoSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(ink.accent)
                        .npPressScale(onClick = onRetry, pressedScale = 0.97f)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = actionLabel,
                        style = NpTypography.labelLarge,
                        color = ink.bg,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ErrorPanel(
    message: String,
    modifier: Modifier = Modifier,
    failure: PlaybackFailure? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val (scale, entranceAlpha) = npPanelEntrance(0.96f)
    // M18（§7.5 `Reject`）：错误面板出现的那一帧响一次——它与入场动画同时起步，
    // 是"停下来告诉你坏了"的面，不响就等于把错误做成了静默的视觉变化。
    val errorHaptics = rememberNpHaptics()
    LaunchedEffect(Unit) { errorHaptics.performHaptic(NpHaptic.Reject) }
    Column(
        modifier = modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 300.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = entranceAlpha.value
            }
            .clip(RoundedCornerShape(NpRadius.card))
            .background(Color.Black.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(NpRadius.card))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failure != null) {
            Text(
                text = failure.title,
                style = NpTypography.titleSmall,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            failure.trackLine?.let { line ->
                Text(
                    text = line,
                    style = NpTypography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = failure.reason,
                style = NpTypography.bodySmall,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            failure.suggestion?.let { suggestion ->
                Text(
                    text = suggestion,
                    style = NpTypography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                text = message,
                style = NpTypography.bodyMedium,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorPanelButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                ErrorPanelButton(
                    text = actionLabel,
                    primary = true,
                    onClick = onAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 错误/结束面板的按钮——**不用 Material 的 `Button`**。
 *
 * `Button` 自带 40dp 高、`primary` 实底与 M3 的涟漪/形状，与设计稿 `.lf-btn`
 * （丸形、`.list-foot .lf-btn.primary` 为 accent 实底 + 反色字）不是一套语言；
 * 混用会让这个面板在三处按钮上看起来像"另一个 App 弹出来的对话框"。
 */
@Composable
private fun ErrorPanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(if (primary) ink.accent else Color.White.copy(alpha = 0.16f))
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(NpRadius.chip),
                    )
                },
            )
            .npPressScale(onClick = onClick, pressedScale = 0.97f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NpTypography.labelLarge,
            color = if (primary) ink.bg else Color.White,
            maxLines = 1,
        )
    }
}
