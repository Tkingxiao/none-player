package com.noneplayer.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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

    /** E220：动作钮文案（如「撤销」）；纯文本 show 时为 null。随文案一起替换/清除。 */
    var actionLabel by mutableStateOf<String?>(null)
        private set

    var action by mutableStateOf<(() -> Unit)?>(null)
        private set

    /** 连续 show 直接替换文案并重新计时（与设计稿 clearTimeout+setTimeout 行为一致）。 */
    fun show(text: String) {
        message = text
        actionLabel = null
        action = null
    }

    /** E220：带动作的 toast（如「已从播放历史移除 · 撤销」）。动作触发即清 toast。 */
    fun show(text: String, actionLabel: String, onAction: () -> Unit) {
        message = text
        this.actionLabel = actionLabel
        action = onAction
    }

    fun clear() {
        message = null
        actionLabel = null
        action = null
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
    // E119：Toast 的半格位移属"位移类"，Reduce Motion / 轻量档下收成纯淡入淡出（§9.3/§9.6）。
    // 原先两条 spec 都是 `tween(npDuration(Toast))`——只缩放、不认 Reduce Motion，
    // 系统「移除动画」下 Toast 仍会整段滑 240ms。走 npMotion 后由它统一覆盖成 ReduceFade。
    val slideAllowed = npDisplacementAllowed()
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(npMotion(Motion.Toast, easing = NpEasing.Silk)) +
                slideInVertically(npMotion(Motion.Toast, easing = NpEasing.Silk)) {
                    if (slideAllowed) it / 2 else 0
                },
            exit = fadeOut(npMotion(Motion.Toast, easing = NpEasing.SilkExit)) +
                slideOutVertically(npMotion(Motion.Toast, easing = NpEasing.SilkExit)) {
                    if (slideAllowed) it / 2 else 0
                },
        ) {
            val message = state.message.orEmpty()
            val shape = RoundedCornerShape(NpRadius.chip)
            // E220：动作钮（撤销）与文案同胶囊；命中区 44dp 红线由行高内边距保证
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    // E129：阴影在**胶囊底之外**（同 NpGlass / NpMenuSurface）——
                    // 反写会让阴影贴图落进自己的底上，内缘出现灰带/错位白框。
                    .npShadow(ink, shape, elevation = 12.dp)
                    .clip(shape)
                    .background(ink.surface)
                    .border(1.dp, ink.border, shape),
            ) {
                Text(
                    text = message,
                    style = NpTypography.bodySmall,
                    color = ink.fg,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 18.dp, top = 11.dp, bottom = 11.dp),
                )
                state.actionLabel?.let { label ->
                    Text(
                        text = label,
                        style = NpTypography.bodySmall,
                        color = ink.accent,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 14.dp)
                            .npClickable {
                                state.action?.invoke()
                                state.clear()
                            }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}
