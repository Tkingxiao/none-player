package com.noneplayer.plugins.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LibraryView
import com.noneplayer.core.data.SortMode
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.NpEmptyState
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 媒体库页面。
 *
 * 通过 MediaSourceProvider 扩展点聚合所有来源的媒体列表；
 * 排序方式/列数/视图经 AppSettings 持久化；扫描目录未选择时引导选择
 * （不选择文件夹不扫描）。
 *
 * [forcedView] 供 Tab 使用：指定后视图固定（如"文件夹"Tab），
 * 且 ⋮ 菜单不再显示视图切换项；null 时跟随用户持久化偏好。
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

    // ---- 用户偏好（持久化） ----
    val sortMode by settings.sortMode.collectAsStateWithLifecycle(SortMode.ADDED)
    val sortAscending by settings.sortAscending.collectAsStateWithLifecycle(false)
    val gridColumns by settings.gridColumns.collectAsStateWithLifecycle(2)
    val folderUris by settings.scanFolderUris.collectAsStateWithLifecycle(initialValue = emptyList())
    val prefView by settings.libraryView.collectAsStateWithLifecycle(LibraryView.VIDEOS)
    // forcedView 供 Tab 固定视图；普通入口跟随用户持久化偏好
    val viewMode = forcedView ?: prefView
    // B5 修复：rememberSaveable，横竖屏切换/进程回收后文件夹选中态不丢
    var selectedFolder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }

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

    // E54：文件夹层级是页内状态（不入返回栈），系统返回必须在此消费，
    // 否则返回事件落到导航层 → 切走 Tab / 弹回首页，用户看到"退出了文件夹页"。
    BackHandler(enabled = viewMode == LibraryView.FOLDERS && selectedFolder != null) {
        selectedFolder = selectedFolder?.substringBeforeLast('/', "")?.ifEmpty { null }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "媒体库",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val countText = when {
                    viewMode == LibraryView.FOLDERS && selectedFolder == null ->
                        "${folderRoot.children.size} 个文件夹"
                    viewMode == LibraryView.FOLDERS -> "${currentFolder?.totalCount ?: 0} 个视频"
                    else -> "${items.size} 个视频"
                }
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) {
                SortMenu(
                    sortMode = sortMode,
                    sortAscending = sortAscending,
                    gridColumns = gridColumns,
                    showViewToggle = forcedView == null,
                    onSelectSort = { scope.launch { settings.setSortMode(it) } },
                    onToggleAscending = { asc -> scope.launch { settings.setSortAscending(asc) } },
                    onSelectColumns = { scope.launch { settings.setGridColumns(it) } },
                    onToggleView = { view -> scope.launch { settings.setLibraryView(view) } },
                )
            }
            IconButton(
                onClick = { navigator.navigate("kernel.settings") },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            sources.isEmpty() -> NpEmptyState(
                title = "还没有视频",
                message = "安装媒体来源插件后，视频会出现在这里。",
            )

            missingPermission -> NpEmptyState(
                title = "需要媒体权限",
                message = "None Player 需要访问设备上的视频，才能在这里展示和播放。",
                actionLabel = "授予媒体权限",
                onAction = { permissionLauncher.launch(permissionStrings.toTypedArray()) },
            )

            folderUris.isEmpty() -> NpEmptyState(
                title = "选择要观看的文件夹",
                message = "选择一个媒体文件夹，None Player 只扫描你指定的位置（可在设置中添加多个）。",
                actionLabel = "选择媒体文件夹",
                onAction = { folderPicker.launch(null) },
            )

            // E78：媒体未加载完（mediaLoaded=false）时不判定"空库"，
            // 否则进入本页的首帧会闪一下「媒体库是空的」再跳出内容。
            mediaLoaded && items.isEmpty() -> NpEmptyState(
                title = "媒体库是空的",
                message = "扫描所选文件夹中的视频，建立你的媒体库。",
                actionLabel = if (requestedScan) null else "开始扫描",
                onAction = if (requestedScan) null else {
                    { requestedScan = true; scanAll() }
                },
            )

            else -> when {
                // E31（B3 收尾）：无顶层文件夹时给出明确说明再展示视频墙，不再"静默"回落
                viewMode == LibraryView.FOLDERS && selectedFolder == null && folderRoot.children.isEmpty() -> Column {
                    FolderFallbackHint()
                    MediaGrid(items = sortedItems, columns = gridColumns, onOpen = { item -> openPlayer(navigator, item) })
                }
                viewMode == LibraryView.FOLDERS -> Column {
                    val node = currentFolder ?: folderRoot
                    if (node.path.isNotEmpty()) {
                        val parentPath = node.path.substringBeforeLast('/', "")
                        Text(
                            text = "← " + (parentPath.substringAfterLast('/').ifEmpty { "全部文件夹" }),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { selectedFolder = parentPath.ifEmpty { null } }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    FolderContentsGrid(
                        node = node,
                        columns = gridColumns,
                        onOpenFolder = { selectedFolder = it.path },
                        onOpenVideo = { item -> openPlayer(navigator, item) },
                    )
                }
                else -> MediaGrid(items = sortedItems, columns = gridColumns, onOpen = { item -> openPlayer(navigator, item) })
            }
        }
    }
}

/**
 * 文件夹视图无子文件夹分组时的内联说明条（E31）：
 * 明确告知"已按全部视频展示"，避免用户误判「文件夹」视图失效。
 */
@Composable
private fun FolderFallbackHint() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "该目录下没有子文件夹，已按全部视频展示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 排序与布局菜单（右上 More：视图 / 排序方式 / 升降序 / 列数） */
@Composable
private fun SortMenu(
    sortMode: SortMode,
    sortAscending: Boolean,
    gridColumns: Int,
    showViewToggle: Boolean,
    onSelectSort: (SortMode) -> Unit,
    onToggleAscending: (Boolean) -> Unit,
    onSelectColumns: (Int) -> Unit,
    onToggleView: (LibraryView) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "排序与布局",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showViewToggle) {
                DropdownMenuItem(
                    text = { Text("按文件夹浏览") },
                    onClick = {
                        onToggleView(LibraryView.FOLDERS)
                        expanded = false
                    },
                )
                androidx.compose.material3.HorizontalDivider()
            }
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text("按${mode.label}排序") },
                    trailingIcon = {
                        if (mode == sortMode) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        onSelectSort(mode)
                        expanded = false
                    },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (sortAscending) "改为降序" else "改为升序") },
                onClick = {
                    onToggleAscending(!sortAscending)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(if (gridColumns == 2) "切换为三列" else "切换为双列") },
                onClick = {
                    onSelectColumns(if (gridColumns == 2) 3 else 2)
                    expanded = false
                },
            )
        }
    }
}

/** 文件夹层级节点（path 为相对路径，如 "Movies/[SAIO-Raws]"；虚拟根为 ""） */
data class FolderNode(
    val path: String,
    val name: String,
    val directVideos: List<MediaItem>,
    val children: List<FolderNode>,
) {
    /** 含所有子层级的视频（角标与封面用），保持列表排序顺序 */
    val allVideos: List<MediaItem> get() = directVideos + children.flatMap { it.allVideos }
    val totalCount: Int get() = allVideos.size
}

private class MutableFolderNode(val path: String) {
    val videos = mutableListOf<MediaItem>()
}

/** 由视频的相对路径构建文件夹层级树（"A/B/C" → A > B > C，自动补齐中间层） */
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

/** 文件夹内容网格：子文件夹卡片在前、本级视频在后（同一网格滚动） */
@Composable
private fun FolderContentsGrid(
    node: FolderNode,
    columns: Int,
    onOpenFolder: (FolderNode) -> Unit,
    onOpenVideo: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(node.children, key = { "folder:${it.path}" }) { child ->
            FolderCard(node = child, modifier = Modifier.animateItem(), onClick = { onOpenFolder(child) })
        }
        items(node.directVideos, key = { "video:${it.sourceId}:${it.id}" }) { item ->
            MediaCard(item = item, modifier = Modifier.animateItem(), onClick = { onOpenVideo(item) })
        }
    }
}

@Composable
private fun FolderCard(node: FolderNode, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.npPressScale(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // 封面取该文件夹（含子层级）有缩略图的最新视频；全无缩略图时显示 ⊘ 标记
            val cover = node.allVideos.firstOrNull { it.coverUri != null }?.coverUri
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = node.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.noneplayer.core.ui.NoneMark(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                }
            }
            // 右上角数量角标（含子层级总数）
            Text(
                text = "${node.totalCount}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            text = node.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = node.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun MediaGrid(items: List<MediaItem>, columns: Int, onOpen: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(items, key = { "${it.sourceId}:${it.id}" }) { item ->
            MediaCard(
                item = item,
                modifier = Modifier.animateItem(),
                onClick = { onOpen(item) },
            )
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(modifier = modifier.npPressScale(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // 优先读扫描时缓存的系统缩略图文件；无缓存时现场抽帧兜底
            val model = if (item.coverUri != null) {
                ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUri)
                    .crossfade(180)
                    .build()
            } else {
                ImageRequest.Builder(LocalContext.current)
                    .data(item.uri)
                    .videoFrameMillis(0)
                    .crossfade(220)
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.durationMs > 0) {
                // 底部渐变 scrim，保证角标在任何封面上可读
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                            ),
                        ),
                )
                Text(
                    text = formatDuration(item.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val meta = formatSize(item.sizeBytes)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 跳转播放页：媒体信息经 URL 编码查询参数传递，由播放页插件解析。
 *
 * E55：编码用 `encode(value, "")` 把允许集显式收到"仅 unreserved 字符"。
 * 单参重载会放行 `& = ? +` 一类分隔符，一个含 `&` 的标题
 * （"Rock & Roll.mkv"）就能把后面的 mime/id 参数整段截掉。
 */
private fun openPlayer(navigator: Navigator, item: MediaItem) {
    fun enc(value: String) = android.net.Uri.encode(value, "")
    navigator.navigate(
        route = "player?uri=${enc(item.uri)}" +
            "&title=${enc(item.title)}" +
            "&mime=${enc(item.mimeType)}" +
            "&id=${enc(item.id)}",
    )
}

/** 时长展示：h:mm:ss / mm:ss；未知为空 */
internal fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val totalSeconds = durationMs / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 文件大小展示 */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
