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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
 * - **点击定位**：整条 `seekTouchBand 26dp` 触控带（视觉仍是细线；原规格 32dp，
 *   竖屏小窗里触控带+按钮行占了视频区近三成，实报"底栏占小窗一半"后收窄），
 *   点哪儿定位到哪儿；
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
    val ink = LocalNpInk.current
    val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
    val fraction = (displayPositionMs / maxPosition).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs / maxPosition).coerceIn(0f, 1f)
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
    var bubbleHalfWidthPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(NpDimens.seekTouchBand),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidthPx = with(density) { maxWidth.toPx() }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // E8：读屏可达——自绘 Canvas 无默认语义，把进度区间与当前值显式给出，
                // TalkBack 以滑杆（adjustable 之外还需 range info）朗读当前位置
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
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
                            onScrubChange(offset.x / size.width * maxPosition)
                        },
                        onDragEnd = {
                            haptics.performHaptic(NpHaptic.Tick)
                            onScrubEnd()
                        },
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
                color = ink.border,
                topLeft = topLeft,
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // 已缓冲段（中）：只有真的领先于播放位置才画，否则是一段假缓冲
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

        // 拇指贴边的时候胶囊整颗要留在轨内——半颗跑出屏幕就读不出时间了。
        // 宽度首帧为 0，此时不做夹取（下一帧即修正，视觉上只是起点稍偏）。
        val bubbleCenterPx = if (bubbleHalfWidthPx > 0f && trackWidthPx > bubbleHalfWidthPx * 2f) {
            (trackWidthPx * fraction).coerceIn(
                bubbleHalfWidthPx,
                trackWidthPx - bubbleHalfWidthPx,
            )
        } else {
            trackWidthPx * fraction
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
                .offset(
                    x = with(density) { bubbleCenterPx.toDp() },
                    y = -NpSeekBubbleRiseDp,
                )
                .onSizeChanged { size: IntSize -> bubbleHalfWidthPx = size.width / 2f }
                .graphicsLayer { translationX = -size.width / 2f },
        ) {
            Text(
                text = formatTimestamp(displayPositionMs.toLong()),
                style = NpMono,
                color = ink.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(ink.scrim, RoundedCornerShape(NpRadius.chip))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** 落点胶囊松手后的下落量（§5.4②） */
private val NpSeekBubbleFallDp = 8.dp

/** 落点胶囊静止时高出轨道的距离——把它抬到拇指上方，避免手指遮住读数 */
private val NpSeekBubbleRiseDp = 28.dp
