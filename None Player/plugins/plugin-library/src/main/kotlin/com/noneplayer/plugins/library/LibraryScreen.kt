package com.noneplayer.plugins.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LibraryView
import com.noneplayer.core.data.SortMode
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.kernel.ui.api.LocalNpBottomInset
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpCarouselItem
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpCoverPlaceholder
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEmptyState
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpFilterSelect
import com.noneplayer.core.ui.NpFolderCard
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpListRow
import com.noneplayer.core.ui.NpMediaCard
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpOptRow
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSectionHead
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSegmented
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpSkeleton
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.formatRelativeDate
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npClickable
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpTabSwitcher
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.NpTabChannel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 媒体库页面——设计稿 `none-player.html` 的 Home / Folders / 聚合列表三套版式。
 *
 * 通过 MediaSourceProvider 扩展点聚合所有来源的媒体列表；
 * 排序方式/视图风格/列数经 AppSettings 持久化；扫描目录未选择时引导选择
 * （不选择文件夹不扫描）。
 *
 * [forcedView] 供 Tab 使用：指定后视图固定（如"文件夹"Tab），
 * 且标题行不再显示视图切换项；null 时跟随用户持久化偏好。
 *
 * 状态机：无来源 → 空状态；缺权限 → 授权引导；未选目录 → 选择文件夹；
 * 有目录未扫描 → 开始扫描；有数据 → 封面墙/文件夹卡片。
 */
@Composable
fun LibraryScreen(navigator: Navigator, forcedView: LibraryView? = null) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { kernel.services.get<AppSettings>() }
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    // 跨 Tab 跳转通道（首页"文件夹"区块 → 文件夹 Tab）。见 [FolderNav] 与
    // `LocalNpTabSwitcher` 的说明：Tab 路由在内层导航图，拼外层路由会崩。
    val switchTab = LocalNpTabSwitcher.current

    // ---- 用户偏好（持久化） ----
    val sortMode by settings.sortMode.collectAsStateWithLifecycle(SortMode.ADDED)
    val sortAscending by settings.sortAscending.collectAsStateWithLifecycle(false)
    val gridColumns by settings.gridColumns.collectAsStateWithLifecycle(2)
    val folderUris by settings.scanFolderUris.collectAsStateWithLifecycle(initialValue = emptyList())
    val prefView by settings.libraryView.collectAsStateWithLifecycle(LibraryView.VIDEOS)
    // 设计稿 viewToggle 的 `state.view`：网格 / 列表，跨页面共用并持久化
    val listViewStyle by settings.listViewStyle.collectAsStateWithLifecycle("grid")
    val onViewStyle: (String) -> Unit = { scope.launch { settings.setListViewStyle(it) } }
    // forcedView 供 Tab 固定视图；普通入口跟随用户持久化偏好
    val viewMode = forcedView ?: prefView
    // B5 修复：rememberSaveable，横竖屏切换/进程回收后文件夹选中态不丢
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    // 页内层级：聚合列表（继续观看 / 全部视频 / …）不下探到导航栈，
    // 否则每个"全部 ›"都要多一层返回，而它本质上是同一页的筛选结果。
    var aggregate by rememberSaveable { mutableStateOf<String?>(null) }
    // 类型筛选（设计稿 FILTERS）：提升到页面顶层，FolderBrowser 与 AggregateList
    // 共用同一份选择——两处各记一份的话，从文件夹进"全部"再返回筛选就"重置"了。
    var filter by rememberSaveable { mutableStateOf(FilterKey.ALL.key) }

    // ---- 媒体数据（扩展点聚合） ----
    // E10：不再本页自建装载器——AsyncImage 经 LocalImageLoader 默认值解析到
    // Application 级 Coil 单例（NonePlayerApplication.newImageLoader）
    val sources by remember {
        kernel.registry.observe<MediaSourceProvider>()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // E78：媒体列表用「可空哨兵」承载，以区分两种语义完全不同的空：
    //   null      = 尚未收到任何发射（插件/扫描数据未就绪）
    //   emptyList = 已确实加载，且结果就是空
    // 此前二者不可区分，导致下面的「目录失效 → 回根层」守卫在**入口重建的首帧**
    // 拿空树去校验刚被 rememberSaveable 恢复的选中路径，把状态当场清掉：
    // 表现为「文件夹进到第 N 层 → 切 Tab 或看视频返回 → 退回根层」。
    val mediaState by remember(sources) {
        if (sources.isEmpty()) {
            flowOf<List<MediaItem>?>(null)
        } else {
            combine(sources.map { it.observeMedia() }) { lists -> lists.flatMap { it } }
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val mediaLoaded = mediaState != null
    val items = mediaState ?: emptyList()

    val sortedItems = remember(items, sortMode, sortAscending) {
        val comparator: Comparator<MediaItem> = when (sortMode) {
            SortMode.NAME -> compareBy { it.title.lowercase() }
            SortMode.ADDED -> compareBy { it.addedAtMs }
            SortMode.DURATION -> compareBy { it.durationMs }
            SortMode.SIZE -> compareBy { it.sizeBytes }
        }
        if (sortAscending) items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
    }

    // 文件夹层级树（根 = 虚拟根；进入子级后展示子文件夹 + 本级视频）
    val folderRoot = remember(sortedItems) { buildFolderTree(sortedItems) }
    val currentFolder = remember(selectedFolder, folderRoot) { folderRoot.findByPath(selectedFolder) }
    // 目录被移除/重扫后选中路径可能失效 → 自动回到根层。
    // E78：**必须等媒体数据真正加载完再校验**——未加载时树必然是空的，
    // 此时校验等于用"还没读到"冒充"已不存在"，会清掉刚恢复的层级状态。
    LaunchedEffect(selectedFolder, folderRoot, mediaLoaded) {
        if (mediaLoaded && selectedFolder != null && currentFolder == null) selectedFolder = null
    }

    // 首页"文件夹"区块请求打开某个目录 → 本页（文件夹 Tab）接住并进入该层。
    // 只在文件夹视图下消费：首页那次点击已经切了 Tab，这里补上"打开哪一层"。
    LaunchedEffect(viewMode, folderRoot, mediaLoaded) {
        if (viewMode != LibraryView.FOLDERS || !mediaLoaded) return@LaunchedEffect
        FolderNav.consume()?.let { path -> selectedFolder = path }
    }

    // "我的"页「最近播放 · 全部」请求打开播放历史聚合 → 首页接住。
    // 通道落在 kernel-ui-api（NpTabChannel）：两个页面分属不同插件，
    // 插件间禁止互相依赖，共享点只能在双方都依赖的内核 UI 契约里。
    LaunchedEffect(viewMode, mediaLoaded) {
        if (viewMode != LibraryView.VIDEOS || !mediaLoaded) return@LaunchedEffect
        NpTabChannel.consumeFor("home")?.let { mode -> aggregate = mode }
    }

    // E54：文件夹层级是页内状态（不入返回栈），系统返回必须在此消费，
    // 否则返回事件落到导航层 → 切走 Tab / 弹回首页，用户看到"退出了文件夹页"。
    BackHandler(enabled = viewMode == LibraryView.FOLDERS && selectedFolder != null) {
        selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
    }
    BackHandler(enabled = aggregate != null) { aggregate = null }

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
                    val current = settings.scanFolderUris.first()
                    settings.setScanFolders(current + uri.toString())
                    sources.filter { it.isAvailable() }.forEach { it.scan() }
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
    var requestedScan by remember { mutableStateOf(false) }
    fun scanAll() {
        scope.launch {
            sources.filter { it.isAvailable() }.forEach { it.scan() }
        }
    }

    val videos = remember(sortedItems) { sortedItems.filter { it.kind == MediaKind.VIDEO } }
    val audios = remember(sortedItems) { sortedItems.filter { it.kind == MediaKind.AUDIO } }
    // 设计稿「最近添加」`videos.slice().reverse().slice(0,8)`：全库（含音频）按加入时间
    // 倒序取 8 条——排序偏好（名称/大小）不该改写这个区块的语义，所以独立计算。
    val recentItems = remember(items) { items.sortedByDescending { it.addedAtMs }.take(8) }

    // D2：播放进度接线——「继续观看 / 已看 x% / 封面进度线 / 未看完筛选」的单一数据源。
    // 播放页/会话层按 mediaId 落盘（DataStore），这里全量收集后经 CompositionLocal 下发，
    // 卡片与筛选侧只读 [LocalPlaybackProgress]，不再各开 DataStore 订阅。
    val positions by settings.playbackPositions.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 「播放历史 / 最近播放」的时间序：进度值排不出时间序，另取一份 lastAt
    val lastAt by settings.playbackLastAt.collectAsStateWithLifecycle(initialValue = emptyMap())
    CompositionLocalProvider(
        LocalPlaybackProgress provides positions,
        LocalPlaybackLastAt provides lastAt,
    ) {

    val title: String
    val subtitle: String
    when {
        aggregate != null -> {
            val meta = aggregateMeta(aggregate!!)
            title = meta.first
            subtitle = meta.second
        }
        viewMode == LibraryView.FOLDERS && selectedFolder != null -> {
            // 设计稿 titles.folder：标题「文件夹」+ 副题「浏览目录内容」；
            // 文件夹名与计数在内容区 listhead / hero 里（双层结构，别在这里重复）
            title = "文件夹"
            subtitle = "浏览目录内容"
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
                    onBack = when {
                        aggregate != null -> ({ aggregate = null })
                        viewMode == LibraryView.FOLDERS && selectedFolder != null -> ({
                            selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
                        })
                        else -> null
                    },
                    trailing = {
                        // 设计稿 topbar 搜索钮常驻（空库也应能进搜索页看引导）
                        NpIconButton(
                            icon = NpIcon.Search,
                            contentDescription = "搜索",
                            onClick = { navigator.navigate(SEARCH_ROUTE) },
                            size = if (compact) 40.dp else 44.dp,
                        )
                        // 顶栏不再放设置齿轮：设置已经是一个**常驻标签**（紧凑档底栏第 4 项 /
                        // Rail 底部一项），再放一个就是同一个动作两个入口。
                    },
                )
            },
        ) { contentModifier ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = contentModifier.fillMaxSize()) {
                    when {
                        sources.isEmpty() -> NpEmptyState(
                            title = "还没有媒体",
                            message = "安装媒体来源插件后，视频与音乐会出现在这里。",
                        )

                        missingPermission -> NpEmptyState(
                            title = "需要媒体权限",
                            message = "None Player 需要访问设备上的视频与音频，才能在这里展示和播放。",
                            actionLabel = "授予媒体权限",
                            onAction = { permissionLauncher.launch(permissionStrings.toTypedArray()) },
                        )

                        folderUris.isEmpty() -> NpEmptyState(
                            title = "选择要观看的文件夹",
                            message = "选择一个媒体文件夹，None Player 只扫描你指定的位置（可在设置中添加多个）。",
                            actionLabel = "选择媒体文件夹",
                            onAction = { folderPicker.launch(null) },
                        )

                        // 数据未就绪时**不渲染列表**，而是给它一块骨架。
                        //
                        // 这不只是观感问题：媒体数据到达前，首页唯一能渲染的区块是"全部音频"
                        // （其余区块都要求列表非空才会出现）。LazyColumn 按 key 锚定当前可见项，
                        // 于是数据到达、前面的区块被插入后，它会**保住"全部音频"的屏幕位置**，
                        // 把新插入的"最近添加 / 文件夹 / 全部视频"顶到上方——用户冷启动看到的是
                        // 一个已经滚到底部的页面。先给骨架再换内容，锚点与内容一起重置。
                        !mediaLoaded && items.isEmpty() -> LoadingPlaceholder(compact = compact)

                        // E78：媒体已加载完但确实没有内容 → 空库。
                        items.isEmpty() -> NpEmptyState(
                            title = "媒体库是空的",
                            message = "扫描所选文件夹中的媒体，建立你的媒体库。",
                            actionLabel = if (requestedScan) null else "开始扫描",
                            onAction = if (requestedScan) null else {
                                { requestedScan = true; scanAll() }
                            },
                        )

                        aggregate != null -> AggregateList(
                            mode = aggregate!!,
                            all = sortedItems,
                            positions = positions,
                            lastAt = lastAt,
                            sortMode = sortMode,
                            sortAscending = sortAscending,
                            gridColumns = gridColumns,
                            viewStyle = listViewStyle,
                            onViewStyle = onViewStyle,
                            filter = filter,
                            onFilter = { filter = it },
                            compact = compact,
                            onSort = { scope.launch { settings.setSortMode(it) } },
                            onToggleAscending = { scope.launch { settings.setSortAscending(it) } },
                            onOpen = { openPlayer(navigator, it) },
                            onOpenEpisodes = { episodeSheet = it },
                        )

                        viewMode == LibraryView.FOLDERS -> FolderBrowser(
                            root = folderRoot,
                            selected = currentFolder,
                            positions = positions,
                            gridColumns = gridColumns,
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
                            onOpenParent = {
                                selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
                            },
                            onOpenVideo = { openPlayer(navigator, it) },
                            onOpenEpisodes = { episodeSheet = it },
                        )

                        else -> HomeView(
                            videos = videos,
                            audios = audios,
                            recent = recentItems,
                            folders = folderRoot.children,
                            compact = compact,
                            onOpen = { openPlayer(navigator, it) },
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
                            onManageFolders = {
                                // 管理 = 文件夹 Tab 根层：只切 Tab，不请求任何子目录
                                FolderNav.clear()
                                switchTab("folders")
                            },
                        )
                    }
                }
            }
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
    onOpen: (MediaItem) -> Unit,
    onMore: (String) -> Unit,
    onOpenFolder: (FolderNode) -> Unit,
    onManageFolders: () -> Unit,
) {
    val gutter = if (compact) 14.dp else 20.dp
    val progressMap = LocalPlaybackProgress.current
    val unfinished = videos.filter { fractionOf(it, progressMap) in 0.01f..0.99f }

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
                    MediaRail(items = unfinished, compact = compact, onOpen = onOpen)
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
                    MediaRail(items = recent, compact = compact, onOpen = onOpen)
                }
            }
        }
        if (hasFolders) {
            item(key = "folders") {
                HomeBlock(
                    title = "文件夹",
                    actionLabel = "管理",
                    gutter = gutter,
                    showTopDivider = hasContinue || hasRecent,
                    // 「管理」= 去文件夹 Tab 的**根层**（管理全部目录），
                    // 而不是"打开第一个文件夹"——那是上一版的实现，
                    // 点"管理"结果进了某个具体目录，用户找不到"管理"这件事。
                    onMore = onManageFolders,
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
                    MediaRail(items = videos.take(8), compact = compact, onOpen = onOpen)
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
                    MediaRail(items = audios.take(8), compact = compact, onOpen = onOpen)
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(folders.take(8), key = { it.path }) { node ->
                FolderCardCell(node = node, modifier = Modifier.width(200.dp))
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
            folders.take(4).forEach { node ->
                Box(modifier = Modifier.width(cellWidth)) { FolderCardCell(node) }
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
    onOpen: (MediaItem) -> Unit,
) {
    val gutter = if (compact) 14.dp else 20.dp
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { "${it.sourceId}:${it.id}" }) { item ->
            NpCarouselItem {
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
    val meta: List<String> = if (showFolderTag) {
        buildList {
            if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
            formatRelativeDate(item.addedAtMs).takeIf { it.isNotEmpty() }?.let { add(it) }
            add(if (isAudio) "音频" else "视频")
        }
    } else {
        buildList {
            if (progress > 0.01f && progress < 0.99f) add("已看 ${(progress * 100).toInt()}%")
            if (item.durationMs > 0) add(formatTimestamp(item.durationMs))
            // 设计稿 `v.res`：1080P / 4K / 无损；拿不到就省略（不留「视频」占位，
            // 类型信息 kindtag 已经表达过一次了）
            item.resolutionLabel.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
    }
    NpMediaCard(
        title = item.title,
        meta = meta,
        onClick = { onOpen(item) },
        modifier = modifier,
        folderTag = if (showFolderTag) item.folder else null,
        folderTagIsAudio = isAudio,
        cover = {
            NpCoverBox(
                placeholderHue = hue,
                progress = progress.takeIf { it > 0.01f },
                isAudio = isAudio,
                showPlayGlyph = true,
                // 设计稿 `v.neu`：48 小时内加入 → 右上「新增」角标
                isNew = System.currentTimeMillis() - item.addedAtMs < 48 * 60 * 60 * 1000L,
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
 * 列表视图行（设计稿 `.listrow`）：112dp 缩略图 + 单行标题 + mono 元信息 + chevron。
 *
 * 与网格卡共用同一份数据与封面组件，只是形态不同——
 * 设计稿四处（文件夹页 / 文件夹详情 / 聚合列表 / 搜索结果）都用这一个形状。
 */
@Composable
internal fun MediaListRow(
    item: MediaItem,
    compact: Boolean,
    onOpen: (MediaItem) -> Unit,
) {
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val progress = item.progressFraction()
    val meta = buildList {
        if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
        formatRelativeDate(item.addedAtMs).takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    NpListRow(
        title = item.title,
        meta = meta,
        onClick = { onOpen(item) },
        compact = compact,
        folderTag = item.folder,
        folderTagIsAudio = isAudio,
        cover = {
            NpCoverBox(
                placeholderHue = hue,
                progress = progress.takeIf { it > 0.01f },
                isAudio = isAudio,
                // 设计稿 `.lr-kind`：列表行的小角标只有图标（放不下「视频」二字），
                // 播放圆钮同理省略（整行本身就是点击目标）
                compactKindTag = true,
                showPlayGlyph = false,
                durationText = formatTimestamp(item.durationMs)
                    .takeIf { item.durationMs > 0 },
                tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
            ) {
                MediaCover(item = item, hue = hue)
            }
        },
    )
}

/** 封面位图：优先扫描时缓存的缩略图，无缓存时对视频现场抽帧；音频用色相占位。 */
@Composable
private fun MediaCover(
    item: MediaItem,
    hue: Int,
    modifier: Modifier = Modifier,
) {
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
            modifier = modifier.fillMaxSize(),
        )
    } else {
        NpCoverPlaceholder(hue = hue, modifier = modifier)
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
    onOpenParent: () -> Unit,
    onOpenVideo: (MediaItem) -> Unit,
    onOpenEpisodes: (List<MediaItem>) -> Unit,
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

    LazyVerticalGrid(
        // 设计稿 compact 档媒体网格恒 2 列（`.tier-compact .grid.v` 607）：
        // Adaptive(176dp) 在 360dp 窗口 floor 出 1 列，比设计稿更稀
        columns = gridColumnsFor(compact, gridColumns),
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
        if (node.path.isNotEmpty()) {
            item(key = "listhead", span = { GridItemSpan(maxLineSpan) }) {
                // 设计稿的双层结构：顶栏「文件夹 / 浏览目录内容」，
                // listhead「‹ 文件夹  层名 …… N 项」
                FolderListHead(
                    name = node.name,
                    count = node.totalCount,
                    onBack = onOpenParent,
                )
            }
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                FolderHead(node = node)
            }
        }
        if (noSubfolders) {
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "该目录下没有子文件夹，已按全部媒体展示",
                    style = NpTypography.bodySmall,
                    color = ink.muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            ControlsRow(
                filter = filter,
                onFilter = onFilter,
                filterEnabled = node.totalCount > 0,
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSort = onSort,
                onToggleAscending = onToggleAscending,
            )
        }
        items(node.children, key = { "folder:${it.path}" }) { child ->
            NpFolderCard(
                name = child.name,
                countLabel = "${child.totalCount} 项",
                onClick = { onOpenFolder(child) },
                modifier = Modifier.animateItem(),
                cover = {
                    FolderCover(
                        node = child,
                        hue = NpMediaColor.folderHue(child.name),
                    )
                },
            )
        }
        if (viewStyle == "list") {
            items(
                shownVideos,
                key = { "video:${it.sourceId}:${it.id}" },
                span = { GridItemSpan(maxLineSpan) },
            ) { item ->
                MediaListRow(item = item, compact = compact, onOpen = onOpenVideo)
            }
        } else {
            items(
                shownVideos,
                key = { "video:${it.sourceId}:${it.id}" },
            ) { item ->
                MediaCardBody(
                    item = item,
                    onOpen = onOpenVideo,
                    showFolderTag = true,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (shownVideos.isEmpty() && node.children.isEmpty() && node.totalCount == 0) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                NpEmptyState(
                    title = "此文件夹没有内容",
                    message = "换个筛选条件看看。",
                )
            }
        }
        // 设计稿 listFoot：本层含多集条目（同目录 > 1 条）时给出「选集」入口
        val episodes = node.directVideos
        if (episodes.size > 1 && shownVideos.isNotEmpty()) {
            item(key = "listfoot", span = { GridItemSpan(maxLineSpan) }) {
                ListFoot(
                    multiCount = shownVideos.size,
                    onOpen = { onOpenEpisodes(episodes) },
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
 * 列表尾部「选集」入口（设计稿 `.list-foot`）：分隔发丝线 + accent 胶囊 + 多集提示。
 *
 * 只在**当前列表里确实有多集条目**时出现（[multiCount] 由调用方按"同文件夹条目数 > 1"
 * 计算）。按钮把整条剧集列表交给页面根层的 sheet（选中即起播）——
 * 这里不自己开 NpSheet：整屏浮层在 Lazy 项里会被裁剪。
 */
@Composable
private fun ListFoot(
    multiCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .height(1.dp)
                .background(ink.border),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ink.accent)
                    .npPressScale(onClick = onOpen, pressedScale = 0.97f)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = NpIcon.List,
                    contentDescription = null,
                    tint = ink.bg,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "选集",
                    style = NpTypography.bodyMedium,
                    color = ink.bg,
                    fontWeight = FontWeight.SemiBold,
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
}

/**
 * 「存储位置」区块：eyebrow + rows（内部存储：剩余/总量）。
 *
 * 只报**内部存储卷**：SAF 授权目录可能落在任意卷上，但"这个卷还剩多少"只有
 * `Environment` 的路径能可靠问到；SD 卡一类的第二卷需要 StorageManager 的
 * `storageVolumes` 且多数设备不给可写路径，宁可不列也不列一个假的。
 */
@Composable
private fun StorageSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
        Box(modifier = Modifier.padding(bottom = 2.dp)) {
            NpEyebrow(text = "存储位置")
        }
        NpSettingRow(
            title = "内部存储",
            subtitle = storageText,
            icon = NpIcon.Folder,
            showDivider = false,
        )
    }
}

/**
 * `.listhead`：内容区顶部的返回行 —— 「‹ 文件夹」chip + 22sp 标题 + 右端 mono 计数。
 *
 * 设计稿的文件夹详情是**双层结构**：顶栏说"你在文件夹页"（标题「文件夹」+
 * 副题「浏览目录内容」），listhead 说"你在哪一层"（层名 + N 项）。
 * 此前两层信息被压成一层，顶栏副标题与 hero 副文案还完全重复。
 */
@Composable
private fun FolderListHead(
    name: String,
    count: Int,
    onBack: (() -> Unit)?,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .npClickable(onClick = onBack)
                    .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = NpIcon.ChevronRight,
                    contentDescription = "返回上一层",
                    tint = ink.muted,
                    // 设计稿 `.listhead .back .xs{transform:rotate(180deg)}`：
                    // 同一个 chevron 转 180° 就是返回箭头
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = 180f },
                )
                Text(
                    text = "文件夹",
                    style = NpTypography.bodyMedium,
                    color = ink.muted,
                    maxLines = 1,
                )
            }
        }
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
            style = NpMonoSmall,
            color = ink.muted,
            maxLines = 1,
        )
    }
}

/** `.folder-head`：色相渐变底 + 54dp 圆角图标 + 名称 + 统计（+ 1px 描边）。 */
@Composable
private fun FolderHead(node: FolderNode) {
    val ink = LocalNpInk.current
    val hue = NpMediaColor.folderHue(node.name)
    val facc = NpMediaColor.accent(hue)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(facc.copy(alpha = 0.16f), ink.surface),
                ),
                shape = RoundedCornerShape(NpRadius.hero),
            )
            // 设计稿 `.folder-head{border:1px solid var(--border)}`
            .border(1.dp, ink.border, RoundedCornerShape(NpRadius.hero))
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = facc.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(NpRadius.card),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NpIcon.Folder,
                    contentDescription = null,
                    tint = facc,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column {
                Text(
                    text = node.name,
                    style = NpTypography.headlineSmall,
                    color = ink.fg,
                    maxLines = 1,
                )
                Text(
                    text = "${node.totalCount} 个媒体文件 · 点击条目开始播放",
                    style = NpTypography.bodySmall,
                    color = ink.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * 自适应网格每格最小宽度：把用户的「列数」偏好重新诠释为**密度档**——
 * 2 列=舒适（大卡）、3 列=紧凑（小卡）。真实列数随窗口宽度连续变化（E47），
 * 平板/折叠展开后自动变多列，不再被 `GridCells.Fixed` 锁死在手机的 2/3 列。
 */
private fun gridMinCell(gridColumns: Int): Dp =
    if (gridColumns <= 2) 176.dp else 120.dp

/**
 * 网格列数：紧凑档恒 2 列（设计稿 `.tier-compact .grid.v{grid-template-columns:repeat(2,1fr)}`），
 * 其余档自适应。
 */
internal fun gridColumnsFor(compact: Boolean, gridColumns: Int): GridCells =
    if (compact) GridCells.Fixed(2) else GridCells.Adaptive(gridMinCell(gridColumns))

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
        FilterKey.UNFINISHED.key -> list.filter { fractionOf(it, positions) in 0.01f..0.99f }
        FilterKey.DONE.key -> list.filter { fractionOf(it, positions) >= 0.99f }
        FilterKey.NEW.key -> list.filter { now - it.addedAtMs < 48 * 60 * 60 * 1000L }
        else -> list
    }
}

/**
 * 控件行（设计稿 `.sec-head` 右侧那三个控件）：筛选 fsel + 伸缩 + 视图切换 + 排序 + 升降序。
 *
 * 升降序用**文字**而不是图标：设计稿的 `I.sortIco` 是"上下双箭头"，正序倒序画的是
 * 同一个字形，只靠颜色区分等于让用户猜。这里给一个 28dp 的文字 pill（升序/降序），
 * 一眼可读，且与左侧 `.fsel-btn` 的圆角描边同族。
 */
@Composable
private fun ControlsRow(
    filter: String,
    onFilter: (String) -> Unit,
    filterEnabled: Boolean,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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
        // 设计稿 viewToggle：网格/列表切换（34dp 图标 seg）
        NpSegmented(
            options = listOf("grid", "list"),
            selected = viewStyle,
            icons = listOf(NpIcon.GridView, NpIcon.ListView),
            itemWidth = 34.dp,
            onSelect = onViewStyle,
        )
        NpFilterSelect(
            label = sortMode.label,
            icon = NpIcon.Sort,
            options = SortMode.entries.map { it.label },
            selected = sortMode.label,
            onSelect = { label ->
                SortMode.entries.firstOrNull { it.label == label }?.let(onSort)
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
    sortMode: SortMode,
    sortAscending: Boolean,
    gridColumns: Int,
    viewStyle: String,
    onViewStyle: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    compact: Boolean,
    onSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onOpenEpisodes: (List<MediaItem>) -> Unit,
) {
    val ink = LocalNpInk.current
    val gutter = if (compact) 14.dp else 20.dp

    val base = remember(all, mode, positions, lastAt) {
        when (mode) {
            "continue" -> all.filter { fractionOf(it, positions) in 0.01f..0.99f }
            // 设计稿 `videos.filter(v => v.prog > 0)`，顺序 = 最近播放时间倒序
            "history" -> all.filter { (positions[it.id] ?: 0L) > 0L }
                .sortedByDescending { lastAt[it.id] ?: 0L }
            "videos" -> all.filter { it.kind == MediaKind.VIDEO }
            "audios" -> all.filter { it.kind == MediaKind.AUDIO }
            // recent = 按加入时间倒序的全量（首页的 take(8) 只是它的前 8 条）
            "recent" -> all.sortedByDescending { it.addedAtMs }
            else -> all
        }
    }
    val shown = remember(base, filter, positions) { filterList(base, filter, positions) }
    val meta = aggregateMeta(mode)
    val isLead = mode == "videos" || mode == "audios"
    // 设计稿 listFoot：多集 = 同目录条目数 > 1（跨文件夹聚合时逐条按目录判定）。
    // 目录计数基于**全量** all 而不是当前聚合列表——剧集数与该聚合的筛选无关。
    val folderCounts = remember(all) { all.groupingBy { it.folder }.eachCount() }
    val multi = remember(shown, folderCounts) { shown.filter { (folderCounts[it.folder] ?: 0) > 1 } }
    val episodes = remember(multi, all) {
        multi.firstOrNull()?.folder?.let { f -> all.filter { it.folder == f } }.orEmpty()
    }

    LazyVerticalGrid(
        columns = gridColumnsFor(compact, gridColumns),
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
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            if (isLead) {
                // listhead：标题 + N 项（返回动作由顶栏返回键承担，
                // 这一行保留设计稿的"标题 + 计数"信息层）
                FolderListHead(name = meta.first, count = shown.size, onBack = null)
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
                    Text(text = "${shown.size} 项", style = NpMonoSmall, color = ink.muted)
                }
            }
        }
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            ControlsRow(
                filter = filter,
                onFilter = onFilter,
                filterEnabled = true,
                viewStyle = viewStyle,
                onViewStyle = onViewStyle,
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSort = onSort,
                onToggleAscending = onToggleAscending,
            )
        }
        item(key = "lead", span = { GridItemSpan(maxLineSpan) }) {
            // 设计稿 `.lead`：列表上方的 13px muted 副标题（"未看完的视频与音频"）
            Text(
                text = meta.second,
                style = NpTypography.bodySmall,
                color = ink.muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (shown.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                NpEmptyState(
                    title = "暂无内容",
                    message = "换个筛选条件看看。",
                )
            }
        }
        if (viewStyle == "list") {
            items(
                shown,
                key = { "${it.sourceId}:${it.id}" },
                span = { GridItemSpan(maxLineSpan) },
            ) { item ->
                MediaListRow(item = item, compact = compact, onOpen = onOpen)
            }
        } else {
            items(shown, key = { "${it.sourceId}:${it.id}" }) { item ->
                MediaCardBody(
                    item = item,
                    onOpen = onOpen,
                    showFolderTag = true,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (multi.isNotEmpty()) {
            item(key = "listfoot", span = { GridItemSpan(maxLineSpan) }) {
                ListFoot(
                    multiCount = multi.size,
                    onOpen = { onOpenEpisodes(episodes) },
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

/** 聚合列表的标题与副标题（设计稿 `moreTitles`）。 */
private fun aggregateMeta(mode: String): Pair<String, String> = when (mode) {
    "continue" -> "继续观看" to "未看完的视频与音频"
    "history" -> "播放历史" to "按最近播放排序"
    "videos" -> "全部视频" to "本地库中的视频文件"
    "audios" -> "全部音频" to "本地库中的纯音频文件"
    else -> "最近添加" to "按加入时间排序"
}

/** 文件夹层级节点（path 为相对路径，如 "Movies/[SAIO-Raws]"；虚拟根为 ""） */
data class FolderNode(
    val path: String,
    val name: String,
    val directVideos: List<MediaItem>,
    val children: List<FolderNode>,
) {
    /** 含所有子层级的媒体（角标与封面用），保持列表排序顺序 */
    val allVideos: List<MediaItem> get() = directVideos + children.flatMap { it.allVideos }
    val totalCount: Int get() = allVideos.size

    /** 只数视频的条目数（设计稿 `.folder .pill` 的「N 个视频」口径） */
    val videoCount: Int get() = allVideos.count { it.kind == MediaKind.VIDEO }
}

private class MutableFolderNode(val path: String) {
    val videos = mutableListOf<MediaItem>()
}

/** 由媒体的相对路径构建文件夹层级树（"A/B/C" → A > B > C，自动补齐中间层） */
private fun buildFolderTree(items: List<MediaItem>): FolderNode {
    val nodes = mutableMapOf<String, MutableFolderNode>()
    fun nodeOf(path: String): MutableFolderNode = nodes.getOrPut(path) { MutableFolderNode(path) }

    items.filter { it.folder != null }.forEach { item ->
        val folder = item.folder!!
        nodeOf(folder).videos += item
        var parent = folder.substringBeforeLast('/', "")
        while (true) {
            nodeOf(parent)
            if (parent.isEmpty()) break
            parent = parent.substringBeforeLast('/', "")
        }
    }

    fun toNode(mutable: MutableFolderNode): FolderNode {
        val children = nodes.values
            .filter { it.path.isNotEmpty() && it.path.substringBeforeLast('/', "") == mutable.path }
            .map { toNode(it) }
            .sortedBy { it.name.lowercase() }
        return FolderNode(
            path = mutable.path,
            name = mutable.path.substringAfterLast('/'),
            directVideos = mutable.videos,
            children = children,
        )
    }
    return toNode(nodeOf(""))
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
    val saved = positions[item.id] ?: return 0f
    if (saved <= 0) return 0f
    return (saved.toFloat() / item.durationMs).coerceIn(0f, 1f)
}

/**
 * 跳转播放页：媒体信息经 URL 编码查询参数传递，由播放页插件解析。
 *
 * E55：编码用 `encode(value, "")` 把允许集显式收到"仅 unreserved 字符"。
 * 单参重载会放行 `& = ? +` 一类分隔符，一个含 `&` 的标题
 * （"Rock & Roll.mkv"）就能把后面的 mime/id 参数整段截掉。
 */
internal fun openPlayer(navigator: Navigator, item: MediaItem) {
    fun enc(value: String) = android.net.Uri.encode(value, "")
    navigator.navigate(
        route = "player?uri=${enc(item.uri)}" +
            "&title=${enc(item.title)}" +
            "&mime=${enc(item.mimeType)}" +
            "&id=${enc(item.id)}",
    )
}
