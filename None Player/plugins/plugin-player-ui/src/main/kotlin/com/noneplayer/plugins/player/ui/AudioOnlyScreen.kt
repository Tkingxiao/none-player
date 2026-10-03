package com.noneplayer.plugins.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpTrackBar
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.bouncySpring
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.PlaybackState

/**
 * 仅听音频整屏页（设计稿 `.audio`，CSS 538-594 / JS enterAudio-renderAudio 1575-1642）——
 * 网易云式"只听不看"形态：辉光 + 旋转黑胶 + 落针唱臂 + 控制排 + 工具 chips。
 *
 * **省电语义**：进入本页后播放页不再组合 VideoStage（SurfaceView 从树上消失，
 * media3 无渲染面即停止视频输出，音频照常走后台服务）。
 */
@Composable
fun AudioOnlyScreen(
    session: PlaybackSession,
    onBack: () -> Unit,
    favorited: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    speed: Float = 1f,
    speedPickerVisible: Boolean = false,
    onPickSpeed: (Float) -> Unit = {},
    onOpenEpisodes: () -> Unit = {},
    speedText: String = "1.0x",
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val nowPlaying by session.nowPlaying.collectAsStateWithLifecycle()
    val snapshot = nowPlaying ?: return
    val player = session.player ?: return
    val isPlaying = snapshot.isPlaying
    val loopMode by session.loopMode.collectAsStateWithLifecycle()
    val loopIcon = when (loopMode) {
        NpLoopMode.OFF -> NpIcon.ListView
        NpLoopMode.ONE -> NpIcon.Rotate
        NpLoopMode.SHUFFLE -> NpIcon.Sort
        NpLoopMode.ALL -> NpIcon.Sound
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ink.bg),
    ) {
        // 径向 accent 辉光（设计稿 .audio .glow：120%×55% at 50% 2%）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(ink.accentSoft, Color.Transparent),
                        radius = 900f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 安全区：顶部状态栏 / 底部手势条
                .safeDrawingPadding(),
        ) {
            // 顶部行：返回 + 标题/来源（设计稿 .audio-top）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NpIconButton(
                    icon = NpIcon.ArrowLeft,
                    contentDescription = "切回视频",
                    onClick = onBack,
                    tint = ink.fg,
                )
                Column {
                    Text(
                        text = "正在播放",
                        style = NpTypography.titleSmall,
                        color = ink.fg,
                    )
                    Text(
                        text = (snapshot.item.folder ?: "本地音乐") + " · 音频",
                        style = com.noneplayer.core.ui.NpMonoSmall,
                        color = ink.muted,
                    )
                }
            }
            // 黑胶（设计稿 .vinyl：min(258px,64vw)，22s 匀速）
            VinylDisc(
                coverUri = snapshot.coverUri,
                title = snapshot.title,
                isPlaying = isPlaying,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.64f)
                    .widthIn(max = 258.dp),
            )
            // 信息（设计稿 .audio-info）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = snapshot.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a"),
                    style = NpTypography.titleLarge,
                    color = ink.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "未知艺术家 · " + (snapshot.item.folder ?: "本地音乐"),
                    style = NpTypography.bodyMedium,
                    color = ink.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 歌词带占位（真 .lrc 解析后置；P3）
            Text(
                text = "仅听音频模式 · 画面已关闭以省电",
                style = com.noneplayer.core.ui.NpMonoSmall,
                color = ink.muted.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 10.dp),
            )
            // 进度（设计稿 .audio-track）
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                NpTrackBar(
                    progress = snapshot.progress,
                    onSeek = { fraction ->
                        (snapshot.durationMs * fraction).let { player.seekTo(it.toLong()) }
                    },
                    trackColor = ink.fgSoft,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatTimestamp(snapshot.positionMs),
                        style = com.noneplayer.core.ui.NpMonoSmall,
                        color = ink.muted,
                    )
                    Text(
                        text = formatTimestamp(snapshot.durationMs),
                        style = com.noneplayer.core.ui.NpMonoSmall,
                        color = ink.muted,
                    )
                }
            }
            // 控制排（设计稿 .audio-controls：循环(sm 44) / 上一首(56) / 播放主钮(56) /
            // 下一首(56) / 播放列表(sm 44)，gap 18 居中）。此前只做了中间三钮，
            // 两侧的循环与播放列表缺失——与设计稿的 5 钮结构不符。
            val loopModes = listOf(NpLoopMode.OFF, NpLoopMode.ONE, NpLoopMode.SHUFFLE, NpLoopMode.ALL)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            ) {
                NpIconButton(
                    icon = loopIcon,
                    contentDescription = "循环模式",
                    onClick = {
                        session.setLoopMode(loopModes[(loopModes.indexOf(loopMode) + 1) % loopModes.size])
                    },
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (loopMode == NpLoopMode.OFF) ink.muted else ink.accent,
                )
                NpIconButton(
                    icon = NpIcon.SkipPrevious,
                    contentDescription = "上一首",
                    onClick = { session.previous() },
                    size = 56.dp,
                    iconSize = 26.dp,
                    tint = ink.fg,
                    enabled = snapshot.hasPrevious,
                )
                Box(
                    modifier = Modifier
                        .size(NpDimens.primaryPlay)
                        .clip(CircleShape)
                        .background(ink.accent)
                        .npPressScale(onClick = { session.toggle() }, pressedScale = 0.93f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPlaying) {
                        AudioPauseGlyph(tint = ink.bg)
                    } else {
                        Icon(
                            imageVector = NpIcon.Play,
                            contentDescription = "播放",
                            tint = ink.bg,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                NpIconButton(
                    icon = NpIcon.SkipNext,
                    contentDescription = "下一首",
                    onClick = { session.next() },
                    size = 56.dp,
                    iconSize = 26.dp,
                    tint = ink.fg,
                    enabled = snapshot.hasNext,
                )
                NpIconButton(
                    icon = NpIcon.List,
                    contentDescription = "播放列表",
                    onClick = onOpenEpisodes,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = ink.muted,
                )
            }
            // 工具 chips（设计稿 .audio-tools：2 列网格、max 420、chip 居中）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolChip(
                        icon = NpIcon.Heart,
                        label = if (favorited) "已收藏" else "收藏",
                        on = favorited,
                        onClick = onToggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                    ToolChip(
                        icon = NpIcon.Film,
                        label = "切回视频",
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolChip(
                        icon = NpIcon.Speed,
                        label = "倍速 $speedText",
                        on = speed != 1f || speedPickerVisible,
                        onClick = onOpenSpeed,
                        modifier = Modifier.weight(1f),
                    )
                    // 设计稿此格是「睡眠定时」——该能力尚未落地，不做假动作：
                    // 换成真实的后台播放状态指示（同一格的诚实替代）。
                    ToolChip(
                        icon = NpIcon.Headphones,
                        label = "后台播放中",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                }
                // 倍速选择条：与播放页同一交互（点钮 → 钮下列表），不走底部弹层
                androidx.compose.animation.AnimatedVisibility(
                    visible = speedPickerVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    SpeedChoiceRow(
                        speed = speed,
                        onPick = onPickSpeed,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 工具 chip（设计稿 .tool-chip：胶囊 + 图标 + 文字；.on 转 accent） */
@Composable
private fun ToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val content = if (on) ink.accent else ink.muted
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(if (on) ink.accentSoft else Color.Transparent)
            .border(1.dp, if (on) ink.accentLine else ink.border, RoundedCornerShape(NpRadius.chip))
            .npPressScale(onClick = onClick, pressedScale = 0.96f)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 设计稿 .audio-tools .tool-chip{justify-content:center;width:100%}
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text = label, style = NpTypography.bodySmall, color = content)
    }
}

/**
 * 黑胶唱片（设计稿 .disc + .tonearm）。
 *
 * 旋转用 withFrameMillis 累加角度：isPlaying=false 时停止累加（设计稿
 * `animation-play-state:paused`），恢复播放从停住的角度继续——比 infinite transition
 * 重启归零更贴近"唱片停在原地"的物理直觉。
 */
@Composable
private fun VinylDisc(
    coverUri: String?,
    title: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            rotation = (rotation + (now - last) * 360f / Motion.Spin) % 360f
            last = now
        }
    }
    // 唱臂角度：播放落针 2°，暂停抬起 -24°（设计稿 .tonearm）
    val tonearmAngle by animateFloatAsState(
        targetValue = if (isPlaying) 2f else -24f,
        animationSpec = bouncySpring(Motion.Tonearm),
        label = "tonearm",
    )
    Box(modifier = modifier.aspectRatio(1f)) {
        // 唱片本体
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2A2A2A),
                            Color(0xFF161616),
                        ),
                    ),
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 纹理环（设计稿 repeating-radial：3px 透明 + 1px 白 6%）
                val rings = (size.minDimension / 2 / 7.dp.toPx()).toInt()
                for (i in 3..rings) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = i * 7.dp.toPx(),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
            }
            // 封面（圆形裁切，占唱片 64%）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.64f)
                    .clip(CircleShape)
                    .background(
                        com.noneplayer.core.ui.NpMediaColor.coverBrush(
                            com.noneplayer.core.ui.NpMediaColor.itemHue(null, isAudio = true),
                        ),
                    ),
            ) {
                if (coverUri != null) {
                    MiniCoverImage(
                        uriString = coverUri,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 中心孔（设计稿 .disc .hole：16% 圆 + bg 内环）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.16f)
                        .clip(CircleShape)
                        .background(ink.surface)
                        .border(6.dp, ink.bg, CircleShape),
                )
            }
        }
        // 唱臂（pivot 在右上 88%/6%）
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(width = 90.dp, height = 150.dp)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.9f, 0.05f)
                    rotationZ = tonearmAngle
                },
        ) {
            val pivot = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.05f)
            val end = androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.78f)
            drawLine(
                color = Color(0xFFC9C2B8),
                start = pivot,
                end = end,
                strokeWidth = 5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawCircle(
                color = Color(0xFFDDD6CB),
                radius = 11.dp.toPx(),
                center = pivot,
            )
            drawCircle(
                color = Color(0xFF4A443C),
                radius = 6.dp.toPx(),
                center = end,
            )
        }
    }
}

/** 音频页主播放键的暂停双竖条（bg 反色） */
@Composable
private fun AudioPauseGlyph(tint: Color) {
    Row {
        Box(
            modifier = Modifier
                .size(width = 6.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(width = 6.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
    }
}
