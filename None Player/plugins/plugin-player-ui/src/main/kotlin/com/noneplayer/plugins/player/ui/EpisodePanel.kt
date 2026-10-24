package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.LocalNpReduceMotion
import com.noneplayer.core.ui.npItemFadeSpec
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.kernel.ui.api.npMorphPart

/**
 * 画面内的「播放结束」轻量态。
 *
 * 与错误面板的分工：错误是**异常**（需要解释原因、给出重试/外部打开/复制地址多条出路），
 * 所以用居中的 `ErrorPanel`；"放完了"是**正常终点**，只需要一个"重播"和一句提示，
 * 用户还能继续看画面、继续点其他地方。
 *
 * 视觉沿用设计稿 `.bigplay` / `.gest-hint` 的深色半透明丸形：
 * 中央 64dp 重播键 + 下方一行等宽小字，全部压在画面之上而非遮住整屏。
 */
@Composable
internal fun EndedOverlay(
    onReplay: () -> Unit,
    hasNext: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (scale, entranceAlpha) = npPanelEntrance(0.96f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = entranceAlpha.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(NpRadius.chip))
                    .npPressScale(onClick = onReplay, pressedScale = 0.92f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NpIcon.Rotate,
                    contentDescription = "重新播放",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = "已播放完",
                style = NpMonoSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .padding(top = NpSpace.md)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // 队列里还有下一集时给一条显式出路：自动连播是所有集的默认行为，
            // 但用户手动点进来看单集的场景下，"下一集"仍然应该是可点的。
            if (hasNext) {
                Text(
                    text = "下一集",
                    style = NpMonoSmall,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = NpSpace.sm)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .npPressScale(onClick = onNext, pressedScale = 0.95f)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * 选集面板（§8.3）——同文件夹队列的列表浮层。
 *
 * 用**底部抽屉式**而非居中对话框：集数可能几十上百，抽屉天然可滚动、
 * 拇指可达（平板/手机一致），且不遮挡正在播的画面上半部分（半透明 scrim + 列表只占下半屏）。
 */
@Composable
internal fun EpisodePanel(
    /** 进出场驱动（审查批次 C3） */
    visible: Boolean = true,
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 审查批次 C3：抽屉自下推入、向下退出（旧实现是硬切）
    val slide by npSheetSlide(visible)
    Box(
        modifier = modifier
            // 遮罩跟着进出场淡入淡出（旧实现恒 0.55，配合硬切更突兀）
            .graphicsLayer {
                alpha = (1f - slide).coerceIn(0f, 1f)
            }
            .background(Color.Black.copy(alpha = 0.55f))
            // 点空白处关闭：给用户"点外面就收起来"的直觉出口
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = NpRadius.sheet, topEnd = NpRadius.sheet),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 只占下半屏：上半留出画面，用户能边看边选
                .fillMaxHeight(0.55f)
                // 审查批次 C3：位移在**绘制期**读，逐帧只重绘不重组整个列表
                .graphicsLayer { translationY = size.height * slide }
                // E49：横屏手势导航条可能在左/右，用 safeDrawing 而非只认底部
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column {
                Text(
                    text = "选集（${queue.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                // M24 增删动画：位移交给 animateItem 的 spring，淡入淡出用 `Switch`(220)
                // ——删掉的一集 fade 掉、下面的行跟着补位，比"瞬间消失"少一次视觉跳跃。
                val queueFade = npItemFadeSpec()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        val selected = index == currentIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .animateItem(fadeInSpec = queueFade, fadeOutSpec = queueFade)
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = item.title.ifBlank { item.uri.substringAfterLast('/') },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 信息区的选集行：选中 = 3dp accent 竖条 + accent 文字 + 声柱。
 * 声柱只在正在播放的那一行起伏；减少动效时停在一组固定高度。
 */
@Composable
internal fun EpisodeRow(
    index: Int,
    title: String,
    durationMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
    /** morph 部件名（横屏左栏同名的那一行）：转屏时它跟着行一起飞到位。 */
    morphKey: String? = null,
    morphDesktop: Boolean? = null,
) {
    val ink = LocalNpInk.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (morphKey == null) Modifier
                else Modifier.npMorphPart(morphKey, desktop = morphDesktop),
            )
            .background(if (selected) ink.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = NpSpace.lg, vertical = 12.dp),
    ) {
        // 选中标记：3×20dp accent 竖条（颜色之外的第二种表达，§0.1 原则 3）
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Text(
            text = "${index + 1}",
            style = NpMono,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(start = NpSpace.sm, end = NpSpace.md),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            PlayingBars(modifier = Modifier.padding(horizontal = NpSpace.sm))
        }
        if (durationMs > 0) {
            Text(
                text = formatTimestamp(durationMs),
                style = NpMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 正在播放的声柱：三根错相起伏。减少动效时用固定高度。 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val reduceMotion = LocalNpReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val fractions = if (reduceMotion) {
        listOf(0.45f, 1f, 0.7f)
    } else {
        val transition = rememberInfiniteTransition(label = "playingBars")
        listOf(0 to 460, 160 to 620, 80 to 540).map { (offset, period) ->
            transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(period, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offset),
                ),
                label = "bar$offset",
            ).value
        }
    }
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp * fraction.coerceIn(0.28f, 1f))
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
