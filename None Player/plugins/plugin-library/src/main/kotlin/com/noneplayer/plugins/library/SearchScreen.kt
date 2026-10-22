package com.noneplayer.plugins.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.SortMode
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaNaturalOrder
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.core.ui.NpChip
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEmptyState
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpFilterSelect
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSegmented
import com.noneplayer.core.ui.NpSkeleton
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npClickable
import com.noneplayer.core.ui.npItemFadeSpec
import com.noneplayer.core.ui.npMaskFadeEdges
import com.noneplayer.kernel.ui.api.LocalNpPaneOccupied
import com.noneplayer.core.ui.npStaggeredEntry
import com.noneplayer.core.ui.npStaggerWindow
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.rememberMediaItems
import com.noneplayer.kernel.ui.api.LocalNpBottomInset
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 搜索页路由（顶栏搜索钮 → navigator.navigate） */
const val SEARCH_ROUTE = "search"

/** L13：搜索防抖窗口——停顿这么久才按新词重算结果（设计文档既定参数 120ms） */
private const val SEARCH_DEBOUNCE_MS = 120L

/**
 * 搜索页——设计稿 `searchView()`。
 *
 * 结构：搜索框（r12 surface 底 + 1px 描边 + icon/input/clear）→
 * 无输入时「最近搜索」chips（DataStore 持久化，最多 5 条）→
 * 有输入时「搜索结果」（排序 + 视图切换 + N 项 + 网格/列表，空态「未找到 "q"」）。
 *
 * 匹配域 = 标题 + 文件夹名（设计稿 1146 `v.t.includes(q) || v.folder.includes(q)`，忽略大小写）。
 */
@Composable
fun SearchScreen(navigator: Navigator) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val settings = remember { kernel.services.get<AppSettings>() }
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    val gutter = if (compact) 14.dp else 20.dp

    var query by rememberSaveable { mutableStateOf("") }
    // L13：搜索防抖——输入框仍逐键实时回显 query，但结果只按 debouncedQuery 重算。
    // 此前 `remember(items, query, …)` 每敲一个键就全量 filter+sort 一遍，数千条的库
    // 会明显卡输入；拖动/快速输入尤其能感到掉帧。
    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query == debouncedQuery) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = query
    }
    // 审查批次 A1：仅由点构成（`.`、`..`）不算一次搜索——旧实现会命中整个媒体库
    // （扩展名分支 `contains("")` 恒真 + 标题域 `.` 命中所有带扩展名的文件）。
    // 判据与结果计算共用 `PinyinSearch.isSearchable`，两处不会再各说各话。
    val effectiveQuery = debouncedQuery.trim().takeIf { PinyinSearch.isSearchable(it) }.orEmpty()
    // 审查批次 A2：输入已改、结果尚未跟上（防抖窗内）时，旧结果**不可提交**——
    // 否则点击结果会把当前输入框里的新词记进搜索历史，打开的却是旧词的条目。
    // 结果区因此显示"计算中"占位，而不是留一批词与结果不匹配的条目让用户点。
    val resultsStale = query.trim() != debouncedQuery.trim()
    val history by settings.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val sortMode by settings.sortMode.collectAsStateWithLifecycle(SortMode.ADDED)
    val sortAscending by settings.sortAscending.collectAsStateWithLifecycle(false)
    val effectiveSortMode = sortMode.forSearch()
    val gridColumns by settings.gridColumns.collectAsStateWithLifecycle(2)
    val viewStyle by settings.listViewStyle.collectAsStateWithLifecycle("grid")

    // E105：媒体列表用共享助手的"首帧同步快照"版（重进页面不再闪一帧空模板）
    val mediaState = rememberMediaItems()
    val items = mediaState ?: emptyList()

    val results = remember(items, debouncedQuery, effectiveSortMode, sortAscending) {
        val q = debouncedQuery.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            // §3.7 匹配域：标题 / 短标题 / 文件夹 / 扩展名 / 拼音。
            // 走 `PinyinSearch.matchesAll` 唯一入口——扩展名分支曾在两处各写一遍，
            // 且 `contains("")` 让搜索单个 "." 命中全库（审查批次 A1）。
            val matched = items.filter { item -> PinyinSearch.matchesAll(item, q) }
            matched.sortedWith(mediaComparator(effectiveSortMode, sortAscending))
        }
    }

    /**
     * 把一次搜索词记进「最近搜索」。
     *
     * 审查批次 A2：**必须传入这次动作真正对应的词**，不能读 [query] 当前值。
     * 防抖窗内输入框已经是新词、而结果还属于旧词，此时若读 [query]，
     * 会把新词记进历史、打开的却是旧词的结果条目——历史与内容对不上。
     */
    fun commitSearch(term: String = query) {
        val q = term.trim()
        if (PinyinSearch.isSearchable(q)) scope.launch { settings.addSearchTerm(q) }
    }

    NpPageScaffold(
        maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
        topBar = {
            NpAppTopBar(
                title = "搜索",
                subtitle = "查找视频与文件夹",
                compact = compact,
                onBack = { navigator.popBackStack() },
            )
        },
    ) { contentModifier ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    // 顶栏是**覆盖式玻璃**（NpPageScaffold）：内容必须自己避让，
                    // 否则搜索框会被压在玻璃条之下（首屏看不见输入框）
                    .padding(top = LocalNpTopBarInset.current),
            ) {
                SearchBox(
                    query = query,
                    onQueryChange = { query = it },
                    onClear = { query = "" },
                    onCommit = { commitSearch() },
                    gutter = gutter,
                )
                if (effectiveQuery.isEmpty()) {
                    RecentSearches(
                        history = history,
                        gutter = gutter,
                        onPick = { term ->
                            query = term
                            commitSearch(term)
                        },
                    )
                } else {
                    SearchResults(
                        // 审查批次 A2：防抖窗内结果属于旧词，此时不能让它们可被打开
                        // ——点了会把新词记进历史却打开旧条目。传空列表 + 占位提示，
                        // 而不是留一批"词与内容不匹配"的条目在屏上。
                        results = if (resultsStale) emptyList() else results,
                        query = effectiveQuery,
                        stale = resultsStale,
                        gutter = gutter,
                        compact = compact,
                        gridColumns = gridColumns,
                        viewStyle = viewStyle,
                        onViewStyle = { scope.launch { settings.setListViewStyle(it) } },
                        sortMode = sortMode,
                        sortAscending = sortAscending,
                        onSort = { scope.launch { settings.setSortMode(it) } },
                        onToggleAscending = { scope.launch { settings.setSortAscending(it) } },
                        onOpen = { item ->
                            // 打开结果也是一次"有效搜索"：只靠 IME 的搜索键记历史，
                            // 硬件键盘/手势输入的用户永远攒不出最近搜索。
                            // 记的是**这批结果所属的词** [effectiveQuery]，不是输入框当前值。
                            commitSearch(effectiveQuery)
                            openPlayer(navigator, item)
                        },
                    )
                }
            }
        }
    }
}

/** 搜索框（设计稿 1148–1151：r12、surface 底、1px border、11×14 内边距）。 */
@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    gutter: Dp,
) {
    val ink = LocalNpInk.current
    val focusRequester = remember { FocusRequester() }
    SearchAutoFocus(focusRequester)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ink.surface)
            .border(1.dp, ink.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = NpIcon.Search,
            contentDescription = null,
            tint = ink.muted,
            modifier = Modifier.size(20.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索视频、文件夹、格式…",
                    style = NpTypography.bodyMedium,
                    color = ink.muted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = NpTypography.bodyMedium.copy(color = ink.fg),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ink.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onCommit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // 硬件键盘的 Enter 不会走 IME action（那是软键盘通道），
                    // 显式吃掉回车：提交搜索并记入最近搜索
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            onCommit()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        if (query.isNotEmpty()) {
            // 设计稿的 clear 钮用的也是 search 图标（原型如此），
            // 这里换成语义正确的 Close：清楚 > 逐字复刻一个含糊的图形
            NpIconButton(
                icon = NpIcon.Close,
                contentDescription = "清除",
                onClick = onClear,
                size = 28.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/** 进页自动聚焦输入框（设计稿 `autofocus`）——只做一次，避免用户手动移焦后被抢回。 */
@Composable
private fun SearchAutoFocus(focusRequester: FocusRequester) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
}

/** 「最近搜索」：eyebrow + 横滑 chips（DataStore 持久化）。 */
@Composable
private fun RecentSearches(
    history: List<String>,
    gutter: Dp,
    onPick: (String) -> Unit,
) {
    if (history.isEmpty()) return
    val ink = LocalNpInk.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(modifier = Modifier.padding(horizontal = gutter)) {
            NpEyebrow(text = "最近搜索")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 10.dp)
                .npMaskFadeEdges(ink.bg, fadeEnd = !LocalNpPaneOccupied.current),
        ) {
            items(history, key = { it }) { term ->
                NpChip(
                    text = term,
                    selected = false,
                    onClick = { onPick(term) },
                )
            }
        }
    }
}

/** 结果区：sec-head「搜索结果」+ 排序 + 视图切换 + N 项 + 网格/列表 + 空态。 */
@Composable
private fun SearchResults(
    results: List<MediaItem>,
    query: String,
    /** 结果尚未跟上输入框（防抖窗内）——显示"搜索中"而不是"未找到" */
    stale: Boolean = false,
    gutter: Dp,
    compact: Boolean,
    gridColumns: Int,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    // 换词 = 新的一屏结果：窗口跟着 query 重新起，结果集每次入场都错峰一次
    val staggerWindow = npStaggerWindow(query)
    val itemFade = npItemFadeSpec()
    // 有 query 就必然已经 commit 过历史（点结果 / 回车 / 点 chip 都会提交）
    if (viewStyle == "list") {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                bottom = LocalNpBottomInset.current + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") {
                SearchHead(
                    count = results.size,
                    viewStyle = viewStyle,
                    onViewStyle = onViewStyle,
                    sortMode = sortMode,
                    sortAscending = sortAscending,
                    onSort = onSort,
                    onToggleAscending = onToggleAscending,
                    compact = compact,
                )
            }
            if (results.isEmpty()) {
                item(key = "empty") { SearchPlaceholder(query = query, stale = stale) }
            }
            itemsIndexed(results, key = { _, item -> "${item.sourceId}:${item.id}" }) { index, item ->
                MediaListRow(
                    item = item,
                    compact = compact,
                    onOpen = onOpen,
                    modifier = Modifier
                        .animateItem(fadeInSpec = itemFade, fadeOutSpec = itemFade)
                        .npStaggeredEntry(index, staggerWindow),
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = gridColumnsFor(compact, gridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = LocalNpBottomInset.current + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            SearchHead(
                count = results.size,
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSort = onSort,
                onToggleAscending = onToggleAscending,
                compact = compact,
            )
        }
        if (results.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                SearchPlaceholder(query = query, stale = stale)
            }
        }
        gridItemsIndexed(results, key = { _, item -> "${item.sourceId}:${item.id}" }) { index, item ->
            MediaCardBody(
                item = item,
                onOpen = onOpen,
                showFolderTag = true,
                modifier = Modifier.animateItem(fadeInSpec = itemFade, fadeOutSpec = itemFade)
                    .npStaggeredEntry(index, staggerWindow),
            )
        }
    }
}

/**
 * `sec-head`：标题 + 计数（右端 mono）+ 排序 + 视图切换。
 *
 * 审查批次 D6：**窄窗不再把五组控件硬塞一行**。旧实现在紧凑档里标题只靠
 * `weight(1f)` 吃剩余宽度，排序/升降序/视图/计数四组都是固定宽，360dp 甚至
 * 分屏窄窗下标题会被压到几乎不可读、各控件热区也贴在一起。
 * 现在紧凑档：计数并进标题（"搜索结果 · N 项"）、隐藏独立计数、间距收到 8dp，
 * 把宽度让给标题与操作；宽档保持设计稿原样。
 */
@Composable
private fun SearchHead(
    count: Int,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    val ink = LocalNpInk.current
    val effectiveSortMode = sortMode.forSearch()
    // 设计稿 `.sec-head{gap:12px}`，控件顺序 `sortMenu → viewToggle → 计数`
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Text(
            // 紧凑档把计数并进标题：省下一组固定宽，标题也不至于被压没
            text = if (compact) "搜索结果 · $count 项" else "搜索结果",
            style = NpTypography.headlineSmall,
            color = ink.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        NpFilterSelect(
            label = effectiveSortMode.label,
            icon = NpIcon.Sort,
            options = SEARCH_SORT_MODES.map { it.label },
            selected = effectiveSortMode.label,
            onSelect = { label ->
                SEARCH_SORT_MODES.firstOrNull { it.label == label }?.let(onSort)
            },
            alignEnd = true,
        )
        Box(
            modifier = Modifier
                .height(NpDimens.chipHeight)
                .clip(RoundedCornerShape(NpRadius.chip))
                .border(
                    width = 1.dp,
                    color = if (sortAscending) ink.accentLine else ink.border,
                    shape = RoundedCornerShape(NpRadius.chip),
                )
                .npClickable { onToggleAscending(!sortAscending) }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (sortAscending) "升序" else "降序",
                style = NpMonoSmall,
                color = if (sortAscending) ink.accent else ink.muted,
                maxLines = 1,
            )
        }
        NpSegmented(
            options = listOf("grid", "list"),
            selected = viewStyle,
            icons = listOf(NpIcon.GridView, NpIcon.ListView),
            itemWidth = 34.dp,
            onSelect = onViewStyle,
        )
        // 紧凑档计数已在标题里，避免同屏两个"N 项"
        if (!compact) {
            Text(text = "$count 项", style = NpMono, color = ink.muted, maxLines = 1)
        }
    }
}

/**
 * 结果区占位：`搜索中…` / `未找到 "q"`（设计稿 1159）。
 *
 * 审查批次 A2：**"还在算"不等于"没结果"**。防抖窗内如果把旧结果撤掉却仍显示
 * "未找到 xxx"，用户会以为这个关键词就是搜不到，而其实只是 120ms 还没到。
 * 两种语义必须分开：stale → 搜索中（骨架），否则 → 未找到。
 */
@Composable
private fun SearchPlaceholder(query: String, stale: Boolean) {
    if (stale) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "搜索中…",
                style = NpTypography.bodyMedium,
                color = LocalNpInk.current.muted,
            )
            NpSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(96.dp),
            )
        }
        return
    }
    NpEmptyState(
        title = "未找到 “$query”",
        message = "试试其他关键词，或检查文件名筛选。",
        modifier = Modifier.padding(top = 24.dp),
    )
}
