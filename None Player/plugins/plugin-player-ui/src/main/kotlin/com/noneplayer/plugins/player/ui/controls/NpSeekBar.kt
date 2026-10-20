package com.noneplayer.plugins.player.ui.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.rememberNpHaptics
import kotlin.math.roundToInt

/**
 * 自绘进度条（§5.4），替代 M3 `Slider`：
 *
 * - **三段式轨道**：已播 `ink.accent` / 已缓冲 `ink.muted` / 未播 `ink.border`
 *   （数据来自 `VideoPlayer.bufferedPositionMs`）——用户能看出"拖过去还有多远要加载"，
 *   这是"seek 为什么卡"的唯一可见解释。三段全部取自色板（§5.4①）：原先硬编
 *   `Color.White@0.45/0.25`，亮色主题下白轨在白底上等于没有轨；
 * - **拖动膨胀**：轨道 `trackLine 3dp → trackLineDragging 4dp`、拇指
 *   `seekThumbIdle 12dp → seekThumbDrag 18dp`，进态 `bouncy(Field)`、回弹
 *   `silk(Press)`（§5.4③——只有"抓起"该有弹性，松手缩回去要干净）；
 * - **落点胶囊**：拖动中拇指上方显示目标时间，松手后 `ReduceFade 120ms` 淡出
 *   并下落 8dp（§5.4②）——落点确认，避免"手一挡就看不见自己拖到了哪"；
 * - **点击定位**：整条触控带（视觉仍是细线，长出来的只有上下留白）点哪儿定位到哪儿；
 *   高度**不低于 [NpDimens.touchTarget] 44dp**，且由组件自身夹取（A27，2026-10-15 订正：
 *   竖屏拆行曾传 18dp，实报"点不中"；旧的小窗 26dp 取舍以 44dp 红线为准）；
 * - **取消 ≠ 提交**（E129 / 审查 #3）：手势被系统边缘返回或父节点打断时走
 *   [onScrubCancel]，**不 seek**——一次被取消的手势不该被读成"确认跳到最后一帧的位置"；
 * - 拖动采用**绝对定位**（滑块跟手到触点），与 M3 Slider 行为一致，
 *   `onScrubChange` 的预览限流与松手精确定位仍由调用方（PlayerScreen）承担。
 *
 * 不用 `Slider` 的原因：它的 track 槽位拿不到"缓冲段"这第三种状态，
 * 而 progress 三态是本组件存在的全部理由。
 */
@Composable
internal fun NpSeekBar(
    durationMs: Long,
    /**
     * 显示位置（A6）：收 [State] 而不是裸值——轨道、拇指与读屏读数都在
     * **绘制 / 布局 / 语义相位**读，250ms 的位置上报只重画，不重组本组件。
     * 拖动中的落点胶囊自己读它（只在那时重组，见 [NpSeekBubble]）。
     */
    displayPositionMs: State<Float>,
    /**
     * 缓冲前沿（A6）：收 [State] 而不是裸值——前沿变化只在**绘制相位**读，
     * 轨道重画一次，不触发本组件与调用方重组（此前页根解包它，前沿一跳整页跟着走）。
     */
    bufferedPositionMs: State<Long>,
    dragging: Boolean,
    onScrubStart: () -> Unit,
    onScrubChange: (positionMs: Float) -> Unit,
    onScrubEnd: () -> Unit,
    /**
     * 手势被**取消**（系统边缘返回 / 父节点拦截）时的出口（E129 / 审查 #3）。
     *
     * 与 [onScrubEnd] 分开是必须的：取消不是"确认跳到最后一帧拖到的位置"。
     * 合成一条会让一次被系统打断的手势变成一次没人要求的跳播。取消方负责把
     * scrubbing 状态清干净并让 `dragging` 落回 false（否则拖动守护会卡住不放手）。
     */
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 透明可交互槽的高度（§3.1 红线 44dp）。视觉轨仍是一条细线，长出来的只有上下留白。
     *
     * 默认取 [NpDimens.touchTarget]。**不再允许低于红线**：A27（2026-10-15 订正）把
     * 2026-10-05 的小窗收窄（26dp/竖屏拆行 18dp）推翻——真机实报"点不中"；调用方
     * 仍传小值时由本组件夹取，新增调用点没有"忘记改"的机会。
     */
    touchBand: Dp = NpDimens.touchTarget,
) {
    val ink = LocalNpInk.current
    // A27：44dp 红线在组件内部兜底（竖屏拆行曾显式传 18dp）。
    val resolvedTouchBand = touchBand.coerceAtLeast(NpDimens.touchTarget)
    val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
    // A6：位置与缓冲前沿的 fraction **都不在组合期算**——它们读在绘制 / 布局 / 语义相位，
    // 250ms 的位置上报只重画轨道，不重组本组件。
    // M7（§7.5）：拖=按住 `Long` / 松手 `Tick`，点击定位=一次 `Tick`。
    // 触觉挂在组件上而不是调用方：这里才分得清"点"与"拖"两条路径。
    val haptics = rememberNpHaptics()

    val expandSpec = npMotion<Dp>(Motion.Field, bouncy = true)
    val shrinkSpec = npMotion<Dp>(Motion.Press)
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) NpDimens.trackLineDragging else NpDimens.trackLine,
        animationSpec = if (dragging) expandSpec else shrinkSpec,
        label = "npSeekTrackHeight",
    )
    val knobRadius by animateDpAsState(
        targetValue = if (dragging) NpDimens.seekThumbDrag / 2 else NpDimens.seekThumbIdle / 2,
        animationSpec = if (dragging) expandSpec else shrinkSpec,
        label = "npSeekKnobRadius",
    )

    // §5.4②：拖动态起落点胶囊，`dragging` 落下时交给 AnimatedVisibility 播退场
    var bubbleVisible by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { bubbleVisible = dragging }

    val density = LocalDensity.current
    val fallAllowed = npDisplacementAllowed()
    val fallPx = remember(density) { with(density) { NpSeekBubbleFallDp.toPx().roundToInt() } }
    val risePx = remember(density) { with(density) { NpSeekBubbleRiseDp.roundToPx() } }
    var bubbleHalfWidthPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(resolvedTouchBand),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidthPx = with(density) { maxWidth.toPx() }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // E8：读屏可达——自绘 Canvas 无默认语义，把进度区间与当前值显式给出，
                // TalkBack 以滑杆（adjustable 之外还需 range info）朗读当前位置
                .semantics {
                    val positionMs = displayPositionMs.value
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        (positionMs / maxPosition).coerceIn(0f, 1f),
                        0f..1f,
                    )
                    // E129（审查 #11）：光有百分比，读屏用户算不出"还剩多久"——
                    // 时间读数与轨道上的胶囊同源，两处读数不会各说各话。
                    stateDescription = if (durationMs > 0L) {
                        "${formatTimestamp(positionMs.toLong())} / ${formatTimestamp(durationMs)}"
                    } else {
                        formatTimestamp(positionMs.toLong())
                    }
                    // E129（审查 #11）：自绘 Canvas 没有可调节动作——读屏能"听到"进度却改不了。
                    // 复用与触摸**完全相同**的提交链（start → change → end），
                    // 于是暂停恢复、预览限流、落点胶囊在无障碍路径上一样成立，
                    // 不为无障碍另开一条只改 UI 的假 seek。
                    setProgress { target ->
                        val position = target.coerceIn(0f, 1f) * maxPosition
                        onScrubStart()
                        onScrubChange(position)
                        onScrubEnd()
                        true
                    }
                }
                .pointerInput(maxPosition) {
                    detectTapGestures { offset ->
                        haptics.performHaptic(NpHaptic.Tick)
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
                            haptics.performHaptic(NpHaptic.Long)
                            onScrubStart()
                            // A27（任务文档 §A27 662）：拖动是绝对定位，起手点同样要夹在轨内——
                            // 手指从槽的上下留白/左右边缘按下时，未夹取的 x 会算出负数或 >时长。
                            onScrubChange(
                                offset.x.coerceIn(0f, size.width.toFloat()) / size.width * maxPosition,
                            )
                        },
                        onDragEnd = {
                            haptics.performHaptic(NpHaptic.Tick)
                            onScrubEnd()
                        },
                        // E129（审查 #3）：取消走独立出口，**不提交 seek**。
                        // 此前两者接的是同一个 `onScrubEnd`，于是被系统边缘返回打断的
                        // 一次拖动会照样 seek 到最后落点并恢复播放——取消被读成了确认。
                        onDragCancel = onScrubCancel,
                    ) { change, _ ->
                        change.consume()
                        onScrubChange(
                            change.position.x.coerceIn(0f, size.width.toFloat()) / size.width *
                                maxPosition,
                        )
                    }
                },
        ) {
            val fraction = (displayPositionMs.value / maxPosition).coerceIn(0f, 1f)
            val trackH = trackHeight.toPx()
            val centerY = size.height / 2f
            val topLeft = Offset(0f, centerY - trackH / 2f)
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            // 未缓冲段（底）
            drawRoundRect(
                color = ink.border,
                topLeft = topLeft,
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // 已缓冲段（中）：只有真的领先于播放位置才画，否则是一段假缓冲。
            // A6：`.value` 读在绘制相位——前沿变化只让轨道重画，不重组本组件。
            val bufferedFraction = (bufferedPositionMs.value / maxPosition).coerceIn(0f, 1f)
            if (bufferedFraction > fraction) {
                drawRoundRect(
                    color = ink.muted,
                    topLeft = topLeft,
                    size = Size(size.width * bufferedFraction, trackH),
                    cornerRadius = radius,
                )
            }
            // 已播段（顶）
            drawRoundRect(
                color = ink.accent,
                topLeft = topLeft,
                size = Size(size.width * fraction, trackH),
                cornerRadius = radius,
            )
            drawCircle(
                color = ink.accent,
                radius = knobRadius.toPx(),
                center = Offset(size.width * fraction, centerY),
            )
        }

        AnimatedVisibility(
            visible = bubbleVisible,
            enter = fadeIn(npMotion(Motion.Field)),
            exit = fadeOut(npMotion(Motion.ReduceFade, easing = NpEasing.SilkExit)) +
                slideOutVertically(npMotion<IntOffset>(Motion.ReduceFade, easing = NpEasing.SilkExit)) {
                    if (fallAllowed) fallPx else 0
                },
            modifier = Modifier
                .align(Alignment.TopStart)
                // A6：胶囊的位置也在**布局相位**读位置——组合期不再碰 `.value`。
                // 拇指贴边时整颗胶囊留在轨内；宽度首帧为 0，此时不做夹取（下一帧修正）。
                .offset {
                    val f = (displayPositionMs.value / maxPosition).coerceIn(0f, 1f)
                    val center = if (bubbleHalfWidthPx > 0f && trackWidthPx > bubbleHalfWidthPx * 2f) {
                        (trackWidthPx * f).coerceIn(
                            bubbleHalfWidthPx,
                            trackWidthPx - bubbleHalfWidthPx,
                        )
                    } else {
                        trackWidthPx * f
                    }
                    IntOffset(center.roundToInt(), -risePx)
                }
                .onSizeChanged { size: IntSize -> bubbleHalfWidthPx = size.width / 2f }
                .graphicsLayer { translationX = -size.width / 2f },
        ) {
            NpSeekBubble(
                displayPositionMs = displayPositionMs,
                textColor = ink.accent,
                backgroundColor = ink.scrim,
            )
        }
    }
}

/**
 * 拖动落点胶囊（§5.4②）。位置上报只在这里重组——**只有拖动中才挂载**，
 * 静止时整棵底栏不随 250ms 的位置更新重组（A6）。
 */
@Composable
private fun NpSeekBubble(
    displayPositionMs: State<Float>,
    textColor: Color,
    backgroundColor: Color,
) {
    Text(
        text = formatTimestamp(displayPositionMs.value.toLong()),
        style = NpMono,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(NpRadius.chip))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 落点胶囊松手后的下落量（§5.4②） */
private val NpSeekBubbleFallDp = 8.dp

/** 落点胶囊静止时高出轨道的距离——把它抬到拇指上方，避免手指遮住读数 */
private val NpSeekBubbleRiseDp = 28.dp
