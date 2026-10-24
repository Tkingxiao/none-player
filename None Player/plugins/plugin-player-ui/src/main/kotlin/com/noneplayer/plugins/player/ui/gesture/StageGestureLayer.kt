package com.noneplayer.plugins.player.ui.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 双击落点分区（§4.3）：左右 1/3 = 快退/快进 10s，中间 = 播放/暂停。
 */
enum class StageTapZone { Back, Center, Forward }

/**
 * 竖滑调节分区（§4.3）：左 1/3 = 亮度、右 1/3 = 音量；中 1/3 不响应竖滑。
 */
enum class StageVerticalMode { Brightness, Volume }

/**
 * 舞台手势层（§4.3 分区图 + 状态机，A7 全族修复）。
 *
 * 结构上分两条 `pointerInput`：
 * - **点按族**（单击/双击/长按）：自写 recognizer。单击**延后到双击窗口超时**才提交，
 *   双击命中则取消单击（"连点不能闪"）；长按在位移越过 slop 前才可能触发，
 *   触发后位移再越 slop 立即取消加速。
 * - **拖动族**（横滑 seek / 左竖滑亮度 / 右竖滑音量）：死区先于分轴；
 *   子树（进度条等）已消费的事件不接管；多指只跟首个 down 的 pointerId。
 *
 * 两族都不在死区内消费事件——左右/顶部/底部留给系统手势。
 *
 * A7 的关键修复点：
 * 1. **开关读"当前值"**：回调在触发时才通过 [rememberUpdatedState] 读当前开关
 *    （锁屏/设置关闭时双击不 seek、长按不变速），pointerInput 不再用 `Unit` 当 key；
 * 2. **点按层边缘死区**：左右 max(24dp, systemGesture inset) + 顶部 48dp 内直接返回；
 * 3. **拖动层四边死区先于分轴**：底部 max(48dp, systemGesture inset)，横竖都不接管；
 * 4. **已消费事件不接管**：`awaitFirstDown(requireUnconsumed = true)`，拖动循环
 *    session 里任 change 被消费即退出——拖进度条不会再让舞台 seek/调亮度；
 * 5. **长按正向守卫**：位移超 slop 前不触发加速，开始加速后再超 slop 立即取消；
 * 6. **onVerticalEnd**：抬手立即收 HUD，不再等 900ms 超时；
 * 7. **时长/状态在回调执行时读**：见调用方（PlayerScreen）——手势层只传增量；
 * 8. **自动隐藏**：见 PlayerScreen 的 key 与守卫；
 * 9. **多指只取首个 down 的 pointerId**：不再遍历所有 pressed change 累加（双倍增益）。
 */
@Composable
internal fun Modifier.npStageGestures(
    gesturesEnabled: Boolean,
    /** 任一锁定档（整页锁或 D3 半锁）都停"动作"；点按永远保留（唤控制层/解锁钮）。 */
    stageLocked: Boolean = false,
    deadZones: StageGestureZones = StageGestureZones.Default,
    onTap: () -> Unit = {},
    onDoubleTap: (zone: StageTapZone) -> Unit = {},
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    seekSpanMs: () -> Float = { STAGE_SEEK_FULL_SPAN_MS },
    onSeekStart: () -> Unit = {},
    onSeekDelta: (deltaMs: Float) -> Unit = {},
    onSeekEnd: () -> Unit = {},
    onVerticalStart: (mode: StageVerticalMode) -> Unit = {},
    onVerticalDelta: (mode: StageVerticalMode, deltaPercent: Float) -> Unit = { _, _ -> },
    onVerticalEnd: () -> Unit = {},
): Modifier {
    // 锁屏只停"动作"，不停"点按唤出解锁钮"：动作开关与点按分开。
    val gestureActionsEnabled = gesturesEnabled && !stageLocked
    // A7#1：pointerInput 的 key 只决定重启时机；回调行为一律读进入回调那一刻的 current 值。
    val current = rememberUpdatedState(
        StageGestureCallbacks(
            actionsEnabled = gestureActionsEnabled,
            deadZones = deadZones,
            onTap = onTap,
            onDoubleTap = onDoubleTap,
            onLongPressStart = onLongPressStart,
            onLongPressEnd = onLongPressEnd,
            seekSpanMs = seekSpanMs,
            onSeekStart = onSeekStart,
            onSeekDelta = onSeekDelta,
            onSeekEnd = onSeekEnd,
            onVerticalStart = onVerticalStart,
            onVerticalDelta = onVerticalDelta,
            onVerticalEnd = onVerticalEnd,
        ),
    )
    return this
        .pointerInput(gesturesEnabled) {
            detectStageTapFamily(
                current = { current.value },
            )
        }
        .pointerInput(gestureActionsEnabled, deadZones) {
            if (!gestureActionsEnabled) return@pointerInput
            detectStageDragFamily(
                deadZones = deadZones,
                current = { current.value },
            )
        }
}

/** 一次组合内所有回调/开关的当前快照（A7#1 的"当前值"来源）。 */
private class StageGestureCallbacks(
    val actionsEnabled: Boolean,
    val deadZones: StageGestureZones,
    val onTap: () -> Unit,
    val onDoubleTap: (StageTapZone) -> Unit,
    val onLongPressStart: () -> Unit,
    val onLongPressEnd: () -> Unit,
    val seekSpanMs: () -> Float,
    val onSeekStart: () -> Unit,
    val onSeekDelta: (Float) -> Unit,
    val onSeekEnd: () -> Unit,
    val onVerticalStart: (StageVerticalMode) -> Unit,
    val onVerticalDelta: (StageVerticalMode, Float) -> Unit,
    val onVerticalEnd: () -> Unit,
)

// ================================ 点按族 ================================

/**
 * 点按族识别器：单击/双击/长按。
 *
 * 不消费 down/up（除长按加速期间）——舞台之外还有系统手势与子树控件，
 * 点按层只负责"读事件做判定"，**不抢**事件所有权；当子树消费后本层自动让路。
 */
private suspend fun PointerInputScope.detectStageTapFamily(
    current: () -> StageGestureCallbacks,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        if (width <= 0f || height <= 0f) return@awaitEachGesture
        val zones = current().deadZones.toPx(this)
        val slopPx = STAGE_SLOP_DP.dp.toPx()
        // A7#2：点按层边缘死区（左右 + 顶部）内直接返回，事件留给系统手势。
        if (isTapDeadZone(down.position.x, down.position.y, width, zones)) return@awaitEachGesture

        val pressEnd = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                awaitPressEnd(down.id, slopPx)
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            // A7#5：按住不动超过长按时长 → 3x 加速；位移守卫由 awaitPressEnd 前置完成。
            val actions = current()
            if (actions.actionsEnabled) {
                actions.onLongPressStart()
                awaitBoostHold(down.id, slopPx, actions.onLongPressEnd)
            }
            return@awaitEachGesture
        }

        if (pressEnd !is StagePressEnd.Up) {
            // 越过 slop / 被子树消费 / 越界：让给拖动族或子树，点按族不提交。
            return@awaitEachGesture
        }

        val actions = current()
        if (!actions.actionsEnabled) {
            // A7#1：当前"关手势/锁屏"时单击立即提交（不压双击延迟），
            // 双击/长按在本分支根本不会触发——修的就是"锁屏了双击还在 seek"。
            actions.onTap()
            return@awaitEachGesture
        }

        // 延后提交：等双击窗口过去、或等到第二次按下（A7#1 实现要点：不得先切后撤）。
        val secondDown = awaitSecondDown(
            firstUpUptimeMs = (pressEnd as StagePressEnd.Up).uptimeMs,
            width = width,
            zones = zones,
        )
        if (secondDown == null) {
            // 双击窗口内没有第二次有效按下（或第二次被子树/死区接管）→ 单击成立。
            current().onTap()
            return@awaitEachGesture
        }

        // 第二次按下的长按/双击语义也要看"当前"开关。
        val secondActions = current()
        if (!secondActions.actionsEnabled) {
            secondActions.onTap()
            return@awaitEachGesture
        }

        val secondEnd = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                awaitPressEnd(secondDown.id, slopPx)
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            // 双击的第二下按住不放：与 detectTapGestures 一致，按长按处理。
            val boosting = current()
            if (boosting.actionsEnabled) {
                boosting.onLongPressStart()
                awaitBoostHold(secondDown.id, slopPx, boosting.onLongPressEnd)
            }
            return@awaitEachGesture
        }

        when (secondEnd) {
            is StagePressEnd.Up -> {
                val finalActions = current()
                if (finalActions.actionsEnabled) {
                    finalActions.onDoubleTap(tapZoneForX(secondEnd.position.x, width))
                } else {
                    finalActions.onTap()
                }
            }
            // 第二下被拖走/取消：丢弃待提交的单击——"先切后撤"会闪。
            else -> Unit
        }
    }
}

/** 等待"第二次按下"（双击窗口内）；超时、被子树消费或落在死区都返回 null。 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUpUptimeMs: Long,
    width: Float,
    zones: StageGestureZonePx,
): PointerInputChange? {
    val timeoutMs = viewConfiguration.doubleTapTimeoutMillis
    val minTimeMs = viewConfiguration.doubleTapMinTimeMillis
    return withTimeoutOrNull<PointerInputChange?>(timeoutMs) {
        var second: PointerInputChange? = null
        while (second == null) {
            val event = awaitPointerEvent()
            val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
            if (!isSecondTapValid(firstUpUptimeMs, down.uptimeMillis, timeoutMs, minTimeMs)) continue
            // 第二次按下被舞台子树（控制条/进度条）消费，或落在系统手势死区：
            // 这不是双击，也不该再等——直接按单击提交（返回 null 结束等待）。
            if (down.isConsumed || isTapDeadZone(down.position.x, down.position.y, width, zones)) {
                return@withTimeoutOrNull null
            }
            second = down
        }
        second
    }
}

/** 一次按压的结局。 */
private sealed interface StagePressEnd {
    class Up(val position: Offset, val uptimeMs: Long) : StagePressEnd

    object Canceled : StagePressEnd

    object SlopExceeded : StagePressEnd
}

/**
 * 等待一次按压结束：抬手 / 被消费 / 越界 / 位移越过 slop。
 *
 * A7#9：只跟踪首个 down 的 pointerId——其余手指的位移不计入（旧实现遍历全部
 * pressed change 累加，两指滑动就是双倍增益）。
 */
private suspend fun AwaitPointerEventScope.awaitPressEnd(
    primaryId: PointerId,
    slopPx: Float,
): StagePressEnd {
    var total = Offset.Zero
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) return StagePressEnd.Canceled
        val change = event.changes.firstOrNull { it.id == primaryId } ?: return StagePressEnd.Canceled
        if (change.isOutOfBounds(size, extendedTouchPadding)) return StagePressEnd.Canceled
        if (!change.pressed) return StagePressEnd.Up(change.position, change.uptimeMillis)
        total += change.positionChange()
        if (movementCancelsLongPress(total.getDistance(), slopPx)) return StagePressEnd.SlopExceeded
    }
}

/**
 * 长按加速保持阶段（A7#5）：
 * - 加速中位移再越 slop → **立即** onLongPressEnd 撤销加速（只调一次）；
 * - 期间消费全部事件，拖动族与子树在此阶段不再接管（避免"3x + seek"叠加）；
 * - finally 兜底：抬手/取消/异常都保证 onLongPressEnd 恰好一次。
 */
private suspend fun AwaitPointerEventScope.awaitBoostHold(
    primaryId: PointerId,
    slopPx: Float,
    onLongPressEnd: () -> Unit,
) {
    var total = Offset.Zero
    var ended = false
    fun finish() {
        if (!ended) {
            ended = true
            onLongPressEnd()
        }
    }
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == primaryId }
            if (change == null || !change.pressed) {
                finish()
                return
            }
            if (!ended) {
                total += change.positionChange()
                if (movementCancelsLongPress(total.getDistance(), slopPx)) finish()
            }
            event.changes.forEach { it.consume() }
        }
    } finally {
        finish()
    }
}

// ================================ 拖动族 ================================

/**
 * 拖动族识别器：横滑 seek / 左 1/3 竖滑亮度 / 右 1/3 竖滑音量。
 *
 * Pending 阶段不消费；判定出轴后才消费，点按族与子树据此自动让路。
 */
private suspend fun PointerInputScope.detectStageDragFamily(
    deadZones: StageGestureZones,
    current: () -> StageGestureCallbacks,
) {
    awaitEachGesture {
        // A7#4：子树（进度条/控制条）已经消费过的 down 不接管。
        val down = awaitFirstDown(requireUnconsumed = true)
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        if (width <= 0f || height <= 0f) return@awaitEachGesture
        val zones = deadZones.toPx(this)
        val slopPx = STAGE_SLOP_DP.dp.toPx()
        // A7#3：四边死区先于任何分轴判断——死区内横竖都不接管、不消费。
        if (isDragDeadZone(down.position.x, down.position.y, width, height, zones)) {
            return@awaitEachGesture
        }
        val primaryId = down.id
        var total = Offset.Zero
        var kind: StageDragKind? = null
        while (kind == null) {
            val event = awaitPointerEvent()
            // A7#4：子树已消费该事件（例如拖 NpSeekBar）→ 舞台立即让路。
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            // A7#9：只看首个 down 的 pointerId。
            val change = event.changes.firstOrNull { it.id == primaryId } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            total += change.positionChange()
            if (total.getDistance() < slopPx) continue
            kind = decideDragKind(
                totalX = total.x,
                totalY = total.y,
                startX = down.position.x,
                startY = down.position.y,
                width = width,
                height = height,
                zones = zones,
            ) ?: return@awaitEachGesture
        }
        val dragKind = kind ?: return@awaitEachGesture

        val actions = current()
        when (dragKind) {
            StageDragKind.Seek -> actions.onSeekStart()
            StageDragKind.Brightness -> actions.onVerticalStart(StageVerticalMode.Brightness)
            StageDragKind.Volume -> actions.onVerticalStart(StageVerticalMode.Volume)
        }
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                val change = event.changes.firstOrNull { it.id == primaryId } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                when (dragKind) {
                    StageDragKind.Seek ->
                        current().onSeekDelta(
                            seekDeltaMs(delta.x, width, current().seekSpanMs()),
                        )
                    StageDragKind.Brightness ->
                        current().onVerticalDelta(
                            StageVerticalMode.Brightness,
                            verticalDeltaPercent(delta.y, height),
                        )
                    StageDragKind.Volume ->
                        current().onVerticalDelta(
                            StageVerticalMode.Volume,
                            verticalDeltaPercent(delta.y, height),
                        )
                }
                change.consume()
            }
        } finally {
            when (dragKind) {
                StageDragKind.Seek -> current().onSeekEnd()
                else -> current().onVerticalEnd()
            }
        }
    }
}

/** dp 参数 → px，供纯策略函数使用。 */
private fun StageGestureZones.toPx(density: Density): StageGestureZonePx = with(density) {
    StageGestureZonePx(
        left = leftDp.dp.toPx(),
        right = rightDp.dp.toPx(),
        top = topDp.dp.toPx(),
        bottom = bottomDp.dp.toPx(),
    )
}
