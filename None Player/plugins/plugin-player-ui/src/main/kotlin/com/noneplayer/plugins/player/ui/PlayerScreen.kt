package com.noneplayer.plugins.player.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.VideoPlayerFactory
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.PlaybackContext
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs

/** 倍速预设（B 站同款档位） */
private val SPEED_PRESETS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f)

/** 拖动预览 seek 的最小间隔：更密会在本地大文件上反复解码，更疏则画面不跟手 */
private const val SCRUB_PREVIEW_INTERVAL_MS = 200L

/** 播放页自动隐藏控制层延时 */
private const val CONTROLS_AUTO_HIDE_MS = 3500L

/**
 * 播放页方向锁（E48）：UI 只表达**意图**，窗口方向由一个 effect 单点落地，
 * 避免多处命令式 `requestedOrientation` 互相覆盖。
 */
private enum class OrientationLock(val requestedOrientation: Int) {
    /** 不干预：跟随系统"自动旋转"设置（横过来即横向，即全屏） */
    FollowSensor(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),

    /** 用户点「全屏」：锁横向，直到显式退出 */
    Landscape(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),

    /** 用户点「退出全屏」：锁竖向，窗口转竖后立刻交还 [FollowSensor] */
    Portrait(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

/**
 * 播放页——媒体库点击视频后的目的地。
 *
 * - 经 VideoPlayerFactory 扩展点选择播放引擎；引擎驻留后台服务，退到后台继续播放；
 * - 断点续播：进入时恢复上次进度，离开时保存；
 * - 倍速 / 外挂字幕（srt/vtt/ass）一键切换；
 * - B 站式控制层：上下渐变 scrim + 点按切换 + 自动隐藏（拖动进度条时不隐藏）+ 沉浸式系统栏；
 * - 进度条擦洗：拖动中暂停并按关键帧预览（CLOSEST_SYNC），松手精确定位（EXACT）；
 * - 全屏 ≡ 窗口横向：进入播放页不预设方向（E48），点「全屏」锁横向、
 *   把设备横过来也进横向；退出全屏只短暂锁竖向把窗口转过去，随即交还传感器；
 * - 动态挂载 PlaybackOverlay 覆盖层（字幕/弹幕插件零侵入接入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    uri: String,
    title: String,
    mimeType: String,
    mediaId: String,
    navigator: Navigator,
) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { kernel.services.get<AppSettings>() }

    // L0（§11.2，**前置不是优化项**）：播放引擎由 `PlaybackSession` 独占，
    // 播放页只是它的一个"视图"。此前页面用 `remember { factory.create(...) }` 自建
    // 引擎、`onDispose` 时 `release()`——退页即销毁，于是：
    //   ① 后台播放被 UI 生命周期绑架（退页/切 Tab 就把引擎拆了）；
    //   ② 迷你条拿不到任何会话状态；
    //   ③ L1/L2 的预载配置与预载好的源一起被释放，预载永远没有收益。
    // 现在引擎归会话，退页只"停用视图"，不 release（释放交给进程结束/引擎换代）。
    val session = remember { SessionHolder.of(sessionFactoryOf(kernel.services)) }
    val player = remember(session) { session.acquire(mimeType) }

    // 退页收口（E28 语义保留，但**不再 release 播放器**）：
    // 位置在会话失效前同步读取，落盘用 NonCancellable 协程——它会活过本组合的取消，
    // 但不阻塞主线程（runBlocking 会在每次退页时卡住退出动画，扫描期间尤其明显）。
    DisposableEffect(mediaId) {
        onDispose {
            val position = player.positionMs.value
            if (mediaId.isNotEmpty() && position > 1_000) {
                scope.launch(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        settings.savePlaybackPosition(mediaId, position)
                    }
                }
            }
            // 注意：**不调用 player.release()**（L0）。播放继续在后台服务里跑，
            // 迷你条据此持续可见、点回来无缝续播。
        }
    }

    // 深链/通知入口（E2/E3）可能只携带 uri：标题与封面先用兜底解析补齐，
    // 保证媒体通知有标题封面、播放页标题栏不空
    var displayTitle by remember(uri) { mutableStateOf(title) }
    var artworkUri by remember(uri) { mutableStateOf<String?>(null) }
    // E57 修复：scheme 不在白名单内时在此落地一个本地（非播放器）错误态——
    // 连 prepare() 都不调用，避免未知 scheme 的字符串以任何形式送进引擎。
    var rejectedUriError by remember(uri) { mutableStateOf<String?>(null) }

    // 准备播放：等会话就绪。
    // B1 修复：会话已在播同一视频（后台播放中重进）时仅续播——
    // 禁止用过期断点 prepare/seek 覆盖会话里更新的进度。
    LaunchedEffect(uri) {
        // E57 修复：深链 `noneplayer://player?uri=...` 对外暴露（BROWSABLE），任何
        // 第三方 App 都能携带任意 uri 唤起本页。此前该值未经任何 scheme 校验就直接送进
        // ContentResolver.query 与播放引擎——scheme 白名单是信任边界的第一道闸。
        // 只允许本机内容来源（content/file），拒绝 http(s) 等其他任意 scheme
        // （本应用是纯本地播放器，产品边界本就不含网络流）。
        if (!isPlayableUriScheme(uri)) {
            rejectedUriError = "不支持的地址来源，已拒绝加载。"
            return@LaunchedEffect
        }
        // 1) 解析展示名与封面（同一协程内先于装载，保证通知拿到元数据）
        val resolvedTitle = title.ifBlank { resolveDisplayName(context, uri) }
        displayTitle = resolvedTitle
        val cover = resolveCoverUri(kernel.registry, mediaId)
        artworkUri = cover
        // 2) 会话就绪后装载
        player.awaitReady()
        val sameMedia = player.currentUri.value == uri
        if (!sameMedia) {
            // §8.2 同文件夹队列：把当前媒体的同目录兄弟一起交给会话，
            // 于是"播完自动下一集"与通知栏 ⏮⏭ 由 media3 免费提供。
            // 查询失败/目录不可得时退化为单项队列，不影响单集播放。
            val queue = resolveFolderQueue(kernel.registry, uri, mediaId)
            // 队列里匹配到的那一项才是"权威条目"（有真实标题/时长/封面/来源），
            // 路由参数只是入口线索——深链场景下 title 甚至可能为空。
            val authoritative = queue.firstOrNull { it.uri == uri || it.id == mediaId }
            val saved = if (mediaId.isNotEmpty()) settings.playbackPosition(mediaId).first() else 0L
            session.open(
                item = authoritative ?: MediaItem(
                    id = mediaId,
                    sourceId = "",
                    title = resolvedTitle,
                    uri = uri,
                    mimeType = mimeType,
                    coverUri = cover,
                ),
                queueOfFolder = queue,
            )
            if (saved > 5_000) player.seekTo(saved)
            player.setSpeed(settings.playbackSpeed.first())
            if (mediaId.isNotEmpty()) {
                settings.subtitleUri(mediaId).first()?.let { saved ->
                    player.setSubtitle(prepareSubtitleUri(context, saved.toUri()))
                }
            }
            player.play()
        } else {
            player.play()
        }
    }

    val state by player.state.collectAsStateWithLifecycle()
    val positionMs by player.positionMs.collectAsStateWithLifecycle()
    val durationMs by player.durationMs.collectAsStateWithLifecycle()
    val videoAspectRatio by player.videoAspectRatio.collectAsStateWithLifecycle()
    val cues by player.cues.collectAsStateWithLifecycle()
    // 队列与下标提到组合顶层：底部控制层（上/下一集按钮）与选集面板两处都要用，
    // 放在底栏 AnimatedVisibility 内部会让面板拿不到（且底栏隐藏时值也读不到）。
    val queue by session.queue.collectAsStateWithLifecycle()
    val queueIndex by session.index.collectAsStateWithLifecycle()

    // ---- 方向与全屏（E48）----
    // 单一真相：**全屏 ≡ 窗口为横向**，不再有独立的 isFullscreen UI 状态。
    // 于是三件事同时成立且无需任何抑制标志位协调：
    //   进入播放页 = 当前持机姿态（竖持即竖屏，不再被视频宽高比强行转横）；
    //   手动点"全屏" = 锁横向；
    //   把手机/平板横过来 = 窗口转横向 = 全屏自动成立。
    // 旧写法（比例驱动自动全屏 + autoFullscreenSuppressed 竞态闩）正是
    // "点视频直接横屏、返回时先转竖屏再退出界面"的根因。
    val activity = context as? ComponentActivity
    val configuration = LocalConfiguration.current
    val windowIsPortrait = configuration.screenHeightDp >= configuration.screenWidthDp
    val isFullscreen = !windowIsPortrait
    var orientationLock by rememberSaveable { mutableStateOf(OrientationLock.FollowSensor) }

    fun setFullscreen(fullscreen: Boolean) {
        orientationLock = if (fullscreen) OrientationLock.Landscape else OrientationLock.Portrait
    }

    // 方向只在用户显式操作时锁定；FollowSensor 用 UNSPECIFIED 而非 SENSOR，
    // 前者尊重系统"自动旋转"开关，用户关掉它就不该替他转
    LaunchedEffect(orientationLock) {
        activity?.requestedOrientation = orientationLock.requestedOrientation
    }
    // 手动退出全屏：锁竖屏把窗口转过去，到位后立刻交还传感器——
    // 锁不留存量，否则"退出全屏后再把设备横过来"不会再进全屏
    LaunchedEffect(orientationLock, windowIsPortrait) {
        if (orientationLock == OrientationLock.Portrait && windowIsPortrait) {
            orientationLock = OrientationLock.FollowSensor
        }
    }
    // 离开播放页恢复系统默认方向（全屏方向不残留到其他页面）
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // 全屏时返回键先退出全屏（业界一致语义）；第二次返回才离开播放页
    BackHandler(enabled = isFullscreen) { setFullscreen(false) }

    // 系统栏随全屏状态联动：全屏沉浸（滑动呼出），非全屏显示系统栏
    DisposableEffect(isFullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (isFullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 前台播放时保持屏幕常亮；暂停/退后台时窗口不可见，系统照常息屏
    val activityWindow = activity?.window
    DisposableEffect(activityWindow, state == PlaybackState.Playing) {
        if (state == PlaybackState.Playing) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // 选集面板（§8.3）：控制层之上的模态浮层，选中即跳集并关闭
    var episodePanelVisible by remember { mutableStateOf(false) }
    var userDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }
    // 拖动释放后的目标位置：seek 完成前进度条停在目标处，避免"回跳再快进"的跳变感
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    // 擦洗会话：拖动开始时是否在播（松手后恢复），以及预览 seek 限流时间戳
    var scrubWasPlaying by remember { mutableStateOf(false) }
    var lastPreviewSeekAtMs by remember { mutableLongStateOf(0L) }
    // B2 修复：倍速以会话实际值为准（后台会话保持的倍速在重进后仍正确显示）
    val speed by player.speed.collectAsStateWithLifecycle()
    val savedSubtitle by settings.subtitleUri(mediaId).collectAsStateWithLifecycle(initialValue = null)
    val subtitleLoaded = savedSubtitle != null

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uriResult ->
        if (uriResult != null) {
            context.contentResolver.takePersistableUriPermission(
                uriResult,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            scope.launch {
                // E40：GBK/Big5 字幕先转 UTF-8 副本再挂载，否则整片乱码
                player.setSubtitle(prepareSubtitleUri(context, uriResult))
                // 记忆原始地址：缓存目录可能被清理，恢复时重新转码
                settings.setSubtitleUri(mediaId, uriResult.toString())
            }
            controlsVisible = true
        }
    }

    // 拖动进度条：暂停 + 关键帧预览（限流），松手精确 seek 后恢复播放。
    // 拖动中自动隐藏守护（见下）保证进度条不会在拖动中途被收起。
    fun onScrubStart() {
        if (userDragging) return
        userDragging = true
        scrubWasPlaying = state == PlaybackState.Playing
        if (scrubWasPlaying) player.pause()
        player.setScrubbing(true)
        // 首次预览 seek 延迟到拖动开始 200ms 后：点击式 seek 不会白做一次预览
        lastPreviewSeekAtMs = SystemClock.uptimeMillis()
    }

    fun onScrubProgress(position: Float) {
        dragPositionMs = position
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewSeekAtMs >= SCRUB_PREVIEW_INTERVAL_MS) {
            lastPreviewSeekAtMs = now
            player.seekTo(position.toLong())
        }
    }

    fun onScrubEnd() {
        val target = dragPositionMs.toLong()
        pendingSeekMs = target
        player.setScrubbing(false)
        player.seekTo(target)
        if (scrubWasPlaying) player.play()
        userDragging = false
    }

    // 播放中控制层自动隐藏；拖动进度条期间不隐藏
    LaunchedEffect(controlsVisible, state, userDragging) {
        if (controlsVisible && state == PlaybackState.Playing && !userDragging) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        // 视频画面：按视频分辨率比例渲染（横竖屏自适应），黑边交给布局而非拉伸
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .then(
                    if (videoAspectRatio > 0f) {
                        Modifier.aspectRatio(videoAspectRatio)
                    } else {
                        Modifier.fillMaxSize()
                    },
                ),
        ) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx) },
                update = { view -> player.attachDisplay(view) },
                modifier = Modifier.fillMaxSize(),
            )
            // E1：字幕浮层（外挂 + 内嵌轨道统一出口），位于画面坐标系内
            SubtitleLayer(cues = cues, modifier = Modifier.fillMaxSize())
        }

        // 覆盖层扩展点：字幕/弹幕等插件动态挂载
        // E23：响应式收集——插件启用后 overlay 即刻生效，无需重启/重进页面
        val overlays by remember { kernel.registry.observe<PlaybackOverlay>() }
            .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackOverlay>())
        overlays.forEach { overlay ->
            overlay.Render(
                PlaybackContext(
                    media = toMediaItem(mediaId, title, uri, mimeType),
                    player = player,
                    navigator = navigator,
                ),
            )
        }

        // 缓冲指示
        if (state == PlaybackState.Buffering && !userDragging) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }

        // E57 修复：scheme 被拒绝——不提供"重试"（没有合法地址可重试），也不提供
        // "用其他播放器打开"/"复制地址"（这正是我们要拒绝转发的那条不可信地址）。
        if (rejectedUriError != null) {
            ErrorPanel(
                message = rejectedUriError.orEmpty(),
                onBack = navigator::popBackStack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 错误态（E6 可重试 + E41 体面失败：容器/编码三行 + 转码建议 + 外部打开）
        if (state is PlaybackState.Error) {
            val error = state as PlaybackState.Error
            ErrorPanel(
                message = error.message,
                failure = error.failure,
                mediaUri = uri,
                actionLabel = "重试",
                onAction = {
                    player.prepare(uri, mimeType)
                    player.play()
                },
                onBack = navigator::popBackStack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 播放结束
        if (state == PlaybackState.Ended) {
            ErrorPanel(
                message = "播放结束",
                actionLabel = "重新播放",
                onAction = {
                    player.seekTo(0)
                    player.play()
                },
                onBack = navigator::popBackStack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ---- 顶部控制层 ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { -it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = navigator::popBackStack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- 底部控制层 ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                // 进度条：显示位置优先级——拖动中 > seek 未完成的目标 > 播放器实际位置
                val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
                val displayPosition = when {
                    userDragging -> dragPositionMs
                    pendingSeekMs != null &&
                        abs(positionMs - pendingSeekMs!!) > 1_500 -> pendingSeekMs!!.toFloat()
                    else -> positionMs.coerceAtMost(durationMs).toFloat()
                }
                LaunchedEffect(positionMs) {
                    val pending = pendingSeekMs
                    if (pending != null && abs(positionMs - pending) <= 1_500) {
                        pendingSeekMs = null
                    }
                }
                // B 站式布局：细轨道 + 小圆点滑块，两段时间码贴身排布
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 拖动中时间码变成 accent 胶囊：明确"这是目标位置"，与实时位置区分
                    Text(
                        text = formatTimestamp(if (userDragging) dragPositionMs.toLong() else positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (userDragging) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White.copy(alpha = 0.9f)
                        },
                        modifier = if (userDragging) {
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        } else {
                            Modifier
                        },
                    )
                    Slider(
                        value = displayPosition.coerceIn(0f, maxPosition),
                        onValueChange = {
                            onScrubStart()
                            onScrubProgress(it)
                        },
                        onValueChangeFinished = { onScrubEnd() },
                        valueRange = 0f..maxPosition,
                        thumb = { PlaybackThumb(dragging = userDragging) },
                        track = { sliderState -> PlaybackTrack(sliderState, dragging = userDragging) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(
                        text = formatTimestamp(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 上一集 / 播放暂停 / 下一集——连播的操作面（§8.2）。
                    // 无上/下一集时按钮禁用而非隐藏：位置固定，避免点"下一集"时误触
                    // 到滑过来的"倍速"。按钮走 session（与迷你条、通知栏同一份队列状态）。
                    val hasPrevious = queueIndex > 0
                    val hasNext = queueIndex in 0 until (queue.size - 1)
                    IconButton(
                        onClick = { session.previous() },
                        enabled = hasPrevious,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = NpIcons.SkipPrevious,
                            contentDescription = "上一集",
                            tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f),
                        )
                    }
                    AnimatedContent(
                        targetState = state == PlaybackState.Playing,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 0.7f))
                                .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.7f))
                        },
                        label = "playPause",
                    ) { isPlaying ->
                        IconButton(
                            onClick = { if (isPlaying) player.pause() else player.play() },
                            modifier = Modifier.size(44.dp),
                        ) {
                            if (isPlaying) {
                                PauseGlyph()
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { session.next() },
                        enabled = hasNext,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = NpIcons.SkipNext,
                            contentDescription = "下一集",
                            tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    // 选集（§8.3）：仅当队列 > 1 时出现——单项队列没有"选"的意义
                    if (queue.size > 1) {
                        ControlChip(
                            text = "选集 ${queueIndex + 1}/${queue.size}",
                            contentDescription = "选集面板",
                        ) {
                            episodePanelVisible = true
                            controlsVisible = true
                        }
                    }
                    // 倍速（B 站式档位循环），并记忆为默认倍速
                    ControlChip(
                        text = "%.2fx".format(speed).removeSuffix("0").removeSuffix("."),
                        contentDescription = "倍速播放",
                    ) {
                        val next = SPEED_PRESETS[(SPEED_PRESETS.indexOf(speed) + 1).mod(SPEED_PRESETS.size)]
                        player.setSpeed(next)
                        scope.launch { settings.setPlaybackSpeed(next) }
                        controlsVisible = true
                    }
                    // 外挂字幕
                    ControlChip(
                        text = if (subtitleLoaded) "字幕✓" else "字幕",
                        contentDescription = "外挂字幕",
                    ) {
                        subtitlePicker.launch(arrayOf("*/*"))
                    }
                    // 全屏切换（横屏窗口下为沉浸/非沉浸）
                    ControlChip(
                        text = if (isFullscreen) "退出全屏" else "全屏",
                        contentDescription = "全屏切换",
                    ) {
                        setFullscreen(!isFullscreen)
                        controlsVisible = true
                    }
                }
            }
        }

        // ---- 选集面板（§8.3）----
        // 放在最上层：它是模态的，控制层不应从它下面透出来抢点击。
        // BackHandler 优先关面板而不是退出播放页（面板开着时的返回语义）。
        if (episodePanelVisible) {
            BackHandler { episodePanelVisible = false }
            EpisodePanel(
                queue = queue,
                currentIndex = queueIndex,
                onSelect = { index ->
                    session.selectQueueIndex(index)
                    episodePanelVisible = false
                    controlsVisible = false
                },
                onDismiss = { episodePanelVisible = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 选集面板（§8.3）——同文件夹队列的列表浮层。
 *
 * 用**底部抽屉式**而非居中对话框：集数可能几十上百，抽屉天然可滚动、
 * 拇指可达（平板/手机一致），且不遮挡正在播的画面上半部分（半透明 scrim + 列表只占下半屏）。
 */
@Composable
private fun EpisodePanel(
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            // 点空白处关闭：给用户"点外面就收起来"的直觉出口
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 只占下半屏：上半留出画面，用户能边看边选
                .fillMaxHeight(0.55f)
                .navigationBarsPadding(),
        ) {
            Column {
                Text(
                    text = "选集（${queue.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        val selected = index == currentIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = item.title.ifBlank { item.uri.substringAfterLast('/') },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * B 站式进度条滑块：小圆点，拖动时轻微放大。
 * 默认 M3 竖条滑块（4×44dp）视觉占比过重，且与进度条本体争抢视线。
 */
@Composable
private fun PlaybackThumb(dragging: Boolean) {
    Box(
        modifier = Modifier
            .size(if (dragging) 16.dp else 11.dp)
            .clip(CircleShape)
            .background(Color.White),
    )
}

/** B 站式进度条轨道：细线圆角，拖动时略加粗；fill 比例与滑块位置由 Slider 保证一致 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTrack(sliderState: SliderState, dragging: Boolean) {
    val activeColor = MaterialTheme.colorScheme.primary
    val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
    val fraction = if (range > 0f) {
        ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (dragging) 4.dp else 3.dp),
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = activeColor,
            size = Size(size.width * fraction, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/** 控制层文字按钮（倍速/字幕/全屏），胶囊样式，触控目标 ≥ 44dp */
@Composable
private fun ControlChip(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** 双竖条暂停图形（material-icons-core 不含 Pause，手绘保持视觉一致） */
@Composable
private fun PauseGlyph() {
    Row {
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
        Box(modifier = Modifier.size(width = 8.dp, height = 1.dp))
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    modifier: Modifier = Modifier,
    failure: PlaybackFailure? = null,
    mediaUri: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failure != null) {
            // E41 三行结构：标题 / 容器·编码行 / 原因（+ 转码建议）
            Text(
                text = failure.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            failure.trackLine?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = failure.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
            failure.suggestion?.let { suggestion ->
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
        Row(modifier = Modifier.padding(top = 16.dp)) {
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(actionLabel)
                }
            }
            if (mediaUri != null) {
                Button(
                    onClick = {
                        if (!openExternally(context, mediaUri)) {
                            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(start = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("用其他播放器打开")
                }
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(mediaUri))
                        Toast.makeText(context, "已复制文件地址", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(start = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("复制地址")
                }
            }
            Button(
                onClick = onBack,
                modifier = Modifier.padding(start = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                ),
            ) {
                Text("返回媒体库")
            }
        }
    }
}

/**
 * 允许进入播放/探测/外发链路的 scheme 白名单（E57/E76）。
 *
 * 本应用是纯本地播放器（产品边界零网络），`content`/`file` 覆盖本机选择器、
 * MediaStore、SAF 三类来源；深链等外部输入若携带其他 scheme（含任意网络 scheme），
 * 一律在最早的入口处拒绝，不送进 ContentResolver 查询或播放引擎。
 */
private val ALLOWED_URI_SCHEMES = setOf("content", "file")

private fun isPlayableUriScheme(uriString: String): Boolean =
    runCatching { uriString.toUri().scheme?.lowercase() }.getOrNull() in ALLOWED_URI_SCHEMES

/** ACTION_VIEW 外唤（本地 Intent，零网络）；scheme 不在白名单、无处理方或 file:// 受限时返回 false */
private fun openExternally(context: Context, uriString: String): Boolean {
    // E76 修复：与 E57 同根因——此前未校验 scheme 就把外部/深链来源的地址转发给
    // 其他 App，同样属于"未经校验的不可信输入扩大信任面"模式。
    if (!isPlayableUriScheme(uriString)) return false
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriString.toUri(), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        true
    }.getOrDefault(false)
}

private fun toMediaItem(
    id: String,
    title: String,
    uri: String,
    mimeType: String,
): com.noneplayer.core.media.MediaItem =
    com.noneplayer.core.media.MediaItem(
        id = id,
        sourceId = "local",
        title = title,
        uri = uri,
        mimeType = mimeType,
    )

/** 深链只带 uri 时的展示名兜底：MediaStore 显示名 → uri 末段 */
private suspend fun resolveDisplayName(context: android.content.Context, uri: String): String =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver
                .query(
                    android.net.Uri.parse(uri),
                    arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: android.net.Uri.parse(uri).lastPathSegment.orEmpty()
        } catch (e: SecurityException) {
            // E70 修复：此前与"查不到名字"一视同仁地吞掉，权限问题被伪装成了正常兜底。
            // 深链传入未授权的 content:// 时最常触发这条路径（见 E57）——保留诊断信息。
            Log.d("PlayerScreen", "resolveDisplayName 无权限访问 $uri", e)
            android.net.Uri.parse(uri).lastPathSegment.orEmpty()
        } catch (e: Exception) {
            Log.d("PlayerScreen", "resolveDisplayName 查询失败 $uri", e)
            android.net.Uri.parse(uri).lastPathSegment.orEmpty()
        }
    }

/**
 * 从媒体来源（MediaSourceProvider 扩展点）按 id 找封面路径，转为 URI 字符串（E4）。
 * 播放页不依赖任何具体来源插件，只面向内核契约。
 */
private suspend fun resolveCoverUri(
    registry: ExtensionRegistry,
    mediaId: String,
): String? {
    if (mediaId.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val sources = registry.query<MediaSourceProvider>()
            for (source in sources) {
                val item = source.observeMedia().first().firstOrNull { it.id == mediaId }
                val cover = item?.coverUri ?: continue
                return@withContext if (cover.contains("://")) cover else File(cover).toURI().toString()
            }
            null
        }.getOrNull()
    }
}

/**
 * 解析**同文件夹队列**（§8.2）——当前媒体所在目录的兄弟条目，按标题自然序排列。
 *
 * 为什么在播放端而不是库里做：队列属于"播放会话"的输入，而播放页可能由深链/通知
 * 直接唤起（此时没有任何"库页面选择上下文"可继承）。在这里按 uri 反查来源，深链
 * 与普通点击因此走同一条路径。
 *
 * 失败 / 找不到目录 / 只有一项时返回**只含当前项的列表**：单集播放照样成立，
 * 不会因为队列构造失败而播不出来（宁可没有连播，也不能没有播放）。
 */
private suspend fun resolveFolderQueue(
    registry: ExtensionRegistry,
    uri: String,
    mediaId: String,
): List<MediaItem> {
    val fallback = listOf(
        MediaItem(id = mediaId, sourceId = "", title = "", uri = uri, mimeType = "video/mp4"),
    )
    if (mediaId.isEmpty()) return fallback
    return withContext(Dispatchers.IO) {
        runCatching {
            val sources = registry.query<MediaSourceProvider>()
            for (source in sources) {
                // 不能只取 `.first()`：来源的媒体列表是 StateFlow，首帧可能是
                // `emptyList()`（冷启动时扫描尚未落库），此时会误判成"找不到本项"
                // 而退化成单项队列——表现为迷你条没有标题、也没有 '1 / N'。
                // 这里等到出现"包含本项"的那一次发射为止（有上限，绝不无限等）。
                // 匹配顺序：先 id，再 uri。
                //
                // **为什么 uri 是必不可少的兜底**：`id` 是来源自己的稳定标识
                // （本地来源是 `ms-<MediaStore._id>`），而 MediaStore 的 `_id`
                // **会变**——重扫、删除后重建、系统数据清理都可能让同一个视频换号。
                // 一旦换号，路由里带过来的旧 id 就再也匹配不上，队列会静默退化成
                // "只有当前项"（迷你条没标题、没有 1/N、连播失效）——**播放本身却正常**，
                // 于是这个缺陷很难被发现。uri 是播放真正依赖的东西，它不变。
                val all = withTimeoutOrNull(SOURCE_WAIT_MS) {
                    source.observeMedia().first { list ->
                        list.any { it.id == mediaId || it.uri == uri }
                    }
                } ?: source.observeMedia().first()
                val self = all.firstOrNull { it.id == mediaId }
                    ?: all.firstOrNull { it.uri == uri }
                    ?: continue
                val folder = self.folder ?: return@withContext listOf(self)
                val siblings = all
                    .filter { it.folder == folder }
                    // 自然序（"第2集" 排在 "第10集" 前）——库里列表用的同款比较器，
                    // 队列顺序必须与用户看到的顺序一致，否则"下一集"会跳错
                    .sortedWith(compareBy({ it.title.lowercase() }, { it.title }))
                val result = if (siblings.none { it.id == self.id }) siblings + self else siblings
                return@withContext result
            }
            fallback
        }.getOrElse { fallback }
    }
}

/** 等媒体来源把"本项"扫出来的上限；超过则退回单项队列（绝不卡住进页） */
private const val SOURCE_WAIT_MS = 3_000L
