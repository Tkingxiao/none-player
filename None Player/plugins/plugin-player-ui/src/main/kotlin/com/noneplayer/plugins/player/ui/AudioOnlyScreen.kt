package com.noneplayer.plugins.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.ui.InkTokens
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMediaPalette
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMaskFade
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npCoverScrim
import com.noneplayer.core.ui.npInkForBackdrop
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npQuality
import com.noneplayer.core.ui.npReadableBackdropAlpha
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.plugins.player.ui.controls.WaveformSeekBar
import kotlinx.coroutines.delay

/**
 * 仅听音频整屏页（设计稿 `.audio`，CSS 538-594 / JS enterAudio-renderAudio 1575-1642）——
 * 网易云式"只听不看"形态：辉光 + 旋转黑胶 + 落针唱臂 + 控制排 + 工具 chips。
 *
 * **两条版式**按实际内容区域约束分派，整屏和内嵌右栏共用：
 * - 窄栏或竖窗：纵排，整页可滚。
 * - 足够宽的横向区域：黑胶在左、内容在右的两栏，整页**不滚**——
 *   纵排那一列约 800dp，压进 400dp 出头的横向窗口会把播放键推出屏幕。
 *
 * **省电语义**：进入本页后不再组合 VideoStage；会话禁用视频轨道，
 * TextureView 与视频解码退出，音频照常走后台服务。
 */
@Composable
fun AudioOnlyScreen(
    session: PlaybackSession,
    onBack: () -> Unit,
    onReturnToVideo: (() -> Unit)? = null,
    favorited: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    speed: Float = 1f,
    speedPickerVisible: Boolean = false,
    onPickSpeed: (Float) -> Unit = {},
    onOpenEpisodes: () -> Unit = {},
    speedText: String = "1.0x",
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val themeInk = LocalNpInk.current
    // E120：订**位置无关**的摘要，不订 nowPlaying——后者每 250ms 随进度发一次新值，
    // 在页面顶解包等于让整页跟着进度 4/s 重组（实测 39 次/12s：两个 radialGradient、
    // 唱片、6 枚 chip 全部重跑）。进度只在下面的 ProgressBlock 叶子里订引擎的流。
    val nowPlaying by session.nowPlayingSummary.collectAsStateWithLifecycle()
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
    // 窗口档位只决定整屏边距与圆盘上限；版式由实际容器尺寸决定。
    val windowInfo = LocalNpWindowInfo.current
    // 进度（§5.6）：音频模式的主视觉换成装饰波形——同一文件任何时候形状一致，
    // 但比一根细线更像"这里有一段内容"。视频页仍用 §5.4 的实线条，
    // 波形放在画面上会误导"这是音频文件"。
    val quality = npQuality()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(themeInk.bg),
    ) {
        val horizontalLayout = maxWidth >= 640.dp && maxWidth > maxHeight
        val compactWidth = maxWidth < 380.dp
        val contentInset = if (embedded || compactWidth) 16.dp else 26.dp
        val discLimit = if (embedded) minOf(258.dp, maxHeight * 0.32f) else 258.dp
        val portraitDiscSize = minOf(maxWidth * 0.64f, discLimit)
        val safeInsets = if (embedded) Modifier else Modifier.safeDrawingPadding()
        // 封面取色舞台底（§4.7）：先于辉光，辉光在它之上"点亮"顶部。
        AudioModeBackdrop(coverUri = snapshot.coverUri)
        // 径向 accent 辉光（设计稿 .audio .glow：120%×55% at 50% 2%）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(themeInk.accentSoft, Color.Transparent),
                        radius = 900f,
                    ),
                ),
        )
        val readableAlpha = remember(themeInk) { npReadableBackdropAlpha(themeInk) }
        val coverTone = npCoverScrim(snapshot.coverUri)
        val backdrop = Color(
            red = themeInk.bg.red * readableAlpha + coverTone.red * (1f - readableAlpha),
            green = themeInk.bg.green * readableAlpha + coverTone.green * (1f - readableAlpha),
            blue = themeInk.bg.blue * readableAlpha + coverTone.blue * (1f - readableAlpha),
        )
        val ink = npInkForBackdrop(themeInk, backdrop)
        Box(Modifier.fillMaxSize().background(themeInk.bg.copy(alpha = readableAlpha)))
        // ==================== 版式块：两条版式共用，差异只在排列与对齐 ====================

        // 顶部行 / 信息 / 控制排 / 工具区都是**文件级** composable（A6）：
        // 不再作为局部函数捕获整页的 `snapshot/ink/isPlaying`，各有自己的重组域。

        // 进度行（§5.6：装饰波形 + 两端时间码）见文件级 [AudioProgressRow]。
        // 它**不能**写成这里的局部 @Composable：E120 实测，局部函数里解包
        // `player.positionMs` 照样把整页拖进 4/s 重组（拔掉这两个订阅，
        // 12s 内整页重组从 41 次掉到 0 次）。进度必须是独立重组域。

        // 控制排（设计稿 .audio-controls）：见文件级 [AudioTransportRow]。

        // 工具区（原设计稿 .audio-tools 是 2 列网格）。2026-10-13 用户裁定改成自适应单行：
        // 纯音频时「切回视频」为 null，2×2 网格因此留下一格空位，三枚挤在四格里
        // 读起来像漏了一块；有几枚排几枚才不留洞。见文件级 [AudioToolChips]。

        // ==================== 版式分派（§13.2 单点判定）====================
        if (horizontalLayout) {
            // 横窗（手机横置全屏 / 平板横屏 / 类 PC）：**黑胶在左、内容在右**，整页不滚。
            // 竖窗那一列约 800dp，压进 400dp 出头的横向窗口会把播放键顶出屏幕外，
            // 所以横窗的预算换着花：圆盘吃可用高度，右栏只放内容本身。
            // E129（审查 #6）：**"整页不滚"现在由右栏自己兜底**——圆盘仍不滚（它本就
            // 按可用高度定尺寸），右栏内容高过窗口时自行滚动，低高度窗口里工具不再够不到。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(safeInsets)
                    .padding(horizontal = if (embedded) 16.dp else windowInfo.pageGutter, vertical = NpSpace.xs),
            ) {
                if (!embedded) AudioHeaderRow(ink = ink, snapshot = snapshot, onBack = onBack)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // 圆盘三条上限：可用高度（别顶穿窗口）、宽度的 42%（给右栏留够）、
                    // 设计稿 258（类 PC 窗口高，放到 300 也不显空）。
                    // 下限 120：分屏/自由窗口能把横向窗压到很矮，再小就不是一张碟了。
                    val discSize = minOf(
                        maxHeight,
                        maxWidth * 0.42f,
                        if (windowInfo.isDesktopLike) 300.dp else 258.dp,
                    ).coerceAtLeast(120.dp)
                    // 低高度横窗（手机横置 / 折叠分屏 / 自由窗口）的判据（E129 / 审查 #6）：
                    // 右栏内容（标题 + 进度 + 五枚传输键 + 两列 chips，展开倍速还更多）
                    // 本来就会高过窗口，而这一页此前**整页声明不滚**——底部工具因此永远够不到。
                    // 够不到时收紧间距，先把内容压进窗口，压不进再由右栏自己滚。
                    val tightHeight = maxHeight < 420.dp
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(NpSpace.xxl, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VinylDisc(
                            coverUri = snapshot.coverUri,
                            title = snapshot.title,
                            isPlaying = isPlaying,
                            modifier = Modifier.size(discSize),
                        )
                        // 右栏 560dp 封顶：平板横屏 1280dp 宽时内容不横着摊满整窗。
                        // 间距由 Arrangement 给，块自己不再写纵向 padding。
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(max = 560.dp)
                                // E129（审查 #6）：**右栏自己可滚**——"整页不滚"在低高度横窗里
                                // 等于把工具 chips 判了不可达。只让右栏滚：圆盘仍吃满可用高度、
                                // 不跟着内容一起滚，横窗"黑胶在左、内容在右"的版式意图不变。
                                // 内容装得下时高度仍按内容走（`weight(fill = false)` + 滚动容器
                                // 取 min(内容, 可用)），视觉与加滚动之前完全一致。
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(
                                if (tightHeight) NpSpace.sm else NpSpace.lg,
                                Alignment.CenterVertically,
                            ),
                        ) {
                            AudioInfoBlock(
                                ink = ink,
                                snapshot = snapshot,
                                embedded = embedded,
                                startAligned = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            AudioProgressRow(
                                player = player,
                                mediaKey = HistoryEntry.keyOf(snapshot.item),
                                durationMs = snapshot.durationMs,
                                barCount = quality.waveformBars,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            AudioTransportRow(
                                ink = ink,
                                snapshot = snapshot,
                                isPlaying = isPlaying,
                                loopIcon = loopIcon,
                                loopMode = loopMode,
                                compactWidth = compactWidth,
                                onCycleLoop = { cycleAudioLoop(session, loopMode) },
                                onPrevious = { session.previous() },
                                onTogglePlay = { session.toggle() },
                                onNext = { session.next() },
                                onOpenEpisodes = onOpenEpisodes,
                                arrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // chips 走自适应单行（不再有 2×2 的空格）；右栏左对齐，
                            // 与上方左对齐的标题/控制排同一条起读线。
                            AudioToolChips(
                                ink = ink,
                                favorited = favorited,
                                speed = speed,
                                speedText = speedText,
                                speedPickerVisible = speedPickerVisible,
                                onToggleFavorite = onToggleFavorite,
                                onReturnToVideo = onReturnToVideo,
                                onOpenSpeed = onOpenSpeed,
                                onPickSpeed = onPickSpeed,
                                fillChips = true,
                                startAligned = true,
                                modifier = Modifier.widthIn(max = 420.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 安全区在滚动之外（§8.5）：内容滚到边缘时被状态栏/手势条裁住，
                    // 而不是从底下透出来压在系统栏上
                    .then(safeInsets)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!embedded) {
                    AudioHeaderRow(ink = ink, snapshot = snapshot, onBack = onBack)
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                // 黑胶（设计稿 .vinyl：min(258px,64vw)，22s 匀速）
                VinylDisc(
                    coverUri = snapshot.coverUri,
                    title = snapshot.title,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(portraitDiscSize),
                )
                AudioInfoBlock(
                    ink = ink,
                    snapshot = snapshot,
                    embedded = embedded,
                    startAligned = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 12.dp),
                )
                // 歌词带占位（真 .lrc 解析后置；P3）。只在竖窗出现：横窗的垂直预算
                // 要留给控制排与工具 chips，这一行是装饰性的空位
                if (!embedded) Text(
                    text = "仅听音频模式 · 画面已关闭以省电",
                    style = com.noneplayer.core.ui.NpMonoSmall,
                    color = ink.muted,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = contentInset, vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
                AudioProgressRow(
                    player = player,
                    mediaKey = HistoryEntry.keyOf(snapshot.item),
                    durationMs = snapshot.durationMs,
                    barCount = quality.waveformBars,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset),
                )
                AudioTransportRow(
                    ink = ink,
                    snapshot = snapshot,
                    isPlaying = isPlaying,
                    loopIcon = loopIcon,
                    loopMode = loopMode,
                    compactWidth = compactWidth,
                    onCycleLoop = { cycleAudioLoop(session, loopMode) },
                    onPrevious = { session.previous() },
                    onTogglePlay = { session.toggle() },
                    onNext = { session.next() },
                    onOpenEpisodes = onOpenEpisodes,
                    arrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 18.dp),
                )
                AudioToolChips(
                    ink = ink,
                    favorited = favorited,
                    speed = speed,
                    speedText = speedText,
                    speedPickerVisible = speedPickerVisible,
                    onToggleFavorite = onToggleFavorite,
                    onReturnToVideo = onReturnToVideo,
                    onOpenSpeed = onOpenSpeed,
                    onPickSpeed = onPickSpeed,
                    fillChips = embedded,
                    startAligned = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/** 循环模式的轮换顺序（设计稿 .audio-controls 的 5 钮之一） */
private val AUDIO_LOOP_MODES = listOf(NpLoopMode.OFF, NpLoopMode.ONE, NpLoopMode.SHUFFLE, NpLoopMode.ALL)

private fun cycleAudioLoop(session: PlaybackSession, current: NpLoopMode) {
    session.setLoopMode(
        AUDIO_LOOP_MODES[(AUDIO_LOOP_MODES.indexOf(current) + 1) % AUDIO_LOOP_MODES.size],
    )
}

/**
 * 顶部行（设计稿 .audio-top）：返回 + 标题/来源。
 *
 * A6：文件级 composable，不再作为 `AudioOnlyScreen` 的局部函数捕获整页状态——
 * 局部函数与页面共享重组域，任何捕获值变化都会把整页拖进来。
 */
@Composable
private fun AudioHeaderRow(
    ink: InkTokens,
    snapshot: NowPlaying,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NpIconButton(
            icon = NpIcon.ArrowLeft,
            contentDescription = "返回",
            onClick = onBack,
            tint = ink.fg,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "正在播放",
                style = NpTypography.titleSmall,
                color = ink.fg,
            )
            Text(
                text = (snapshot.item.folder ?: "本地音乐") + " · 音频",
                style = com.noneplayer.core.ui.NpMonoSmall,
                color = ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 信息块（设计稿 .audio-info）：竖窗整块居中；横窗它落在黑胶右侧，改左对齐。 */
@Composable
private fun AudioInfoBlock(
    ink: InkTokens,
    snapshot: NowPlaying,
    embedded: Boolean,
    startAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (startAligned) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            text = snapshot.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a"),
            style = if (embedded) NpTypography.titleMedium else NpTypography.titleLarge,
            color = ink.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
        )
        Text(
            text = "未知艺术家 · " + (snapshot.item.folder ?: "本地音乐"),
            style = NpTypography.bodyMedium,
            color = ink.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 控制排（设计稿 .audio-controls：循环 / 上一首 / 播放主钮 / 下一首 / 播放列表）。
 *
 * 排列由版式给：竖窗整排居中；横窗它落在左对齐的标题之下，从栏的左缘起排。
 */
@Composable
private fun AudioTransportRow(
    ink: InkTokens,
    snapshot: NowPlaying,
    isPlaying: Boolean,
    loopIcon: ImageVector,
    loopMode: NpLoopMode,
    compactWidth: Boolean,
    onCycleLoop: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenEpisodes: () -> Unit,
    arrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement,
    ) {
        NpIconButton(
            icon = loopIcon,
            contentDescription = "循环模式",
            onClick = onCycleLoop,
            size = 44.dp,
            iconSize = 22.dp,
            tint = if (loopMode == NpLoopMode.OFF) ink.muted else ink.accent,
        )
        NpIconButton(
            icon = NpIcon.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = if (compactWidth) 48.dp else 56.dp,
            iconSize = 26.dp,
            tint = ink.fg,
            enabled = snapshot.hasPrevious,
        )
        Box(
            modifier = Modifier
                .size(NpDimens.primaryPlay)
                .clip(CircleShape)
                .background(ink.accent)
                .npPressScale(onClick = onTogglePlay, pressedScale = 0.93f)
                // E129（审查 #4）：播放态画的是自绘 `AudioPauseGlyph`，它没有语义节点
                // ——名称必须挂在**按钮**上并按状态给，否则读屏只报"按钮"。
                .semantics { contentDescription = if (isPlaying) "暂停" else "播放" },
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                AudioPauseGlyph(tint = ink.bg)
            } else {
                Icon(
                    imageVector = NpIcon.Play,
                    // 名称已由按钮节点给出，图标不再重复报一次
                    contentDescription = null,
                    tint = ink.bg,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        NpIconButton(
            icon = NpIcon.SkipNext,
            contentDescription = "下一首",
            onClick = onNext,
            size = if (compactWidth) 48.dp else 56.dp,
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
}

/**
 * 工具区（原设计稿 .audio-tools 是 2 列网格）。2026-10-13 用户裁定改成自适应单行：
 * 纯音频时「切回视频」为 null，2×2 网格因此留下一格空位，三枚挤在四格里
 * 读起来像漏了一块；有几枚排几枚才不留洞。
 *
 * [fillChips]：横窗给 true——设计稿 `.tool-chip{width:100%;justify-content:center}`
 * 是"胶囊铺满格、内容居中"。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AudioToolChips(
    ink: InkTokens,
    favorited: Boolean,
    speed: Float,
    speedText: String,
    speedPickerVisible: Boolean,
    onToggleFavorite: () -> Unit,
    onReturnToVideo: (() -> Unit)?,
    onOpenSpeed: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    fillChips: Boolean,
    startAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    val speedMenu = remember { BringIntoViewRequester() }
    val speedMenuDuration = npDuration(Motion.Field)
    LaunchedEffect(speedPickerVisible) {
        if (speedPickerVisible) {
            delay(speedMenuDuration.toLong())
            speedMenu.bringIntoView()
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = if (startAligned) Alignment.Start else Alignment.CenterHorizontally,
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
                fill = fillChips,
                modifier = Modifier.weight(1f),
            )
            if (onReturnToVideo != null) {
                ToolChip(
                    icon = NpIcon.Film,
                    label = "切回视频",
                    onClick = onReturnToVideo,
                    fill = fillChips,
                    modifier = Modifier.weight(1f),
                )
            }
            ToolChip(
                icon = NpIcon.Speed,
                label = "倍速 $speedText",
                on = speed != 1f || speedPickerVisible,
                onClick = onOpenSpeed,
                fill = fillChips,
                modifier = Modifier.weight(1f),
            )
        }
        // 「后台播放中」原先也占一枚 chip 位，但 `onClick = {}`——看着能点、点了没反应。
        // 它是状态说明而不是动作，降成一行弱化文字，胶囊排布也不再为它留格。
        // 设计稿这一格本是「睡眠定时」，该能力尚未落地，不做假动作。
        Text(
            text = "后台播放中",
            style = com.noneplayer.core.ui.NpMonoSmall,
            color = ink.muted,
        )
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
                modifier = Modifier.padding(top = 10.dp).bringIntoViewRequester(speedMenu),
            )
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
    /** true = 胶囊铺满所在格（设计稿 `width:100%`，横窗用）；false = 按内容收、格内居中（竖窗现状） */
    fill: Boolean = false,
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
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(if (on) ink.accentSoft else ink.surface.copy(alpha = 0.92f))
                .border(1.dp, if (on) ink.accentLine else ink.border, RoundedCornerShape(NpRadius.chip))
                .npPressScale(onClick = onClick, pressedScale = 0.96f)
                .padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            // 设计稿 .audio-tools .tool-chip{justify-content:center;width:100%}
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            // 单行 + 省略号：横窗把右栏压窄（分屏/自由窗口）时，标签换行会把 chip
            // 撑出 44dp 命中区，读起来像两枚叠在一起的钮
            Text(
                text = label,
                style = NpTypography.bodySmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                            NpMediaPalette.vinylRim,
                            NpMediaPalette.vinylCore,
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
        // 唱臂（pivot 在右上 88%/6%）。盒按碟面等比取（0.35×0.58，258dp 碟 = 90×150，
        // 与设计稿同值）：横窗的圆盘会小于 258，写死尺寸唱臂就长出碟面外。
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.58f)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.9f, 0.05f)
                    rotationZ = tonearmAngle
                },
        ) {
            val pivot = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.05f)
            val end = androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.78f)
            drawLine(
                color = NpMediaPalette.tonearmRod,
                start = pivot,
                end = end,
                strokeWidth = 5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawCircle(
                color = NpMediaPalette.tonearmPivot,
                radius = 11.dp.toPx(),
                center = pivot,
            )
            drawCircle(
                color = NpMediaPalette.tonearmHead,
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

/**
 * 仅听页的进度行（§5.6）：装饰波形 + 两端时间码。
 *
 * 为什么是**文件级**而不是页面里的局部 @Composable（E120）：进度与缓冲两条流每
 * 250ms 跳一次，**订阅点在哪，失效就打到哪**——写在局部函数里时整页仍按 4/s 重组
 * （实测 41 次/12s，把这两个订阅拔掉就是 0 次）。独立签名 = 独立重组域，
 * 进度只重算这四行，唱片/渐变/六枚 chip 一概不动。
 */
@Composable
private fun AudioProgressRow(
    player: VideoPlayer,
    mediaKey: String,
    durationMs: Long,
    barCount: Int,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val positionMs by player.positionMs.collectAsStateWithLifecycle()
    val bufferedMs by player.bufferedPositionMs.collectAsStateWithLifecycle()
    var previewPositionMs by remember(mediaKey, durationMs) { mutableStateOf<Long?>(null) }
    Column(modifier = modifier) {
        WaveformSeekBar(
            mediaKey = mediaKey,
            durationMs = durationMs,
            positionMs = positionMs,
            bufferedFraction = if (durationMs > 0) bufferedMs.toFloat() / durationMs else 0f,
            barCount = barCount,
            onPreviewPosition = { previewPositionMs = it },
            onSeek = { player.seekTo(it) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTimestamp(previewPositionMs ?: positionMs),
                style = com.noneplayer.core.ui.NpMonoSmall,
                color = ink.muted,
            )
            Text(
                text = formatTimestamp(durationMs),
                style = com.noneplayer.core.ui.NpMonoSmall,
                color = ink.muted,
            )
        }
    }
}
