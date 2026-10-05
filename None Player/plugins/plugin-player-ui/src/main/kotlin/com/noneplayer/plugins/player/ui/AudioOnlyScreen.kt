package com.noneplayer.plugins.player.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMaskFade
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npCoverScrim
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npQuality
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.plugins.player.ui.controls.WaveformSeekBar

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
        // 封面取色舞台底（§4.7）：先于辉光，辉光在它之上"点亮"顶部。
        AudioModeBackdrop(coverUri = snapshot.coverUri)
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
            // 进度（§5.6）：音频模式的主视觉换成装饰波形——同一文件任何时候形状一致，
            // 但比一根细线更像"这里有一段内容"。视频页仍用 §5.4 的实线条，
            // 波形放在画面上会误导"这是音频文件"。
            val quality = npQuality()
            val bufferedMs by player.bufferedPositionMs.collectAsStateWithLifecycle()
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                WaveformSeekBar(
                    mediaKey = HistoryEntry.keyOf(snapshot.item),
                    durationMs = snapshot.durationMs,
                    positionMs = snapshot.positionMs,
                    bufferedFraction = if (snapshot.durationMs > 0)
                        bufferedMs.toFloat() / snapshot.durationMs else 0f,
                    barCount = quality.waveformBars,
                    onSeek = { player.seekTo(it) },
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
                    icon = NpIcon.QueueMusic,
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
                // M21：锚在钮上的紧凑菜单走 `Field`；默认的 fadeIn()/fadeOut() 是 150ms
                // LinearOutSlowIn，和其余钮上菜单不同曲线，同屏会读出两种"出现"的节奏。
                val speedSlideAllowed = npDisplacementAllowed()
                androidx.compose.animation.AnimatedVisibility(
                    visible = speedPickerVisible,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = npMotion(Motion.Field),
                    ) +
                        androidx.compose.animation.slideInVertically(
                            animationSpec = npMotion(Motion.Field),
                        ) { if (speedSlideAllowed) it else 0 },
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = npMotion(Motion.Field),
                    ) +
                        androidx.compose.animation.slideOutVertically(
                            animationSpec = npMotion(Motion.Field),
                        ) { if (speedSlideAllowed) it else 0 },
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

/**
 * 音频模式舞台底（§4.7 + §9.5）：封面铺满 → 放大 1.35 去掉留白边 → 模糊或马赛克 →
 * 主色 @0.30 叠加 → 顶部 `ink.bg` @0.55 渐隐。
 *
 * **两条柔化路径**（§9.5 是全应用唯一允许的 blur 落点，因为被模糊的是一张静态图）：
 * - API 31+ 且档位给了半径 → `Modifier.blur`（内部即 `RenderEffect`），
 *   半径取 `NpMotionQuality.imageBlurRadiusDp`（平衡 24dp / 全效 60dp），
 *   源图按 `NpMotionQuality.imageBlurDownsample` 降采样，GPU 采样点数随之下降。
 * - 其余（API 26–30、轻量档 radius=0）→ 8×8 最近邻放大成马赛克 + 1dp 格线压缝。
 *   读起来是"故意的像素点阵"而不是"图坏了"，成本恒定。
 *
 * 无封面时整块底只剩 `ink.bg`：主色取到 null 时叠加色就是 Transparent，
 * 顶部渐隐仍然画（它垫的是页面底，不该随封面有无而变化）。
 */
@Composable
private fun AudioModeBackdrop(coverUri: String?, modifier: Modifier = Modifier) {
    val ink = LocalNpInk.current
    val quality = npQuality()
    val renderEffectSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val blurRadiusDp = quality.imageBlurRadiusDp
    val mosaic = !renderEffectSupported || blurRadiusDp <= 0f
    // 马赛克路径只要 8 像素；模糊路径按档位降采样（1/2 → 256，1/4 → 128）
    val sourceLongestPx = if (mosaic) 8 else (512 / quality.imageBlurDownsample).coerceAtLeast(96)
    val cover = rememberCoverBitmap(coverUri, sourceLongestPx)
    // 主色：切集时算一次（§9.4），切集过渡由 npCoverScrim 的单条 AccentShift 颜色动画插值
    val accent = npCoverScrim(coverUri)
    val bitmap = cover
    Box(modifier = modifier.fillMaxSize()) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 马赛克靠"不做滤波的最近邻放大"成立；平滑路径交给 blur 去做
                filterQuality = if (mosaic) FilterQuality.None else FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 1.35 的过放是为了让模糊/马赛克后的边缘不露出页面底
                        scaleX = 1.35f
                        scaleY = 1.35f
                    }
                    .then(
                        if (mosaic) Modifier else Modifier.blur(blurRadiusDp.dp),
                    ),
            )
        }
        if (mosaic && bitmap != null) {
            // 1dp 格线压在 8×8 的分块缝上（§9.5 近似方案的收尾）：
            // 缝不压就会读成"图裂了"，压了才读成"点阵"。
            Canvas(modifier = Modifier.fillMaxSize()) {
                val line = 1.dp.toPx()
                for (i in 1 until 8) {
                    val at = size.width * i / 8f
                    val down = size.height * i / 8f
                    drawRect(
                        color = ink.bg.copy(alpha = 0.5f),
                        topLeft = Offset(at - line / 2f, 0f),
                        size = Size(line, size.height),
                    )
                    drawRect(
                        color = ink.bg.copy(alpha = 0.5f),
                        topLeft = Offset(0f, down - line / 2f),
                        size = Size(size.width, line),
                    )
                }
            }
        }
        // 主色叠加：默认一层平涂；全效档换成上重下轻的渐变（§9.6 accentGradient）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (quality.accentGradient) {
                        Modifier.background(
                            Brush.verticalGradient(
                                0f to accent.copy(alpha = 0.42f),
                                1f to accent.copy(alpha = 0.12f),
                            ),
                        )
                    } else {
                        Modifier.background(accent.copy(alpha = 0.30f))
                    },
                ),
        )
        NpMaskFade(
            color = ink.bg,
            size = 200.dp,
            fromTop = true,
            startAlpha = 0.55f,
        )
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
    // E8：命中区抬到 44dp（§3.1 红线，此前整枚约 34dp）——视觉层仍是 34dp 丸形，
    // 底色/描边只画里层，同 NpChip 的分层手法
    Box(
        modifier = modifier.heightIn(min = NpDimens.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
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
        animationSpec = npMotion(Motion.Tonearm, bouncy = true),
        label = "tonearm",
    )
    // M23：整页 crossfade 之外，唱片自己从 0.92 涨到 1——"被放到机上"的一点体积感。
    // 轻量档 / Reduce Motion 把缩放归零，只留页面的交叉淡入。
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { placed = true }
    val discScale by animateFloatAsState(
        targetValue = if (placed) 1f else if (npDisplacementAllowed()) 0.92f else 1f,
        animationSpec = npMotion(Motion.Scheme),
        label = "discEntrance",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = discScale
                scaleY = discScale
            }
            .aspectRatio(1f),
    ) {
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
