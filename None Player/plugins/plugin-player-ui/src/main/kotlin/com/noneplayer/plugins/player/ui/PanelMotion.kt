package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npMotion
import kotlinx.coroutines.delay

/**
 * M18 入场：面板挂载后从 [from] 弹到 1、同时淡入（`bouncySpring(Switch)`）。
 *
 * 返回 State 而不是值，是为了让 `graphicsLayer` 在绘制期读——每一帧不触发重组。
 * 错误/结束原先是硬切（Q9）：一个"出事了"的面板直接拍在脸上，比淡入更让人慌。
 */
@Composable
internal fun npPanelEntrance(from: Float): Pair<State<Float>, State<Float>> {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val spec = npMotion<Float>(Motion.Switch, bouncy = true)
    return animateFloatAsState(if (shown) 1f else from, spec, label = "panelScale") to
        animateFloatAsState(if (shown) 1f else 0f, spec, label = "panelAlpha")
}

/**
 * 模态卡/面板的**对称进出场**（审查批次 C2 / C3）。
 *
 * [npPanelEntrance] 只有入场：它靠"挂载即显示"驱动，没有退场状态机，
 * 所以面板只能瞬间卸载。结果是同一批浮层里 `NpSheet`/FS 菜单有优雅开合，
 * 文件信息卡与选集面板却"啪"地出现又"啪"地消失——产品内不一致。
 *
 * 这里改成以 [visible] 为唯一驱动：
 *  - 进场：scale [from] → 1 + alpha 0 → 1，走 `Switch` bouncy（与既有入场同手感）；
 *  - 退场：反向，走 `Field`(150) 加速离开（§3.9 进慢出快）；
 *  - 全部返回 State 供 `graphicsLayer` 在**绘制期**读，逐帧不重组面板内容；
 *  - 位移/缩放受 [npDisplacementAllowed] 门控，轻量档与 Reduce Motion 退化成纯淡。
 *
 * 调用方负责在退场动画走完之后才真正把面板移出组合树，否则面板会在退场途中直接消失。
 */
@Composable
internal fun npPanelTransition(
    visible: Boolean,
    from: Float = 0.96f,
): Pair<State<Float>, State<Float>> {
    val scaleAllowed = npDisplacementAllowed()
    val enterSpec = npMotion<Float>(Motion.Switch, bouncy = true)
    val exitSpec = npMotion<Float>(Motion.Field)
    val scale: State<Float> = animateFloatAsState(
        targetValue = if (visible) 1f else if (scaleAllowed) from else 1f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "panelScale",
    )
    val alpha: State<Float> = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "panelAlpha",
    )
    return scale to alpha
}

/**
 * 退场播完再真正卸载（审查批次 C2 / C3）。
 *
 * 直接 `if (visible) { Panel() }` 会让退场动画没有机会播；
 * 这里先让 [visible] 落 false、把动画跑完，再回调 [onRemoved] 让调用方移除状态。
 * 期间 [content] 仍留在组合树里（只改 alpha/scale），触摸已由 [onDismiss] 屏蔽。
 */
@Composable
internal fun PanelExitHost(
    visible: Boolean,
    exitMs: Int = 160,
    onRemoved: () -> Unit,
    content: @Composable (visible: Boolean) -> Unit,
) {
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            present = true
        } else if (present) {
            delay(exitMs.toLong())
            present = false
            onRemoved()
        }
    }
    if (present) content(visible)
}

/**
 * 底部抽屉形态的进出场（审查批次 C3）：**自下方滑入 / 向下滑出**。
 *
 * 与居中卡片（[npPanelTransition] 用缩放）区分开——抽屉的运动语义是"从屏幕底部
 * 推上来"，缩放会读成"浮出来"，方向不对。
 * 位移受 [npDisplacementAllowed] 门控；轻量档/Reduce Motion 退化为纯淡入淡出。
 */
@Composable
internal fun npSheetSlide(visible: Boolean): State<Float> {
    val enterSpec = npMotion<Float>(Motion.Panel)
    val exitSpec = npMotion<Float>(Motion.Field)
    return animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "sheetSlide",
    )
}

