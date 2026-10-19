package com.noneplayer.plugins.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.DEFAULT_SUBTITLE_COLOR_ARGB
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.kind
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpToastHost
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.rememberNpToastState
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npAnchor
import com.noneplayer.core.ui.rememberNpAnchorHolder
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.InlinePlaybackProvider
import com.noneplayer.kernel.ui.api.NpFormHandoff
import com.noneplayer.kernel.ui.api.NpMorph
import com.noneplayer.kernel.ui.api.NpMorphKey
import com.noneplayer.kernel.ui.api.npMorphPart
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import com.noneplayer.kernel.ui.api.PlaybackMenuContributor
import com.noneplayer.plugins.player.ui.brightness.rememberWindowBrightness
import kotlinx.coroutines.flow.first
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 内嵌控制层自动隐藏延时（与播放页同一节拍） */
private const val INLINE_CONTROLS_AUTO_HIDE_MS = 3_000L

/**
 * 「内嵌播放器」的引擎侧实现（[InlinePlaybackProvider]）——详情页封面区里直接播放。
 *
 * 这是平板/桌面上市面播放器的标准交互：详情页开播 → 全屏键才进独占全屏 →
 * 退出全屏回到详情页、画面仍在原处续播（会话是同一个，落页只续播不重启）。
 *
 * 装载走 **SessionBootstrap.ensureSessionPlaying** ——与播放页同一条链
 * （同文件夹队列 / 断点 / 字幕记忆 / 倍速），不另起一份。
 * 控制层提供播放、进度、全屏与仅音频入口；倍速/字幕/音轨/画面复用独占播放页菜单。
 */
internal class InlinePlayerSurface : InlinePlaybackProvider {

    @Composable
    override fun InlinePlayer(
        media: MediaItem,
        navigator: Navigator,
        onFullscreen: () -> Unit,
        modifier: Modifier,
    ) = InlinePlayerView(media, navigator, onFullscreen, modifier, autoPlay = true)

    /**
     * 类 PC 右栏专用入口（E101）：**只接管画面，不动播放状态**。
     *
     * 与 [InlinePlayer] 只差"装载时要不要起播"：右栏是常驻的"陪你看"，不是一次播放意图——
     * 用户刚在播放页暂停、退回浏览态，右栏一挂载就 `play()` 会把暂停顶掉（模拟器实测）。
     * 接口方法（[InlinePlaybackProvider.InlinePlayer]）不能带默认参数（§二.1 的
     * `AbstractMethodError` 约束），所以另开一个具名入口而不是给接口加开关。
     */
    @Composable
    fun InlinePlayerAttached(
        media: MediaItem,
        navigator: Navigator,
        onFullscreen: () -> Unit,
        modifier: Modifier,
    ) = InlinePlayerView(media, navigator, onFullscreen, modifier, autoPlay = false)

    @Composable
    private fun InlinePlayerView(
        media: MediaItem,
        navigator: Navigator,
        onFullscreen: () -> Unit,
        modifier: Modifier,
        autoPlay: Boolean,
    ) {
        val kernel = LocalKernel.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val settings = remember { kernel.services.get<AppSettings>() }
        val history = remember { kernel.services.get<HistoryRepository>() }
        val toast = rememberNpToastState()
        val activity = context as? androidx.activity.ComponentActivity
        val session = remember { SessionHolder.of(sessionFactoryOf(kernel.services)) }
        val player = remember(session, media.mimeType) { session.acquire(media.mimeType) }
        // E88 留尾：无引擎（插件未启用 / 未知 mime）不再主线程硬抛——内嵌栏渲染
        // 一块黑底错误文案；装载链不启动（player 为 null 时没有引擎可装载）。
        val engineError by session.engineError.collectAsStateWithLifecycle()
        if (player == null) {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = engineError ?: "无法创建播放引擎",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
            return
        }

        // A passive pane can briefly receive the outgoing item's snapshot during navigation.
        // It must not cancel the incoming load or retry a failed item merely by mounting.
        var mediaOpened by remember(media.id, media.uri) { mutableStateOf(false) }
        LaunchedEffect(media.id, media.uri, autoPlay) {
            if (autoPlay) {
                val queue = session.queue.value
                val index = queue.indexOfFirst { it.id.isNotEmpty() && it.id == media.id }
                    .takeIf { it >= 0 }
                    ?: queue.indexOfFirst { it.uri == media.uri }
                val sameIndex = index >= 0 && session.index.value == index
                when {
                    // 同一文件夹里换集：直接跳队列，不要整表重新 prepare。
                    // 先切换再消费，效应被取消时请求还在，下一次组合能重放。
                    index >= 0 && !sameIndex -> session.selectQueueIndex(index)
                    // 会话已停在这一项上、且**一次都没播过**（"只装载不起播"的未播放态）：
                    // 这个入口只在用户点播放时挂载（autoPlay=true 只来自详情页的播放键），
                    // 点位就是播放意图，必须落成一次续播——否则用户按下播放毫无反应
                    // （2026-10-15 真机实测：横屏详情页点「继续观看」）。
                    // 已经播过的会话不在这里动手：退全屏回来重挂载要保持暂停（E104）。
                    sameIndex && !session.playbackStarted.value -> session.startPlayback(item = media)
                    // 会话里没有这一项：整条装载链（同文件夹队列 / 断点 / 字幕记忆）
                    index < 0 -> session.startPlayback(item = media)
                }
            }
            mediaOpened = true
        }

        val state by player.state.collectAsStateWithLifecycle()
        val audioOnly by session.audioOnly.collectAsStateWithLifecycle()
        val summary by session.nowPlayingSummary.collectAsStateWithLifecycle()
        val videoAspectRatio by player.videoAspectRatio.collectAsStateWithLifecycle()
        // A6：Cue 不在这一层解包——订阅点下沉到 VideoStage 里的 PlayerSubtitleLayer
        val overlays by remember { kernel.registry.observe<PlaybackOverlay>() }
            .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackOverlay>())
        // 字幕样式与独占播放页读同一份设置——此前内嵌画面不传 subtitleStyle，
        // 落到 SubtitleStyle() 默认值（1.0× 白字恒开），用户在「字幕设置」里
        // 调过的字号/颜色/开关一进详情页就"复原"（用户实报"视频里的字幕大小
        // 和设置不统一"）。E131 的面板在哪里调，画面在哪里都得是同一份。
        val subtitleEnabled by settings.subtitleEnabled.collectAsStateWithLifecycle(initialValue = true)
        val subtitleColorArgb by settings.subtitleColorArgb
            .collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_COLOR_ARGB)
        val subtitleScale by settings.subtitleScale.collectAsStateWithLifecycle(initialValue = 1f)
        val subtitleItalic by settings.subtitleItalic.collectAsStateWithLifecycle(initialValue = false)
        val subtitleStyle = SubtitleStyle(
            enabled = subtitleEnabled,
            colorArgb = subtitleColorArgb,
            scale = subtitleScale,
            italic = subtitleItalic,
        )
        LaunchedEffect(player, subtitleEnabled) { player.setTextTracksEnabled(subtitleEnabled) }

        var controlsVisible by remember { mutableStateOf(true) }
        var userDragging by remember { mutableStateOf(false) }
        var dragPositionMs by remember { mutableFloatStateOf(0f) }
        var scrubWasPlaying by remember { mutableStateOf(false) }
        var menuPage by remember(media.uri) { mutableStateOf<FsMenuPage?>(null) }
        var menuRoot by remember(media.uri) { mutableStateOf(FsMenuPage.MAIN) }
        var activeAnchor by remember { mutableStateOf(FsMenuAnchor(Rect.Zero)) }
        var locked by remember(media.uri) { mutableStateOf(false) }
        val menuAnchor = rememberNpAnchorHolder()
        var speedAnchor by remember { mutableStateOf(Rect.Zero) }
        var subtitleAnchor by remember { mutableStateOf(Rect.Zero) }
        val episodesAnchor = rememberNpAnchorHolder()
        var seekAnchor by remember { mutableStateOf(Rect.Zero) }
        val contributors by remember { kernel.registry.observe<PlaybackMenuContributor>() }
            .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackMenuContributor>())
        val speed by player.speed.collectAsStateWithLifecycle()
        val tracks by player.tracks.collectAsStateWithLifecycle()
        val chapters by player.chapters.collectAsStateWithLifecycle()
        val queue by session.queue.collectAsStateWithLifecycle()
        val queueIndex by session.index.collectAsStateWithLifecycle()
        val loopMode by session.loopMode.collectAsStateWithLifecycle()
        val favorites by history.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
        val favorited = HistoryEntry.keyOf(media) in favorites
        val sleepEndsAt by session.sleepTimerEndsAtMs.collectAsStateWithLifecycle()
        val screenshotBlocked by settings.screenshotBlocked.collectAsStateWithLifecycle(initialValue = false)
        val surfaceRef = remember { mutableStateOf<android.view.TextureView?>(null) }
        // 转屏交接不再从这里抓帧：画面是同一个 TextureView，整屏 morph 直接把它
        // 从右栏的位置搬到竖屏舞台的位置（E133，见 NpMorph），所以 FLAG_SECURE、
        // "Surface 还没出画抓不到"这一类兜底分支一并消失。
        val audioManager = remember {
            context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        }
        val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
        var volume by remember {
            mutableIntStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / maxVolume)
        }
        // A8：亮度统一走共享助手——进页面捕获窗口原值（含"无覆盖 -1f"）、离开精确写回、
        // 读数优先窗口 override 再读系统亮度。旧实现把"无覆盖"兜底成 62%，第一帧就是假值。
        val windowBrightness = rememberWindowBrightness(activity)
        var brightness by windowBrightness.percent
        // 截屏开关只负责 FLAG_SECURE，不再顺手做亮度恢复（A8）：
        // 旧实现把"进页面时的窗口亮度"塞在这条 DisposableEffect 里，收起右栏/翻转截屏开关
        // 都会走 onDispose→写回旧值，表现为"亮度突然跳回"。
        DisposableEffect(activity, screenshotBlocked) {
            val window = activity?.window
            if (screenshotBlocked) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val mediaKey = mediaMemoryKey(media.sourceId, media.id)
        val savedSubtitle by remember(mediaKey) { settings.subtitleUri(mediaKey) }
            .collectAsStateWithLifecycle(initialValue = null)
        val subtitleOffsetMs by session.subtitleOffsetMs.collectAsStateWithLifecycle()
        var scaleMode by remember(media.uri) { mutableStateOf(VideoScaleMode.FIT) }
        var mirrored by remember(media.uri) { mutableStateOf(false) }
        var rotation by remember(media.uri) { mutableIntStateOf(0) }
        LaunchedEffect(mediaKey) {
            val (mode, mirror, rot) = parseDisplaySettings(settings.displaySettings(mediaKey).first())
            scaleMode = mode
            mirrored = mirror
            rotation = rot
        }
        LaunchedEffect(scaleMode) { player.setVideoCrop(scaleMode == VideoScaleMode.CROP) }
        fun updateDisplay(mode: VideoScaleMode = scaleMode, mirror: Boolean = mirrored, rot: Int = rotation) {
            scaleMode = mode
            mirrored = mirror
            rotation = rot
            if (mediaKey.isNotEmpty()) scope.launch {
                settings.setDisplaySettings(mediaKey, serializeDisplaySettings(mode, mirror, rot))
            }
        }
        fun pickSpeed(value: Float) {
            val next = value.coerceIn(SPEED_FINE_RANGE)
            player.setSpeed(next)
            scope.launch { settings.setPlaybackSpeed(next) }
        }
        val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val prepared = prepareSubtitleUri(context, uri)
                player.setSubtitle(prepared.uri, prepared.format)
                player.setTextTracksEnabled(true)
                settings.setSubtitleEnabled(true)
                if (mediaKey.isNotEmpty()) settings.setSubtitleUri(mediaKey, uri.toString())
            }
        }
        fun openAudioOnly() {
            session.setAudioOnly(true)
            navigator.navigate(
                "player?uri=${android.net.Uri.encode(media.uri, "")}" +
                    "&title=${android.net.Uri.encode(media.title, "")}" +
                    "&mime=${android.net.Uri.encode(media.mimeType, "")}" +
                    "&id=${android.net.Uri.encode(media.id, "")}&audio=1",
            )
        }
        fun openMenu(page: FsMenuPage, rect: Rect, above: Boolean = false) {
            menuRoot = page
            activeAnchor = FsMenuAnchor(
                rect, preferAbove = above,
                aboveLimitRect = if (above) seekAnchor else null,
            )
            menuPage = page
            controlsVisible = true
        }

        // 播放中控制层自动隐藏；拖动进度条期间不隐藏。
        // §8.4：指针环境（类 PC 窗口 / 接着鼠标触控板）里不隐藏——内嵌栏常在一边陪着
        // 列表被浏览，栏一藏用户想换个倍速就得先点一下画面把栏唤回来。
        val windowInfo = LocalNpWindowInfo.current
        val pointerAttached = windowInfo.isDesktopLike || windowInfo.isHoverable
        LaunchedEffect(controlsVisible, state, userDragging, pointerAttached, menuPage) {
            if (pointerAttached) return@LaunchedEffect
            if (controlsVisible && state == PlaybackState.Playing && !userDragging && menuPage == null) {
                delay(INLINE_CONTROLS_AUTO_HIDE_MS)
                controlsVisible = false
            }
        }

        val playing = state == PlaybackState.Playing
        Box(
            modifier = modifier
                .background(Color.Black)
                // 点画面 = 显隐控制层（控制层自身与中央播放钮会消费各自区域）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
        ) {
            if (media.kind == MediaKind.AUDIO || (audioOnly && summary?.item?.uri == media.uri)) {
                AudioPane(session, ::openAudioOnly, Modifier.fillMaxSize())
                return@Box
            }
            VideoStage(
                player = player,
                videoAspectRatio = videoAspectRatio,
                overlays = overlays,
                overlayMedia = media,
                navigator = navigator,
                state = state,
                userDragging = userDragging,
                subtitleStyle = subtitleStyle,
                scaleMode = scaleMode,
                mirrored = mirrored,
                rotation = rotation,
                onSurfaceView = { surfaceRef.value = it },
                // 右栏的画面本体参与整屏 morph。详情页的内联画面（autoPlay）不参与：
                // 一台播放器只有一个输出 Surface，两个候选起点会互相抢。
                morphKey = if (autoPlay) null else NpMorphKey.Video,
                // 右栏 = 类 PC 形态；详情页内联（autoPlay）不参与，传 null。
                morphDesktop = if (autoPlay) null else true,
                morphRotate = !autoPlay,
                modifier = Modifier.fillMaxSize(),
            )
            // 暂停时中央播放钮（与播放页同款玻璃大钮）
            CenterResumeButton(
                visible = state == PlaybackState.Paused && !userDragging && !locked,
                onPlay = { player.play() },
                modifier = Modifier.align(Alignment.Center),
            )
            StageLockBadgeHost(
                visible = controlsVisible,
                locked = locked,
                onUnlock = {
                    locked = !locked
                    menuPage = null
                    controlsVisible = true
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            )
            AnimatedVisibility(
                visible = controlsVisible && !locked,
                enter = fadeIn(tween(npDuration(Motion.Field))),
                exit = fadeOut(tween(npDuration(Motion.Field))),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                // 与手机竖屏的舞台顶栏同款：**通栏**一条浅黑底 + 左标题 + 右动作。
                // 旧版把「选集 + ⋯」画成压在画面右上角的一小块 62% 黑底——
                // 平板实拍就是"两个黑色方框浮在画面上"，观感很脏（用户实报）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = media.title.ifBlank { media.cardTitle },
                        style = NpTypography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (queue.size > 1) BarCtlButton(
                        icon = NpIcon.QueueMusic,
                        contentDescription = "选集",
                        onClick = { openMenu(FsMenuPage.EPISODES, episodesAnchor.current()) },
                        modifier = Modifier.npAnchor(episodesAnchor),
                    )
                    BarCtlButton(
                        icon = NpIcon.Dots,
                        contentDescription = "播放选项",
                        onClick = { openMenu(FsMenuPage.MAIN, menuAnchor.current()) },
                        modifier = Modifier.npAnchor(menuAnchor),
                    )
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                // E120：进度/时长/缓冲三项的订阅点收进控制层这个作用域。
                // 原先它们和 state/cues 一起解包在 InlinePlayerView 顶部，于是整棵
                // 内嵌子树每 250ms 重组一次（实测 43 次/12s）——AndroidView 虽被
                // Compose 跳过、没有真的重挂表面（同窗口 attachSurfaceTo 计 0 次），
                // 但控制层外的父作用域白跑，`Brush.verticalGradient` 与两条
                // enter/exit spec 每 tick 重新分配。§9.2 的纪律就是为这个立的。
                val positionState = player.positionMs.collectAsStateWithLifecycle()
                val durationMs by player.durationMs.collectAsStateWithLifecycle()
                // A6：与页根同一条纪律——缓冲前沿只留 State，交给 NpSeekBar 在绘制相位读
                val bufferedState = player.bufferedPositionMs.collectAsStateWithLifecycle()
                val dragState = rememberUpdatedState(dragPositionMs)
                val displayState = rememberUpdatedState(
                    if (userDragging) dragPositionMs else positionState.value.toFloat(),
                )
                PlayerBottomControls(
                        visible = controlsVisible && !locked,
                        applyInsets = false,
                        showTransport = true,
                        positionMs = positionState,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedState,
                        dragPositionMs = dragState,
                        userDragging = userDragging,
                        displayPosition = displayState,
                        maxPosition = durationMs.coerceAtLeast(1L).toFloat(),
                        onScrubStart = {
                            if (!userDragging) {
                                userDragging = true
                                scrubWasPlaying = playing
                                if (scrubWasPlaying) player.pause()
                            }
                        },
                        onScrubChange = { dragPositionMs = it },
                        onScrubEnd = {
                            player.seekTo(dragPositionMs.toLong())
                            userDragging = false
                            if (scrubWasPlaying) player.play()
                        },
                        // E129（审查 #3）：取消走独立出口，**不提交 seek**。
                        // 这条内嵌控制层不做预览 seek（拖动只改本地 dragPositionMs），
                        // 所以"回滚"只需清状态 + 恢复原播放态，无需把引擎放回起手位置。
                        onScrubCancel = {
                            userDragging = false
                            if (scrubWasPlaying) player.play()
                        },
                        isPlaying = playing,
                        hasPrevious = queueIndex > 0,
                        hasNext = queueIndex in 0 until queue.lastIndex,
                        speed = speed,
                        speedPickerVisible = menuPage == FsMenuPage.SPEED,
                        inlineSpeedRow = false,
                        onToggleSpeedPicker = {
                            openMenu(FsMenuPage.SPEED, speedAnchor, above = true)
                        },
                        onPickSpeed = ::pickSpeed,
                        subtitleLoaded = subtitleEnabled,
                        onPrevious = session::previous,
                        onTogglePlay = { if (playing) player.pause() else player.play() },
                        onNext = session::next,
                        onPickSubtitle = {
                            openMenu(FsMenuPage.SUBTITLE, subtitleAnchor, above = true)
                        },
                        onToggleFullscreen = onFullscreen,
                        onToggleAudioOnly = ::openAudioOnly,
                        fullscreenLabel = "全屏播放",
                        onSpeedAnchor = { speedAnchor = it },
                        onSubtitleAnchor = { subtitleAnchor = it },
                        onSeekBarAnchor = { seekAnchor = it },
                        preferSingleRow = true,
                )
            }
            if (mediaOpened && state is PlaybackState.Error) {
                val error = state as PlaybackState.Error
                ErrorPanel(
                    message = error.message,
                    failure = error.failure,
                    actionLabel = "重试",
                    onAction = {
                        val index = session.index.value
                        if (index in session.queue.value.indices) {
                            player.setQueue(session.queue.value, index, 0L)
                            player.play()
                        }
                    },
                    onBack = { session.stop() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            menuPage?.let { page ->
                FsDropdown(
                    anchor = activeAnchor,
                    page = page,
                    onDismiss = { menuPage = null },
                ) { targetPage, dismiss ->
                    val back = { if (targetPage == menuRoot) dismiss() else menuPage = menuRoot }
                    when (targetPage) {
                        FsMenuPage.MAIN -> FsMainMenuContent(
                            loopMode = loopMode,
                            brightnessPercent = brightness,
                            volumePercent = volume,
                            favorited = favorited,
                            hasChapters = chapters.isNotEmpty(),
                            sleepLabel = if (sleepEndsAt > 0) "睡眠定时已开启" else "睡眠定时",
                            sleepActive = sleepEndsAt > 0,
                            onClose = dismiss,
                            onPage = { menuPage = it },
                            onLoopMode = session::setLoopMode,
                            onBrightness = { windowBrightness.setPercent(it) },
                            onVolume = {
                                volume = it.coerceIn(0, 100)
                                audioManager.setStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    volumeIndexFor(maxVolume, volume), 0,
                                )
                            },
                            onPip = {
                                dismiss()
                                navigator.navigate(
                                    "player?uri=${android.net.Uri.encode(media.uri, "")}" +
                                        "&title=${android.net.Uri.encode(media.title, "")}" +
                                        "&mime=${android.net.Uri.encode(media.mimeType, "")}" +
                                        "&id=${android.net.Uri.encode(media.id, "")}&pip=1",
                                )
                            },
                            onFavorite = { scope.launch { history.toggleFavorite(media) } },
                            onScreenshot = {
                                dismiss()
                                savePlaybackScreenshot(context, surfaceRef.value, scope, screenshotBlocked, toast::show)
                            },
                            onSleep = session::toggleSleepTimer,
                            contributors = contributors,
                            contributorMedia = media,
                            contributorPlayer = player,
                        )
                        FsMenuPage.SPEED -> FsSpeedMenuContent(
                            speed, onPick = ::pickSpeed,
                            onNudge = { pickSpeed(((speed + it) * 20f).roundToInt() / 20f) }, onBack = back,
                        )
                        FsMenuPage.SUBTITLE -> FsSubtitleMenuContent(
                            subtitleOn = subtitleEnabled,
                            subtitleLoaded = savedSubtitle != null,
                            savedSubtitleName = savedSubtitle?.toUri()?.lastPathSegment,
                            textTracks = tracks.filter { it.type == NpTrackType.TEXT },
                            offsetMs = subtitleOffsetMs,
                            colorArgb = subtitleColorArgb,
                            onColor = { scope.launch { settings.setSubtitleColorArgb(it) } },
                            scale = subtitleScale,
                            onScale = { scope.launch { settings.setSubtitleScale(it) } },
                            italic = subtitleItalic,
                            onItalic = { scope.launch { settings.setSubtitleItalic(it) } },
                            onSetEnabled = { enabled ->
                                player.setTextTracksEnabled(enabled)
                                scope.launch { settings.setSubtitleEnabled(enabled) }
                            },
                            onPickTrack = {
                                player.selectTrack(NpTrackType.TEXT, it.id)
                                scope.launch { settings.setSubtitleEnabled(true) }
                            },
                            onMountExternal = {
                                dismiss()
                                subtitlePicker.launch(arrayOf("*/*"))
                            },
                            onOffset = session::setSubtitleOffsetMs,
                            onBack = back,
                        )
                        FsMenuPage.AUDIO -> FsAudioMenuContent(
                            tracks.filter { it.type == NpTrackType.AUDIO },
                            onPick = { player.selectTrack(NpTrackType.AUDIO, it.id) },
                            onBack = back,
                        )
                        FsMenuPage.PICTURE -> FsPictureMenuContent(
                            scaleMode, mirrored, rotation,
                            onMode = { updateDisplay(mode = it) },
                            onMirror = { updateDisplay(mirror = it) },
                            onRotate = { updateDisplay(rot = it) }, onBack = back,
                        )
                        FsMenuPage.EPISODES -> FsEpisodesMenuContent(
                            queue, queueIndex, onSelect = session::selectQueueIndex, onBack = back,
                        )
                        FsMenuPage.CHAPTERS -> FsChaptersMenuContent(
                            chapters, player.positionMs.collectAsStateWithLifecycle(),
                            onSelect = { player.seekTo(it.startMs) }, onBack = back,
                        )
                    }
                }
            }
            NpToastHost(state = toast, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
