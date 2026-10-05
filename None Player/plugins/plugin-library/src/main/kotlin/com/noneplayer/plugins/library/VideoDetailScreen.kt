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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.episodeLabel
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMono
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
 * 版式（用户明确要求）：宽屏（≥720dp）双栏——**视频小窗居左，剧集列表（同文件夹）
 * 常驻右侧**；视频详情（标题/元信息/文件信息）收进播放窗右下角的「详情」按钮，
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
    onSelect: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = LocalNpBottomInset.current
    // 顶栏是**覆盖式悬浮层**（NpPageScaffold 的契约），内容必须自己让位：
    // 不消费这个内边距，封面会被顶栏压掉一整条（用户实报"视频上半部分被挡住"）。
    val topInsetRaw = LocalNpTopBarInset.current
    val topPad = if (topInsetRaw == Dp.Unspecified) 0.dp else topInsetRaw
    val gutter = LocalNpWindowInfo.current.pageGutter
    // 内嵌播放：详情页里直接开播（封面框就地变播放器）；全屏键才进独占播放页。
    // 会话是同一个——落全屏只续播不重启，退出全屏回详情、画面仍在原处继续。
    // 状态**不按键**：在详情页里切换同文件夹条目时保持"继续播新选的那一集"
    // （与市面播放器的选集行为一致）；离开详情页即随组合销毁复位。
    var inlinePlaying by rememberSaveable { mutableStateOf(false) }
    val inlineAvailable = inlinePlayer != null
    fun startPlayback() {
        if (inlineAvailable) inlinePlaying = true else onFullscreen(item)
    }
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val progress = if (item.durationMs > 0 && resumeMs > 0) {
        (resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val canResume = resumeMs > 5_000

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        val pad = PaddingValues(
            start = gutter,
            end = gutter,
            top = topPad + 12.dp,
            bottom = bottomInset + 28.dp,
        )
        if (twoPane) {
            // 视频详情弹成**浮窗层小窗口**；剧集列表（同文件夹）默认常驻右侧。
            // rememberSaveable 让弹窗状态跨播放往返保留——进播放页回来不丢。
            var showDetail by rememberSaveable { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
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
                        onOpenInfo = { showDetail = true },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SiblingList(item = item, siblings = siblings, onSelect = onSelect)
                }
            }
            if (showDetail) {
                DetailPopup(item = item, onDismiss = { showDetail = false })
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(pad),
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
                )
                Spacer(Modifier.height(22.dp))
                DetailMeta(item = item)
                if (siblings.size > 1) {
                    Spacer(Modifier.height(22.dp))
                    SiblingList(item = item, siblings = siblings, onSelect = onSelect)
                }
            }
        }
    }
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
    /** 详情浮窗入口（null/省略 = 窄窗常显详情，不渲染「详情」键） */
    onOpenInfo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
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
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (inline) {
                // 已在内嵌播放：主操作只剩"去哪看"——全屏
                NpPrimaryButton(
                    text = "全屏观看",
                    icon = NpIcon.Expand,
                    onClick = onFullscreen,
                )
            } else {
                NpPrimaryButton(
                    text = if (canResume) "继续观看 · ${formatTimestamp(resumeMs)}" else "播放",
                    icon = NpIcon.Play,
                    onClick = onStartInline,
                )
            }
            if (item.durationMs > 0) {
                Text(
                    text = formatTimestamp(item.durationMs),
                    style = NpMono,
                    color = ink.muted,
                )
            }
            if (onOpenInfo != null) {
                // 详情浮窗键：与全屏/播放同一行，推到最右（用户要求）
                Spacer(Modifier.weight(1f))
                NpPrimaryButton(
                    text = "详情",
                    icon = NpIcon.Info,
                    primary = false,
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
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val ext = item.title
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 }
        ?.uppercase()
    val container = ext ?: item.mimeType.substringAfter('/', "").uppercase().takeIf { it.isNotBlank() }
    val meta = buildList {
        item.episodeLabel?.let { add(it) }
        if (item.resolutionLabel.isNotBlank()) add(item.resolutionLabel)
        if (item.durationMs > 0) add(formatTimestamp(item.durationMs))
        if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
        container?.let { add(it) }
    }

    Column(modifier = modifier) {
        Text(
            // E42：与卡片同一套展示名（富化出短标题就用它，否则退回整条文件名）
            text = item.cardTitle,
            style = NpTypography.headlineSmall,
            color = ink.fg,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            Text(
                text = meta.joinToString("  ·  "),
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        DetailInfoRow("所在文件夹", item.folder?.takeIf { it.isNotBlank() } ?: "—")
        if (item.width > 0 && item.height > 0) {
            DetailInfoRow("分辨率", "${item.width} × ${item.height}")
        }
        if (item.durationMs > 0) {
            DetailInfoRow("时长", formatTimestamp(item.durationMs))
        }
        if (item.sizeBytes > 0) {
            DetailInfoRow("大小", formatFileSize(item.sizeBytes))
        }
        DetailInfoRow("格式", container ?: "—")
        if (item.addedAtMs > 0) {
            DetailInfoRow("加入时间", formatRelativeDate(item.addedAtMs), divider = false)
        }
    }
}

/** 剧集列表（同文件夹）——宽屏常驻右侧，窄窗跟随详情 */
@Composable
private fun SiblingList(
    item: MediaItem,
    siblings: List<MediaItem>,
    onSelect: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Column(modifier = modifier) {
        if (siblings.size > 1) {
            NpSectionHead(title = "同文件夹（${siblings.size}）")
            Spacer(Modifier.height(6.dp))
            siblings.forEachIndexed { index, sibling ->
                val current = sibling.id == item.id
                NpOptRow(
                    // 短标题 + 集号：同名剧集（"剧集 · 第 2 集" / "剧集 · 第 10 集"）才分得清
                    text = listOfNotNull(
                        sibling.cardTitle.ifBlank { sibling.uri.substringAfterLast('/') },
                        sibling.episodeLabel,
                    ).joinToString(" · "),
                    selected = current,
                    onClick = { if (!current) onSelect(sibling) },
                    leading = {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            style = NpMonoSmall,
                            color = ink.muted,
                            modifier = Modifier.width(28.dp),
                        )
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
        }
    }
}

/**
 * 视频详情浮窗层小窗口：标题/元信息/文件信息。
 * 点遮罩、关闭键或系统返回键退出；内容超窗高时内部滚动。
 */
@Composable
private fun DetailPopup(
    item: MediaItem,
    onDismiss: () -> Unit,
) {
    val ink = LocalNpInk.current
    val shape = RoundedCornerShape(NpRadius.card)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(ink.surface)
                .border(1.dp, ink.border, shape)
                .npShadow(ink, shape, elevation = 18.dp)
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
            DetailMeta(item = item)
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
            )
        }
    },
)

/** 详情页状态（LibraryScreen 与测试共用）：可空，null = 不在详情页 */
@Composable
internal fun rememberDetailItemState() =
    rememberSaveable(stateSaver = MediaItemSaver) { mutableStateOf<MediaItem?>(null) }
