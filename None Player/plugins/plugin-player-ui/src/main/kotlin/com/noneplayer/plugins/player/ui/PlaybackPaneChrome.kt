package com.noneplayer.plugins.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.ui.api.DetailPaneProvider
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.NpFormHandoff
import com.noneplayer.kernel.ui.api.NpMorphKey
import com.noneplayer.kernel.ui.api.npMorphPart
import com.noneplayer.kernel.ui.api.NpInlinePlayRequest
import com.noneplayer.kernel.ui.api.LocalNpBottomInset
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

/**
 * 类 PC 右栏的**内联播放栏**（§8.2 路径 A）。
 *
 * 解决的问题：宽窗口里"列表"和"画面"原本只能二选一——要么进独占播放页看不见列表，
 * 要么退回列表就没地方看画面。外壳在 `isDesktopLike` 档把窗口切成三栏后，
 * 这一栏负责右侧那一栏：画面常驻，浏览列表时播放不中断。
 *
 * 渲染直接复用 [InlinePlayerSurface]（详情页内嵌播放的同一份实现）：
 * 装载走同一条 `ensureSessionPlaying` 链（同文件夹队列 / 断点 / 字幕记忆 / 倍速），
 * 控制层、播放选项与仅音频入口复用同一份实现，不另起一份播放器。
 *
 * 点「全屏」导航到独占播放页：会话已在播同一媒体，落页只续播不重启（B1 语义），
 * 与迷你条点回播放页走的是同一条深链参数形态（uri/title/mime/id 齐全）。
 *
 * **顶部栏（2026-10-07 用户实报新增）**：返回主页/切页不关闭右栏是路径 A 的定案，
 * 但面板必须自带一个显式的退出点——顶部栏左键就是「停止播放」（停会话、
 * 清队列，`nowPlaying` 变 null 后外壳收回该栏，迷你条一并消失）。
 * 图标与描述按**实际动作**给（E129 / 审查 #13）：停止用 ✕ 与「停止播放」，
 * 不再借用返回箭头的形状去表达一个破坏性动作。
 * 栏体走 `npGlassSurface` 完整配方（§4.1 横条一律不透明底 + 发丝线）。
 */
class PlaybackPaneChrome : DetailPaneProvider {

    override val order: Int = 0

    /** 与详情页内嵌播放同一份引擎实现；本身无状态，可作为实例字段持有 */
    private val surface = InlinePlayerSurface()

    @Composable
    override fun isOccupied(): Boolean {
        val session = sessionOf()
        // E120：这里只问"有没有在播"，却要订 nowPlaying——它每 250ms 随进度发新值，
        // 于是整个类 PC 外壳（含媒体库那一栏）跟着进度 4/s 失效。改订位置无关的摘要。
        val queue by session.queue.collectAsStateWithLifecycle()
        val index by session.index.collectAsStateWithLifecycle()
        val started by session.playbackStarted.collectAsStateWithLifecycle()
        return isPaneOccupied(queue, index, started)
    }

    @Composable
    override fun showsBottomChrome(): Boolean {
        val audioOnly by sessionOf().audioOnly.collectAsStateWithLifecycle()
        return audioOnly
    }

    @Composable
    override fun Render(navigator: Navigator, modifier: Modifier) {
        val session = sessionOf()
        LaunchedEffect(session) {
            NpInlinePlayRequest.flow.filterNotNull().collect { tick ->
                val item = tick.first.item
                val queue = session.queue.value
                val index = queue.indexOfFirst { it.id.isNotEmpty() && it.id == item.id }
                    .takeIf { it >= 0 }
                    ?: queue.indexOfFirst { it.uri == item.uri }
                if (index >= 0) {
                    // 同一文件夹里换集：直接跳队列，不要整表重新 prepare。
                    // 先切换再消费，效应被取消时请求还在，下一次组合能重放。
                    session.selectQueueIndex(index)
                } else {
                    session.startPlayback(item)
                }
                if (tick.first.audioOnly) session.setAudioOnly(true)
                NpInlinePlayRequest.consume(tick)
            }
        }
        val currentSnapshot = session.nowPlayingSummary.collectAsStateWithLifecycle().value
        val audioOnly by session.audioOnly.collectAsStateWithLifecycle()
        var lastSnapshot by remember { mutableStateOf<NowPlaying?>(null) }
        SideEffect { if (currentSnapshot != null) lastSnapshot = currentSnapshot }
        val snapshot = currentSnapshot ?: lastSnapshot ?: return
        val ink = LocalNpInk.current
        // HDR 概况来自会话播放器的起播探测；换播放器/条目时重新订（默认 null）
        val hdrFlow = remember(session, snapshot.item.uri) {
            session.player?.hdrLabel ?: MutableStateFlow<String?>(null)
        }
        val hdr by hdrFlow.collectAsStateWithLifecycle()
        // 右栏的画面只在**类 PC 档**存在（外壳用 `isDesktopLike` 把关）。平板转竖屏那一瞬
        // 外壳收回这一栏、Surface 随之释放——会话还在跑，屏幕上却只剩底部迷你条，
        // 用户读成"视频没继续播、掉进后台播放了"（2026-10-14 真机实报）。
        // 收回之前先把它交给竖屏的播放页：不带 `fs=1`（那会反向请求横向，跟刚发生的
        // 转屏对着干），落页只续播不重启（B1 语义）。仅听音频不交接——它本就有迷你条
        // 与完整音频页两个承载者，画面不是它的全部内容。
        val windowInfo = LocalNpWindowInfo.current
        // 右栏的画面只在类 PC 档存在。离开这一档时，播放页要在第一帧竖屏画出来之前就
        // 进栈（Activity 转屏回调读这里登记的路线）。晚一帧会先画出首页和剧集封面，
        // 右栏 Surface 与播放页 Surface 叠成一大一小两层画面。仅听音频不交接。
        val handoffRoute = if (
            windowInfo.isDesktopLike &&
            currentSnapshot != null &&
            !audioOnly &&
            snapshot.item.kind != MediaKind.AUDIO
        ) {
            "player?uri=${encode(snapshot.item.uri)}" +
                "&title=${encode(snapshot.title)}" +
                "&mime=${encode(snapshot.item.mimeType)}" +
                "&id=${encode(snapshot.item.id)}"
        } else {
            null
        }
        val handOffToPlayerPage = handoffRoute == null &&
            currentSnapshot != null &&
            !audioOnly &&
            snapshot.item.kind != MediaKind.AUDIO
        SideEffect { NpFormHandoff.offerPaneRoute(handoffRoute) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                // 用户点全屏/停播离开右栏时清**自己这条**路线。转屏交接已经把 wasDesktop
                // 写成 false，不会误清；详情页那条归媒体库，这里不碰。
                NpFormHandoff.clearPaneRouteWhileDesktop()
            }
        }
        Column(
            modifier = modifier
                .background(ink.bg)
                // 右栏通到屏幕顶（外壳三栏是 edge-to-edge 布局），顶栏必须自己
                // 让开状态栏，否则标题/返回键与状态栏图标同高打架（实拍确认）
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NpDimens.paneBarHeight)
                    .npGlassSurface(ink, topHighlight = false, bottomHairline = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NpIconButton(
                    // E129（审查 #13）：这里此前画的是 `ArrowLeft`——**返回/收起**的视觉语言，
                    // 点下去却是 `session.stop()`：停播、清队列、迷你条一并消失。图标承诺的
                    // 是"退一层"，实际是"结束这件事"，两者不是一回事，队列越长越像误触。
                    // 动作既然是停止，就给它停止的形状（与迷你条 ✕ 同一枚）与同一句描述。
                    icon = NpIcon.Close,
                    contentDescription = "停止播放",
                    onClick = {
                        // 关闭 = 停止会话（断点先落盘、队列清空）：
                        // nowPlaying → null 后 isOccupied 翻 false，外壳收回右栏，
                        // 迷你条同步消失——与迷你条 ✕ 是同一个出口。
                        session.stop()
                    },
                    modifier = Modifier
                        .padding(start = NpSpace.sm)
                        // 横屏的 ✕ 与竖屏顶栏的返回箭头是同一个出口 → 同一个 key（E133）
                        .npMorphPart(NpMorphKey.Dismiss, desktop = true),
                )
                Text(
                    text = if (audioOnly || snapshot.item.kind == MediaKind.AUDIO) "正在播放" else snapshot.title,
                    style = NpTypography.titleSmall,
                    color = ink.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 标题在两侧同名：转屏时它从右栏栏头飞到竖屏顶栏，不重排样式
                    modifier = Modifier.weight(1f).npMorphPart(NpMorphKey.Title, desktop = true),
                )
                // 收藏不在这里：横屏的收藏归左侧那一横排动作（与参考实现同一口径），
                // 栏上再放一颗心会让同一个动作在屏上有两个入口、且挡住画面右上角。
                if (audioOnly || snapshot.item.kind == MediaKind.AUDIO) {
                    NpIconButton(
                        icon = NpIcon.Expand,
                        contentDescription = "打开完整音频播放",
                        onClick = { openAudio(navigator, snapshot) },
                    )
                } else {
                    Spacer(modifier = Modifier.width(NpSpace.lg))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                // 这里不再盖"交接过来的最后一帧"：画面是同一个 TextureView，
                // 整屏 morph 把它从竖屏舞台的位置直接飞到右栏的落点（E133），
                // 没有"新 Surface 还没出画"的空档需要顶。
                if (handOffToPlayerPage) return@Box
                if (currentSnapshot == null) {
                    // Exit keeps only a static cover. Never remount a stopped playback surface.
                    AsyncImage(
                        model = snapshot.coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    return@Box
                }
                if (audioOnly || snapshot.item.kind == MediaKind.AUDIO) {
                    AudioPane(
                        session = session,
                        onOpen = { openAudio(navigator, snapshot) },
                        showOpenButton = false,
                        modifier = Modifier.fillMaxSize().padding(bottom = LocalNpBottomInset.current),
                    )
                    return@Box
                }
                // E101：走"只接管画面"的入口——右栏挂载不得改写播放状态
                // （用户在播放页暂停后退回浏览态，暂停必须保持；结束态也不退位）。
                surface.InlinePlayerAttached(
                    media = snapshot.item,
                    navigator = navigator,
                    onFullscreen = {
                        // `fs=1` = 明确的全屏意图：全屏只有沉浸一种（假全屏已删除）
                        navigator.navigate(
                            "player?uri=${encode(snapshot.item.uri)}" +
                                "&title=${encode(snapshot.title)}" +
                                "&mime=${encode(snapshot.item.mimeType)}" +
                                "&id=${encode(snapshot.item.id)}" +
                                "&fs=1",
                        )
                    },
                    // 画面矩形由内层 `InlinePlayerAttached` 报（画面本体，不含承载它的
                    // 黑边/留白）；这里再报一遍整栏会把那份登记覆盖成含黑边的大框。
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 视频下方信息行：手机播放页同款 mono 元数据（大小 · 分辨率 · HDR · 容器 · 文件夹），
            // 一排横向、文件夹收尾——此前平板横屏右栏的视频下方是一整片空白（用户要求补上）。
            if (!audioOnly && snapshot.item.kind != MediaKind.AUDIO) {
                val meta = paneMetaLine(snapshot.item, hdr)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = NpMonoSmall,
                        color = ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun sessionOf() = SessionHolder.of(sessionFactoryOf(LocalKernel.current.services))

    /**
     * 转义路由参数：标题含 `&`/`=`/中文/空格时裸露拼进 route 会截断参数。
     *
     * 用 `Uri.encode(value, "")` 与媒体库那条 `openPlayer` 同一条编码约定（E55）：
     * `URLEncoder` 把空格写成 `+`，而 `+` 只有在"表单解码"那一路才被还原成空格，
     * 查询参数按 URI 规范解码时它就是个加号。
     */
    private fun encode(value: String): String = android.net.Uri.encode(value, "")

    private fun openAudio(navigator: Navigator, snapshot: NowPlaying) {
        navigator.navigate(
            "player?uri=${encode(snapshot.item.uri)}" +
                "&title=${encode(snapshot.title)}" +
                "&mime=${encode(snapshot.item.mimeType)}" +
                "&id=${encode(snapshot.item.id)}&audio=1",
        )
    }

    /** 右栏视频下方的单行元数据：大小 · 分辨率 · HDR · 容器 · 文件夹（文件夹收尾） */
    private fun paneMetaLine(item: MediaItem, hdr: String?): String {
        val ext = item.title
            .substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 5 }
            ?.uppercase()
        val container = ext
            ?: item.mimeType.substringAfter('/', "").uppercase().takeIf { it.isNotBlank() }
        return buildList {
            if (item.sizeBytes > 0) add(formatFileSize(item.sizeBytes))
            item.resolutionLabel.takeIf { it.isNotBlank() }?.let { add(it) }
            hdr?.takeIf { it.isNotBlank() }?.let { add(it) }
            container?.let { add(it) }
            item.folder?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
    }
}

/**
 * 右栏占用的判据（纯函数，便于单测）：**有当前项** 且 **真的播过**。
 *
 * 只看"有当前项"会把竖屏播放页的**未播放态**（路由 `play=0` 只装载不起播）也算成占用：
 * 用户从竖屏的剧集详情转横屏，右栏直接展开一个停在封面的播放面板，而他要看的是
 * 左栏那份剧集详情（2026-10-15 真机实报"竖屏未播放转横屏直接打开右侧栏"）。
 * 反面同样要保住：播放过再暂停/播完的会话**仍是**占用——右栏的常驻语义就是
 * "陪着你浏览"，暂停不该把它收走（E101）。
 */
internal fun isPaneOccupied(
    queue: List<MediaItem>,
    index: Int,
    playbackStarted: Boolean,
): Boolean = playbackStarted && queue.getOrNull(index) != null

@Composable
internal fun AudioPane(
    session: PlaybackSession,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showOpenButton: Boolean = true,
) {
    val services = LocalKernel.current.services
    val history = remember(services) { services.get<HistoryRepository>() }
    val scope = rememberCoroutineScope()
    val snapshot by session.nowPlayingSummary.collectAsStateWithLifecycle()
    val item = snapshot?.item ?: return
    val player = session.player ?: return
    val favoriteKeys by history.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val speed by player.speed.collectAsStateWithLifecycle()
    val queue by session.queue.collectAsStateWithLifecycle()
    val queueIndex by session.index.collectAsStateWithLifecycle()
    var speedPickerVisible by remember(item.uri) { mutableStateOf(false) }
    var queueVisible by remember(item.uri) { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(speedPickerVisible) { speedPickerVisible = false }
    Box(modifier) {
        AudioOnlyScreen(
            session = session,
            onBack = onOpen,
            onReturnToVideo = if (item.kind == MediaKind.AUDIO) null else {
                { session.setAudioOnly(false) }
            },
            favorited = HistoryEntry.keyOf(item) in favoriteKeys,
            onToggleFavorite = { scope.launch { history.toggleFavorite(item) } },
            onOpenSpeed = { speedPickerVisible = !speedPickerVisible },
            speed = speed,
            speedText = "${speed}x",
            speedPickerVisible = speedPickerVisible,
            onPickSpeed = { next ->
                // D4/E198：倍速会话内生效、不持久化（见 PlayerScreen.pickSpeed）
                player.setSpeed(next)
                speedPickerVisible = false
            },
            onOpenEpisodes = { queueVisible = true },
            embedded = true,
        )
        if (showOpenButton) {
            NpIconButton(
                icon = NpIcon.Expand,
                contentDescription = "打开完整音频播放",
                onClick = onOpen,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        PanelExitHost(visible = queueVisible, onRemoved = { queueVisible = false }) { visible ->
            androidx.activity.compose.BackHandler { queueVisible = false }
            EpisodePanel(
                visible = visible,
                queue = queue,
                currentIndex = queueIndex,
                onSelect = {
                    session.selectQueueIndex(it)
                    queueVisible = false
                },
                onDismiss = { queueVisible = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
