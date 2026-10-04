package com.noneplayer.plugins.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.episodeLabel
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpCarouselItem
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpCoverPlaceholder
import com.noneplayer.core.ui.NpEmptyState
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpMediaCard
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMoreLink
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpSectionHead
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.ui.api.ABOUT_ROUTE
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpTabSwitcher
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.NpTabChannel
import com.noneplayer.kernel.ui.api.SETTINGS_ROUTE
import com.noneplayer.kernel.ui.api.TabProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.kernel.ui.api.LocalNpBottomInset

/** "我的" Tab（route "profile"） */
class ProfileTabProvider : TabProvider {

    override val tabId: String = "profile"
    override val label: String = "我的"
    override val icon: Any = NpIcon.Person
    override val selectedIcon: Any? = NpIcon.PersonFill
    override val order: Int = 30
    override val route: String = "profile"
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            ProfileScreen(navigator = navigator)
        }
    }
}

/**
 * "我的"页——设计稿 `mineView()`。
 *
 * 结构：四个入口行 → 最近播放横滑 → 播放列表/插件清单 → 关于。
 *
 * **不编造数据**：设计稿的"128 条记录 · 今日 6 条"、"12 个视频"、"5 个自定义列表"
 * 是演示稿的假数据，本应用没有收藏/播放列表存储。这些行保留（设计稿的行结构与文案
 * 是信息架构的一部分），但副标题只显示能拿到的真实值；拿不到的就不显示数字，
 * 点击给出"功能尚未接入"的明确提示——一个会撒谎的界面比一个诚实的空位更糟。
 */
@Composable
private fun ProfileScreen(navigator: Navigator) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val ink = LocalNpInk.current
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    // 跳到同为顶层标签的设置页（见 [LocalNpTabSwitcher] 的说明）
    val switchTab = LocalNpTabSwitcher.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    // 最近播放：**真实播放记录**——有进度（>0）的条目按"上次播放时间"倒序。
    // 此前是按 joinedAtMs（入库时间）倒序的临时近似；D2/D7 接线后：
    // 进度与 lastAt 都由播放会话落盘（AppSettings），这里只排序，不再近似。
    val sources by remember { kernel.registry.observe<MediaSourceProvider>() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val mediaState by remember(sources) {
        if (sources.isEmpty()) {
            flowOf<List<MediaItem>?>(null)
        } else {
            combine(sources.map { it.observeMedia() }) { lists -> lists.flatMap { it } }
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val settings = remember { kernel.services.get<AppSettings>() }
    // §8.3：播放历史/收藏（进度与时间序都读 Room 仓库）
    val history = remember { kernel.services.get<HistoryRepository>() }
    val historyEntries by history.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val positions = remember(historyEntries) { historyEntries.associate { it.mediaId to it.positionMs } }
    val lastAt = remember(historyEntries) { historyEntries.associate { it.mediaId to it.lastPlayedAt } }
    val recent = remember(mediaState, positions, lastAt) {
        (mediaState ?: emptyList())
            .filter { (positions[it.id] ?: 0L) > 0L }
            .sortedByDescending { lastAt[it.id] ?: 0L }
            .take(12)
    }
    val historyCount = remember(positions) { positions.count { it.value > 0L } }
    // §8.3 收藏：与媒体库长按菜单的「加入收藏」共用同一个仓库 → 天然联动
    val favorites by history.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteItems = remember(mediaState, favorites) {
        val list = mediaState ?: emptyList()
        favorites.mapNotNull { key -> list.firstOrNull { HistoryEntry.keyOf(it) == key } }
    }

    /**
     * 打开首页的「播放历史」聚合。
     *
     * 两步走：意图交给内核的 [NpTabChannel]（首页取用后打开对应聚合），
     * 再切到首页 Tab。`switchTab` 必须在意图之后调用——先切 Tab 再写意图，
     * 首页可能已经完成本轮组合而消费不到。
     */
    fun openHistory() {
        NpTabChannel.request("home", "history")
        switchTab("home")
    }

    /** 打开首页的「我的收藏」聚合（与 [openHistory] 同一套跨 Tab 通道） */
    fun openFavorites() {
        NpTabChannel.request("home", "favorites")
        switchTab("home")
    }

    fun notYet(name: String) {
        Toast.makeText(context, "$name 尚未接入", Toast.LENGTH_SHORT).show()
    }

    NpPageScaffold(
        maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
        topBar = {
            ProfileTopBar(compact = compact)
        },
    ) { contentModifier ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = contentModifier.fillMaxSize(),
                // 底部让位由外层 Scaffold（Dock / Rail 内容列）负责，这里只补内容自身的呼吸空间
                contentPadding = PaddingValues(
                    top = LocalNpTopBarInset.current,
                    bottom = LocalNpBottomInset.current + if (compact) 24.dp else 32.dp,
                ),
            ) {
                val gutter = if (compact) 14.dp else 20.dp

                item(key = "entries") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = 8.dp),
                    ) {
                        EntryRow(
                            icon = NpIcon.Clock,
                            title = "播放历史",
                            // 设计稿是「128 条记录 · 今日 6 条」的假数据；
                            // 这里给真实条数（有播放记录即 > 0），拿不到就不显示数字
                            subtitle = if (historyCount > 0) "$historyCount 条记录" else null,
                            onClick = { openHistory() },
                        )
                        EntryRow(
                            icon = NpIcon.Heart,
                            title = "我的收藏",
                            // §8.3：真实收藏条数（数据来自与媒体库长按菜单同一个仓库）
                            subtitle = if (favorites.isNotEmpty()) "${favorites.size} 个" else null,
                            onClick = { openFavorites() },
                        )
                        EntryRow(
                            icon = NpIcon.List,
                            title = "播放列表",
                            subtitle = null,
                            onClick = { notYet("播放列表") },
                        )
                        EntryRow(
                            icon = NpIcon.Sound,
                            title = "音频收藏",
                            subtitle = null,
                            onClick = { notYet("音频收藏") },
                            showDivider = false,
                        )
                    }
                }

                if (favoriteItems.isNotEmpty()) {
                    item(key = "favorites") {
                        Column(modifier = Modifier.padding(top = 20.dp)) {
                            Box(modifier = Modifier.padding(horizontal = gutter)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NpEyebrow(text = "我的收藏")
                                    Box(modifier = Modifier.weight(1f))
                                    NpMoreLink(text = "全部", onClick = { openFavorites() })
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = gutter, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(favoriteItems, key = { "${it.sourceId}:${it.id}" }) { item ->
                                    NpCarouselItem {
                                        RecentCard(
                                            item = item,
                                            onOpen = { openPlayer(navigator, item) },
                                            progress = fractionOf(item, positions),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "recent") {
                    Column(modifier = Modifier.padding(top = 20.dp)) {
                        // 设计稿这一块的标题是 **eyebrow**（等宽 accent 小字，表示"次级流"），
                        // 而"播放列表"等区块才用 h2（内容分区）——层级语言不能混
                        Box(modifier = Modifier.padding(horizontal = gutter)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NpEyebrow(text = "最近播放")
                                Box(modifier = Modifier.weight(1f))
                                if (recent.isNotEmpty()) {
                                    NpMoreLink(
                                        text = "全部",
                                        // data-more="history"：进首页的「播放历史」聚合
                                        // （跨 Tab 通道见 [NpTabChannel]）
                                        onClick = { openHistory() },
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        if (recent.isEmpty()) {
                            NpEmptyState(
                                title = "还没有播放记录",
                                message = "播放过的条目会出现在这里。先在媒体库里开始播放任意一条。",
                            )
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = gutter, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(recent, key = { "${it.sourceId}:${it.id}" }) { item ->
                                    NpCarouselItem {
                                        RecentCard(
                                            item = item,
                                            onOpen = { openPlayer(navigator, item) },
                                            progress = fractionOf(item, positions),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 设计稿 mineView 的第三段：`<h2>播放列表</h2>` + 列表行。
                // 本应用尚无播放列表存储，按"不编造数据"原则给一个诚实的空态
                // （与"最近播放"无记录时同款），而不是三行演示稿的假列表。
                item(key = "playlists") {
                    Column(modifier = Modifier.padding(top = 20.dp)) {
                        Box(modifier = Modifier.padding(horizontal = gutter)) {
                            NpSectionHead(title = "播放列表")
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        NpEmptyState(
                            title = "还没有播放列表",
                            message = "播放列表功能尚未接入，之后自定义列表会出现在这里。",
                        )
                    }
                }

                // 「设置 / 关于 / 插件 / 应用 / 运行环境」**都不在这里**。
                //
                // "我的"页是**用户内容**的归属地（历史/收藏/播放列表），
                // 而设置与关于是**应用自身**的事，已经各有一个常驻标签与一个二级页：
                // 设置是底栏第 4 项（Rail 底部一项），关于在设置里下钻。
                // 在这里再放一遍，等于同一件事有三个入口，且把用户内容挤到下面。
            }
        }
    }
}

/**
 * 顶栏：与媒体库同款（大标题 + 副标题）。
 *
 * 副标题在紧凑档隐藏的理由与媒体库一致：22sp 标题已占满一行，
 * 再叠一行统计会把内容往下推。
 */
@Composable
private fun ProfileTopBar(compact: Boolean) {
    val ink = LocalNpInk.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 12.dp else 14.dp,
            ),
    ) {
        Text(
            text = "我的",
            style = if (compact) {
                NpTypography.displayLarge.copy(fontSize = NpTypography.headlineMedium.fontSize)
            } else {
                NpTypography.displayLarge
            },
            color = ink.fg,
            maxLines = 1,
        )
        if (!compact) {
            Text(
                text = "历史、收藏与播放列表",
                style = NpTypography.bodySmall,
                color = ink.muted,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 入口行：`NpSettingRow` 的薄封装，只为把"图标 → 行"的样板收在一处。 */
@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    NpSettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        showDivider = showDivider,
        trailing = {
            Icon(
                imageVector = NpIcon.ChevronRight,
                contentDescription = null,
                tint = LocalNpInk.current.muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        },
    )
}

/** 横滑卡片：封面 + 标题 + 时长（与媒体库同款密度）。 */
@Composable
private fun RecentCard(item: MediaItem, onOpen: () -> Unit, progress: Float = 0f) {
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    NpMediaCard(
        // E42：与媒体库卡片同一套展示名（富化短标题 + 「第 N 集」）
        title = item.cardTitle,
        // 设计稿 medCard 的 meta：`已看 x% · 时长 · 分辨率`
        meta = buildList {
            item.episodeLabel?.let { add(it) }
            if (progress > 0.01f && progress < 0.99f) add("已看 ${(progress * 100).toInt()}%")
            formatTimestamp(item.durationMs).takeIf { item.durationMs > 0 }?.let { add(it) }
            item.resolutionLabel.takeIf { it.isNotEmpty() }?.let { add(it) }
            item.videoCodec?.let { add(it) }
        },
        onClick = onOpen,
        cover = {
            NpCoverBox(
                placeholderHue = hue,
                isAudio = isAudio,
                showPlayGlyph = true,
                isNew = false,
                durationText = formatTimestamp(item.durationMs).takeIf { item.durationMs > 0 },
                progress = progress.takeIf { it > 0.01f },
                tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
            ) {
                RecentCover(item = item, hue = hue)
            }
        },
    )
}

@Composable
private fun RecentCover(item: MediaItem, hue: Int) {
    val context = LocalContext.current
    val isAudio = item.kind == MediaKind.AUDIO
    val model = remember(item.coverUri, item.uri, isAudio) {
        when {
            item.coverUri != null -> ImageRequest.Builder(context)
                .data(item.coverUri)
                .crossfade(180)
                .build()

            isAudio -> null
            else -> ImageRequest.Builder(context)
                .data(item.uri)
                .videoFrameMillis(0)
                .crossfade(220)
                .build()
        }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        NpCoverPlaceholder(hue = hue)
    }
}

/**
 * 跳转播放页：媒体信息经 URL 编码查询参数传递，由播放页插件解析。
 *
 * 编码用 `encode(value, "")` 把允许集显式收到"仅 unreserved 字符"——单参重载会放行
 * `& = ? +` 一类分隔符，一个含 `&` 的标题（"Rock & Roll.mkv"）就能把后面的
 * mime/id 参数整段截掉。这里与媒体库侧同一约定，但**不能复用它的函数**：
 * 插件之间禁止互相依赖，重复这十行是可接受的代价。
 */
/**
 * 进度分数：与媒体库侧同一算法，但**各持一份**——插件之间禁止互相依赖，
 * 十行重复是可接受的代价（与 `openPlayer` 的编码约定同理）。
 */
private fun fractionOf(item: MediaItem, positions: Map<String, Long>): Float {
    if (item.durationMs <= 0 || item.id.isEmpty()) return 0f
    val saved = positions[item.id] ?: return 0f
    if (saved <= 0) return 0f
    return (saved.toFloat() / item.durationMs).coerceIn(0f, 1f)
}

private fun openPlayer(navigator: Navigator, item: MediaItem) {
    fun enc(value: String) = android.net.Uri.encode(value, "")
    navigator.navigate(
        route = "player?uri=${enc(item.uri)}" +
            "&title=${enc(item.title)}" +
            "&mime=${enc(item.mimeType)}" +
            "&id=${enc(item.id)}",
    )
}
