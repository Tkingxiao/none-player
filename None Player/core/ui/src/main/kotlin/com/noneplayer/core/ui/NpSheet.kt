package com.noneplayer.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 底部 Sheet 体系（设计稿 `.sheet / .sheet-scrim / .grab / .opt / .opt-tile`）。
 *
 * 不用 Material `ModalBottomSheet`：它的圆角/把手/遮罩/动画曲线都不可控，而设计稿
 * 对这套浮层有精确规格——r20 顶角 + 38×4 把手 + 82vh 上限 + `.32s cubic-bezier(.34,1.1,.5,1)`
 * 弹入。自绘的代价（进出动画状态机）换的是与设计稿逐像素对齐。
 */

/**
 * 底部弹层：遮罩 + 容器（surface、顶 r20、上边框、把手、可选标题）+ 内容槽。
 *
 * 内容超过 82% 屏高时内部滚动；点遮罩/系统返回键关闭（都带退场动画）。
 * [content] 的竖向滚动已由容器接管，调用方不要再套 `verticalScroll`。
 */
@Composable
fun NpSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = LocalNpInk.current
    val reduceMotion = rememberReduceMotion()
    // 进出场状态机：进场后 visible=true；任何关闭路径先 visible=false，动画走完再回调
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    fun close() {
        if (visible) visible = false
    }
    val sheetDuration = if (reduceMotion) 1 else Motion.Sheet
    LaunchedEffect(visible) {
        if (!visible) {
            delay(sheetDuration.toLong() + 32L)
            onDismiss()
        }
    }
    BackHandler(enabled = true) { close() }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = sheetDuration, easing = NpEasing.Sheet),
        label = "npSheetProgress",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 设计稿 .sheet{max-height:82vh}——在 BoxWithConstraints 作用域先固化，
        // 内层 lambda 里拿不到这个隐式接收器
        val sheetMaxHeight = maxHeight * 0.82f
        // 遮罩（设计稿 --scrim 62%，fade 跟随 sheet 进度）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(progress)
                .background(ink.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { close() },
                ),
        )
        val shape = RoundedCornerShape(topStart = NpRadius.sheet, topEnd = NpRadius.sheet)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = (1f - progress) * size.height },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    .clip(shape)
                    .background(ink.surface)
                    .border(1.dp, ink.border, shape)
                    .npShadow(ink, shape, elevation = 18.dp)
                    .npTopHairline(ink),
            ) {
                // 把手（设计稿 `.grab`：38×4 r999 居中）
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 6.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(ink.border)
                        .align(Alignment.CenterHorizontally),
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = NpTypography.titleMedium,
                            color = ink.fg,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                    content()
                }
            }
        }
    }
}

/**
 * `.sheet .opt`：弹层选项行 —— 13/12 padding、r12、选中 accent-soft 底 + accent 字 600。
 */
@Composable
fun NpOptRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NpRadius.button))
            .background(if (selected) ink.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .npClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Text(
            text = text,
            style = NpTypography.bodyMedium,
            color = if (selected) ink.accent else ink.fg,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/**
 * `.opt-tile`：圆形图标 tile（4 列网格用）——50dp 圆 + 11.5sp 标签，
 * 选中 accent 实底反白，[danger] 走 danger 色（"移除"）。
 */
@Composable
fun NpQuickTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val ink = LocalNpInk.current
    val tint = when {
        danger -> ink.danger
        selected -> ink.bg
        else -> ink.fg
    }
    val circleColor = when {
        selected -> ink.accent
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Column(
        modifier = modifier.npPressScale(onClick = onClick, pressedScale = 0.95f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(NpDimens.themeTile)
                .clip(CircleShape)
                .background(circleColor)
                .border(1.dp, if (danger) ink.danger else ink.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = NpTypography.labelSmall,
            color = if (danger) ink.danger else ink.fg,
            textAlign = TextAlign.Center,
        )
    }
}
