package com.noneplayer.plugins.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.episodeLabel
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.probe.FormatProbe
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpOptRow
import com.noneplayer.core.ui.NpPrimaryButton
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSectionHead
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.formatRelativeDate
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npShadow
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.InlinePlaybackProvider
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.LocalNpBottomInset
import com.noneplayer.kernel.ui.api.LocalNpPaneOccupied
import com.noneplayer.kernel.ui.api.NpInlinePlayRequest
import com.noneplayer.kernel.ui.api.NpMorphKey
import com.noneplayer.kernel.ui.api.npMorphPart
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「视频详情」内页（平板横屏 / 类 PC）。
 *
 * 为什么不是路由页：详情要**侧边 Rail 常驻不变**（用户明确要求），而 Rail 只属于
 * Shell；只有把详情作为 Tab 内页渲染，导航选中态与层级语义才天然正确。
 * 因此它是 LibraryScreen 的页内状态（[detailItem]），配合 rememberSaveable
 * 跨「进播放页 → 返回」的往返保留；返回键/顶栏返回 = 退出详情。
 *
 * 版式（E134 用户定稿）：宽屏（≥720dp）双栏——**剧集列表常驻左侧、视频小窗居右**；
 * 视频详情（标题/元信息/文件信息）收进播放窗右下角的「详情」按钮，
 * 点按弹出**浮窗层小窗口**显示，点遮罩 / 关闭键 / 返回键退出。
 * 窄窗（<720dp）单栏：视频、详情、剧集列表自上而下常显。
 *
 * 剧集列表与播放器队列用**同一个比较器**（小写标题、再按原串），
 * 顺序与连播的"下一集"一致，不会出现"列表第 3 项、下一集却跳到第 5 项"。
 */
@Composable
internal fun VideoDetailScreen(
    item: MediaItem,
    siblings: List<MediaItem>,
    resumeMs: Long,
    navigator: Navigator,
    inlinePlayer: InlinePlaybackProvider?,
    /** 进独占全屏播放页（内嵌控制层的全屏键 / 未注册内嵌实现时的回退） */
    onFullscreen: (MediaItem) -> Unit,
    onAudioOnly: (MediaItem) -> Unit,
    onSelect: (MediaItem) -> Unit,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = LocalNpBottomInset.current
    // 顶栏是**覆盖式悬浮层**（NpPageScaffold 的契约），内容必须自己让位：
    // 不消费这个内边距，封面会被顶栏压掉一整条（用户实报"视频上半部分被挡住"）。
    val topInsetRaw = LocalNpTopBarInset.current
    val topPad = if (topInsetRaw == Dp.Unspecified) 0.dp else topInsetRaw
    val gutter = LocalNpWindowInfo.current.pageGutter
    val desktopLike = LocalNpWindowInfo.current.isDesktopLike
    val context = LocalContext.current
    // HDR 标识：与起播探测同源（同一个 FormatProbe 与结果缓存，判读口径不另写一份）。
    // 音频不探；探测跑在后台、命中缓存时几乎零成本；拿不到结论就是 null（不编造）。
    val hdr by produceState<String?>(null, item.uri, item.sizeBytes, item.lastModifiedSec) {
        if (item.kind == MediaKind.AUDIO || item.uri.isBlank()) return@produceState
        value = runCatching {
            val result = FormatProbe.probe(
                context.applicationContext,
                item.uri,
                item.mimeType,
                item.sizeBytes,
                item.lastModifiedSec,
                recordMemo = false,
            )
            (result as? FormatProbe.Result.Playable)?.hdr
        }.getOrNull()
    }
    // 视频下方的一排元数据：大小 · 分辨率 · HDR · 容器 · 文件夹（文件夹收尾）
    val heroMeta = videoMetaLine(item, hdr)
    // 内嵌播放：详情页里直接开播（封面框就地变播放器）；全屏键才进独占播放页。
    // 会话是同一个——落全屏只续播不重启，退出全屏回详情、画面仍在原处继续。
    // 状态**不按键**：在详情页里切换同文件夹条目时保持"继续播新选的那一集"
    // （与市面播放器的选集行为一致）；离开详情页即随组合销毁复位。
    var inlinePlaying by rememberSaveable { mutableStateOf(false) }
    val inlineAvailable = inlinePlayer != null
    val paneOccupied = LocalNpPaneOccupied.current
    val selectEpisode: (MediaItem) -> Unit = { next ->
        // 右栏或本页内嵌已经在播：点左侧剧集必须换画面，不能只换详情页状态。
        if (paneOccupied || inlinePlaying) NpInlinePlayRequest.request(next)
        onSelect(next)
    }
    fun startPlayback() {
        when {
            paneOccupied -> NpInlinePlayRequest.request(item)
            inlineAvailable -> inlinePlaying = true
            else -> onFullscreen(item)
        }
    }
    // 右栏接管播放的那一刻，详情页的内嵌态必须复位：否则右栏顶栏的「关闭播放器」
    // 停掉会话后 paneOccupied 翻回 false，这里残留的 inlinePlaying=true 会让封面框
    // 立刻挂上内嵌播放器、把会话**再拉起来**——用户点的可是"关闭"（2026-10-07 实测）。
    // 紧凑/中等档没有右栏（paneOccupied 恒 false），此复位不生效，内嵌行为不变。
    LaunchedEffect(paneOccupied) {
        if (paneOccupied) inlinePlaying = false
    }
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val progress = if (item.durationMs > 0 && resumeMs > 0) {
        (resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val canResume = resumeMs > 5_000
    var showDetail by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 右栏只在**真有内容**时才占位（E129 / 审查 #9）：同文件夹兄弟项不足两条时
        // [siblingRows] 直接 return，右栏就是一整片空白——主详情还被挤在左半边，
        // 宽屏上等于白扔半屏。判据从"窗口够宽"改成"窗口够宽 **且** 有东西可放"，
        // 无兄弟项时落回单栏，详情独占整幅宽度。
        val twoPane = maxWidth >= 720.dp && siblings.size > 1
        val pad = PaddingValues(
            start = gutter,
            end = gutter,
            top = topPad + 12.dp,
            bottom = bottomInset + 28.dp,
        )
        if (siblings.size <= 1 && maxWidth >= 600.dp && !paneOccupied) {
            // 平板竖屏/宽窗单文件：**不再常显左侧详情列**——详细信息收进「详情」按钮的
            // 浮窗（与两栏分支同一口径；用户实报"详细信息展示删除，只有点击按钮再显示"）。
            // 视频下方保留一排元数据（大小 · 分辨率 · HDR · 容器 · 文件夹）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.TopCenter,
            ) {
                DetailHero(
                    item = item,
                    hue = hue,
                    isAudio = isAudio,
                    progress = progress,
                    canResume = canResume,
                    resumeMs = resumeMs,
                    navigator = navigator,
                    inlinePlayer = inlinePlayer,
                    inlinePlaying = inlinePlaying,
                    onStartInline = ::startPlayback,
                    onFullscreen = { onFullscreen(item) },
                    onAudioOnly = { onAudioOnly(item) },
                    onOpenInfo = { showDetail = true },
                    metaLine = heroMeta,
                    favorited = favorited,
                    onToggleFavorite = onToggleFavorite,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        } else if (paneOccupied && siblings.size <= 1) {
            // 右栏已经在播、且这一条**没有同文件夹兄弟**（单视频 / 深链进来）。
            //
            // 用户口径（2026-10-15）：这里**不要封面、也不要「详情」键**——封面占掉一整块，
            // 而详情本来是另一个键才能看到的内容，单文件这一档没有别的可看，直接把详细信息
            // 摆在动作行下面最省事。动作行的样式仍与多集那一排同源（只少了「详情」）。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NpPrimaryButton(
                        text = if (canResume) "继续观看" else "播放",
                        icon = NpIcon.Play,
                        onClick = ::startPlayback,
                        modifier = Modifier.npMorphPart(NpMorphKey.ListPlay, desktop = true),
                    )
                    Spacer(Modifier.weight(1f))
                    NpIconButton(
                        icon = NpIcon.Heart,
                        contentDescription = if (favorited) "取消收藏" else "收藏",
                        tint = if (favorited) LocalNpInk.current.accent else LocalNpInk.current.fg,
                        onClick = onToggleFavorite,
                        modifier = Modifier.npMorphPart(NpMorphKey.Fav, desktop = true),
                    )
                    NpIconButton(
                        icon = NpIcon.Headphones,
                        contentDescription = "仅听音频",
                        onClick = { onAudioOnly(item) },
                        modifier = Modifier.npMorphPart(NpMorphKey.Audio, desktop = true),
                    )
                }
                Spacer(Modifier.height(22.dp))
                DetailMeta(item = item, hdr = hdr)
            }
        } else if (paneOccupied) {
            // The shell already owns the video surface; keep this narrow pane focused on episodes.
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = pad) {
                item(key = "detail-actions") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NpPrimaryButton(
                                text = if (canResume) "继续观看" else "播放",
                                icon = NpIcon.Play,
                                onClick = ::startPlayback,
                                modifier = Modifier.npMorphPart(NpMorphKey.ListPlay, desktop = true),
                            )
                            Spacer(Modifier.weight(1f))
                            // 收藏（与手机播放页同一动作）：此前平板的操作行没有这个出口
                            NpIconButton(
                                icon = NpIcon.Heart,
                                contentDescription = if (favorited) "取消收藏" else "收藏",
                                tint = if (favorited) LocalNpInk.current.accent else LocalNpInk.current.fg,
                                onClick = onToggleFavorite,
                                // 与竖屏播放页那一横排同名：转屏时样式不变，只各自飞到位（E133）
                                modifier = Modifier.npMorphPart(NpMorphKey.Fav, desktop = true),
                            )
                            NpIconButton(
                                icon = NpIcon.Headphones,
                                contentDescription = "仅听音频",
                                onClick = { onAudioOnly(item) },
                                modifier = Modifier.npMorphPart(NpMorphKey.Audio, desktop = true),
                            )
                            NpIconButton(
                                icon = NpIcon.Info,
                                contentDescription = "视频详情",
                                onClick = { showDetail = true },
                                modifier = Modifier.npMorphPart(NpMorphKey.Info, desktop = true),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                siblingRows(item = item, siblings = siblings, onSelect = selectEpisode)
            }
        } else if (twoPane) {
            // E134（用户明确要求）：**剧集列表居左、视频小窗居右**（此前相反）。
            // 视频详情弹成浮窗层小窗口，由播放窗右下角的「详情」按钮打开。
            // rememberSaveable 让弹窗状态跨播放往返保留——进播放页回来不丢。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // 剧集列表自己就是滚动容器（LazyColumn）——同文件夹动辄几百集，
                // 整表 `forEach` 会在打开详情的那一帧把全部行组合并测量一遍。
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    siblingRows(item = item, siblings = siblings, onSelect = selectEpisode)
                }
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DetailHero(
                        item = item,
                        hue = hue,
                        isAudio = isAudio,
                        progress = progress,
                        canResume = canResume,
                        resumeMs = resumeMs,
                        navigator = navigator,
                        inlinePlayer = inlinePlayer,
                        inlinePlaying = inlinePlaying,
                        onStartInline = ::startPlayback,
                        onFullscreen = { onFullscreen(item) },
                        onAudioOnly = { onAudioOnly(item) },
                        onOpenInfo = { showDetail = true },
                        metaLine = heroMeta,
                        favorited = favorited,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        } else {
            // 窄窗：整页一个 Lazy 列表——头部（封面 + 元信息）在前，同文件夹条目按需组合。
            // contentPadding 与旧的 `verticalScroll + padding` 同义：内容整块内缩，
            // 滚动时同样能进到顶栏与底条之下。
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = pad,
            ) {
                item(key = "detail-hero") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                    DetailHero(
                        item = item,
                        hue = hue,
                        isAudio = isAudio,
                        progress = progress,
                        canResume = canResume,
                        resumeMs = resumeMs,
                        navigator = navigator,
                        inlinePlayer = inlinePlayer,
                        inlinePlaying = inlinePlaying,
                        onStartInline = ::startPlayback,
                        onFullscreen = { onFullscreen(item) },
                        onAudioOnly = { onAudioOnly(item) },
                        onOpenInfo = { showDetail = true },
                        favorited = favorited,
                        onToggleFavorite = onToggleFavorite,
                        modifier = if (desktopLike && siblings.size <= 1) {
                            Modifier.widthIn(max = 640.dp)
                        } else {
                            Modifier
                        },
                    )
                    }
                }
                if (!desktopLike) item(key = "detail-meta") {
                    Column {
                        Spacer(Modifier.height(22.dp))
                        DetailMeta(item = item, hdr = hdr)
                    }
                }
                if (siblings.size > 1) {
                    siblingRows(
                        item = item,
                        siblings = siblings,
                        onSelect = selectEpisode,
                        topSpacing = 22.dp,
                    )
                }
            }
        }
    }
    if (showDetail) DetailPopup(item = item, hdr = hdr, onDismiss = { showDetail = false })
}

/** 封面 + 主操作：点封面即播；「继续观看」带上断点时刻，播放器仍会把断点还原 */
@Composable
private fun DetailHero(
    item: MediaItem,
    hue: Int,
    isAudio: Boolean,
    progress: Float,
    canResume: Boolean,
    resumeMs: Long,
    navigator: Navigator,
    inlinePlayer: InlinePlaybackProvider?,
    inlinePlaying: Boolean,
    onStartInline: () -> Unit,
    onFullscreen: () -> Unit,
    onAudioOnly: () -> Unit,
    /** 详情浮窗入口（null/省略 = 窄窗常显详情，不渲染「详情」键） */
    onOpenInfo: (() -> Unit)? = null,
    /** 视频下方一排元数据（大小 · 分辨率 · HDR · 容器 · 文件夹）；null = 不渲染 */
    metaLine: String? = null,
    favorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val inline = inlinePlayer != null && inlinePlaying
        if (inline) {
            // 内嵌播放：就占封面那个 16:9 框；画面/字幕/控制层由引擎侧实现提供。
            // 全屏键在控制层里——它把用户送进独占播放页，回来时这里仍在原处续播。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp)),
            ) {
                inlinePlayer.InlinePlayer(
                    media = item,
                    navigator = navigator,
                    onFullscreen = onFullscreen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            NpCoverBox(
                aspectRatio = 16f / 9f,
                shape = RoundedCornerShape(18.dp),
                placeholderHue = hue,
                progress = progress.takeIf { it > 0.01f },
                isAudio = isAudio,
                durationText = formatTimestamp(item.durationMs).takeIf { item.durationMs > 0 },
                tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
                modifier = Modifier.npPressScale(onClick = onStartInline, pressedScale = 0.985f),
            ) {
                MediaCover(item = item, hue = hue)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NpIcon.Play,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
        // 视频下方信息行（平板横屏/竖屏都补上）：一排横向、文件夹收尾
        metaLine?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                text = line,
                style = NpMonoSmall,
                color = LocalNpInk.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 内嵌播放中不再单独放「全屏观看」主钮（用户裁定）：全屏入口就是内嵌
            // 控制层里的 ⛶，详情页再放一个只是同一动作的重复按钮。
            if (!inline) {
                NpPrimaryButton(
                    text = if (canResume) "继续观看 · ${formatTimestamp(resumeMs)}" else "播放",
                    icon = NpIcon.Play,
                    onClick = onStartInline,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onToggleFavorite != null) {
                // 收藏（与手机播放页同一动作与选中态）：平板的操作行此前没有这个出口
                NpIconButton(
                    icon = NpIcon.Heart,
                    contentDescription = if (favorited) "取消收藏" else "收藏",
                    tint = if (favorited) LocalNpInk.current.accent else LocalNpInk.current.fg,
                    onClick = onToggleFavorite,
                )
            }
            NpIconButton(
                icon = NpIcon.Headphones,
                contentDescription = "仅听音频",
                onClick = onAudioOnly,
            )
            if (onOpenInfo != null) {
                // 详情浮窗键：与全屏/播放同一行，推到最右（用户要求）
                NpIconButton(
                    icon = NpIcon.Info,
                    contentDescription = "视频详情",
                    onClick = onOpenInfo,
                )
            }
        }
    }
}

/** 视频详情（标题 + 元信息 + 文件信息）——浮窗内容，窄窗也常显 */
@Composable
private fun DetailMeta(
    item: MediaItem,
    hdr: String? = null,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    // 用户口径：一排横向、文件夹在最后（旧版文件夹单独一行再叠一条元数据 = 两排）
    val meta = videoMetaLine(item, hdr, lead = item.episodeLabel)
    val ext = item.title
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 }
        ?.uppercase()
    val container = ext
        ?: item.mimeType.substringAfter('/', "").uppercase().takeIf { it.isNotBlank() }

    Column(modifier = modifier) {
        Text(
            // 详情里用文件原名，不换富化短标题，也不截成三行。
            text = item.title.ifBlank { item.cardTitle },
            style = NpTypography.headlineSmall,
            color = ink.fg,
        )
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        // 行先攒齐再渲染：分隔线只挂在**最后一行之外**，缺字段时也不会留下一条悬空的分隔线。
        // 单文件那一档（横屏左栏）就是靠这份把"只有一个名字和格式"补成真正的文件信息。
        val rows = buildList {
            if (item.width > 0 && item.height > 0) add("分辨率" to "${item.width} × ${item.height}")
            // HDR 标识（HDR10/HLG）：探测自容器声明的 colorTransfer；SDR/未知不显示
            hdr?.takeIf { it.isNotBlank() }?.let { add("HDR" to it) }
            if (item.durationMs > 0) add("时长" to formatTimestamp(item.durationMs))
            if (item.sizeBytes > 0) add("大小" to formatFileSize(item.sizeBytes))
            add("格式" to (container ?: "—"))
            item.folder?.takeIf { it.isNotBlank() }?.let { add("文件夹" to it) }
            readablePath(item.uri)?.let { add("位置" to it) }
            if (item.addedAtMs > 0) add("加入时间" to formatRelativeDate(item.addedAtMs))
        }
        rows.forEachIndexed { index, row ->
            DetailInfoRow(row.first, row.second, divider = index != rows.lastIndex)
        }
    }
}

/**
 * 文件地址的人味写法：`file://` 与裸绝对路径给出去掉前缀的路径；
 * `content://` 这类拿不到可读路径的**返回 null**（宁可不显示，也不摆一串无意义的 id）。
 */
private fun readablePath(uri: String): String? {
    if (uri.isBlank()) return null
    val decoded = runCatching { java.net.URLDecoder.decode(uri, "UTF-8") }.getOrNull() ?: uri
    return when {
        decoded.startsWith("file://") -> decoded.removePrefix("file://")
        decoded.startsWith("/") -> decoded
        else -> null
    }
}

/**
 * 一排横向的元数据：大小 · 分辨率 · HDR · 容器 · 文件夹（文件夹收尾）。
 * 视频下方信息行与详情共用同一份拼装，两处不会各写一份而漂移。
 */
private fun videoMetaLine(item: MediaItem, hdr: String?, lead: String? = null): String {
    val ext = item.title
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 }
        ?.uppercase()
    val container = ext
        ?: item.mimeType.substringAfter('/', "").uppercase().takeIf { it.isNotBlank() }
    return buildList {
        lead?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
        item.resolutionLabel.takeIf { it.isNotBlank() }?.let { add(it) }
        hdr?.takeIf { it.isNotBlank() }?.let { add(it) }
        container?.let { add(it) }
        item.folder?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

/**
 * 剧集列表——宽屏常驻**左栏**（E134 起），窄窗跟随详情。
 *
 * 写成 [LazyListScope] 扩展而不是普通的 `@Composable Column`：两处调用点各自
 * 已经是滚动容器，行必须交给它们**按需组合**。此前是 `Column { siblings.forEach }`，
 * 一个几百集的文件夹在打开详情的瞬间要组合并测量全部行——平板/类 PC 档点任意
 * 视频都会走详情页，这一跳就是主线程上一次实打实的停顿。
 */
private fun LazyListScope.siblingRows(
    item: MediaItem,
    siblings: List<MediaItem>,
    onSelect: (MediaItem) -> Unit,
    topSpacing: Dp = 0.dp,
) {
    if (siblings.size <= 1) return
    item(key = "siblings-head") {
        Column {
            Spacer(Modifier.height(topSpacing))
            // E134：用户指定叫「剧集」（"同文件夹"是内部口径，不是给用户看的词）
            NpSectionHead(title = "剧集（${siblings.size}）")
            Spacer(Modifier.height(6.dp))
        }
    }
    itemsIndexed(siblings, key = { _, sibling -> sibling.id }) { index, sibling ->
        SiblingRow(
            index = index,
            sibling = sibling,
            current = sibling.id == item.id,
            onSelect = onSelect,
        )
    }
}

/**
 * 同文件夹里的一行（设计稿 `.optrow`）；编号 / 封面 / 当前项标记 / 时长四处口径与列表一致。
 *
 * 封面（E133）：用户指定左侧剧集也要带缩略图——参考实现里横屏列表每行都有一枚 16:9 的
 * `thumb`，转竖屏时它跟着行一起移动。取帧口径与媒体库列表同一套：有 `coverUri` 用它，
 * 否则让 Coil 的视频解码器取首帧。
 */
@Composable
private fun SiblingRow(
    index: Int,
    sibling: MediaItem,
    current: Boolean,
    onSelect: (MediaItem) -> Unit,
) {
    val ink = LocalNpInk.current
    val context = LocalContext.current
    NpOptRow(
        // 左边剧集用文件原名。富化短标题和「第 N 集」是解析结果，不是文件名。
        text = sibling.title.ifBlank { sibling.uri.substringAfterLast('/') },
        selected = current,
        onClick = { if (!current) onSelect(sibling) },
        modifier = Modifier.npMorphPart(NpMorphKey.episode(index), desktop = true),
        leading = {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.width(28.dp),
            )
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .width(72.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ink.accentSoft)
                    .npMorphPart(NpMorphKey.cover(index), desktop = true),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(sibling.coverUri ?: sibling.uri)
                        .videoFrameMillis(0)
                        .crossfade(180)
                        .build(),
                    // 封面是装饰：行尾已经有标题文本，读屏不需要再念一遍（E128 同一口径）
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        trailing = {
            if (current) {
                Text(
                    text = "当前",
                    style = NpTypography.labelSmall,
                    color = ink.accent,
                )
            } else if (sibling.durationMs > 0) {
                Text(
                    text = formatTimestamp(sibling.durationMs),
                    style = NpMonoSmall,
                    color = ink.muted,
                )
            }
        },
    )
}

/**
 * 视频详情浮窗层小窗口：标题/元信息/文件信息。
 * 点遮罩、关闭键或系统返回键退出；内容超窗高时内部滚动。
 */
@Composable
private fun DetailPopup(
    item: MediaItem,
    hdr: String? = null,
    onDismiss: () -> Unit,
) {
    val ink = LocalNpInk.current
    val shape = RoundedCornerShape(NpRadius.card)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                // E129：阴影在**白底之外**（同 NpGlass / NpMenuSurface 的顺序）
                .npShadow(ink, shape, elevation = 18.dp)
                .clip(shape)
                .background(ink.surface)
                .border(1.dp, ink.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "视频详情",
                    style = NpTypography.titleMedium,
                    color = ink.fg,
                    modifier = Modifier.weight(1f),
                )
                NpIconButton(
                    icon = NpIcon.Close,
                    contentDescription = "关闭",
                    onClick = onDismiss,
                    size = 36.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            DetailMeta(item = item, hdr = hdr)
        }
    }
}

/** 文件信息行：label 左、mono 值右、发丝分割线 */
@Composable
private fun DetailInfoRow(label: String, value: String, divider: Boolean = true) {
    val ink = LocalNpInk.current
    NpSettingRow(
        title = label,
        showDivider = divider,
        trailing = {
            Text(
                text = value,
                style = NpMonoSmall,
                color = ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * 详情页的记账条目（rememberSaveable 用）。
 *
 * 进播放页会让 Shell 离开组合，普通 `remember` 会在回来时丢——详情页必须
 * 在「看视频 → 返回」的往返里原地保留（用户明确要求返回到详情页）。
 * 只存 Bundle 可承载的字符串，避免把领域模型拖进 saved state。
 */
internal val MediaItemSaver: Saver<MediaItem?, List<String>> = Saver(
    save = { item ->
        item?.let {
            listOf(
                it.id,
                it.sourceId,
                it.title,
                it.uri,
                it.mimeType,
                it.durationMs.toString(),
                it.sizeBytes.toString(),
                it.addedAtMs.toString(),
                it.coverUri ?: "",
                it.folder ?: "",
                it.width.toString(),
                it.height.toString(),
                // 富化字段（displayTitle/集号/编码）必须随状态保存：
                // 漏掉任何一个，rememberSaveable 一恢复 cardTitle 就退回整条文件名
                // （「剧集」→「剧集 E2.mkv」、meta 丢「第 N 集」），E42 白做
                it.displayTitle ?: "",
                it.seasonNumber.toString(),
                it.episodeNumber.toString(),
                it.videoCodec ?: "",
                it.clipStartUs.toString(),
                it.clipEndUs.toString(),
            )
        } ?: emptyList()
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            MediaItem(
                id = saved[0],
                sourceId = saved[1],
                title = saved[2],
                uri = saved[3],
                mimeType = saved[4],
                durationMs = saved[5].toLongOrNull() ?: 0L,
                sizeBytes = saved[6].toLongOrNull() ?: 0L,
                addedAtMs = saved[7].toLongOrNull() ?: 0L,
                coverUri = saved[8].ifEmpty { null },
                folder = saved[9].ifEmpty { null },
                width = saved[10].toIntOrNull() ?: 0,
                height = saved[11].toIntOrNull() ?: 0,
                displayTitle = saved[12].ifBlank { null },
                seasonNumber = saved[13].toIntOrNull() ?: 0,
                episodeNumber = saved[14].toIntOrNull() ?: 0,
                videoCodec = saved[15].ifBlank { null },
                clipStartUs = saved[16].toLongOrNull() ?: 0L,
                clipEndUs = saved[17].toLongOrNull() ?: 0L,
            )
        }
    },
)

/** 详情页状态（LibraryScreen 与测试共用）：可空，null = 不在详情页 */
@Composable
internal fun rememberDetailItemState() =
    rememberSaveable(stateSaver = MediaItemSaver) { mutableStateOf<MediaItem?>(null) }
