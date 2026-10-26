@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.noneplayer.plugins.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LibraryView
import com.noneplayer.core.data.SortMode
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.episodeLabel
import com.noneplayer.core.media.kind
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.media.MediaNaturalOrder
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.kernel.ui.api.LocalNpBottomInset
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpCarouselItem
import com.noneplayer.core.ui.NpChip
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpCoverPlaceholder
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEmptyState
import com.noneplayer.core.ui.NpFilterSelect
import com.noneplayer.core.ui.npGridColumnsFor
import com.noneplayer.core.ui.NpFolderCard
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpListRow
import com.noneplayer.core.ui.NpCoverFlight
import com.noneplayer.core.ui.npQuality
import com.noneplayer.core.ui.LocalNpReduceMotion
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.NpMediaCard
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpOptRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpPrimaryButton
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSectionHead
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpAnchorHolder
import com.noneplayer.core.ui.NpMenuSurface
import com.noneplayer.core.ui.NpSegmented
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpSkeleton
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.formatRelativeDate
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npAnchor
import com.noneplayer.core.ui.npItemFadeSpec
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npStaggeredEntry
import com.noneplayer.core.ui.npStaggerWindow
import com.noneplayer.core.ui.rememberNpAnchorHolder
import com.noneplayer.core.ui.rememberNpHaptics
import com.noneplayer.kernel.api.ContextMenuItem
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import com.noneplayer.kernel.api.subscribe
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.ScanProgress
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.ScanFolderController
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.rememberMediaItems
import com.noneplayer.kernel.ui.api.InlinePlaybackProvider
import com.noneplayer.kernel.ui.api.LocalNpPaneOccupied
import com.noneplayer.kernel.ui.api.LocalNpTabSwitcher
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.NpDetailRequest
import com.noneplayer.kernel.ui.api.NpFormHandoff
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体库页面——设计稿 `none-player.html` 的 Home / Folders / 聚合列表三套版式。
 *
 * 通过 MediaSourceProvider 扩展点聚合所有来源的媒体列表；
 * 排序方式/视图风格/列数经 AppSettings 持久化；扫描目录未选择时引导选择
 * （不选择文件夹不扫描）。
 *
 * [forcedView] 必传：决定本页渲染哪套版式（首页 = VIDEOS，文件夹 Tab = FOLDERS）。
 * 曾经它可空、回落到持久化偏好 `libraryView`——该偏好的写入入口（标题行视图切换）
 * 移除后，旧数据会把首页永久钉在文件夹界面（用户实报），于是收窄为必传。
 *
 * 状态机：无来源 → 空状态；缺权限 → 授权引导；未选目录 → 选择文件夹；
 * 有目录未扫描 → 开始扫描；有数据 → 封面墙/文件夹卡片。
 */
/**
 * 媒体库的**页内态**（`AnimatedContent` 的 key）。
 *
 * 桌面档把"详情 / 聚合 / 文件夹层级"做成了**页内态**而不是路由——没有路由转场可借，
 * 此前它们之间是硬切（`when` 直接换块，见 2026-10-08 的走查）。这里给每态一个 [rank]
 * （层级深度）：交叉淡入是底色，**层级差不等于零**时才给 8dp 方向位移。
 */
private sealed interface LibraryStage {
    val rank: Int

    data object NoSource : LibraryStage { override val rank = 0 }
    data object NoPermission : LibraryStage { override val rank = 0 }
    data object NoFolder : LibraryStage { override val rank = 0 }
    data object Loading : LibraryStage { override val rank = 0 }
    data object Empty : LibraryStage { override val rank = 0 }
    data object Home : LibraryStage { override val rank = 0 }

    data object Folder : LibraryStage { override val rank = 1 }

    /**
     * 载荷**随 key 一起走**：`AnimatedContent` 在过渡期会继续渲染旧帧，而旧帧读到的
     * 实时状态（`detailItem` / `aggregate`）可能已经被置空——第一版就是这么崩的
     * （NPE，`LibraryScreen.kt` 旧帧的 `detailItem!!`）。臂里一律用 key 里的载荷。
     */
    data class Aggregate(val mode: String) : LibraryStage { override val rank = 1 }
    data class Detail(val item: MediaItem) : LibraryStage { override val rank = 2 }
}

@Composable
fun LibraryScreen(navigator: Navigator, forcedView: LibraryView) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { kernel.services.get<AppSettings>() }
    // §8.3：播放历史/收藏（进度、时间序、收藏键都从这里来）
    val history = remember { kernel.services.get<HistoryRepository>() }
    // E232：删除成功后广播 MediaDeleted（播放会话据此停掉正在播的那一条）。
    // 声明提前到删除回调之前——Kotlin 局部变量不支持前向引用。
    val eventBus = remember { kernel.services.get<EventBus>() }
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    // 跨 Tab 跳转通道（首页"文件夹"区块 → 文件夹 Tab）。见 [FolderNav] 与
    // `LocalNpTabSwitcher` 的说明：Tab 路由在内层导航图，拼外层路由会崩。
    val switchTab = LocalNpTabSwitcher.current
    // ---- 扫描目录管理（统一入口）----
    // 「文件夹管理界面」此前只在设置页深处；本页把它收成一处：文件夹 Tab 根层的
    // 入口行打开管理面板；首页文件夹区块只浏览目录，不进入管理。
    var scanFolderSheet by remember { mutableStateOf(false) }
    // 增删目录的副作用（释放 SAF 授权 / 重扫 / 清列表）归扫描来源所有，
    // 走能力契约调用；未注册实现时各回调退化为"只改设置 + 重扫"。
    val scanFolderController = remember {
        runCatching { kernel.registry.query<ScanFolderController>().firstOrNull() }.getOrNull()
    }
    // ---- 用户偏好（持久化） ----
    val libraryPreferences by remember(settings) {
        settings.libraryPreferences.map { it as com.noneplayer.core.data.LibraryPreferences? }
    }.collectAsStateWithLifecycle(initialValue = settings.libraryPreferencesSnapshot)
    val sortMode = libraryPreferences?.sortMode ?: SortMode.ADDED
    val sortAscending = libraryPreferences?.sortAscending ?: false
    val gridColumns = libraryPreferences?.gridColumns ?: 2
    // E78 的可空哨兵 + E105 的首帧同步：初值取进程级快照（本进程还没读过时为 null）。
    // 旧写法初值直接给 emptyList()，把"还没读到"当成"确实没配"——而 AnimatedContent
    // 在过渡期会继续渲染旧帧（见 LibraryStage 注释），于是每次切 Tab / 从播放页返回
    // 都要闪一帧「选择要观看的文件夹」空态浮在正在淡出的页面上（2026-10-11 用户实报，
    // 实机抓帧在案）。
    val folderUris by remember(settings) {
        settings.scanFolderUris.map<List<String>, List<String>?> { it }
    }.collectAsStateWithLifecycle(initialValue = settings.scanFolderUrisSnapshot)
    // 设计稿 viewToggle 的 `state.view`：网格 / 列表，跨页面共用并持久化
    val listViewStyle = libraryPreferences?.viewStyle ?: "grid"
    val onViewStyle: (String) -> Unit = { scope.launch { settings.setListViewStyle(it) } }
    // 视图由调用方（Tab）固定：首页 Home 版式、文件夹 Tab 文件夹版式
    val viewMode = forcedView
    // 首页「管理」→ 文件夹 Tab（已在 Tab 上时切换被短路，靠这枚值型信号开面板）
    val manageRequest by FolderNav.manageRequest.collectAsStateWithLifecycle()
    LaunchedEffect(manageRequest, viewMode) {
        val tick = manageRequest ?: return@LaunchedEffect
        if (viewMode != LibraryView.FOLDERS) return@LaunchedEffect
        scanFolderSheet = true
        FolderNav.consumeManage(tick)
    }
    // 首屏错峰窗口：**每页取一次**，再发给各区块（首页 rail / 文件夹网格 / 聚合网格）。
    // 放在区块内部取会让"滚动进来的区块"每次都当成首屏，滚动全程都在跑入场动画。
    val staggerWindow = npStaggerWindow(viewMode)
    // B5 修复：rememberSaveable，横竖屏切换/进程回收后文件夹选中态不丢
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    // 页内层级：聚合列表（继续观看 / 全部视频 / …）不下探到导航栈，
    // 否则每个"全部 ›"都要多一层返回，而它本质上是同一页的筛选结果。
    var aggregate by rememberSaveable { mutableStateOf<String?>(null) }
    // 类型筛选（设计稿 FILTERS）：提升到页面顶层，FolderBrowser 与 AggregateList
    // 共用同一份选择——两处各记一份的话，从文件夹进"全部"再返回筛选就"重置"了。
    var filter by rememberSaveable { mutableStateOf(FilterKey.ALL.key) }

    // 平板横屏（类 PC）：点视频先进「视频详情」内页（侧边 Rail 常驻不变），
    // 从详情页的播放键/封面再进全屏播放器；返回链路 = 播放器 → 详情 → 媒体库。
    // 详情是**页内状态**而不是路由：Rail 属于 Shell，只有留在 Tab 内，
    // 导航选中态与层级语义才天然正确；rememberSaveable 让它跨播放页往返保留。
    var detailItem by rememberDetailItemState()
    // 同一部剧里换集：只改高亮和播放队列，不换详情页这个导航态。
    // 换 detailItem 会让 AnimatedContent / 顶栏把「切集」做成一次界面过渡（文字重影）。
    var episodeFocus by remember { mutableStateOf<MediaItem?>(null) }
    // 内嵌播放扩展点：详情页里直接开播（封面框就地变播放器），全屏键才进独占播放页。
    // 未注册实现时详情页回退为整页播放——缺插件不该让"播放"这个动作消失。
    val inlinePlayers by remember { kernel.registry.observe<InlinePlaybackProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<InlinePlaybackProvider>())
    // §8.2 路径 A：外壳已经把右栏让给播放器时，详情页不再自带内联画面——
    // 一台播放器只有一个输出 Surface，两处同时挂会把先挂的那个顶成黑屏。
    // 此时详情页只剩封面海报；整页观看走「全屏观看」（全屏只有沉浸一种）。
    val paneOccupied = LocalNpPaneOccupied.current
    val inlinePlayer = if (paneOccupied) null else inlinePlayers.firstOrNull()
    /** 点视频的落点分派：类 PC 先看详情；其余形态保持"直接进播放页" */
    val openDetailOrPlayer: (MediaItem) -> Unit = { item ->
        if (windowInfo.isDesktopLike) {
            episodeFocus = null
            detailItem = item
        } else {
            openPlayer(navigator, item)
        }
    }
    // 详情页**只在类 PC 档渲染**（两个入口都拿 `isDesktopLike` 把关：横屏点卡片先进
    // 剧集页，竖屏是直接进播放页）。但"只在类 PC 档"落在**渲染分支**上，不落进状态：
    // 转出那一档就把它清空，转回横屏时左栏只剩列表，用户实报"竖屏播放页转横屏，
    // 左边默认在首页/文件夹页，应该是横屏的剧集页"（2026-10-14 真机）。
    // 留住状态、只按形态决定画不画，两个方向就都对了：竖屏不画它（不再"详情外显"），
    // 转回横屏它自己回来。
    //
    // **交接期间照旧画它**（`keepsDesktopChrome`）：横转竖那一帧 `isDesktopLike` 已经翻假，
    // 而竖屏播放页要到下一帧才组合出来——中间这一帧左栏若收成首页，用户就在"背景里"
    // 看见首页和剧集页各闪一下。留在类 PC 版上，等播放页盖上来外壳再收。
    val detailOpen = (windowInfo.isDesktopLike || NpFormHandoff.keepsDesktopChrome) &&
        detailItem != null
    // 剧集详情页在竖屏的对应物是播放页（这一档压根没有详情页）。右栏没在播时转屏，
    // 若这里不登记落点，`NpFormHandoff` 就没有路线可走，人会被留在收成首页的媒体库上
    // ——真机实报"横屏播放界面不播放时切竖屏直接返回首页"（2026-10-14 第二轮）。
    // `play=0` = 只装载不起播：横屏那一档本来就没在播，转个屏不该把片子放起来。
    val detailHandoff = detailItem?.takeIf { it.kind != MediaKind.AUDIO }
    SideEffect {
        NpFormHandoff.offerDetailRoute(
            if (windowInfo.isDesktopLike && detailHandoff != null) playerRouteOf(detailHandoff, play = false)
            else null,
        )
    }
    DisposableEffect(Unit) {
        onDispose { NpFormHandoff.clearDetailRouteWhileDesktop() }
    }
    // 竖屏点卡片是直接进播放页（这一档没有剧集页可进），窗口转横、播放页交还右栏时，
    // 本页得把左栏落到正在播的那条上——请求由播放页发，见 [NpDetailRequest]。
    // 只认**新鲜**请求：本页不在当前 Tab 时没人消费，迟到值会在很久以后凭空跳详情页。
    val detailRequest by NpDetailRequest.flow.collectAsStateWithLifecycle()
    LaunchedEffect(detailRequest, windowInfo.isDesktopLike) {
        val tick = detailRequest ?: return@LaunchedEffect
        if (!windowInfo.isDesktopLike) return@LaunchedEffect
        if (System.currentTimeMillis() - tick.second > NpDetailRequest.MAX_AGE_MS) {
            NpDetailRequest.consume(tick)
            return@LaunchedEffect
        }
        if (detailItem?.uri != tick.first.uri) {
            episodeFocus = null
            detailItem = tick.first
        }
        NpDetailRequest.consume(tick)
    }

    // ---- 媒体数据（扩展点聚合） ----
    // E10：不再本页自建装载器——AsyncImage 经 LocalImageLoader 默认值解析到
    // Application 级 Coil 单例（NonePlayerApplication.newImageLoader）
    val sources by remember {
        kernel.registry.observe<MediaSourceProvider>()
    }.collectAsStateWithLifecycle(
        // E105：初始值用 registry 的同步查询——`observe` 的首次发射也要等一帧
        initialValue = remember { kernel.registry.query<MediaSourceProvider>() },
    )
    // E78：媒体列表用「可空哨兵」承载，以区分两种语义完全不同的空：
    //   null      = 尚未收到任何发射（插件/扫描数据未就绪）
    //   emptyList = 已确实加载，且结果就是空
    // 此前二者不可区分，导致下面的「目录失效 → 回根层」守卫在**入口重建的首帧**
    // 拿空树去校验刚被 rememberSaveable 恢复的选中路径，把状态当场清掉：
    // 表现为「文件夹进到第 N 层 → 切 Tab 或看视频返回 → 退回根层」。
    // E105：媒体列表改用 `rememberMediaItems()`——它首帧就给**同步快照**。此前这里
    // 首帧必然是 null → 先渲染一帧"0 个视频 · 0 个文件夹"的空模板，叠上转场淡出就是
    // 用户实报的"每切一次界面闪一帧模板页"。E78 的可空哨兵语义不变（null＝确实还没扫过）。
    val mediaState = rememberMediaItems()
    val mediaLoaded = mediaState != null
    val items = mediaState ?: emptyList()

    // D2：播放进度接线——「继续观看 / 已看 x% / 封面进度线 / 未看完筛选」的单一数据源。
    // §8.3 起来源是 Room 历史（DataStore 断点键随 E11 迁移退役）：会话每 5s/暂停/切集
    // 写入仓库，这里一次读全量再经 CompositionLocal 下发，卡片与筛选只读
    // [LocalPlaybackProgress]，不再各开订阅。
    // 「最近播放 / 最常看」排序要用同一份历史，故在排序前收集；键必须是跨来源唯一键
    // `sourceId:mediaId`（[HistoryEntry.keyOf]，E73 分命名空间），不能退化成裸 ID：
    // 不同 MediaSourceProvider 出现相同媒体 ID 时，associate 会互相覆盖同一键。
    val historyEntries by history.recent.collectAsStateWithLifecycle()
    val positions = remember(historyEntries) {
        historyEntries.associate { it.mediaKey to it.positionMs }
    }
    val lastAt = remember(historyEntries) {
        historyEntries.associate { it.mediaKey to it.lastPlayedAt }
    }
    val playCounts = remember(historyEntries) {
        historyEntries.associate { it.mediaKey to it.playCount }
    }
    val playbackSort = remember(lastAt, playCounts) {
        PlaybackSortContext(lastPlayedAt = lastAt, playCounts = playCounts)
    }

    val sortedItems = rememberSortedMediaItems(items, sortMode, sortAscending, playbackSort)

    // 文件夹层级树（根 = 虚拟根；进入子级后展示子文件夹 + 本级视频）
    val folderRoot = remember(sortedItems) { buildFolderTree(sortedItems) }
    val currentFolder = remember(selectedFolder, folderRoot) { folderRoot.findByPath(selectedFolder) }
    // 目录被移除/重扫后选中路径可能失效 → 自动回到根层。
    // E78：**必须等媒体数据真正加载完再校验**——未加载时树必然是空的，
    // 此时校验等于用"还没读到"冒充"已不存在"，会清掉刚恢复的层级状态。
    LaunchedEffect(selectedFolder, folderRoot, mediaLoaded) {
        // 首页刚请求打开的路径还没被本页消费时，树可能还对不上——这时清掉
        // 会把用户点的文件夹打回根层（根层第一行就是「扫描目录 / 管理」）。
        if (FolderNav.pathRequest.value != null) return@LaunchedEffect
        if (mediaLoaded && selectedFolder != null && currentFolder == null) selectedFolder = null
    }

    // 首页"文件夹"区块请求打开某个目录 → 本页（文件夹 Tab）接住并进入该层。
    // 只在文件夹视图下消费：首页那次点击已经切了 Tab，这里补上"打开哪一层"。
    val folderRequest by FolderNav.pathRequest.collectAsStateWithLifecycle()
    LaunchedEffect(viewMode, folderRequest, mediaLoaded, folderRoot) {
        if (viewMode != LibraryView.FOLDERS || !mediaLoaded) return@LaunchedEffect
        val path = folderRequest ?: return@LaunchedEffect
        // 树里还没有这一层就先留着请求，别消费完再被上面的失效守卫清回根层。
        if (path.isNotBlank() && folderRoot.findByPath(path) == null) return@LaunchedEffect
        FolderNav.consume()?.let { consumed ->
            detailItem = null
            aggregate = null
            episodeFocus = null
            selectedFolder = consumed.takeIf { it.isNotBlank() }
        }
    }

    // E54：文件夹层级是页内状态（不入返回栈），系统返回必须在此消费，
    // 否则返回事件落到导航层 → 切走 Tab / 弹回首页，用户看到"退出了文件夹页"。
    BackHandler(enabled = viewMode == LibraryView.FOLDERS && selectedFolder != null) {
        selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
    }
    BackHandler(enabled = aggregate != null) { aggregate = null }
    // 详情页在最上层：后注册的 BackHandler 优先，返回先退详情再谈层级。
    // 认 `detailOpen` 而不是 `detailItem`：竖屏不画这一页，也就不该替返回键占位。
    BackHandler(enabled = detailOpen) { detailItem = null }

    // ---- 权限与目录门控 ----
    var permissionTick by remember { mutableIntStateOf(0) }
    val availability by produceState(emptyMap<String, Boolean>(), sources, permissionTick) {
        value = sources.associate { it.sourceId to it.isAvailable() }
    }
    val missingPermission = sources.isNotEmpty() &&
        availability.isNotEmpty() &&
        availability.values.any { !it }

    // E29：响应式收集——插件动态启用后其权限可被补申请（与 E22/E23 同源）
    val permissionProviders by remember { kernel.registry.observe<PermissionProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PermissionProvider>())
    val permissionStrings = permissionProviders.flatMap { it.permissions }.distinct()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) permissionTick++
    }
    // REV-13：部分授权（只给视频或只给音频）不再是"整页不可用"，但要明确缺哪一类。
    // 授权状态按 permissionTick 重读（权限回调驱动），避免每次重组都走一遍 binder。
    val grantedPermissionStrings = remember(permissionStrings, permissionTick) {
        permissionStrings.filterTo(mutableSetOf()) {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    val partialPermission = remember(permissionStrings, grantedPermissionStrings) {
        partialPermissionHint(permissionStrings, grantedPermissionStrings)
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // E64 修复：与 LocalScannerSettings 同根因——take 侧补齐与 release 侧
            // （LocalMediaSource.removeScanFolder）一致的 SecurityException 捕获。
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (granted) {
                scope.launch {
                    val uriStr = uri.toString()
                    val controller = scanFolderController
                    if (controller != null) {
                        // 契约实现（扫描插件）自带判重与"持久化后立即扫描"语义
                        controller.addScanFolder(uriStr)
                    } else {
                        // 降级：只改设置 + 重扫。判重口径与 scanner.addScanFolder 一致——
                        // 重复添加同一目录会让扫描范围重叠（双份文件的历史来源）
                        val current = settings.scanFolderUris.first()
                        if (uriStr !in current) settings.setScanFolders(current + uriStr)
                        sources.filter { it.isAvailable() }.forEach { it.scan() }
                    }
                }
            } else {
                android.widget.Toast.makeText(
                    context,
                    "无法持久化该目录的访问权限（可能已达到系统上限）",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    // 移除扫描目录：走契约（释放授权 + 重扫 / 清列表）；降级路径补做释放授权
    val removeScanFolder: (String) -> Unit = { uriStr ->
        scope.launch {
            val controller = scanFolderController
            if (controller != null) {
                controller.removeScanFolder(uriStr)
            } else {
                releasePersistedTree(context, uriStr)
                settings.setScanFolders(settings.scanFolderUris.first() - uriStr)
                sources.filter { it.isAvailable() }.forEach { it.scan() }
            }
        }
    }

    var requestedScan by remember { mutableStateOf(false) }
    fun scanAll() {
        scope.launch {
            sources.filter { it.isAvailable() }.forEach { it.scan() }
        }
    }

    // ---- 长按菜单「删除」（删除设备上的文件）----
    // E232 用户裁定：**应用内确认是唯一一道确认**。旧实现在应用确认之后还走
    // `MediaStore.createDeleteRequest`，Android 11+ 会再弹一次系统确认框——那套
    // UI/样式与播放器无关，用户明确要求去掉、只保留应用自己的确认。
    // 删除按能力顺序尝试：SAF 授权树写入（有写授权时）→ ContentResolver 直接删除
    // （文件属主 / 已授予「所有文件访问」）。都不行就如实提示，不再借系统弹框。
    // 结果无论成败都做一次重扫（增量对比，没变则零写盘）。
    var pendingDelete by remember { mutableStateOf<MediaItem?>(null) }
    val requestDelete: (MediaItem) -> Unit = { item ->
        scope.launch {
            val trees = settings.scanFolderUris.first()
            val ok = withContext(Dispatchers.IO) {
                deleteMediaItem(context, item, trees)
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "已删除" else "删除失败：请在系统设置中开启「所有文件访问」后重试",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            if (ok) {
                // E232：让播放会话停掉"正在播的那一条"——引擎握着的旧句柄不会因为
                // 文件消失而自动停，迷你条/通知也会残留一条不存在的媒体。
                eventBus.publish(KernelEvent.MediaDeleted(item.id, item.uri))
                scanAll()
            }
        }
    }

    // 一次 partition 得到两组（两次 filter 要全量扫两遍 sortedItems）
    val (videos, audios) = remember(sortedItems) {
        sortedItems.partition { it.kind == MediaKind.VIDEO }
    }
    // 设计稿「最近添加」`videos.slice().reverse().slice(0,8)`：全库（含音频）按加入时间
    // 倒序取 8 条——排序偏好（名称/大小）不该改写这个区块的语义，所以独立计算。
    val recentItems = remember(items) { items.sortedByDescending { it.addedAtMs }.take(8) }

    // 下拉刷新（A25）：指示器跟随各来源的 scanProgress，不再用本地 refreshing +
    // 15s 超时猜收尾；扫描失败/取消时来源自己回到 idle，指示器随之收起。
    val scanProgress by produceState(ScanProgress(), sources) {
        val flows = sources.map { it.observeScanProgress() }
        if (flows.isEmpty()) {
            value = ScanProgress()
        } else {
            combine(*flows.toTypedArray()) { progresses ->
                aggregateScanProgress(progresses.toList())
            }.collect { value = it }
        }
    }
    val refreshing = scanProgress.active
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val haptics = rememberNpHaptics()

    // 详情页跟随队列：引擎推进播放项（自动连播 / 通知栏 ⏭）时，右栏画面已经换集，
    // 详情页若还停在播完的那一集，标题/继续观看/同文件夹列表全都对不上画面。
    // 只在**同一文件夹**内跟随——用户看着 A 的详情、后台在放 B 的队列时不应被拽走。
    LaunchedEffect(detailItem?.folder) {
        val folder = detailItem?.folder ?: return@LaunchedEffect
        eventBus.subscribe<KernelEvent.PlaybackStarted>().collect { event ->
            val next = sortedItems.firstOrNull { it.id == event.mediaId && it.folder == folder }
            // 跟集只改高亮，不替换 detailItem——那是详情页的导航锚点。
            if (next != null && next.id != episodeFocus?.id) episodeFocus = next
        }
    }

    fun refresh() {
        if (refreshing || sources.isEmpty()) return
        // M14（§7.5 `Tick`）：真的开始重扫才响——空来源/正在扫时按下去没有变化，不该给反馈
        haptics.performHaptic(NpHaptic.Tick)
        scope.launch {
            sources.filter { it.isAvailable() }.forEach { it.scan() }
        }
    }

    // 清空播放历史（D2/§8.3）：破坏性操作，先确认再清
    var confirmClearHistory by remember { mutableStateOf(false) }

    // ---- 长按菜单（§3.6）状态：被长按的条目 + 卡片矩形（菜单据此锚定）----
    var contextMenu by remember { mutableStateOf<Pair<MediaItem, Rect>?>(null) }
    // 收藏键（卡片红心 / 菜单文案 / 我的页联动都读它）
    val favoriteKeys by history.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    // 收藏键（按收藏时间倒序）——「我的收藏」聚合的排序依据
    val favoriteKeysList by history.favorites.collectAsStateWithLifecycle()
    // 插件贡献的菜单项（ContextMenuItem 扩展点；分享是本插件自己的实现）
    val contextItems by remember { kernel.registry.observe<ContextMenuItem>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<ContextMenuItem>())

    // E128：长按回调**记住实例**。`LocalMediaLongPress` 是 staticCompositionLocalOf——
    // 值一变，其下整个子树全部重组；而 `{ item, rect -> contextMenu = item to rect }`
    // 字面量每次重组都是新实例。于是 LibraryScreen 每次重组（含播放中每 5s 的进度表
    // 重建、下拉刷新收尾等）都会把**整页内容**连带重组一遍——本页动辄上百张卡片。
    // 记住后值恒定，这条"整子树失效"从此不再触发（positions 的变化仍会，见 A5 活账）。
    val longPressHandler: (MediaItem, Rect) -> Unit = remember {
        { item, rect -> contextMenu = item to rect }
    }
    CompositionLocalProvider(
        LocalPlaybackProgress provides positions,
        LocalPlaybackLastAt provides lastAt,
        LocalMediaLongPress provides longPressHandler,
    ) {

    val title: String
    val subtitle: String
    when {
        detailOpen -> {
            val shown = episodeFocus?.takeIf { it.folder == detailItem!!.folder } ?: detailItem!!
            // 顶栏标题直接用视频名（卡片同款展示名），不再写死「视频详情」
            title = shown.cardTitle
            subtitle = shown.folder?.takeIf { it.isNotBlank() } ?: "本地视频"
        }
        aggregate != null -> {
            val meta = aggregateMeta(aggregate!!, sortMode)
            title = meta.first
            subtitle = meta.second
        }
        viewMode == LibraryView.FOLDERS && selectedFolder != null -> {
            // E134（用户实报"两个返回键"+"统计框占位过大"）：文件夹名与计数**都收进顶栏**，
            // 内容区的 listhead（「‹ 文件夹 层名 N 项」）与 hero（大统计卡）同轮删除——
            // 单层结构：返回箭头 + 层名（含相对路径）+ 一行计数。
            val node = currentFolder
            title = node?.name ?: "文件夹"
            subtitle = node?.let {
                // 一层目录的 path 与 name 相同，别念两遍
                if (it.path == it.name) {
                    "${it.totalCount} 个媒体文件"
                } else {
                    "${it.totalCount} 个媒体文件 · ${it.path}"
                }
            } ?: "浏览目录内容"
        }
        viewMode == LibraryView.FOLDERS -> {
            title = "文件夹"
            subtitle = "浏览本地目录与挂载"
        }
        else -> {
            title = "媒体库"
            subtitle = "${videos.size} 个视频 · ${folderRoot.children.size} 个文件夹 · 全部本地"
        }
    }

    // D13（设计稿 listFoot）：含多集条目时列表尾部的「选集」入口。
    // sheet 挂在页面根层而不是列表项里——NpSheet 是整屏浮层，放进 Lazy 项
    // 会被裁剪成那一条的高度，遮罩也盖不住顶栏。
    var episodeSheet by remember { mutableStateOf<List<MediaItem>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        NpPageScaffold(
            maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
            topBar = {
                NpAppTopBar(
                    title = title,
                    subtitle = subtitle,
                    compact = compact,
                    titleStyle = when {
                        // 平板文件夹名用 22sp 会顶满顶栏（用户实报「标题文字过大」）
                        viewMode == LibraryView.FOLDERS && !compact -> NpTypography.titleMedium
                        viewMode == LibraryView.FOLDERS || detailOpen -> NpTypography.titleLarge
                        else -> null
                    },
                    titleKey = when {
                        detailOpen -> "detail:${detailItem!!.folder ?: detailItem!!.id}"
                        viewMode == LibraryView.FOLDERS -> "folder:${selectedFolder ?: ""}"
                        aggregate != null -> "agg:$aggregate"
                        else -> null
                    },
                    onBack = when {
                        detailOpen -> ({
                            detailItem = null
                            episodeFocus = null
                        })
                        aggregate != null -> ({ aggregate = null })
                        viewMode == LibraryView.FOLDERS && selectedFolder != null -> ({
                            selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
                        })
                        else -> null
                    },
                    trailing = {
                        // 详情页是聚焦页：顶栏只留返回，不再挂搜索入口
                        if (!detailOpen) {
                            // §8.3：播放历史聚合里给一个「清空」（确认后清 Room 历史）
                            if (aggregate == "history") {
                                NpIconButton(
                                    icon = NpIcon.Trash,
                                    contentDescription = "清空播放历史",
                                    onClick = { confirmClearHistory = true },
                                    size = if (compact) 40.dp else 44.dp,
                                )
                            }
                            // 设计稿 topbar 搜索钮常驻（空库也应能进搜索页看引导）
                            if (viewMode == LibraryView.FOLDERS && selectedFolder == null && aggregate == null) {
                                NpIconButton(
                                    icon = NpIcon.Folder,
                                    contentDescription = "管理扫描目录",
                                    onClick = { scanFolderSheet = true },
                                    size = if (compact) 40.dp else 44.dp,
                                )
                            }
                            NpIconButton(
                                icon = NpIcon.Search,
                                contentDescription = "搜索",
                                onClick = { navigator.navigate(SEARCH_ROUTE) },
                                size = if (compact) 40.dp else 44.dp,
                            )
                        }
                        // 顶栏不再放设置齿轮：设置已经是一个**常驻标签**（紧凑档底栏第 4 项 /
                        // Rail 底部一项），再放一个就是同一个动作两个入口。
                    },
                )
            },
        ) { contentModifier ->
            val topBarInset = LocalNpTopBarInset.current
            val pullIndicatorTop = if (topBarInset == Dp.Unspecified) 8.dp else topBarInset + 8.dp
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = ::refresh,
                    // 必须把 state 交进去：不传时 Box 内部自建一份，下面 indicator 里那个
                    // `pullState` 就永远停在 0——下拉手势其实生效了，指示器却始终不出现。
                    state = pullState,
                    modifier = contentModifier.fillMaxSize(),
                    // 内容区从 y=0 起（顶栏是悬浮层），默认指示器会被顶栏盖住——
                    // 给指示器单独加顶栏高度的偏移，内容本身的内边距不动
                    indicator = {
                        androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = refreshing,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = pullIndicatorTop),
                        )
                    },
                ) {
                Column(modifier = Modifier.fillMaxSize()) {
                        // 扫描中的紧凑状态（契约 §5）：阶段文案 + 常驻取消入口，紧贴下拉刷新
                        // 指示器下方一行。不接管 isRefreshing、不重排页面结构，扫描结束自动消失。
                        if (refreshing) {
                            ScanStatusStrip(
                                progress = scanProgress,
                                onCancel = { sources.forEach { it.cancelScan() } },
                                modifier = Modifier.padding(
                                    top = if (topBarInset == Dp.Unspecified) 0.dp else topBarInset,
                                ),
                            )
                        }
                        // 页内态：把上面这串条件**只写一遍**，分支按枚举分派——条件与动效同源，
                        // 不会出现"加了分支忘了动效"（M2 的"其余 fade"与 M13 的"骨架→内容 240"都在这里兑现）。
                        // 有内容时的版式判定（聚合 / 文件夹 / 首页）。提成一份给两处用——
                        // "目录首读未落地但内容已在手"那一臂也要走它，复制条件就会漂。
                        val contentStage: LibraryStage = when {
                            aggregate != null -> LibraryStage.Aggregate(aggregate!!)
                            viewMode == LibraryView.FOLDERS -> LibraryStage.Folder
                            else -> LibraryStage.Home
                        }
                        val folders = folderUris
                        val stage = when {
                            libraryPreferences == null -> LibraryStage.Loading
                            detailOpen -> LibraryStage.Detail(detailItem!!)
                            sources.isEmpty() -> LibraryStage.NoSource
                            missingPermission -> LibraryStage.NoPermission
                            // 目录还没读到（冷启的头几帧）：内容已在手就直接出内容，否则给
                            // 骨架。"未配置"是给用户看的**结论**，不许拿来填加载中间态。
                            folders == null -> if (items.isNotEmpty()) contentStage else LibraryStage.Loading
                            folders.isEmpty() -> LibraryStage.NoFolder
                            !mediaLoaded && items.isEmpty() -> LibraryStage.Loading
                            items.isEmpty() -> LibraryStage.Empty
                            else -> contentStage
                        }
                        // REV-13：部分授权（只给视频或只给音频）时媒体库仍可用，但要明说缺哪一类，
                        // 并给一个补授权入口。整页空态/详情页已有自己的文案，不叠这条横幅。
                        val showPartialPermission = partialPermission != null &&
                            stage != LibraryStage.Loading &&
                            stage != LibraryStage.NoSource &&
                            stage != LibraryStage.NoPermission &&
                            stage !is LibraryStage.Detail
                        if (showPartialPermission) {
                            PartialPermissionBanner(
                                text = partialPermission!!,
                                onGrant = { permissionLauncher.launch(permissionStrings.toTypedArray()) },
                                compact = compact,
                                topInset = topBarInset,
                            )
                        }
                        // 横幅自己消费了顶栏避让：把子树里的顶栏 inset 下调为 0，否则
                        // Home/Folder/Aggregate 列表的 contentPadding 会再避让一次，
                        // 横幅与首行之间空出一整条顶栏高度。
                        // 扫描状态条同理：它自己也避让了一次顶栏（padding top = topBarInset），
                        // 若不再把子树 inset 归零，状态条与首行之间会空出第二条顶栏高度。
                        val contentTopInset = if (showPartialPermission || refreshing) 0.dp else topBarInset
                        val stageFadeMs = npDuration(Motion.NavFade)
                        val stageExitMs = npDuration(Motion.NavExit)
                        val stageShiftPx = with(LocalDensity.current) { 8.dp.roundToPx() }
                        val stageShiftAllowed = npDisplacementAllowed()
                        CompositionLocalProvider(LocalNpTopBarInset provides contentTopInset) {
                        AnimatedContent(
                            targetState = stage,
                            contentKey = { if (it is LibraryStage.Detail) "detail" else it },
                            // 层级前进/后退带 8dp 方向位移（"读得出方向"的最小量），同级切换只交叉淡入；
                            // 位移在轻量档 / Reduce Motion 下并掉（§9.6）。
                            // **转屏交接期间整块免掉**：交还右栏时详情页是"落位"而不是"下钻"，
                            // 首页与详情页叠着交叉淡入，撞上系统转屏动画就是实报的闪屏
                            // （真机录屏逐帧可见两层同时存在，见《播放体验与动效审查》E135）。
                            transitionSpec = {
                                if (NpFormHandoff.isSilent) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    val forward = targetState.rank > initialState.rank
                                    val shift = if (stageShiftAllowed && targetState.rank != initialState.rank) {
                                        if (forward) stageShiftPx else -stageShiftPx
                                    } else { 0 }
                                    (fadeIn(tween(stageFadeMs, easing = NpEasing.Silk)) +
                                        slideInHorizontally(tween(stageFadeMs, easing = NpEasing.Silk)) { shift }) togetherWith
                                        fadeOut(tween(stageExitMs, easing = NpEasing.SilkExit))
                                }
                            },
                            label = "libraryStage",
                        ) { stageValue ->
                            when (stageValue) {
                            // 详情页（平板横屏）：整块内容区替换为视频详情，Rail 与选中态不变。
                            // 同文件夹列表与播放器队列用同一个比较器，顺序与"下一集"一致。
                        is LibraryStage.Detail -> {
                                val anchor = stageValue.item
                                val detail = episodeFocus?.takeIf { it.folder == anchor.folder } ?: anchor
                                val siblings = remember(detail.id, detail.folder, sortedItems) {
                                    val folder = detail.folder
                                    if (folder.isNullOrBlank()) {
                                        emptyList()
                                    } else {
                                        // 与游玩器队列同一比较器（原注释承诺的"顺序一致"）
                                        sortedItems
                                            .filter { it.kind == MediaKind.VIDEO && it.folder == folder }
                                            .sortedWith(MediaNaturalOrder)
                                    }
                                }
                                VideoDetailScreen(
                                    item = detail,
                                    siblings = siblings,
                                    // 与建表同键（审查批次 A3）
                                    resumeMs = positions[HistoryEntry.keyOf(detail)] ?: 0L,
                                    navigator = navigator,
                                    inlinePlayer = inlinePlayer,
                                    // ⛶ = 明确的全屏意图：独占播放页只有沉浸一种（假全屏已删除）
                                    onFullscreen = { openPlayer(navigator, it, immersive = true) },
                                    onAudioOnly = { openPlayer(navigator, it, audioOnly = true) },
                                    onSelect = { next ->
                                        // 切集是播放动作，不是打开另一页。锚点 detailItem 保持不动，
                                        // 否则整页 AnimatedContent 会把列表文字淡出再淡入（重影）。
                                        episodeFocus = next
                                    },
                                    favorited = HistoryEntry.keyOf(detail) in favoriteKeys,
                                    onToggleFavorite = {
                                        scope.launch {
                                            // R2-1/REV-06：写失败（null）不再静默——详情页也给一次提示。
                                            if (history.toggleFavorite(detail) == null) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "收藏保存失败，请重试",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                            }

                        LibraryStage.NoSource -> EmptyStage {
                                NpEmptyState(
                                    title = "还没有媒体",
                                    message = "安装媒体来源插件后，视频与音乐会出现在这里。",
                                )
                            }

                        LibraryStage.NoPermission -> EmptyStage {
                                NpEmptyState(
                                    title = "需要媒体权限",
                                    message = "None Player 需要访问设备上的视频与音频，才能在这里展示和播放。",
                                    actionLabel = "授予媒体权限",
                                    onAction = { permissionLauncher.launch(permissionStrings.toTypedArray()) },
                                )
                            }

                        LibraryStage.NoFolder -> EmptyStage {
                                NpEmptyState(
                                    title = "选择要观看的文件夹",
                                    message = "选择一个媒体文件夹，None Player 只扫描你指定的位置（可在设置中添加多个）。",
                                    actionLabel = "选择媒体文件夹",
                                    onAction = { folderPicker.launch(null) },
                                )
                            }

                            // 数据未就绪时**不渲染列表**，而是给它一块骨架。
                            //
                            // 这不只是观感问题：媒体数据到达前，首页唯一能渲染的区块是"全部音频"
                            // （其余区块都要求列表非空才会出现）。LazyColumn 按 key 锚定当前可见项，
                            // 于是数据到达、前面的区块被插入后，它会**保住"全部音频"的屏幕位置**，
                            // 把新插入的"最近添加 / 文件夹 / 全部视频"顶到上方——用户冷启动看到的是
                            // 一个已经滚到底部的页面。先给骨架再换内容，锚点与内容一起重置。
                        LibraryStage.Loading -> LoadingPlaceholder(compact = compact)

                            // E78：媒体已加载完但确实没有内容 → 空库。
                        LibraryStage.Empty -> EmptyStage {
                                NpEmptyState(
                                    title = "媒体库是空的",
                                    message = "扫描所选文件夹中的媒体，建立你的媒体库。",
                                    actionLabel = if (requestedScan) null else "开始扫描",
                                    onAction = if (requestedScan) null else {
                                        { requestedScan = true; scanAll() }
                                    },
                                )
                            }

                        is LibraryStage.Aggregate -> AggregateList(
                                mode = stageValue.mode,
                                all = sortedItems,
                                positions = positions,
                                lastAt = lastAt,
                                favoriteKeys = favoriteKeysList,
                                sortMode = sortMode,
                                sortAscending = sortAscending,
                                gridColumns = gridColumns,
                                onGridColumns = { scope.launch { settings.setGridColumns(it) } },
                                viewStyle = listViewStyle,
                                onViewStyle = onViewStyle,
                                filter = filter,
                                onFilter = { filter = it },
                                compact = compact,
                                onSort = { scope.launch { settings.setSortMode(it) } },
                                onToggleAscending = { scope.launch { settings.setSortAscending(it) } },
                                onOpen = openDetailOrPlayer,
                                onOpenEpisodes = { episodeSheet = it },
                                sources = sources,
                            )

                        LibraryStage.Folder -> FolderBrowser(
                                root = folderRoot,
                                selected = currentFolder,
                                positions = positions,
                                gridColumns = gridColumns,
                                onGridColumns = { scope.launch { settings.setGridColumns(it) } },
                                sortMode = sortMode,
                                sortAscending = sortAscending,
                                viewStyle = listViewStyle,
                                onViewStyle = onViewStyle,
                                filter = filter,
                                onFilter = { filter = it },
                                compact = compact,
                                onSort = { scope.launch { settings.setSortMode(it) } },
                                onToggleAscending = { scope.launch { settings.setSortAscending(it) } },
                                onOpenFolder = { selectedFolder = it.path },
                                onOpenVideo = openDetailOrPlayer,
                                onOpenEpisodes = { episodeSheet = it },
                                sources = sources,
                            )

                        LibraryStage.Home -> HomeView(
                                videos = videos,
                                audios = audios,
                                recent = recentItems,
                                folders = folderRoot.children,
                                compact = compact,
                                staggerWindow = staggerWindow,
                                onOpen = openDetailOrPlayer,
                                onMore = { aggregate = it },
                                onOpenFolder = {
                                    // 首页的"文件夹"区块点进去 = 切到**文件夹 Tab**，
                                    // 而不是在本页内换视图。
                                    //
                                    // 必须走 Tab 切换通道：`folders` 注册在**内层** Tab NavHost，
                                    // 而 `TabProvider.register` 拿到的是**外层** Navigator。
                                    // 用 `navigator.navigate("folders")` 会抛
                                    // `IllegalArgumentException: Navigation destination that
                                    // matches route folders cannot be found` 直接把 App 打崩。
                                    FolderNav.request(it.path)
                                    switchTab("folders")
                                },
                                onBrowseFolders = {
                                    FolderNav.request("")
                                    switchTab("folders")
                                },
                            )
                        }
                            }
                        }
                        }
                }
                }
            }
        }

        // 清空历史确认（破坏性操作不静默执行）
        if (confirmClearHistory) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { androidx.compose.material3.Text("清空播放历史？") },
                text = { androidx.compose.material3.Text("将删除全部播放记录与断点，媒体文件本身不受影响。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            // §5.5 危险变体（`Warning`）：在**确认落下**的那一帧响，
                            // 不是打开对话框时——对话框出现是询问，不是后果。
                            haptics.performHaptic(NpHaptic.Warning)
                            confirmClearHistory = false
                            scope.launch {
                                history.clearHistory()
                                android.widget.Toast.makeText(context, "已清空播放历史", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { androidx.compose.material3.Text("清空") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmClearHistory = false }) {
                        androidx.compose.material3.Text("取消")
                    }
                },
            )
        }

        // 长按菜单（§3.6）：锚定卡片、点外部即收；收藏/分享/详情都在这里收口
        contextMenu?.let { (menuItem, rect) ->
            MediaContextMenu(
                item = menuItem,
                anchor = rect,
                isFavorite = HistoryEntry.keyOf(menuItem) in favoriteKeys,
                extensionItems = contextItems.filter { it.isVisible(menuItem) },
                onDismiss = { contextMenu = null },
                onPlay = {
                    contextMenu = null
                    openPlayer(navigator, menuItem)
                },
                onPlayFromStart = {
                    contextMenu = null
                    openPlayer(navigator, menuItem, fromStart = true)
                },
                onToggleFavorite = {
                    contextMenu = null
                    scope.launch {
                        // R2-1/REV-06：写失败（null）显式提示，不再把失败当"已取消收藏"。
                        val message = when (history.toggleFavorite(menuItem)) {
                            true -> "已加入收藏"
                            false -> "已取消收藏"
                            null -> "收藏保存失败，请重试"
                        }
                        android.widget.Toast.makeText(
                            context,
                            message,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onDetail = if (windowInfo.isDesktopLike) {
                    {
                        contextMenu = null
                        // 详情是页内状态（类 PC）：查看条目而离开当前页的动作交给返回键
                        detailItem = menuItem
                    }
                } else {
                    null
                },
                // 「删除」先走自己的确认框，再交给系统授权（Android 11+ 删别人的文件躲不开）。
                onDelete = {
                    contextMenu = null
                    pendingDelete = menuItem
                },
            )
        }

        pendingDelete?.let { item ->
            DeleteConfirmDialog(
                item = item,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    haptics.performHaptic(NpHaptic.Warning)
                    requestDelete(item)
                },
            )
        }

        // 扫描目录管理面板（统一入口：文件夹 Tab 根层入口行 / 首页区块「管理」）
        if (scanFolderSheet) {
            ScanFolderSheet(
                folders = folderUris ?: emptyList(),
                scanProgress = scanProgress,
                onDismiss = { scanFolderSheet = false },
                onPickFolder = { folderPicker.launch(null) },
                onRemoveFolder = removeScanFolder,
                onRescan = ::refresh,
                onCancelScan = { sources.forEach { it.cancelScan() } },
            )
        }

        episodeSheet?.let { episodes ->
            val sheetInk = LocalNpInk.current
            NpSheet(
                onDismiss = { episodeSheet = null },
                title = "选集 · 共 ${episodes.size} 集",
            ) {
                episodes.forEachIndexed { index, ep ->
                    NpOptRow(
                        text = ep.title.ifBlank { ep.id },
                        onClick = {
                            episodeSheet = null
                            openPlayer(navigator, ep)
                        },
                        leading = {
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                style = NpMonoSmall,
                                color = sheetInk.muted,
                            )
                        },
                        trailing = {
                            if (ep.durationMs > 0) {
                                Text(
                                    text = formatTimestamp(ep.durationMs),
                                    style = NpMonoSmall,
                                    color = sheetInk.muted,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 页面级空态的落位：在**可见内容区**垂直居中。
 *
 * 内容层是全高、且延伸到顶栏之下的（顶栏是覆盖式悬浮），若按顶对齐，空态会紧贴
 * 顶栏底边——平板上尤其明显：下半屏整块空着，内容却挤在顶栏下面，像是被顶栏压住。
 * 居中时先扣掉顶栏与底部条的高度，让居中点是"看得见的那块区域"的中点，
 * 而不是整块（含被覆盖部分）的中点。
 *
 * 只用于**整页空态**（媒体库根页的四种空库情形）；列表/网格内联的空态
 * （如某文件夹为空、某区块为空）仍按内容流排布，不进这里。
 */
@Composable
private fun EmptyStage(content: @Composable () -> Unit) {
    val topInset = LocalNpTopBarInset.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = if (topInset == Dp.Unspecified) 0.dp else topInset,
                bottom = LocalNpBottomInset.current,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 「部分媒体权限」横幅（REV-13）：只拿到视频或音频中的一类时，媒体库仍可用，
 * 但要让用户知道缺的是哪一类，并给一个直接的补授权入口。
 *
 * 横幅自己消费顶栏避让（[topInset]），调用点因此把子树内的 [LocalNpTopBarInset]
 * 下调为 0——否则 Home/Folder/Aggregate 列表的 contentPadding 会再避让一次，
 * 横幅与首行之间空出一整条顶栏高度。
 */
@Composable
private fun PartialPermissionBanner(
    text: String,
    onGrant: () -> Unit,
    compact: Boolean,
    topInset: Dp,
) {
    val ink = LocalNpInk.current
    val gutter = if (compact) 14.dp else 20.dp
    val top = if (topInset == Dp.Unspecified) 8.dp else topInset + 8.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top, start = gutter, end = gutter, bottom = 6.dp)
            .clip(RoundedCornerShape(NpRadius.card))
            .background(ink.surface)
            .border(1.dp, ink.border, RoundedCornerShape(NpRadius.card))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text,
            style = NpTypography.bodySmall,
            color = ink.muted,
            modifier = Modifier.weight(1f),
        )
        NpPrimaryButton(
            text = "补授权",
            onClick = onGrant,
            primary = false,
        )
    }
}

/**
 * 首页的加载骨架：与真实区块同形状的灰条（设计稿 `.sk` 的 shimmer）。
 *
 * 它的职责不只是"看起来在加载"——见调用点注释，它同时把 LazyColumn 的
 * key 锚点从"空列表的尾部"重置掉，否则数据到达后会保住旧锚点、
 * 把首屏顶成滚到底部的样子。
 */
@Composable
private fun LoadingPlaceholder(compact: Boolean) {
    val gutter = if (compact) 14.dp else 20.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(2) { block ->
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 20.dp)) {
                NpSkeleton(
                    modifier = Modifier
                        .padding(horizontal = gutter)
                        .width(120.dp)
                        .height(19.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        NpSkeleton(
                            modifier = Modifier
                                .width(NpDimens.railCardWidth)
                                .height(NpDimens.railCardWidth * 9 / 16),
                        )
                    }
                }
                if (block == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .height(1.dp)
                            .background(LocalNpInk.current.border),
                    )
                }
            }
        }
    }
}

/**
 * 首页：横向走马灯区块（设计稿 `homeView()`）。
 *
 * 每个区块都是"标题 + 全部 › + 一行横滑卡片"——这是设计稿的核心版式决策：
 * 首屏用横滑 rails 展示**多个维度**（继续观看 / 最近添加 / 文件夹 / 全部视频 / 全部音频），
 * 而不是一个纵向大网格。纵向网格只在点进"全部 ›"之后出现。
 *
 * 唯一的例外是「文件夹」区块：设计稿这里是**换行网格**（`grid v`，
 * minmax(min(200px,60vw),1fr)），一屏铺 4 张——文件夹是"去某个地方"的入口，
 * 横滑会让第 5 个之后的位置不可见；网格把范围一次摊开。
 * 紧凑档是适应性还原：手机窄屏上按设计稿的 200px 最小宽只会排 1 列
 * （360dp 减 gutter 后不足两格），信息暴露反而更差，所以保持横滑。
 */
@Composable
private fun HomeView(
    videos: List<MediaItem>,
    audios: List<MediaItem>,
    recent: List<MediaItem>,
    folders: List<FolderNode>,
    compact: Boolean,
    staggerWindow: Long,
    onOpen: (MediaItem) -> Unit,
    onMore: (String) -> Unit,
    onOpenFolder: (FolderNode) -> Unit,
    onBrowseFolders: () -> Unit,
) {
    val gutter = if (compact) 14.dp else 20.dp
    val progressMap = LocalPlaybackProgress.current
    // 记忆化：此前这行 filter 直接写在组合体里，进度表每变化一次（播放页返回、
    // 进度落盘）都要全量重扫一遍 videos；而它是"继续观看"轨道唯一的数据来源，
    // 与 videos/progressMap 之外的重组无关。
    val unfinished = remember(videos, progressMap) {
        videos.filter { fractionOf(it, progressMap) in 0.01f..<HistoryRepository.WATCHED_DONE_RATIO }
    }

    // 区块存在性先算出来：`.block:first-child` 无上边框（设计稿 106），
    // 首个区块紧贴顶栏线时再画一条就是双线。
    val hasContinue = unfinished.isNotEmpty()
    val hasRecent = recent.isNotEmpty()
    val hasFolders = folders.isNotEmpty()
    val hasVideos = videos.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = LocalNpTopBarInset.current, bottom = LocalNpBottomInset.current + 24.dp),
    ) {
        if (hasContinue) {
            item(key = "continue") {
                HomeBlock(
                    title = "继续观看",
                    gutter = gutter,
                    showTopDivider = false,
                    onMore = { onMore("continue") },
                ) {
                    MediaRail(
                        items = unfinished,
                        compact = compact,
                        staggerWindow = staggerWindow,
                        onOpen = onOpen,
                    )
                }
            }
        }
        if (hasRecent) {
            item(key = "recent") {
                HomeBlock(
                    title = "最近添加",
                    gutter = gutter,
                    showTopDivider = hasContinue,
                    // 设计稿 `data-more="recent"` → recent 聚合（"按加入时间排序"）；
                    // 此前错映射到 videos，点"全部"标题就变成了「全部视频」
                    onMore = { onMore("recent") },
                ) {
                    MediaRail(
                        items = recent,
                        compact = compact,
                        staggerWindow = staggerWindow,
                        onOpen = onOpen,
                    )
                }
            }
        }
        if (hasFolders) {
            item(key = "folders") {
                HomeBlock(
                    title = "文件夹",
                    actionLabel = "查看全部",
                    gutter = gutter,
                    showTopDivider = hasContinue || hasRecent,
                    // 「管理」= 去文件夹 Tab 的**根层**（管理全部目录），
                    // 而不是"打开第一个文件夹"——那是上一版的实现，
                    // 点"管理"结果进了某个具体目录，用户找不到"管理"这件事。
                    onMore = onBrowseFolders,
                ) {
                    FolderRailOrGrid(
                        folders = folders,
                        compact = compact,
                        gutter = gutter,
                        onOpenFolder = onOpenFolder,
                    )
                }
            }
        }
        if (hasVideos) {
            item(key = "allvideos") {
                HomeBlock(
                    title = "全部视频",
                    actionLabel = "查看全部",
                    gutter = gutter,
                    showTopDivider = hasContinue || hasRecent || hasFolders,
                    onMore = { onMore("videos") },
                ) {
                    MediaRail(
                        items = videos.take(8),
                        compact = compact,
                        staggerWindow = staggerWindow,
                        onOpen = onOpen,
                    )
                }
            }
        }
        item(key = "allaudios") {
            HomeBlock(
                title = "全部音频",
                actionLabel = "查看全部",
                gutter = gutter,
                showTopDivider = hasContinue || hasRecent || hasFolders || hasVideos,
                onMore = { onMore("audios") },
            ) {
                if (audios.isEmpty()) {
                    // 设计稿 980：空态是虚线环 + 标题 + 描述（占满 grid 行）
                    NpEmptyState(
                        title = "没有音频",
                        message = "纯音频文件会显示在这里。",
                    )
                } else {
                    MediaRail(
                        items = audios.take(8),
                        compact = compact,
                        staggerWindow = staggerWindow,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/**
 * 「文件夹」区块的两种形态：紧凑档横滑，其余档换行网格（设计稿 `grid v` min 200px）。
 *
 * 网格取 `folders.take(4)` 与设计稿 `folders.slice(0,4)` 对齐——
 * 这是摘要区块，全量在「管理 ›」里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderRailOrGrid(
    folders: List<FolderNode>,
    compact: Boolean,
    gutter: Dp,
    onOpenFolder: (FolderNode) -> Unit,
) {
    @Composable
    fun FolderCardCell(node: FolderNode, modifier: Modifier = Modifier) {
        NpFolderCard(
            name = node.name,
            countLabel = "${node.totalCount} 项",
            onClick = { onOpenFolder(node) },
            modifier = modifier,
            cover = {
                FolderCover(node = node, hue = NpMediaColor.folderHue(node.name))
            },
        )
    }
    if (compact) {
        // §20.3 余量②：紧凑档横排也接首屏错峰（窗口在本函数取一次 = 整个文件夹区块一次）
        val staggerWindow = npStaggerWindow()
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(folders.take(8), key = { _, node -> node.path }) { index, node ->
                FolderCardCell(
                    node = node,
                    modifier = Modifier
                        .width(200.dp)
                        .npStaggeredEntry(index, staggerWindow),
                )
            }
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        // 设计稿 `minmax(min(200px,60vw),1fr)` 的 Compose 等价：按可用宽度取整数列。
        // 列数上限 4（设计稿 `folders.slice(0,4)`），每格宽度**显式算出来**而不是
        // 用 weight(1f) 平分——只有 1–2 个文件夹时，平分会让唯一一张卡片
        // 拉满整行（封面被撑成巨幅），与"卡片网格"的观感完全不符。
        val columns = (maxWidth / 200.dp).toInt().coerceIn(1, 4)
        val cellWidth = (maxWidth - 12.dp * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = columns,
        ) {
            folders.take(4).forEachIndexed { index, node ->
                // §20.3 余量③：非 Lazy 的一小组不传 window（按首屏错峰，见 npStaggeredEntry）
                Box(
                    modifier = Modifier
                        .width(cellWidth)
                        .npStaggeredEntry(index),
                ) { FolderCardCell(node) }
            }
        }
    }
}

/**
 * `.block`：一个首页区块。区块之间用 1px 发丝线分隔（设计稿 `.block+.block`），
 * 而不是靠更大的间距——分隔线让"这是两组不同维度"变得明确。
 *
 * [showTopDivider] 为 false 时是首个区块：设计稿 `.block:first-child` 无上边框
 * （顶栏已有 border-bottom，双线会糊成一条粗线）。
 */
@Composable
private fun HomeBlock(
    title: String,
    gutter: Dp,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "全部",
    showTopDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ink = LocalNpInk.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (showTopDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ink.border),
            )
        }
        Column(modifier = Modifier.padding(top = 20.dp, bottom = 20.dp)) {
            Box(modifier = Modifier.padding(horizontal = gutter)) {
                NpSectionHead(
                    title = title,
                    actionLabel = actionLabel,
                    onAction = onMore,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

/** 横滑媒体 rail：固定 184dp 一格（设计稿 `.rail-scroll .cw`）。 */
@Composable
private fun MediaRail(
    items: List<MediaItem>,
    compact: Boolean,
    /**
     * 首屏错峰窗口——由**页面**取一次后传进来（见 [npStaggerWindow]）。
     *
     * 这里曾经自己 `npStaggerWindow()` 取一次：那等于把每个 rail 都当首屏。
     * 首页是纵向 LazyColumn + 每个区块一条 rail，滚动时新区块进入视野会重新组合，
     * 于是每露出一段就有 8 张卡片同时起"延迟最多 372ms + 220ms"的入场动画——
     * 实测这正是主页纵向滚动卡顿的最大单一来源（卡顿帧里 Compose 动画相位占 ~49%）。
     */
    staggerWindow: Long,
    onOpen: (MediaItem) -> Unit,
) {
    val gutter = if (compact) 14.dp else 20.dp
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> "${item.sourceId}:${item.id}" }) { index, item ->
            NpCarouselItem(modifier = Modifier.npStaggeredEntry(index, staggerWindow)) {
                MediaCardBody(item = item, onOpen = onOpen)
            }
        }
    }
}

/**
 * 卡片主体（封面 + 标题 + 元信息）——首页 rail 与网格视图共用。
 *
 * 元信息行按设计稿分成两种口径：
 *   rail 卡（`medCard`）：`已看 x% · 时长 · 分辨率`；
 *   grid 卡（`gridCard`）：`foldertag 胶囊 · 大小 · 日期 · 类型`。
 * 同一个条目在"横向、一屏多格"（要判断"看没看过/多长"）与"纵向、大格"
 * （要判断"在哪/多大/什么时候加的"）两种密度下该给的信息本来就不同，
 * 所以元信息由 [showFolderTag] 切换形态，而不是各写一份卡片。
 */
@Composable
internal fun MediaCardBody(
    item: MediaItem,
    onOpen: (MediaItem) -> Unit,
    showFolderTag: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val progress = item.progressFraction()
    // 长按菜单的通道（§3.6）：页面提供、卡片消费，避免三层回调透传
    val longPress = LocalMediaLongPress.current
    // 菜单锚点：布局期写入、长按现取，**不进快照系统**——用 mutableStateOf 接住的话，
    // 滚动时每张可见卡片的矩形每帧都变，写一次状态就是一次全卡片重组（§9 滚动预算）。
    val anchor = rememberNpAnchorHolder()
    // 元信息行是纯展示派生：内容只取决于 item / 形态 / 进度，三者不变时不重建字符串与日期。
    // `formatRelativeDate` 每次都要取时区并构造 LocalDate，逐帧重建在 rail 上是纯浪费。
    val meta: List<String> = remember(item, showFolderTag, progress) {
        if (showFolderTag) {
            buildList {
                // E42：文件名解析出的「第 N 集」排在元信息行首（验收：标题 · 第 1 集）
                item.episodeLabel?.let { add(it) }
                if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
                formatRelativeDate(item.addedAtMs).takeIf { it.isNotEmpty() }?.let { add(it) }
                add(if (isAudio) "音频" else "视频")
            }
        } else {
            buildList {
                item.episodeLabel?.let { add(it) }
                if (progress > 0.01f && progress < 0.99f) add("已看 ${(progress * 100).toInt()}%")
                if (item.durationMs > 0) add(formatTimestamp(item.durationMs))
                // 设计稿 `v.res`：1080P / 4K / 无损；拿不到就省略（不留「视频」占位，
                // 类型信息 kindtag 已经表达过一次了）
                item.resolutionLabel.takeIf { it.isNotEmpty() }?.let { add(it) }
                // E42：文件名解析出的编码（HEVC/AVC…），没有就不占位
                item.videoCodec?.let { add(it) }
            }
        }
    }
    // 设计稿 `v.neu`：48 小时内加入 → 右上「新增」角标。取一次时间即可，无需逐帧读钟。
    val isNew = remember(item) { System.currentTimeMillis() - item.addedAtMs < NEW_BADGE_WINDOW_MS }
    // M2 共享封面（§6）：起飞条件 = 档位允许（§9.6 `sharedElement`）且非 Reduce Motion。
    // 机位就是长按菜单用的那个锚点（窗口坐标，滚动期零重组）；没量到就不起飞。
    val sharedCoverOn = npQuality().sharedElement && !LocalNpReduceMotion.current
    NpMediaCard(
        // E42：富化出短标题就用它（「标题」而不是「[字幕组] 标题 - 01 [1080p]」）
        title = item.cardTitle,
        meta = meta,
        onClick = {
            if (sharedCoverOn) NpCoverFlight.launch(item.id, anchor.current())
            onOpen(item)
        },
        onLongClick = longPress?.let { cb -> { cb(item, anchor.current()) } },
        modifier = modifier.npAnchor(anchor),
        folderTag = if (showFolderTag) item.folder else null,
        folderTagIsAudio = isAudio,
        cover = {
            NpCoverBox(
                placeholderHue = hue,
                progress = progress.takeIf { it > 0.01f },
                isAudio = isAudio,
                showPlayGlyph = true,
                isNew = isNew,
                durationText = formatTimestamp(item.durationMs)
                    .takeIf { item.durationMs > 0 },
                // 设计稿 `.thumb::after`：按文件夹色相的 1px 内环
                tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
            ) {
                MediaCover(item = item, hue = hue)
            }
        },
    )
}

/**
 * 列表视图行（设计稿 `.listrow`）：160dp 缩略图 + 单行标题（前置类型图标）+ mono 元信息 + chevron。
 *
 * 与网格卡共用同一份数据与封面组件，只是形态不同——
 * 设计稿四处（文件夹页 / 文件夹详情 / 聚合列表 / 搜索结果）都用这一个形状，
 * 扫描进来的普通目录与 `.` 开头的隐藏目录同样走这里（E231 同一口径）。
 * E231：封面不再叠类型角标与时长（用户实报遮挡封面）——角标挪标题前（[NpListRow] 的
 * kindIsAudio），时长并进元信息行放在文件大小之前。
 */
@Composable
internal fun MediaListRow(
    item: MediaItem,
    compact: Boolean,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val progress = item.progressFraction()
    val longPress = LocalMediaLongPress.current
    // 同 [MediaCardBody]：锚点走非可观察持有者，元信息行做记忆化——
    // 列表视图一屏十几行，逐帧重建字符串与日期的代价比 rail 上更高。
    val anchor = rememberNpAnchorHolder()
    val meta = remember(item) {
        buildList {
            item.episodeLabel?.let { add(it) }
            // E231：时长从封面右下角挪进元信息行，置于大小之前
            if (item.durationMs > 0) add(formatTimestamp(item.durationMs))
            if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
            formatRelativeDate(item.addedAtMs).takeIf { it.isNotEmpty() }?.let { add(it) }
            item.videoCodec?.let { add(it) }
        }
    }
    NpListRow(
        title = item.cardTitle,
        meta = meta,
        onClick = { onOpen(item) },
        onLongClick = longPress?.let { cb -> { cb(item, anchor.current()) } },
        modifier = modifier.npAnchor(anchor),
        compact = compact,
        folderTag = item.folder,
        folderTagIsAudio = isAudio,
        kindIsAudio = isAudio,
        cover = {
            NpCoverBox(
                placeholderHue = hue,
                progress = progress.takeIf { it > 0.01f },
                isAudio = isAudio,
                // E231：类型角标挪到标题前、时长挪进元信息行——封面不再叠任何角标，
                // 播放圆钮也照旧省略（整行本身就是点击目标）
                showKindTag = false,
                showPlayGlyph = false,
                tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
            ) {
                MediaCover(item = item, hue = hue)
            }
        },
    )
}

/** 封面位图：优先扫描时缓存的缩略图，无缓存时对视频现场抽帧；音频用色相占位。 */
@Composable
internal fun MediaCover(
    item: MediaItem,
    hue: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isAudio = item.kind == MediaKind.AUDIO
    var frameFailed by remember(item.uri) {
        mutableStateOf(CoverFrameFailureMemo.hasFailed(item.uri))
    }
    val model = remember(item.coverUri, item.uri, isAudio, frameFailed) {
        when {
            item.coverUri != null -> ImageRequest.Builder(context)
                .data(item.coverUri)
                .crossfade(180)
                .build()

            isAudio -> null
            !shouldExtractFrame(item.sourceId, item.coverUri, isAudio, frameFailed) -> null
            else -> ImageRequest.Builder(context)
                .data(item.uri)
                .videoFrameMillis(0)
                .crossfade(220)
                .build()
        }
    }
    // A25：占位铺在图片下方而不是塞进 AsyncImage 的 placeholder/error——后者只能给
    // 单色块，抽帧失败时会把设计稿的色相渐变+胶片图标盖成一块死灰。载入完成前/失败后
    // 自然露出 NpCoverPlaceholder，与“没有封面”的形态一致。
    Box(modifier = modifier.fillMaxSize()) {
        NpCoverPlaceholder(hue = hue, modifier = Modifier.fillMaxSize())
        if (model != null) {
            AsyncImage(
                model = model,
                // E128：封面是**装饰**——四张调用点（网格卡/列表行/文件夹卡/详情海报）旁边
                // 都有标题文本，卡片语义合并后（NpMediaCard 的 mergeDescendants），
                // 保留 item.title 会让 TalkBack 把标题读两遍。读屏用户不缺这一条。
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = {
                    // 只给“现场抽帧”记失败；缓存封面失败另有兜底，不把视频 uri 钉死。
                    if (item.coverUri == null && !isAudio) {
                        frameFailed = true
                        CoverFrameFailureMemo.markFailed(item.uri)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 文件夹封面：取该文件夹（含子层级）有缩略图的最新一项；全无缩略图时用色相底。 */
@Composable
private fun FolderCover(
    node: FolderNode,
    hue: Int,
    modifier: Modifier = Modifier,
) {
    val cover = node.allVideos.firstOrNull { it.coverUri != null }
    val facc = NpMediaColor.accent(hue)
    Box(modifier = modifier.fillMaxSize()) {
        if (cover != null) {
            MediaCover(item = cover, hue = hue)
        } else {
            NpCoverPlaceholder(hue = hue)
        }
        // 设计稿 `.folder .cover::after`：顶部 22% 色相薄纱（到 55% 处淡出）——
        // 把"这个文件夹是什么颜色"铺满封面顶部，而不是只在边线上出现
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to facc.copy(alpha = 0.22f),
                        0.55f to Color.Transparent,
                    ),
                ),
        )
        // 左下角"N 个视频"pill（设计稿 `.folder .pill`）：计数只数**视频**，
        // 与设计稿 `f.c`（folderCount 只累计视频）同口径
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    color = facc.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(5.dp),
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text = "${node.videoCount} 个视频",
                style = NpTypography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/**
 * 文件夹视图：`.folder-head` 头部 + 子文件夹网格 + 本级视频。
 *
 * 头部用色相渐变底（设计稿 `linear-gradient(135deg, facc 16%, surface)`），
 * 它把"我在哪个文件夹里"从标题文字升级成一个有色块的确认。
 */
@Composable
private fun FolderBrowser(
    root: FolderNode,
    selected: FolderNode?,
    positions: Map<String, Long>,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    compact: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    onOpenFolder: (FolderNode) -> Unit,
    onOpenVideo: (MediaItem) -> Unit,
    onOpenEpisodes: (List<MediaItem>) -> Unit,
    sources: List<MediaSourceProvider>,
) {
    val ink = LocalNpInk.current
    val node = selected ?: root
    val gutter = if (compact) 14.dp else 20.dp
    // 无顶层文件夹时明确说明，再展示内容，不静默回落（E31）
    val noSubfolders = node.path.isEmpty() && node.children.isEmpty()
    // 设计稿 `filterList(vList.concat(aList), state.filter)`：筛选作用于**本层内容**，
    // 子文件夹是导航不是内容，不参与筛选
    val shownVideos = remember(node.directVideos, filter, positions) {
        filterList(node.directVideos, filter, positions)
    }
    val staggerWindow = npStaggerWindow(node.path)
    val itemFade = npItemFadeSpec()
    // E203-C（契约 §8.5）：网格槽位 → 条目序号的基准。内容项之前是：无子目录提示（可选）/
    // 控件行 / 选集入口（可选）/ 子文件夹（可选，每个占一槽）。
    // 在内容项之前增删 item 必须同步改下面这个式子，清单见 CoverWindow.kt 顶部「维护提示」段。
    val coverLeaderSlots = (if (noSubfolders) 1 else 0) + 1 +
        (if (node.directVideos.size > 1 && shownVideos.isNotEmpty()) 1 else 0) +
        node.children.size
    val gridState = rememberLazyGridState()
    CoverDemandEffect(items = shownVideos, leaderSlots = coverLeaderSlots, sources = sources, state = gridState)

    LazyVerticalGrid(
        // 设计稿 compact 档媒体网格恒 2 列（`.tier-compact .grid.v` 607）：
        // Adaptive(176dp) 在 360dp 窗口 floor 出 1 列，比设计稿更稀
        columns = npGridColumnsFor(compact, gridColumns),
        state = gridState,
        contentPadding = PaddingValues(
            top = LocalNpTopBarInset.current,
            start = gutter,
            end = gutter,
            bottom = LocalNpBottomInset.current + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 扫描目录管理不放在列表第一行：点首页文件夹进来时，这一行会让整页看起来像
        // 「管理文件夹」。入口改到文件夹根层顶栏的文件夹图标。
        if (noSubfolders) {
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }, contentType = "hint") {
                Text(
                    text = "该目录下没有子文件夹，已按全部媒体展示",
                    style = NpTypography.bodySmall,
                    color = ink.muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            ControlsRow(
                filter = filter,
                onFilter = onFilter,
                filterEnabled = node.totalCount > 0,
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                gridColumns = gridColumns,
                onGridColumns = onGridColumns,
                compact = compact,
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSort = onSort,
                onToggleAscending = onToggleAscending,
            )
        }
        // E134（用户实报"选集按钮位置不好"）：本层含多集条目（同目录 > 1 条）时，
        // 选集入口放在**控件行之下、内容之上**——原来在列表尾部，一屏几十条要滚到底才看得见。
        val episodes = node.directVideos
        if (episodes.size > 1 && shownVideos.isNotEmpty()) {
            item(key = "episodes-entry", span = { GridItemSpan(maxLineSpan) }, contentType = "episodes") {
                EpisodesEntry(
                    multiCount = shownVideos.size,
                    onOpen = { onOpenEpisodes(episodes) },
                )
            }
        }
        // §20.3 余量①：子文件夹也接首屏错峰（此前只有本级视频接了）
        //
        // 审查批次 D5：**两组共用一条递增序号**。旧写法让子文件夹与视频各自从 index=0
        // 起算，于是"第 1 个文件夹"与"第 1 个视频"拿到同一个延迟、第 2 个也同延迟，
        // 首屏读起来是两组同时弹出来，而不是一条连续的错峰。
        // 视频组从 `node.children.size` 接着数，整屏才是 0,1,2,… 的单一序列。
        val videoStaggerBase = node.children.size
        gridItemsIndexed(
            node.children,
            key = { _, child -> "folder:${child.path}" },
            span = { _, _ -> GridItemSpan(if (viewStyle == "list") maxLineSpan else 1) },
            contentType = { _, _ -> if (viewStyle == "list") "folder-list" else "folder-card" },
        ) { index, child ->
            val folderModifier = Modifier
                .animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)
                .npStaggeredEntry(index, staggerWindow)
            if (viewStyle == "list") {
                NpOptRow(
                    text = child.name,
                    onClick = { onOpenFolder(child) },
                    modifier = folderModifier,
                    leading = {
                        Icon(NpIcon.Folder, contentDescription = null, tint = ink.muted)
                    },
                    trailing = {
                        Text("${child.totalCount} 项", style = NpMonoSmall, color = ink.muted)
                        Icon(NpIcon.ChevronRight, contentDescription = null, tint = ink.muted)
                    },
                )
            } else {
                NpFolderCard(
                name = child.name,
                countLabel = "${child.totalCount} 项",
                onClick = { onOpenFolder(child) },
                modifier = folderModifier,
                cover = {
                    FolderCover(
                        node = child,
                        hue = NpMediaColor.folderHue(child.name),
                    )
                },
            )
            }
        }
        if (viewStyle == "list") {
            gridItemsIndexed(
                shownVideos,
                key = { _, item -> "video:${item.sourceId}:${item.id}" },
                span = { _, _ -> GridItemSpan(maxLineSpan) },
                contentType = { _, _ -> "video-list" },
            ) { index, item ->
                MediaListRow(
                    item = item,
                    compact = compact,
                    onOpen = onOpenVideo,
                    modifier = Modifier
                        .animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)
                        .npStaggeredEntry(videoStaggerBase + index, staggerWindow),
                )
            }
        } else {
            gridItemsIndexed(
                shownVideos,
                key = { _, item -> "video:${item.sourceId}:${item.id}" },
                contentType = { _, _ -> "video-card" },
            ) { index, item ->
                MediaCardBody(
                    item = item,
                    onOpen = onOpenVideo,
                    showFolderTag = true,
                    modifier = Modifier
                        .animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)
                        .npStaggeredEntry(videoStaggerBase + index, staggerWindow),
                )
            }
        }
        if (shownVideos.isEmpty() && node.children.isEmpty() && node.totalCount == 0) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                NpEmptyState(
                    title = "此文件夹没有内容",
                    message = "换个筛选条件看看。",
                )
            }
        }
        // 设计稿 foldersView 第二段「存储位置」：只在根层显示（进到子目录后它与本层无关）。
        // 设计稿是假数据（"剩余 42.6 GB / 128 GB"），这里用 StatFs 取**真实**容量；
        // 取不到就不显示——不编造数字。
        if (node.path.isEmpty()) {
            item(key = "storage", span = { GridItemSpan(maxLineSpan) }) {
                StorageSection(modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/**
 * 「选集」入口（E134：从列表尾部移到控件行之下，并按"位置变了、体量变小"重做细行）。
 *
 * 只在**当前列表里确实有多集条目**时出现（[multiCount] 由调用方按"同文件夹条目数 > 1"
 * 计算）。旧版是设计稿 `.list-foot` 的"尾部分隔线 + 大胶囊"——真机实报"位置不好：
 * 一屏几十条时要滚到底才看得见"。移到顶部后与筛选/排序同区一眼可达，胶囊同步收成
 * 34dp 芯片高。点击把整条剧集列表交给页面根层的 sheet（选中即起播）——
 * 这里不自己开 NpSheet：整屏浮层在 Lazy 项里会被裁剪。
 */
@Composable
private fun EpisodesEntry(
    multiCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(NpDimens.chipHeight)
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(ink.accent)
                .npPressScale(onClick = onOpen, pressedScale = 0.97f)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = NpIcon.List,
                contentDescription = null,
                tint = ink.bg,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "选集",
                style = NpTypography.labelLarge,
                color = ink.bg,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$multiCount 个条目包含多集",
            style = NpTypography.bodySmall,
            color = ink.muted,
        )
    }
}

/**
 * 「存储位置」区块：sec-head 标题 + rows（内部存储：剩余/总量）。
 *
 * 设计稿这里是 `<div class="sec-head"><h2>存储位置</h2></div>`（19px h2），
 * 与页面其它区块标题同级；此前误用 `.eyebrow`（accent 等宽小字），层级低了一档。
 *
 * 只报**内部存储卷**：SAF 授权目录可能落在任意卷上，但"这个卷还剩多少"只有
 * `Environment` 的路径能可靠问到；SD 卡一类的第二卷需要 StorageManager 的
 * `storageVolumes` 且多数设备不给可写路径，宁可不列也不列一个假的。
 */
@Composable
private fun StorageSection(modifier: Modifier = Modifier) {
    val storageText = remember {
        runCatching {
            val dir = android.os.Environment.getExternalStorageDirectory() ?: return@runCatching null
            val stat = android.os.StatFs(dir.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total <= 0) null else "剩余 ${formatFileSize(free)} / ${formatFileSize(total)}"
        }.getOrNull()
    } ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        NpSectionHead(
            title = "存储位置",
            modifier = Modifier.padding(bottom = 6.dp),
        )
        NpSettingRow(
            title = "内部存储",
            subtitle = storageText,
            icon = NpIcon.Folder,
            showDivider = false,
        )
    }
}

/**
 * 聚合列表的内容区头：标题 + 右端 mono 计数。
 *
 * E134（同"两个返回键"的收口）：返回不再在这里出现——顶栏（覆盖式，任何档位常驻）
 * 已经有返回箭头，内容区再放一枚就是同一动作两个入口；文件夹页的 listhead 连同返回
 * 一并删除后，本组件只剩"标题 + N 项"这一种形态，参数随之收窄。
 */
@Composable
private fun AggregateHead(
    name: String,
    count: Int,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = name,
            style = NpTypography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).sp,
            ),
            color = ink.fg,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$count 项",
            // 设计稿 `.listhead .cnt`：13px mono（与聚合头 `.val.mono` 同一口径）
            style = NpMono,
            color = ink.muted,
            maxLines = 1,
        )
    }
}

/**
 * E134：`.folder-head` 大统计卡**已删除**（用户实报"统计框在手机和电脑上占用位置过大"）。
 *
 * 它承载的三样信息全部有更好的归属：层名与相对路径 → 顶栏标题/副题；计数 → 顶栏副题；
 * "点击条目开始播放" → 可发现性由卡片自身的点击态承担。同轮删除的还有 listhead
 * （用户实报"两个返回键"）——内容区头部就此清零，文件夹页从控件行直接进入内容。
 */

/**
 * 筛选键 → 列表（设计稿 `filterList`）。
 *
 * 「未看完 / 已看完」依赖进度接线（[positions] 由 LibraryScreen 收集后传入），
 * 「新增」按 addedAtMs 48 小时判定（设计稿 `v.neu`）。
 */
internal fun filterList(
    list: List<MediaItem>,
    filter: String,
    positions: Map<String, Long>,
): List<MediaItem> {
    val now = System.currentTimeMillis()
    return when (filter) {
        FilterKey.VIDEO.key -> list.filter { it.kind == MediaKind.VIDEO }
        FilterKey.AUDIO.key -> list.filter { it.kind == MediaKind.AUDIO }
        // 「未看完 / 已看完」共用 HistoryRepository 的「看完」线——此前 UI 硬编码 0.99
        // 而数据层写 0.95，"同一部片子两个结论"（L5 家族）
        FilterKey.UNFINISHED.key ->
            list.filter { fractionOf(it, positions) in 0.01f..<HistoryRepository.WATCHED_DONE_RATIO }
        FilterKey.DONE.key ->
            list.filter { fractionOf(it, positions) >= HistoryRepository.WATCHED_DONE_RATIO }
        FilterKey.NEW.key -> list.filter { now - it.addedAtMs < 48 * 60 * 60 * 1000L }
        else -> list
    }
}

/**
 * 控件行（§5.7）：第一行 筛选 + 升降序 + 视图钮，第二行**排序 chips 横排**。
 *
 * 排序不再是下拉：四个档位本身就值得占一排——一键切换、当前档位一眼可见。
 * 原先它是 `NpFilterSelect`（点开→列表→选），改档要两次点击，而且选中态藏在
 * 触发钮的文字里，不看那两个字就不知道现在按什么排。
 *
 * 视图模式（网格/列表）与列数收进**锚定菜单**（E129：从右侧视图钮下方长出的小窗，
 * 不再是向上弹的底部 Sheet）：列数只在紧凑档出现——`Medium/Expanded`
 * 下列数由窗口宽度自适应，给用户一个"双列/三列"开关是欺骗（§5.7）。
 *
 * 升降序用**文字**而不是图标：设计稿的 `I.sortIco` 是"上下双箭头"，正序倒序画的是
 * 同一个字形，只靠颜色区分等于让用户猜。这里给一个 34dp 的文字 pill（升序/降序），
 * 一眼可读，且与左侧 `.fsel-btn` 的圆角描边同族。
 */
@Composable
private fun ControlsRow(
    filter: String,
    onFilter: (String) -> Unit,
    filterEnabled: Boolean,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
    compact: Boolean,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
) {
    val ink = LocalNpInk.current
    var viewMenuVisible by rememberSaveable { mutableStateOf(false) }
    // E129：视图菜单的锚点——非可观察持有者，滚动期零写入（见 NpAnchorHolder 的约定）
    val viewAnchor = rememberNpAnchorHolder()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NpFilterSelect(
                label = FilterKey.labelOf(filter),
                icon = NpIcon.Film,
                options = FilterKey.entries.map { it.label },
                selected = FilterKey.labelOf(filter),
                onSelect = { label -> onFilter(FilterKey.keyOf(label)) },
                enabled = filterEnabled,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .height(NpDimens.chipHeight)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .border(
                        width = 1.dp,
                        color = if (sortAscending) ink.accentLine else ink.border,
                        shape = RoundedCornerShape(NpRadius.chip),
                    )
                    .npPressScale(onClick = { onToggleAscending(!sortAscending) }, pressedScale = 0.96f)
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
            Box(modifier = Modifier.npAnchor(viewAnchor)) {
                NpIconButton(
                    icon = if (viewStyle == "list") NpIcon.ListView else NpIcon.GridView,
                    contentDescription = "视图模式",
                    // E129：「视图」是**锚定弹层**——从这颗钮的下方长出（右对齐 + 从右上角
                    // 原点 scale 0.95→1），不再用向上弹的底部 Sheet：触发器在哪、选项就长在哪。
                    onClick = { viewMenuVisible = true },
                    selected = viewMenuVisible,
                )
            }
        }
        // 排序 chips（§5.7）：窄屏放不下四枚就横滑，不缩字号、不改标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortMode.entries.forEach { mode ->
                NpChip(
                    text = mode.label,
                    selected = mode == sortMode,
                    onClick = { if (mode != sortMode) onSort(mode) },
                )
            }
        }
    }
    if (viewMenuVisible) {
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember { ViewMenuPositionProvider(viewAnchor) },
            onDismissRequest = { viewMenuVisible = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            ViewMenu(
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                gridColumns = gridColumns,
                onGridColumns = onGridColumns,
                compact = compact,
            )
        }
    }
}

/**
 * 「视图」锚定菜单（E129，取代原来的底部 Sheet）：视图模式 segmented + 紧凑档的列数 chips。
 *
 * 材质与入场复用 [NpMenuSurface]（L3：白底画在阴影层内、从右上角长出的 scale 0.95→1 + fade），
 * 与筛选菜单 / 设置值行菜单同一套语言——三类"选项"长得像同一件东西，用户才认得出。
 */
@Composable
private fun ViewMenu(
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
    compact: Boolean,
) {
    val ink = LocalNpInk.current
    NpMenuSurface(
        alignEnd = true,
        modifier = Modifier.width(236.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "视图", style = NpTypography.titleSmall, color = ink.fg)
            NpSegmented(
                options = listOf("网格", "列表"),
                selected = if (viewStyle == "list") "列表" else "网格",
                onSelect = { onViewStyle(if (it == "列表") "list" else "grid") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (compact) {
                Text(text = "列数", style = NpMonoSmall, color = ink.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { count ->
                        NpChip(
                            text = "$count 列",
                            selected = count == gridColumns,
                            onClick = { if (count != gridColumns) onGridColumns(count) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「视图」菜单的落点：锚在触发钮下方 8dp、右对齐（同设置值行与 `.fsel-menu` 的规则）。
 * 锚点矩形在 `calculatePosition` 那一刻从持有者现取——持有者不是状态，滚动期零写入。
 */
private class ViewMenuPositionProvider(
    private val anchor: NpAnchorHolder,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val rect = anchor.current()
        val x = rect.right.toInt() - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(
            x.coerceIn(0, maxX),
            rect.bottom.toInt() + 8,
        )
    }
}

/**
 * 聚合列表（点"全部 ›"后的整页）——设计稿 `aggregateView()`。
 *
 * 与首页的区别：这里是**完整列表**（不限条数）+ 类型筛选 + 排序 + 视图切换，
 * 用纵向网格（或列表）而不是横滑。
 *
 * 头部两种形态（设计稿 `head`）：
 *   continue / history / recent —— 标题 + 右侧计数；
 *   videos / audios —— 「标题 + 计数」的 listhead 形态（它们是从首页"查看全部"进来的）。
 */
@Composable
private fun AggregateList(
    mode: String,
    all: List<MediaItem>,
    positions: Map<String, Long>,
    lastAt: Map<String, Long>,
    favoriteKeys: List<String>,
    sortMode: SortMode,
    sortAscending: Boolean,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    compact: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onOpenEpisodes: (List<MediaItem>) -> Unit,
    sources: List<MediaSourceProvider>,
) {
    val ink = LocalNpInk.current
    val gutter = if (compact) 14.dp else 20.dp

    val base = remember(all, mode, sortMode, positions, lastAt, favoriteKeys) {
        when (mode) {
            "continue" ->
                all.filter { fractionOf(it, positions) in 0.01f..<HistoryRepository.WATCHED_DONE_RATIO }
            // 设计稿 `videos.filter(v => v.prog > 0)`，顺序 = 最近播放时间倒序
            // 与建表同键（审查批次 A3）。默认档（ADDED）保留这个语义；
            // 用户显式换到其它档时按所选排序，别让排序按钮形同虚设。
            "history" -> {
                val played = all.filter { (positions[HistoryEntry.keyOf(it)] ?: 0L) > 0L }
                if (sortMode == SortMode.ADDED) {
                    played.sortedByDescending { lastAt[HistoryEntry.keyOf(it)] ?: 0L }
                } else {
                    played
                }
            }
            "videos" -> all.filter { it.kind == MediaKind.VIDEO }
            "audios" -> all.filter { it.kind == MediaKind.AUDIO }
            // recent = 全量的加入时间序；`all` 已按当前排序档排好，不能再强制按 addedAt 重排。
            "recent" -> all
            // favorites = 我的收藏（按收藏时间倒序；键匹配走 E73 口径的 media_key）
            "favorites" -> {
                val order = favoriteKeys.withIndex().associate { (i, k) -> k to i }
                all.filter { HistoryEntry.keyOf(it) in order }
                    .sortedBy { order[HistoryEntry.keyOf(it)] ?: Int.MAX_VALUE }
            }
            else -> all
        }
    }
    val shown = remember(base, filter, positions) { filterList(base, filter, positions) }
    val meta = aggregateMeta(mode, sortMode)
    val isLead = mode == "videos" || mode == "audios"
    // 设计稿 listFoot：多集 = 同目录条目数 > 1（跨文件夹聚合时逐条按目录判定）。
    // 目录计数基于**全量** all 而不是当前聚合列表——剧集数与该聚合的筛选无关。
    val folderCounts = remember(all) { all.groupingBy { it.folder }.eachCount() }
    val multi = remember(shown, folderCounts) { shown.filter { (folderCounts[it.folder] ?: 0) > 1 } }
    val episodes = remember(multi, all) {
        multi.firstOrNull()?.folder?.let { f -> all.filter { it.folder == f } }.orEmpty()
    }
    val staggerWindow = npStaggerWindow(mode)
    val itemFade = npItemFadeSpec()
    // E203-C（契约 §8.5）：把网格可见区换算成条目区间，再按方向扩一/两屏，交给来源预热封面。
    // 内容项之前的槽位：标题 / 控件行 / 副标题，外加可能出现的空态与选集入口。
    // 在内容项之前增删 item 必须同步改下面这个式子，清单见 CoverWindow.kt 顶部「维护提示」段。
    val coverLeaderSlots = 3 + (if (shown.isEmpty()) 1 else 0) + (if (multi.isNotEmpty()) 1 else 0)
    val gridState = rememberLazyGridState()
    CoverDemandEffect(items = shown, leaderSlots = coverLeaderSlots, sources = sources, state = gridState)

    LazyVerticalGrid(
        columns = npGridColumnsFor(compact, gridColumns),
        state = gridState,
        contentPadding = PaddingValues(
            top = LocalNpTopBarInset.current,
            start = gutter,
            end = gutter,
            bottom = LocalNpBottomInset.current + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }, contentType = "head") {
            if (isLead) {
                // E134：`‹ 返回` chip 已删（顶栏有返回，内容区不再重复）；
                // 这里只留「标题 + N 项」。
                AggregateHead(
                    name = meta.first,
                    count = shown.size,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = meta.first,
                        style = NpTypography.headlineSmall,
                        color = ink.fg,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "${shown.size} 项", style = NpMono, color = ink.muted)
                }
            }
        }
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            ControlsRow(
                filter = filter,
                onFilter = onFilter,
                filterEnabled = true,
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                gridColumns = gridColumns,
                onGridColumns = onGridColumns,
                compact = compact,
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSort = onSort,
                onToggleAscending = onToggleAscending,
            )
        }
        item(key = "lead", span = { GridItemSpan(maxLineSpan) }, contentType = "lead") {
            // 设计稿 `.lead`：列表上方的 13px muted 副标题（"未看完的视频与音频"）
            Text(
                text = meta.second,
                style = NpTypography.bodySmall,
                color = ink.muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (shown.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                NpEmptyState(
                    title = "暂无内容",
                    message = "换个筛选条件看看。",
                )
            }
        }
        // E134：选集入口从列表尾部移到"lead"之下（与文件夹页同口径：入口在内容之前）
        if (multi.isNotEmpty()) {
            item(key = "episodes-entry", span = { GridItemSpan(maxLineSpan) }, contentType = "episodes") {
                EpisodesEntry(
                    multiCount = multi.size,
                    onOpen = { onOpenEpisodes(episodes) },
                )
            }
        }
        if (viewStyle == "list") {
            gridItemsIndexed(
                shown,
                key = { _, item -> "${item.sourceId}:${item.id}" },
                span = { _, _ -> GridItemSpan(maxLineSpan) },
                contentType = { _, _ -> "video-list" },
            ) { index, item ->
                MediaListRow(
                    item = item,
                    compact = compact,
                    onOpen = onOpen,
                    modifier = Modifier
                        .animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)
                        .npStaggeredEntry(index, staggerWindow),
                )
            }
        } else {
            gridItemsIndexed(
                shown,
                key = { _, item -> "${item.sourceId}:${item.id}" },
                contentType = { _, _ -> "video-card" },
            ) { index, item ->
                MediaCardBody(
                    item = item,
                    onOpen = onOpen,
                    showFolderTag = true,
                    modifier = Modifier
                        .animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)
                        .npStaggeredEntry(index, staggerWindow),
                )
            }
        }
    }
}

/** 类型筛选键（设计稿 `FILTERS`）。 */
internal enum class FilterKey(val key: String, val label: String) {
    ALL("all", "全部类型"),
    VIDEO("video", "仅视频"),
    AUDIO("audio", "仅音频"),
    UNFINISHED("unfinished", "未看完"),
    DONE("done", "已看完"),
    NEW("new", "新增"),
    ;

    companion object {
        fun keyOf(label: String): String =
            entries.firstOrNull { it.label == label }?.key ?: ALL.key

        fun labelOf(key: String): String =
            entries.firstOrNull { it.key == key }?.label ?: ALL.label
    }
}

/** 聚合列表的标题与副标题（设计稿 `moreTitles`）。排序可变的聚合把当前排序档带进副标题。 */
private fun aggregateMeta(mode: String, sortMode: SortMode): Pair<String, String> = when (mode) {
    "continue" -> "继续观看" to "未看完的视频与音频"
    "history" -> "播放历史" to if (sortMode == SortMode.ADDED) {
        "按最近播放排序"
    } else {
        "按${sortMode.label}排序"
    }
    "favorites" -> "我的收藏" to "收藏的条目"
    "videos" -> "全部视频" to "本地库中的视频文件"
    "audios" -> "全部音频" to "本地库中的纯音频文件"
    else -> "最近添加" to "按${sortMode.label}排序"
}


private fun FolderNode.findByPath(path: String?): FolderNode? {
    if (path == null) return this
    if (this.path == path) return this
    return children.firstNotNullOfOrNull { it.findByPath(path) }
}

/** 播放进度下发通道（mediaId → 毫秒），由 [LibraryScreen] 收集并注入。 */
val LocalPlaybackProgress = staticCompositionLocalOf<Map<String, Long>> { emptyMap() }

/** 最近播放时间下发通道（mediaId → epoch 毫秒），「播放历史 / 最近播放」排序用。 */
val LocalPlaybackLastAt = staticCompositionLocalOf<Map<String, Long>> { emptyMap() }

/**
 * 播放进度：会话层按 mediaId 记忆在 DataStore（保存于退页/切集时），
 * 这里从 [LocalPlaybackProgress] 读全量 map。 fraction 与 durationMs>0 一起成立才有意义。
 */
@Composable
private fun MediaItem.progressFraction(): Float =
    fractionOf(this, LocalPlaybackProgress.current)

/** 进度分数的纯函数形态（remember 计算块 / 列表过滤里用，不读 CompositionLocal）。 */
internal fun fractionOf(item: MediaItem, positions: Map<String, Long>): Float {
    if (item.durationMs <= 0 || item.id.isEmpty()) return 0f
    // 与建表同键（审查批次 A3）：跨来源唯一键，而不是裸 mediaId
    val saved = positions[HistoryEntry.keyOf(item)] ?: return 0f
    if (saved <= 0) return 0f
    return (saved.toFloat() / item.durationMs).coerceIn(0f, 1f)
}

/**
 * 长按菜单通道（§3.6）：页面提供「长按某卡片」的回调，卡片组件消费。
 * 用 CompositionLocal 而不是三层回调透传——卡片散布在 rail / 网格 / 列表 / 聚合页
 * 四种版式里，逐层加参数会污染一大片签名；未提供时（如搜索页）自然就没有长按。
 */
internal val LocalMediaLongPress =
    staticCompositionLocalOf<((MediaItem, Rect) -> Unit)?> { null }

/**
 * 跳转播放页：媒体信息经 URL 编码查询参数传递，由播放页插件解析。
 *
 * E55：编码用 `encode(value, "")` 把允许集显式收到"仅 unreserved 字符"。
 * 单参重载会放行 `& = ? +` 一类分隔符，一个含 `&` 的标题
 * （"Rock & Roll.mkv"）就能把后面的 mime/id 参数整段截掉。
 *
 * [immersive]：明确的全屏意图（详情页「全屏观看」/ 右栏 ⛶）带 `fs=1`——
 * 假全屏已删除（2026-10-07）：全屏只有沉浸一种，`fs=1` 落地即全屏（竖窗先转横向）。
 */
internal fun openPlayer(
    navigator: Navigator,
    item: MediaItem,
    fromStart: Boolean = false,
    immersive: Boolean = false,
    audioOnly: Boolean = false,
) {
    navigator.navigate(playerRouteOf(item, fromStart, immersive, audioOnly))
}

/**
 * 播放页路由字符串——[openPlayer] 与转屏交接（[com.noneplayer.kernel.ui.api.NpFormHandoff.offerDetailRoute]）
 * 共用一份，两处各拼必然在参数演进时漂。
 *
 * [play] = false 带 `play=0`：**只装载、不起播**。转屏交接自横屏详情页时用——
 * 那一档本来就没在播，把设备转个方向不该替用户按下播放键。
 */
internal fun playerRouteOf(
    item: MediaItem,
    fromStart: Boolean = false,
    immersive: Boolean = false,
    audioOnly: Boolean = false,
    play: Boolean = true,
): String {
    fun enc(value: String) = android.net.Uri.encode(value, "")
    return "player?uri=${enc(item.uri)}" +
        "&title=${enc(item.title)}" +
        "&mime=${enc(item.mimeType)}" +
        "&id=${enc(item.id)}" +
        // § 长按菜单「从头播放」：不恢复断点（路由参数带默认值，属契约增量）
        (if (fromStart) "&from=start" else "") +
        (if (immersive) "&fs=1" else "") +
        (if (audioOnly) "&audio=1" else "") +
        (if (play) "" else "&play=0")
}

/** E20：≤此规模在组合内同步排序（零延迟、无闪烁）；超过则先原序占位，后台排完再替换 */
private const val SYNC_SORT_LIMIT = 512

/** 设计稿 `v.neu`：「新增」角标的时间窗——48 小时内加入的条目才带角标 */
private const val NEW_BADGE_WINDOW_MS = 48 * 60 * 60 * 1000L

/** 播放历史排序的只读快照；只含排序需要的 lastPlayedAt / playCount，不含每 5s 变的进度。 */
internal data class PlaybackSortContext(
    val lastPlayedAt: Map<String, Long> = emptyMap(),
    val playCounts: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = PlaybackSortContext()
    }
}

/** E20：排序口径收口为普通函数——组合内外共用，六档比较器不再散在 remember lambda 里 */
internal fun mediaComparator(
    sortMode: SortMode,
    ascending: Boolean,
    playback: PlaybackSortContext = PlaybackSortContext.EMPTY,
): Comparator<MediaItem> {
    val comparator: Comparator<MediaItem> = when (sortMode) {
        // E42：自然序（E2 排在 E10 前）——共享比较器，与连播队列/详情页列表同一口径
        SortMode.NAME -> MediaNaturalOrder
        SortMode.ADDED -> compareBy { it.addedAtMs }
        SortMode.DURATION -> compareBy { it.durationMs }
        SortMode.SIZE -> compareBy { it.sizeBytes }
        SortMode.RECENT -> compareBy<MediaItem> {
            playback.lastPlayedAt[HistoryEntry.keyOf(it)] ?: 0L
        }.thenByDescending { it.addedAtMs }
        SortMode.MOST_PLAYED -> compareBy<MediaItem> {
            playback.playCounts[HistoryEntry.keyOf(it)] ?: 0
        }.thenByDescending { it.addedAtMs }
    }
    return if (ascending) comparator else comparator.reversed()
}

private fun sortedMediaItems(
    items: List<MediaItem>,
    sortMode: SortMode,
    ascending: Boolean,
    playback: PlaybackSortContext = PlaybackSortContext.EMPTY,
): List<MediaItem> = items.sortedWith(mediaComparator(sortMode, ascending, playback))

/** 搜索页没有播放历史上下文：不提供历史档，当前全局档是历史档时回落到「最近添加」。 */
internal val SEARCH_SORT_MODES: List<SortMode> =
    SortMode.entries.filterNot { it.usesPlaybackHistory }

internal fun SortMode.forSearch(): SortMode =
    if (usesPlaybackHistory) SortMode.ADDED else this

/**
 * 排序结果的**跨重组缓存**（用户实报"每次从其他界面切过来都会出现从默认排序
 * 重排到选中排序的动画"的修复）。
 *
 * 背景：> [SYNC_SORT_LIMIT] 的列表异步排序，`remember` 的作用域随页面卸载一起丢——
 * 每次重进页面 `asyncSorted` 初值都是 null，于是先渲一帧**原序占位**、排完再换。
 * 换的那一帧里 `animateItem` 会把整屏条目从原序"搬"到选中序，读起来就是每次
 * 切页都重放一遍重排动画。缓存按 (items, mode, ascending) 记住上一次的结果：
 * 命中即首帧就按选中排序渲染，动画没有素材。
 *
 * 单线程访问（组合期与 LaunchedEffect 续体都在主线程），不需要同步。
 */
private object MediaSortCache {
    private var items: List<MediaItem>? = null
    private var mode: SortMode? = null
    private var ascending = false
    private var playback: PlaybackSortContext = PlaybackSortContext.EMPTY
    private var result: List<MediaItem>? = null

    fun get(
        items: List<MediaItem>,
        mode: SortMode,
        ascending: Boolean,
        playback: PlaybackSortContext,
    ): List<MediaItem>? = result?.takeIf {
        this.items == items &&
            this.mode == mode &&
            this.ascending == ascending &&
            this.playback == playback
    }

    fun put(
        items: List<MediaItem>,
        mode: SortMode,
        ascending: Boolean,
        playback: PlaybackSortContext,
        result: List<MediaItem>,
    ) {
        this.items = items
        this.mode = mode
        this.ascending = ascending
        this.playback = playback
        this.result = result
    }
}

/**
 * E20：排序离主线程。此前 `remember(items, …)` 在组合期全量排序，列表上千时
 * 每次增删/换档都在主线程卡一帧。现在小列表维持同步（行为不变），大列表先给
 * 缓存顺序（若有）再在 `Dispatchers.Default` 排完替换——**没有缓存时才**退到
 * 原序占位一帧（首开/数据变化的那次）。
 */
@Composable
private fun rememberSortedMediaItems(
    items: List<MediaItem>,
    sortMode: SortMode,
    ascending: Boolean,
    playback: PlaybackSortContext,
): List<MediaItem> {
    // 只有历史档才把历史快照纳入排序/缓存键；普通档不让播放进度变化触发重排。
    val effectivePlayback = if (sortMode.usesPlaybackHistory) playback else PlaybackSortContext.EMPTY
    val small = items.size <= SYNC_SORT_LIMIT
    val syncSorted = if (small) {
        remember(items, sortMode, ascending, effectivePlayback) {
            sortedMediaItems(items, sortMode, ascending, effectivePlayback)
                .also { MediaSortCache.put(items, sortMode, ascending, effectivePlayback, it) }
        }
    } else {
        items
    }
    var asyncSorted by remember(items, sortMode, ascending, effectivePlayback) {
        mutableStateOf(MediaSortCache.get(items, sortMode, ascending, effectivePlayback))
    }
    LaunchedEffect(items, sortMode, ascending, effectivePlayback) {
        if (!small) {
            val sorted = withContext(Dispatchers.Default) {
                sortedMediaItems(items, sortMode, ascending, effectivePlayback)
            }
            MediaSortCache.put(items, sortMode, ascending, effectivePlayback, sorted)
            asyncSorted = sorted
        }
    }
    return asyncSorted ?: syncSorted
}

/**
 * E232：删除一个媒体文件（**不再走系统二次确认**）。
 *
 * 能力顺序：
 * ① 授权 SAF 树里按 `RELATIVE_PATH + DISPLAY_NAME` 找到文档并 `deleteDocument`
 *    （目录授权带写权限时可行；只读授权会抛 SecurityException，继续下一步）；
 * ② `ContentResolver.delete` 直接删（文件属主、或已授予「所有文件访问」）。
 * 两步都没权限就返回 false，由调用方 toast 告知——不再用 `createDeleteRequest`
 * 换一次与播放器无关的系统确认框。
 */
private suspend fun deleteMediaItem(
    context: android.content.Context,
    item: MediaItem,
    authorizedTrees: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val uri = runCatching { item.uri.toUri() }.getOrNull() ?: return@withContext false
    if (item.id.startsWith("saf-")) {
        return@withContext runCatching {
            android.provider.DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)
    }
    val meta = queryMediaFileMeta(resolver, uri)
    if (meta != null && deleteViaAuthorizedTree(resolver, authorizedTrees, meta.first, meta.second)) {
        return@withContext true
    }
    runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

/** MediaStore 条目 → (相对目录, 显示名)；旧系统没有 RELATIVE_PATH 列时返回 null 走直接删除。 */
private fun queryMediaFileMeta(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
): Pair<String, String>? = runCatching {
    // RELATIVE_PATH 是 API 29+ 列；旧系统没有它，直接走 ContentResolver.delete。
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
    resolver.query(
        uri,
        arrayOf(
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
        val relative = cursor.getString(1)?.trim('/') ?: return@use null
        relative to name
    }
}.getOrNull()

/**
 * 在授权树里逐段下钻到文件所在目录，再按显示名删文档。
 *
 * 与播放页字幕查找同一条路径规则（`treeDocId` 冒号后是相对 external storage 的路径），
 * 但这里不能跨插件引用——插件间禁止互相依赖，同一小段纯字符串逻辑各自持有一份。
 */
private fun deleteViaAuthorizedTree(
    resolver: android.content.ContentResolver,
    authorizedTrees: List<String>,
    relativePath: String,
    displayName: String,
): Boolean {
    for (tree in authorizedTrees) {
        val treeUri = runCatching { tree.toUri() }.getOrNull() ?: continue
        val treeDocId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: continue
        val treeRoot = treeDocId.substringAfter(':', "").trim('/')
        val segments = treeRelativeSegments(treeRoot, relativePath) ?: continue
        var dirDocId = treeDocId
        var walked = true
        for (segment in segments) {
            val child = findChildDocument(resolver, treeUri, dirDocId, segment)
            if (child == null) {
                walked = false
                break
            }
            dirDocId = child
        }
        if (!walked) continue
        val fileDocId = findChildDocument(resolver, treeUri, dirDocId, displayName) ?: continue
        val docUri = runCatching {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
        }.getOrNull() ?: continue
        if (runCatching {
                android.provider.DocumentsContract.deleteDocument(resolver, docUri)
            }.getOrDefault(false)
        ) {
            return true
        }
    }
    return false
}

/** 视频相对路径落在授权树里的剩余目录段；不在树内返回 null（同播放页字幕查找口径）。 */
private fun treeRelativeSegments(treeRoot: String, relativePath: String): List<String>? {
    val tree = treeRoot.trim('/')
    val target = relativePath.trim('/')
    if (tree.isEmpty()) return target.split('/').filter { it.isNotEmpty() }
    if (target == tree) return emptyList()
    if (!target.startsWith("$tree/")) return null
    return target.removePrefix("$tree/").split('/').filter { it.isNotEmpty() }
}

/** 在授权树某目录下按显示名找直接子文档 id；找不到返回 null。 */
private fun findChildDocument(
    resolver: android.content.ContentResolver,
    treeUri: android.net.Uri,
    parentDocumentId: String,
    displayName: String,
): String? = runCatching {
    val children = android.provider.DocumentsContract
        .buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    resolver.query(
        children,
        arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == displayName) return@use cursor.getString(0)
        }
        null
    }
}.getOrNull()
