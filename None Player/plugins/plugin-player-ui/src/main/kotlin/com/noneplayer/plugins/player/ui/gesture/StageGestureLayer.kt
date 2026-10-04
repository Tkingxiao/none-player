package com.noneplayer.plugins.player.ui.gesture

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 双击落点分区（§4.3）：左右 1/3 = 快退/快进 10s，中间 = 播放/暂停。
 */
enum class StageTapZone { Back, Center, Forward }

/**
 * 竖滑调节分区（§4.3）：左 1/3 = 亮度、右 1/3 = 音量；中 1/3 不响应竖滑。
 */
enum class StageVerticalMode { Brightness, Volume }

private enum class DragKind { Seek, Brightness, Volume }

/** 起手判定阈值（§4.3 状态机：位移 > 10dp 才分派轴） */
private const val GESTURE_SLOP_DP = 10f

/** 左右边缘不拦截宽度——留给系统返回手势（§4.3） */
private const val GESTURE_EDGE_EXCLUSION_DP = 24f

/** 竖滑手势在上/下这段距离内不启动——留给状态栏呼出与底部导航（§4.3） */
private const val GESTURE_VERTICAL_DEAD_ZONE_DP = 48f

/** 横滑 seek 的满幅基准：整屏宽 = ±90s（§4.3） */
private const val GESTURE_SEEK_FULL_SPAN_MS = 90_000f

/**
 * 舞台手势层（§4.3 分区图 + 状态机，替代旧 `npStageGestures`）。
 *
 * 与旧实现的三处行为差异，均来自规格而非实现偏好：
 * 1. **轴语义换位**：横滑 = seek（带目标时间气泡），竖滑 = 左 1/3 亮度 / 右 1/3 音量。
 *    旧实现把亮度/音量放在横滑上、竖滑完全无动作——与市面播放器肌肉记忆相反。
 * 2. **双击分区**：左右 1/3 = ∓/±10s，**中心 = 播放/暂停**（旧实现整屏双击都是 ±10s，
 *    想暂停只能去控制条）。
 * 3. **系统手势避让**：左右边缘 24dp 整段不拦截（系统返回）、竖滑在上/下 48dp 内
 *    不启动（状态栏/导航条）。旧实现全屏拦截，与手势导航打架。
 *
 * 实现分两个 `pointerInput`：点按族（单击/双击/长按）交给 `detectTapGestures`——
 * 它自带二次点击判定与 400ms 长按时钟；拖动族自写 `awaitEachGesture` 状态机，
 * Pending 阶段**不消费任何事件**（点按族照常工作），越过 slop 判定出轴后才消费，
 * 同一时刻 `detectTapGestures` 会因事件被消费而自动取消——优先级
 * 「横滑 > 竖滑 > 双击 > 长按 > 单击」由"谁先越过阈值谁赢"自然达成，无需集中仲裁。
 */
fun Modifier.npStageGestures(
    gesturesEnabled: Boolean,
    onTap: () -> Unit = {},
    onDoubleTap: (zone: StageTapZone) -> Unit = {},
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    onSeekStart: () -> Unit = {},
    onSeekDelta: (deltaMs: Float) -> Unit = {},
    onSeekEnd: () -> Unit = {},
    onVerticalStart: (mode: StageVerticalMode) -> Unit = {},
    onVerticalDelta: (mode: StageVerticalMode, deltaPercent: Float) -> Unit = { _, _ -> },
    onVerticalEnd: () -> Unit = {},
): Modifier =
    pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                if (gesturesEnabled) {
                    val w = size.width.toFloat()
                    onDoubleTap(
                        when {
                            offset.x < w / 3f -> StageTapZone.Back
                            offset.x > w * 2f / 3f -> StageTapZone.Forward
                            else -> StageTapZone.Center
                        },
                    )
                }
            },
            onLongPress = { if (gesturesEnabled) onLongPressStart() },
            onPress = {
                // 长按未触发就松手（普通点按）时无需恢复；长按后松手走这里恢复原速
                tryAwaitRelease()
                onLongPressEnd()
            },
        )
    }
    .pointerInput(gesturesEnabled) {
        if (!gesturesEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0f || height <= 0f) return@awaitEachGesture
            val edgePx = GESTURE_EDGE_EXCLUSION_DP.dp.toPx()
            val deadPx = GESTURE_VERTICAL_DEAD_ZONE_DP.dp.toPx()
            val slopPx = GESTURE_SLOP_DP.dp.toPx()
            val startX = down.position.x
            val startY = down.position.y
            // 左右边缘：整段不拦截（含拖动），让系统返回手势原样收到事件
            if (startX < edgePx || startX > width - edgePx) return@awaitEachGesture

            var total = Offset.Zero
            // Pending → 判定轴。抬手（无拖动的点按）直接退出，交给点按族。
            var decided: DragKind? = null
            while (decided == null) {
                val event = awaitPointerEvent()
                val changes = event.changes
                if (changes.all { !it.pressed }) return@awaitEachGesture
                for (change in changes) if (change.pressed) total += change.positionChange()
                if (total.getDistance() < slopPx) continue
                // 横滑优先（§4.3 优先级）；竖滑受上下死区与中 1/3 限制
                if (abs(total.x) >= abs(total.y)) {
                    decided = DragKind.Seek
                    break
                }
                if (startY < deadPx || startY > height - deadPx) return@awaitEachGesture
                when {
                    startX < width / 3f -> decided = DragKind.Brightness
                    startX > width * 2f / 3f -> decided = DragKind.Volume
                    // 中 1/3 的竖滑不承担任何手势，也不消费——保持"只有双击/单击"语义
                    else -> return@awaitEachGesture
                }
            }
            // 循环只经由 decided != null 退出（其余分支直接 return），此处非空是必然
            val kind = decided ?: return@awaitEachGesture

            when (kind) {
                DragKind.Seek -> onSeekStart()
                DragKind.Brightness -> onVerticalStart(StageVerticalMode.Brightness)
                DragKind.Volume -> onVerticalStart(StageVerticalMode.Volume)
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val changes = event.changes
                    var anyPressed = false
                    for (change in changes) {
                        if (!change.pressed) continue
                        anyPressed = true
                        val delta = change.positionChange()
                        when (kind) {
                            DragKind.Seek ->
                                onSeekDelta(delta.x / width * GESTURE_SEEK_FULL_SPAN_MS)
                            DragKind.Brightness ->
                                onVerticalDelta(StageVerticalMode.Brightness, delta.y / height * 100f)
                            DragKind.Volume ->
                                onVerticalDelta(StageVerticalMode.Volume, delta.y / height * 100f)
                        }
                        change.consume()
                    }
                    if (!anyPressed) break
                }
            } finally {
                when (kind) {
                    DragKind.Seek -> onSeekEnd()
                    else -> onVerticalEnd()
                }
            }
        }
    }
