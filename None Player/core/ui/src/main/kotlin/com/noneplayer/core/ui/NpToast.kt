package com.noneplayer.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 轻量 Toast（设计稿 `.toast`）：底部居中胶囊 —— surface 实底 + 发丝描边 + 主投影，
 * 240ms 出入场 / 1900ms 停留（[Motion.Toast] / [Motion.ToastHold]）。
 *
 * 不用 `android.widget.Toast`（系统样式与设计语言完全脱节），也不用 Snackbar
 * （带动作位、入场从屏幕外滑入，都太重）。设计稿的 toast 是"操作确认"的轻声反馈。
 *
 * 用法：页面根部放一个 [NpToastHost]（通常包在 Box 底部居中），动作处调
 * `toastState.show("倍速：1.5x")`。
 */
class NpToastState {
    var message by mutableStateOf<String?>(null)
        private set

    /** 连续 show 直接替换文案并重新计时（与设计稿 clearTimeout+setTimeout 行为一致）。 */
    fun show(text: String) {
        message = text
    }

    fun clear() {
        message = null
    }
}

@Composable
fun rememberNpToastState(): NpToastState = remember { NpToastState() }

@Composable
fun NpToastHost(
    state: NpToastState,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    // 停留计时：每次 message 变化重新走一遍（含"替换文案"的场景）
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(Motion.ToastHold.toLong())
            state.clear()
        }
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(tween(npDuration(Motion.Toast), easing = NpEasing.Silk)) +
                slideInVertically(tween(npDuration(Motion.Toast), easing = NpEasing.Silk)) { it / 2 },
            exit = fadeOut(tween(npDuration(Motion.Toast), easing = NpEasing.SilkExit)) +
                slideOutVertically(tween(npDuration(Motion.Toast), easing = NpEasing.SilkExit)) { it / 2 },
        ) {
            val message = state.message.orEmpty()
            val shape = RoundedCornerShape(NpRadius.chip)
            Text(
                text = message,
                style = NpTypography.bodySmall,
                color = ink.fg,
                maxLines = 2,
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .clip(shape)
                    .background(ink.surface)
                    .border(1.dp, ink.border, shape)
                    .npShadow(ink, shape, elevation = 12.dp)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}
