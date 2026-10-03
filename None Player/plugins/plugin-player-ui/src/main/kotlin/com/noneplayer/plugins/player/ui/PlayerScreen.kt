package com.noneplayer.plugins.player.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpGlass
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpOptRow
import com.noneplayer.core.ui.NpQuickTile
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpToastHost
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npGlassChrome
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.rememberReduceMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.rememberNpToastState
import com.noneplayer.core.ui.silkSpring
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.VideoPlayerFactory
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
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
import kotlin.math.roundToInt

/** 倍速预设（设计稿 `.speeds` 的档位与顺序：0.5x → 2.0x） */
private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** 拖动预览 seek 的最小间隔：更密会在本地大文件上反复解码，更疏则画面不跟手 */
private const val SCRUB_PREVIEW_INTERVAL_MS = 200L

/** 播放页自动隐藏控制层延时 */
private const val CONTROLS_AUTO_HIDE_MS = 3500L

/** 等系统把方向落地的最长时限 / 轮询间隔（转屏动画 + 配置下发） */
private const val ORIENTATION_CONFIRM_TIMEOUT_MS = 1200L
private const val ORIENTATION_CONFIRM_POLL_MS = 150L

/**
 * 播放页方向锁（E48）：UI 只表达**意图**，窗口方向由一个 effect 单点落地，
 * 避免多处命令式 `requestedOrientation` 互相覆盖。
 */
private enum class OrientationLock(val requestedOrientation: Int) {
    /** 不干预：跟随系统"自动旋转"设置（横过来即横向，即全屏） */
    FollowSensor(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),

    /**
     * 用户点「全屏」：请求横向，直到显式退出。
     *
     * 请求**不等于生效**：系统有权不转屏，转不过去时把应用信箱化成屏幕中间一条
     * 横向小带（平板 1600x2560 上实测退化成 1600x1200，画面凭空小一圈、上下全黑，
     * `SENSOR_*` 与固定 `LANDSCAPE` 都会）。所以这里只负责表达意图，
     * 生效与否由 [awaitLandscapeApplied] 事后核对，不成立就收回请求。
     */
    Landscape(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),

    /** 用户点「退出全屏」：锁竖向，窗口转竖后立刻交还 [FollowSensor] */
    Portrait(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
}

/**
 * 横向请求是否真的换来了一个铺满的横向窗口。
 *
 * 两种"没生效"都要认出来：系统直接忽略请求（屏幕还竖着，窗口也竖着），
 * 以及系统不转屏改为**信箱化**（屏幕还竖着，应用窗口却被压成一条横向带）。
 * 两者共同点都是"屏幕仍是竖向"，于是窗口形状是否横向并不重要——
 * 只有屏幕真的横过来、且应用窗口也横向，才算全屏成立。
 */
@Suppress("DEPRECATION")
private fun landscapeApplied(activity: ComponentActivity): Boolean {
    val displaySize = Point()
    activity.windowManager.defaultDisplay.getRealSize(displaySize)
    if (displaySize.x <= displaySize.y) return false
    val configuration = activity.resources.configuration
    return configuration.screenWidthDp > configuration.screenHeightDp
}

/** 轮询到系统把方向落地为止；超时返回最后一次判定结果。 */
private suspend fun awaitLandscapeApplied(activity: ComponentActivity): Boolean {
    val deadline = SystemClock.elapsedRealtime() + ORIENTATION_CONFIRM_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        if (landscapeApplied(activity)) return true
        delay(ORIENTATION_CONFIRM_POLL_MS)
    }
    return landscapeApplied(activity)
}

/** 系统「自动旋转」开关是否关着——决定被驳回时该给用户哪句可操作提示。 */
private fun rotationLocked(activity: ComponentActivity): Boolean =
    Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0

/**
 * 播放页两种形态版式共享的**只读状态快照**。
 *
 * 为什么要把状态打包：`PlayerScreen` 曾把三种版式（横屏全屏 / 类 PC / 竖屏）与
 * 三个弹层全部内联在同一个组合函数里，编译出的 `PlayerScreen` 方法达到
 * **253 个寄存器 / 18256 个码元**——ART 校验器直接以 `VerifyError` 拒绝加载该类，
 * 播放页一打开就崩。拆成独立组合函数后每个函数只持有自己那一份状态，
 * 方法规模回落到校验器可接受的量级；打包成一个类只是免得签名有几十个参数。
 *
 * 只读：所有可变状态仍留在 [PlayerScreen]（`remember` 的作用域不能跨函数），
 * 由 [PlayerStageActions] 里的回调写回。
 */
private class PlayerStageState(
    val player: VideoPlayer,
    val navigator: Navigator,
    val state: PlaybackState,
    val queue: List<MediaItem>,
    val queueIndex: Int,
    val currentItem: MediaItem?,
    val headerTitle: String,
    val headerContainer: String?,
    val speed: Float,
    val speedText: String,
    val subtitleLoaded: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val dragPositionMs: Float,
    val displayPosition: Float,
    val maxPosition: Float,
    val userDragging: Boolean,
    val controlsVisible: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val gestureHint: String?,
    val boostHintVisible: Boolean,
    val videoAspectRatio: Float,
    val cues: List<SubtitleCue>,
    val overlays: List<PlaybackOverlay>,
    val overlayMedia: MediaItem,
    val mediaOpened: Boolean,
    val decodeNotice: String?,
    val gesturesEnabled: Boolean,
)

/** 播放页三形态版式共享的回调集（写回 [PlayerScreen] 持有的可变状态）。 */
private class PlayerStageActions(
    val togglePlay: () -> Unit,
    val seekByGesture: (Boolean) -> Unit,
    val adjustBrightnessBy: (Float) -> Unit,
    val adjustVolumeBy: (Float) -> Unit,
    val onScrubStart: () -> Unit,
    val onScrubProgress: (Float) -> Unit,
    val onScrubEnd: () -> Unit,
    val onBoostStart: () -> Unit,
    val onBoostEnd: () -> Unit,
    val onToggleControls: () -> Unit,
    val onShowControls: () -> Unit,
    val onHideControls: () -> Unit,
    val setFullscreen: (Boolean) -> Unit,
    val onBack: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSelectQueueIndex: (Int) -> Unit,
    val onToggleAudioOnly: () -> Unit,
    val onShowEpisodes: () -> Unit,
    val onShowSpeedSheet: () -> Unit,
    val onShowSubtitleSheet: () -> Unit,
    val onShowMoreSheet: () -> Unit,
    val onShowFileInfo: () -> Unit,
    val onBrightness: (Int) -> Unit,
    val onVolume: (Int) -> Unit,
    val enterPip: () -> Unit,
    val onFavoriteToggle: () -> Unit,
    val onLoopMode: (NpLoopMode) -> Unit,
    val showToast: (String) -> Unit,
)

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

    // ---- 设置页「后台与屏幕 / 手势」三组的消费端（设计稿 settingsView） ----
    // 后台播放关闭 → 退页即停；屏幕常亮关闭 → 播放中不取 FLAG_KEEP_SCREEN_ON；
    // 手势关闭 → 舞台左右滑动与双击快进全部禁用（开关落在设置页，行为落在播放页）。
    val backgroundPlay by settings.backgroundPlay.collectAsStateWithLifecycle(initialValue = true)
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle(initialValue = true)
    val gesturesEnabled by settings.gesturesEnabled.collectAsStateWithLifecycle(initialValue = true)
    // 手势反馈胶囊（"亮度 62%" / "音量 40%" / "快进 10 秒"）
    var gestureHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(1_200)
            gestureHint = null
        }
    }

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
    DisposableEffect(mediaId, backgroundPlay) {
        onDispose {
            // 落盘要落**当前真正在播的那一项**：页内切过集之后路由 mediaId 已不是
            // 会话当前项，继续用它当 key 会把新一集的进度写进旧文件的断点。
            val nowId = session.queue.value.getOrNull(session.index.value)?.id?.takeIf { it.isNotEmpty() }
                ?: mediaId
            val position = player.positionMs.value
            if (nowId.isNotEmpty() && position > 1_000) {
                scope.launch(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        settings.savePlaybackPosition(nowId, position)
                    }
                }
            }
            // 设置页「后台播放」关闭 → 退页即停（并清掉迷你条）；
            // 打开则播放继续在后台服务里跑，迷你条据此持续可见、点回来无缝续播（L0）。
            if (!backgroundPlay) session.pause()
        }
    }

    // 深链/通知入口（E2/E3）可能只携带 uri：标题与封面先用兜底解析补齐，
    // 保证媒体通知有标题封面、播放页标题栏不空
    var displayTitle by remember(uri) { mutableStateOf(title) }
    var artworkUri by remember(uri) { mutableStateOf<String?>(null) }
    // 深链只带 uri（不带 mime/id）时，从 MediaStore 取真实 MIME_TYPE 供页头容器名显示。
    // 路由 mime 默认值是 video/mp4，直接用它会把一个 .avi 显示成「MP4」（§7.4）；
    // 而 content:// 地址没有扩展名，靠扩展名也猜不出来——只有 MediaStore 知道真相。
    // 仅用于**显示**，不改装载用的 mime（引擎按路由 mime 创建，改这里会让引擎换代、
    // 页面持有的 player 引用失效）。
    var resolvedMimeType by remember(uri) { mutableStateOf<String?>(null) }
    // E57 修复：scheme 不在白名单内时在此落地一个本地（非播放器）错误态——
    // 连 prepare() 都不调用，避免未知 scheme 的字符串以任何形式送进引擎。
    var rejectedUriError by remember(uri) { mutableStateOf<String?>(null) }

    // 本页是否已完成首个媒体的装载。
    //
    // 用来把"装载中"与"真的播完了"分开：`LaunchedEffect` 里 `awaitReady()` 与
    // 队列解析都要花时间，这段时间组合已经在渲染了，而引擎状态仍是**上一个**媒体
    // 留下的值。没有这个闸门时，进新视频会先闪一屏"播放结束"（上一个视频正常播完）
    // 或旧错误面板，再开始播放。
    var mediaOpened by remember(uri) { mutableStateOf(false) }

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
            mediaOpened = true
            return@LaunchedEffect
        }
        // 1) 解析展示名与封面（同一协程内先于装载，保证通知拿到元数据）
        val resolvedTitle = title.ifBlank { resolveDisplayName(context, uri) }
        displayTitle = resolvedTitle
        // 深链（无 id、无 mime）时补查 MediaStore 的真实 MIME，供页头容器名显示（§7.4）
        if (mediaId.isEmpty()) resolvedMimeType = resolveMimeType(context, uri)
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
            // 会话里已经是同一个媒体（从迷你条点回来）。
            // **已播完的必须从头起**：media3 在 STATE_ENDED 上 `play()` 是空操作，
            // 不退位就永远停在结束画面——用户看到"点进来没反应"。
            if (player.state.value == PlaybackState.Ended) player.seekTo(0L)
            player.play()
        }
        mediaOpened = true
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
    // 页头（playerbar / pinfo / 文件信息卡）以**会话当前项**为准而不是路由入参：
    // 选集切换或底栏 ⏭ 之后，标题与容器必须立刻换成新一集，
    // 而不是停在进页时那一条（路由 uri 不会因子会话内切集而改变）。
    val currentItem = queue.getOrNull(queueIndex)
    val headerTitle = currentItem?.title?.takeIf { it.isNotBlank() } ?: displayTitle
    val headerMimeType = currentItem?.mimeType?.takeIf { it.isNotBlank() } ?: mimeType
    // 容器名三级取值（越靠前越可信）：
    // ① 地址带扩展名（file:// 深链）→ 直接按扩展名判定（`MediaNames.containerName`）；
    // ② 深链从 MediaStore 补查到的真实 MIME（`resolvedMimeType`，见装载前的解析）；
    // ③ 条目自带的 mime（库内流程走这条，本就是 MediaStore 给的真值）。
    // 若不这样分级，深链 content:// 地址（无扩展名）会退到路由默认值 video/mp4，
    // 把 .avi 显示成「MP4」（§7.4）。
    val headerContainer = MediaNames.containerName(currentItem?.uri ?: uri)
        ?.takeIf { it.isNotBlank() }
        ?: (resolvedMimeType ?: headerMimeType).substringAfter('/', "").uppercase()
            .takeIf { it.isNotBlank() }

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
    // E48 余项 / §13.4：形态分派走 LocalNpWindowInfo（全局唯一判定层）。
    // **全屏 ≡ 窗口横向**，类 PC（平板横屏 / 桌面自由窗口）不另起一种"嵌入"版式：
    // 它同样是横向窗口，画面一样铺满可用区域。形态差异只留在两处——系统栏是否
    // 沉浸隐藏（[isImmersive]）、控制层是否自动隐藏（见下方 autoHide 分支）。
    // 旧写法把类 PC 排除在全屏之外、再套一层 760dp 限宽列：平板横屏里 16:9 只渲染到
    // 窗口面积的 53%，且这一版式里根本没有全屏入口，用户没有任何办法把画面放大。
    val windowInfo = LocalNpWindowInfo.current
    val isDesktopLike = windowInfo.isDesktopLike
    val isFullscreen = !windowIsPortrait
    /** 隐藏系统栏的手机式沉浸——类 PC 形态系统栏常驻，不抢用户的状态栏 */
    val isImmersive = isFullscreen && !isDesktopLike
    var orientationLock by rememberSaveable { mutableStateOf(OrientationLock.FollowSensor) }
    // 弹层提示的状态位提前到这里：方向请求被系统驳回时要靠它说清楚原因
    val toast = rememberNpToastState()

    fun setFullscreen(fullscreen: Boolean) {
        orientationLock = if (fullscreen) OrientationLock.Landscape else OrientationLock.Portrait
    }

    // 方向只在用户显式操作时锁定；FollowSensor 用 UNSPECIFIED 而非 SENSOR，
    // 前者尊重系统"自动旋转"开关，用户关掉它就不该替他转。
    // 进入播放页不预设方向（E48）：orientationLock 的初值就是 FollowSensor，
    // 这里只会落地"点全屏 / 退全屏"这类用户意图，不会因为"视频是横的"自动转屏。
    LaunchedEffect(orientationLock) {
        val act = activity ?: return@LaunchedEffect
        act.requestedOrientation = orientationLock.requestedOrientation
        // 请求发出去不等于屏幕真的转了：不转就收回，否则用户拿到一条信箱化的窄带
        if (orientationLock == OrientationLock.Landscape && !awaitLandscapeApplied(act)) {
            orientationLock = OrientationLock.FollowSensor
            toast.show(
                if (rotationLocked(act)) "方向已被系统锁定，无法横屏"
                else "系统未允许横屏，把设备横过来即可全屏",
            )
        }
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
    // 全屏时返回键先退出全屏（业界一致语义）；第二次返回才离开播放页。
    //
    // **"退出全屏"必须能真的退掉**，否则返回键会被无限吞掉。两个坑叠在一起：
    // ① `setFullscreen(false)` 只是**请求**竖向，系统未必照办（用户锁了自动旋转）；
    // ② 平板/横屏终端的**自然方向就是横向**，压根不存在"回竖向"这一步——
    //    而 `isFullscreen`（= 窗口横向）在平板上恒为 true，`BackHandler` 恒使能。
    // 于是每次返回都只发一个不会生效的方向请求、页面纹丝不动。实测连按 6 次仍停在
    // 播放页，用户被关在里面出不去（竖屏/手机横屏不会命中，因为它们的窗口能真转）。
    //
    // 语义：请求竖向并**确认窗口真的转了**，才算"退出了全屏"；转不过去就把这次返回
    // 交给导航栈（离开播放页），保证用户总能出去。
    //
    // 为什么是"确认"而不是"直接 pop"：手机上点视频不会强制转屏（E48），横屏只可能来自
    // 两种情况——用户点了「全屏」，或把设备横了过来。前者有"退出全屏"这一步，返回键应当
    // 先退全屏而不是直接离开播放页（业界一致语义，也是本项目既有验收项）。
    // 判据只能是"竖向请求有没有兑现"：`requestedOrientation` 是请求不是事实，系统可能拒绝
    // （用户锁了自动旋转），平板/横屏终端的自然方向更是本来就横向、压根没有竖向可回，
    // 此时若还认自己"退出了全屏"，`isFullscreen` 恒为 true、`BackHandler` 恒使能，
    // 返回键就被无限吞掉（实测平板横屏连按 6 次仍停在播放页，用户被关在里面）。
    //
    // 「返回键该不该先退出全屏」的判据：**这次横向是不是用户自己点「全屏」要来的**。
    //
    // 不能用"等窗口真的转竖了"作判据（曾这样实现，实测两种机型都会把用户关死）：方向请求
    // 只是意图，系统可以不照办，且有两种失败形态：
    //   ① 平板 / 横屏终端的自然方向本就是横向 → 没有竖向可回，窗口纹丝不动；
    //   ② 手机横持且开着自动旋转 → PORTRAIT 请求短暂生效后立刻被传感器拉回横向。
    // 两种情况下 `isFullscreen`（= 窗口横向）都恒为 true、`BackHandler` 恒使能，
    // 每次返回都重复同一个不留结果的动作——实测连按 6 次都退不出播放页。
    //
    // 改用 `orientationLock`（用户意图，页面自己持有、可见）判定：
    //   · `Landscape` = 用户点了「全屏」→ 这次返回退全屏（请求竖向并留在播放页）；
    //   · 其余（`FollowSensor` / `Portrait`）= 横向来自设备姿态或系统 → **没有全屏可退**，
    //     这次返回直接离开播放页。
    // 两条路都必然推进：即使竖向请求被系统拒绝，`orientationLock` 也已被改成 `Portrait`，
    // 下一次返回落到"其余"分支 ⇒ 最多两下必定出得去，不存在空转死循环。
    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) orientationLock = OrientationLock.FollowSensor
    }

    /**
     * 「返回类」出口（系统返回键 / Esc / 左上角返回箭头）：**保证能出去**。
     * 有全屏可退 → 退全屏留在播放页；否则 → 离开播放页。
     */
    fun exitFullscreenOrLeave() {
        if (orientationLock != OrientationLock.Landscape) {
            navigator.popBackStack()
            return
        }
        // 用户点过「全屏」：这一次返回用于退出全屏（请求竖向，成功与否都不吞掉下次返回）
        setFullscreen(false)
    }

    /**
     * 「切换类」出口（控制层右下角的「全屏切换」按钮）：**只表达意图，不带走用户**。
     *
     * 它与返回键的区别正是"用户想不想离开这一页"：点全屏图标的人想换一种观看版式，
     * 不想被丢回媒体库。所以在没有竖向可退的终端上（平板自然方向就是横向）
     * 它只是无从生效——保持现状即可，绝不能用 popBackStack 把用户弹出播放页。
     * 真正的离页出口始终由返回键承担，因此这里不会造成"出不去"。
     */
    fun requestExitFullscreen() {
        setFullscreen(false)
    }
    BackHandler(enabled = isFullscreen) { exitFullscreenOrLeave() }

    // 系统栏随沉浸状态联动：手机式横屏沉浸（滑动呼出），其余形态显示系统栏
    DisposableEffect(isImmersive) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (isImmersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 前台播放时保持屏幕常亮；暂停/退后台时窗口不可见，系统照常息屏。
    // 「播放时保持屏幕常亮」开关（设置页）关闭后不再取这个 flag。
    val activityWindow = activity?.window
    DisposableEffect(activityWindow, state == PlaybackState.Playing, keepScreenOn) {
        if (state == PlaybackState.Playing && keepScreenOn) {
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

    // 播放中控制层自动隐藏；拖动进度条期间不隐藏。
    // 类 PC / 有指针设备：控制层常驻（§13.6——有鼠标时"3.5s 自动消失"是反模式）
    LaunchedEffect(controlsVisible, state, userDragging, isDesktopLike) {
        if (isDesktopLike) return@LaunchedEffect
        if (controlsVisible && state == PlaybackState.Playing && !userDragging) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // 覆盖层扩展点：字幕/弹幕等插件动态挂载
    // E23：响应式收集——插件启用后 overlay 即刻生效，无需重启/重进页面
    val overlays by remember { kernel.registry.observe<PlaybackOverlay>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackOverlay>())
    val overlayMedia = remember(mediaId, title, uri, mimeType) {
        toMediaItem(mediaId, title, uri, mimeType)
    }

    // 进度条显示位置优先级：拖动中 > seek 未完成的目标 > 播放器实际位置。
    // 从原底栏内部**提升到分派之外**：此前它挂在 `AnimatedVisibility(controlsVisible)`
    // 之内，控制层一隐藏就不再执行，"目标位置钉住"会一直残留。
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

    val hasPrevious = queueIndex > 0
    val hasNext = queueIndex in 0 until (queue.size - 1)
    val speedText = "%.2fx".format(speed).removeSuffix("0").removeSuffix(".")
    fun togglePlay() {
        if (state == PlaybackState.Playing) player.pause() else player.play()
    }

    // ---- 设计稿播放器交互面（sheet / fs-panel / 仅听音频 / 循环模式）----
    val ink0 = LocalNpInk.current
    val audioOnly by session.audioOnly.collectAsStateWithLifecycle()
    val loopMode by session.loopMode.collectAsStateWithLifecycle()
    var speedPickerVisible by remember { mutableStateOf(false) }
    var subtitleSheetVisible by remember { mutableStateOf(false) }
    var moreSheetVisible by remember { mutableStateOf(false) }
    var fsPanelVisible by remember { mutableStateOf(false) }

    /** 选倍速：落到播放器 + 持久化 + 回执，并收起选择条 */
    fun pickSpeed(preset: Float) {
        player.setSpeed(preset)
        scope.launch { settings.setPlaybackSpeed(preset) }
        toast.show("倍速：" + speedLabel(preset))
        speedPickerVisible = false
    }

    // C18：文件信息卡（标题/大小/分辨率/编码/文件夹/时长 的真实值）
    var fileInfoVisible by remember { mutableStateOf(false) }
    // C18：允许屏幕截图（FLAG_SECURE 取反）。默认关闭 = 允许截图，
    // 打开"禁止"时窗口不可被截图/录屏（与设计稿 check-row 同一语义）
    var screenshotBlocked by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activityWindow, screenshotBlocked) {
        if (screenshotBlocked) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // 收藏：库侧收藏数据层未落地（§9 记录在案），先做状态 + toast 的诚实占位
    var favorited by rememberSaveable(mediaId) { mutableStateOf(false) }
    // 长按 2.0x 快进（设计稿 .gest-hint）：进入时记住原速，松手恢复
    var boostHintVisible by remember { mutableStateOf(false) }
    var boostPreviousSpeed by remember { mutableStateOf(1f) }
    // 亮度（window attr 0.01..1 映射为 1..100）/ 音量（AudioManager STREAM_MUSIC）
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
    var brightnessPercent by rememberSaveable {
        mutableStateOf(
            ((activityWindow?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                .takeIf { it in 0.01f..1f }
                ?.times(100)?.toInt() ?: 62),
        )
    }
    var volumePercent by rememberSaveable {
        mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / maxVolume)
    }
    fun setBrightnessPercent(value: Int) {
        brightnessPercent = value.coerceIn(1, 100)
        activityWindow?.attributes = activityWindow?.attributes?.apply {
            screenBrightness = brightnessPercent / 100f
        }
    }
    fun setVolumePercent(value: Int) {
        volumePercent = value.coerceIn(0, 100)
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            maxVolume * volumePercent / 100,
            0,
        )
    }
    /** 双击左右半屏：±10 秒（设计稿「双击动作：快退 / 快进 10 秒」）。 */
    fun seekByGesture(forward: Boolean) {
        val step = 10_000L
        val target = (player.positionMs.value + if (forward) step else -step)
            .coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(target)
        gestureHint = if (forward) "快进 10 秒" else "快退 10 秒"
    }

    /** 滑动调亮度：delta 为"屏幕宽度的百分比"，累加到当前值。 */
    fun adjustBrightnessBy(delta: Float) {
        setBrightnessPercent((brightnessPercent + delta).roundToInt())
        gestureHint = "亮度 $brightnessPercent%"
    }

    /** 滑动调音量：同上。 */
    fun adjustVolumeBy(delta: Float) {
        setVolumePercent((volumePercent + delta).roundToInt())
        gestureHint = "音量 $volumePercent%"
    }
    fun enterPip() {
        val a = context as? ComponentActivity ?: return
        runCatching {
            a.enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build(),
            )
        }
        toast.show("已进入画中画")
    }

    // ================= 形态分派（E48 余项 / §13.4）=================
    // 两种形态各有一套版式，但共用同一份会话状态与同一套控制件：
    //   ① 横向全屏（手机横置 + 平板横屏 / 桌面窗口）——整页视频 + 覆盖式控制层
    //   ② 竖屏（手机竖持 / 平板竖屏）——上视频 + 下信息区（B 站式）
    //
    // C19：键盘（空格/←→/Esc）从根节点统一处理——各形态都吃到，
    // 类 PC 形态（外接键盘/桌面窗口）尤其需要。onPreviewKeyEvent 取 KeyUp，
    // 免得长按被系统重复投递成连点。
    //
    // 键事件只会沿**焦点路径**下发，播放页本身没有可聚焦控件，所以根节点
    // 自己变成 focusable 并在进场时请求焦点（离开页面即自动释放）——
    // 否则这层 onPreviewKeyEvent 永远收不到任何按键。
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> {
                        togglePlay()
                        true
                    }
                    Key.DirectionLeft -> {
                        seekByGesture(forward = false)
                        true
                    }
                    Key.DirectionRight -> {
                        seekByGesture(forward = true)
                        true
                    }
                    Key.Escape -> {
                        // Esc 逐层退：全屏 → 退全屏；否则 → 返回媒体库
                        // 与返回键同一出口：退不掉全屏时直接离开播放页，不做空转
                        if (isFullscreen) exitFullscreenOrLeave() else navigator.popBackStack()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (audioOnly) {
            // 仅听音频整屏页（设计稿 `.audio`）：NetEase 风黑胶 + 控制排，
            // 覆盖所有形态；「切回视频」回到当前形态的播放版式
            AudioOnlyScreen(
                session = session,
                onBack = {
                    session.toggleAudioOnly()
                    toast.show("已切回视频播放")
                },
                favorited = favorited,
                onToggleFavorite = {
                    favorited = !favorited
                    toast.show(if (favorited) "已加入收藏" else "已取消收藏")
                },
                onOpenSpeed = { speedPickerVisible = !speedPickerVisible },
                speed = speed,
                speedPickerVisible = speedPickerVisible,
                onPickSpeed = ::pickSpeed,
                onOpenEpisodes = { episodePanelVisible = true },
                speedText = speedText,
            )
        } else {
        when {
            // ---------------- 形态①：横向全屏（手机横屏 + 平板横屏 / 类 PC）----------------
            isFullscreen -> {
                // 版式拆成局部组合函数：内联进 PlayerScreen 会把该方法撑到
                // 253 寄存器 / 18256 码元，ART 校验器直接 VerifyError 拒绝加载
                // （详见 [PlayerStageState] 的拆分说明）。
                @Composable fun FullscreenForm() {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .npStageGestures(
                            gesturesEnabled = gesturesEnabled,
                            onTap = { controlsVisible = !controlsVisible },
                            onBoostStart = {
                                boostPreviousSpeed = speed
                                player.setSpeed(2f)
                                boostHintVisible = true
                            },
                            onBoostEnd = {
                                if (boostHintVisible) {
                                    player.setSpeed(boostPreviousSpeed)
                                    boostHintVisible = false
                                }
                            },
                            onDoubleTap = ::seekByGesture,
                            onSwipeBrightness = ::adjustBrightnessBy,
                            onSwipeVolume = ::adjustVolumeBy,
                        ),
                ) {
                    VideoStage(
                        player = player,
                        videoAspectRatio = videoAspectRatio,
                        cues = cues,
                        overlays = overlays,
                        overlayMedia = overlayMedia,
                        navigator = navigator,
                        state = state,
                        userDragging = userDragging,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 暂停时的中央"继续"：64dp 玻璃圆（设计稿 .bigplay），
                    // 播放中不出现——它只在"用户可能需要我"的时刻存在
                    CenterResumeButton(
                        visible = state == PlaybackState.Paused && !userDragging,
                        onPlay = ::togglePlay,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 播完：画面内的轻量结束态（非模态，不遮全屏、不打断）
                    if (state == PlaybackState.Ended && mediaOpened) {
                        EndedOverlay(
                            onReplay = {
                                player.seekTo(0L)
                                player.play()
                            },
                            hasNext = queueIndex >= 0 && queueIndex < queue.size - 1,
                            onNext = { session.next() },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    SpeedPill(speed = speed, modifier = Modifier.align(Alignment.TopStart))
                    GestureHintPill(
                        text = gestureHint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    PlayerTopBar(
                        visible = controlsVisible,
                        title = headerTitle,
                        // 全屏形态的返回 = **退出全屏**（与系统返回键、Esc 同一语义），
                        // 不是退出播放页。走与返回键相同的出口：设备自然方向就是横向时
                        // （平板）没有"退出全屏"这一步，直接离开播放页——否则这一点会变成
                        // 一个永远点不动的死键。
                        onBack = {
                            exitFullscreenOrLeave()
                            controlsVisible = true
                        },
                        applyInsets = true,
                        // 右上：⋯（选项抽屉）/ 选集。**图标直接摆在顶栏里**，和底栏同一
                        // 材质、同一条 scrim、随控制层一起显隐——原先它们套在一个圆角
                        // 黑框里，像个独立贴在画面上的悬浮物。倍速不在这里出现：
                        // 底栏已有倍速钮，右上角再放一份是重复。
                        trailing = {
                            BarCtlButton(
                                icon = NpIcon.Dots,
                                contentDescription = "播放选项",
                                onClick = { fsPanelVisible = !fsPanelVisible },
                                active = fsPanelVisible,
                            )
                            if (queue.size > 1) {
                                BarCtlButton(
                                    icon = NpIcon.List,
                                    contentDescription = "选集",
                                    onClick = { episodePanelVisible = true },
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    PlayerBottomControls(
                        visible = controlsVisible,
                        applyInsets = true,
                        showTransport = true,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        dragPositionMs = dragPositionMs,
                        userDragging = userDragging,
                        displayPosition = displayPosition,
                        maxPosition = maxPosition,
                        onScrubChange = {
                            onScrubStart()
                            onScrubProgress(it)
                        },
                        onScrubEnd = ::onScrubEnd,
                        isPlaying = state == PlaybackState.Playing,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        speed = speed,
                        speedPickerVisible = speedPickerVisible,
                        onToggleSpeedPicker = {
                            controlsVisible = true
                            speedPickerVisible = !speedPickerVisible
                        },
                        onPickSpeed = ::pickSpeed,
                        subtitleLoaded = subtitleLoaded,
                        onPrevious = { session.previous() },
                        onTogglePlay = ::togglePlay,
                        onNext = { session.next() },
                        onPickSubtitle = { subtitleSheetVisible = true },
                        onToggleFullscreen = {
                            // 切换类出口：只换版式，不把用户弹出播放页
                            requestExitFullscreen()
                            controlsVisible = true
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    // 全屏播放选项右抽屉（设计稿 .fs-panel）
                    FullscreenOptionsPanel(
                        visible = fsPanelVisible,
                        speedText = speedText,
                        subtitleLoaded = subtitleLoaded,
                        loopMode = loopMode,
                        brightnessPercent = brightnessPercent,
                        volumePercent = volumePercent,
                        favorited = favorited,
                        hasSubtitles = true,
                        queueSize = queue.size,
                        queueIndex = queueIndex,
                        onDismiss = { fsPanelVisible = false },
                        onLoopMode = { mode ->
                            session.setLoopMode(mode)
                            toast.show("循环模式：" + when (mode) {
                                NpLoopMode.OFF -> "顺序播放"
                                NpLoopMode.ONE -> "单集循环"
                                NpLoopMode.ALL -> "列表循环"
                                NpLoopMode.SHUFFLE -> "随机播放"
                            })
                        },
                        onSpeed = {
                            fsPanelVisible = false
                            controlsVisible = true
                            speedPickerVisible = true
                        },
                        onSubtitle = { subtitleSheetVisible = true },
                        onEpisodes = { episodePanelVisible = true; fsPanelVisible = false },
                        onBrightness = ::setBrightnessPercent,
                        onVolume = ::setVolumePercent,
                        onPip = ::enterPip,
                        onFavorite = {
                            favorited = !favorited
                            toast.show(if (favorited) "已加入收藏" else "已取消收藏")
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                }
                FullscreenForm()
            }

            // ---------------- 形态②：竖屏（上视频 + 下信息区，§4.1）----------------
            // 不使用沉浸（系统状态栏常驻）；顶部**常驻玻璃 playerbar**（设计稿
            // `.playerbar`：返回 + 标题 + mono 副标题，不随控制层自动隐藏），
            // 控制层只在视频区内自动隐；标题/元数据/主操作/选集在下方信息区，可滚动。
            else -> {
                @Composable fun PortraitForm() {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                        ),
                ) {
                    // 常驻 playerbar（设计稿 `.playerbar`）：播放中的返回出口永远在
                    PlayerBar(
                        title = headerTitle,
                        subtitle = listOf(
                            headerContainer,
                            currentItem?.folder ?: "本地文件",
                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        onBack = navigator::popBackStack,
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val ratio = if (videoAspectRatio > 0f) videoAspectRatio else 16f / 9f
                        // 横屏视频按 16:9 定高；竖屏视频限高（55% 窗口高），
                        // 免得一条 9:16 视频把信息区整个挤出屏幕
                        val stageHeight = if (ratio >= 1f) {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .height(maxHeight * 0.55f)
                        }
                        Box(
                            modifier = stageHeight
                                .background(Color.Black)
                                .npStageGestures(
                                    gesturesEnabled = gesturesEnabled,
                                    onTap = { controlsVisible = !controlsVisible },
                                    onBoostStart = {
                                        boostPreviousSpeed = speed
                                        player.setSpeed(2f)
                                        boostHintVisible = true
                                    },
                                    onBoostEnd = {
                                        if (boostHintVisible) {
                                            player.setSpeed(boostPreviousSpeed)
                                            boostHintVisible = false
                                        }
                                    },
                                    onDoubleTap = ::seekByGesture,
                                    onSwipeBrightness = ::adjustBrightnessBy,
                                    onSwipeVolume = ::adjustVolumeBy,
                                ),
                        ) {
                            VideoStage(
                                player = player,
                                videoAspectRatio = videoAspectRatio,
                                cues = cues,
                                overlays = overlays,
                                overlayMedia = overlayMedia,
                                navigator = navigator,
                                state = state,
                                userDragging = userDragging,
                                modifier = Modifier.fillMaxSize(),
                            )
                            CenterResumeButton(
                                visible = state == PlaybackState.Paused && !userDragging,
                                onPlay = ::togglePlay,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            if (state == PlaybackState.Ended && mediaOpened) {
                                EndedOverlay(
                                    onReplay = {
                                        player.seekTo(0L)
                                        player.play()
                                    },
                                    hasNext = queueIndex >= 0 && queueIndex < queue.size - 1,
                                    onNext = { session.next() },
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            SpeedPill(speed = speed, modifier = Modifier.align(Alignment.TopStart))
                            GestureHintPill(
                                text = gestureHint,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            // 长按 2.0x 提示胶囊（设计稿 .gest-hint：黑 62% 丸 + mono 13sp）
                            androidx.compose.animation.AnimatedVisibility(
                                visible = boostHintVisible,
                                enter = androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.fadeOut(),
                                modifier = Modifier.align(Alignment.Center),
                            ) {
                                Text(
                                    text = "长按 2.0x 快进",
                                    style = NpMono,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(NpRadius.chip))
                                        .background(Color.Black.copy(alpha = 0.62f))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            // 视频区底部：进度 + 时间 + 全屏（§4.1 的版式）；
                            // 顶栏已上移为常驻 playerbar，舞台内不再放返回键
                            PlayerBottomControls(
                                visible = controlsVisible,
                                applyInsets = false,
                                showTransport = false,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                dragPositionMs = dragPositionMs,
                                userDragging = userDragging,
                                displayPosition = displayPosition,
                                maxPosition = maxPosition,
                                onScrubChange = {
                                    onScrubStart()
                                    onScrubProgress(it)
                                },
                                onScrubEnd = ::onScrubEnd,
                                isPlaying = state == PlaybackState.Playing,
                                hasPrevious = hasPrevious,
                                hasNext = hasNext,
                                speed = speed,
                                speedPickerVisible = speedPickerVisible,
                                onToggleSpeedPicker = {
                                    controlsVisible = true
                                    speedPickerVisible = !speedPickerVisible
                                },
                                onPickSpeed = ::pickSpeed,
                                subtitleLoaded = subtitleLoaded,
                                onPrevious = { session.previous() },
                                onTogglePlay = ::togglePlay,
                                onNext = { session.next() },
                                onPickSubtitle = { subtitleSheetVisible = true },
                                onToggleFullscreen = {
                                    setFullscreen(true)
                                    controlsVisible = true
                                },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }

                    // --- 信息区（标题 / 元数据 / 主操作 / 选集）---
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            )
                            .verticalScroll(rememberScrollState()),
                    ) {
                        val current = currentItem
                        // 标题不在这里重复：设计稿的标题归常驻 playerbar（`.pttl b`），
                        // 信息区首行就是 pinfo 的 mono 元数据行。
                        val meta = buildList {
                            current?.sizeBytes?.let { bytes ->
                                if (bytes > 0) add(formatFileSize(bytes))
                            }
                            current?.resolutionLabel
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { add(it) }
                            headerContainer?.let { add(it) }
                            current?.folder
                                ?.takeIf { it.isNotBlank() }
                                ?.let { add(it) }
                        }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = NpMono.copy(fontSize = 11.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 13.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                        // 主操作行（设计稿 .pactions/.btnp）：**只有 3 个**——
                        // 收藏 / 播放选项 / 仅听音频。播放/暂停由画面中央大钮承担、
                        // 选集有内联 ep-inline 卡片、全屏在时间行右端，都不在这里重复。
                        // 等分横排（紧凑档 .btnp{flex:1 1 0}），选中态 accent。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerActionButton(
                                glyph = ActionGlyph.Fav,
                                label = if (favorited) "已收藏" else "收藏",
                                active = favorited,
                                onClick = {
                                    favorited = !favorited
                                    toast.show(if (favorited) "已加入收藏" else "已取消收藏")
                                },
                                modifier = Modifier.weight(1f),
                            )
                            PlayerActionButton(
                                glyph = ActionGlyph.More,
                                label = "播放选项",
                                onClick = { moreSheetVisible = true },
                                modifier = Modifier.weight(1f),
                            )
                            PlayerActionButton(
                                glyph = ActionGlyph.Audio,
                                label = "仅听音频",
                                onClick = {
                                    session.toggleAudioOnly()
                                    toast.show("已进入仅听音频模式")
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 续播提示（设计稿 .notice.resume）：明确的"上次看到哪儿"+ 一键从头
                        val resumeFraction = if (durationMs > 0) {
                            positionMs.toFloat() / durationMs
                        } else {
                            0f
                        }
                        if (resumeFraction in 0.01f..0.99f && !userDragging) {
                            // 设计稿 .notice.resume：accent-soft 底 + accent-line 边框胶囊
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = NpSpace.lg, vertical = NpSpace.xs)
                                    .clip(RoundedCornerShape(NpRadius.chip))
                                    .background(ink0.accentSoft)
                                    .border(1.dp, ink0.accentLine, RoundedCornerShape(NpRadius.chip))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "上次看到 ${(resumeFraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "从头播放",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = androidx.compose.ui.text.font.FontWeight(650),
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = NpSpace.md)
                                        .clip(RoundedCornerShape(NpRadius.chip))
                                        .clickable {
                                            player.seekTo(0)
                                            pendingSeekMs = 0L
                                            controlsVisible = true
                                        }
                                        .padding(horizontal = NpSpace.sm, vertical = NpSpace.xs),
                                )
                            }
                        }
                        // C21 软解前置提示（设计稿 .notice.warn）：探测发现只有软件解码器可用时
                        // 在信息区顶部给一条 warn 胶囊——"能播，但可能发热/掉帧"必须提前说，
                        // 而不是等用户自己发现卡顿。
                        val decodeNotice by player.decodeNotice.collectAsStateWithLifecycle()
                        if (decodeNotice != null) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = NpSpace.lg, vertical = NpSpace.xs)
                                    .clip(RoundedCornerShape(NpRadius.chip))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = NpIcon.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = decodeNotice.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        // 选集：内联卡片列表（设计稿 .ep-inline）
                        if (queue.size > 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = NpSpace.md,
                                        end = NpSpace.md,
                                        top = NpSpace.md,
                                    )
                                    .clip(RoundedCornerShape(NpRadius.card))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .border(
                                        1.dp,
                                        LocalNpInk.current.border,
                                        RoundedCornerShape(NpRadius.card),
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = NpSpace.lg,
                                            end = NpSpace.lg,
                                            top = NpSpace.md,
                                            bottom = NpSpace.xs,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 设计稿 .ep-inline .epi-head：eyebrow「选集」+ 右侧 mono 计数
                                    NpEyebrow(text = "选集")
                                    Box(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${queueIndex + 1} / ${queue.size}",
                                        style = NpMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // 设计稿 .epi-list：max-height 264 内部滚动（集数多时不再拉长信息区）
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 264.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = NpSpace.sm),
                                ) {
                                    queue.forEachIndexed { index, item ->
                                        EpisodeRow(
                                            index = index,
                                            title = item.title.ifBlank { item.id },
                                            durationMs = item.durationMs,
                                            selected = index == queueIndex,
                                            onClick = { session.selectQueueIndex(index) },
                                        )
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.height(NpSpace.xxl))
                    }
                }
                }
                PortraitForm()
            }
        }

        } // else(audioOnly)

        // ---- 错误 / 播完 / 选集：所有形态的最上层（模态）----
        //
        // 三者都受 [mediaOpened] 门控：装载完成前引擎状态还带着**上一个媒体**的终值，
        // 不过闸就会在进入播放页时先闪一屏"播放结束"或旧错误面板。
        // 重新装载（重试）时把闸门拉回去，避免旧错误在新一轮探测期间继续显示。

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
        if (state is PlaybackState.Error && mediaOpened) {
            val error = state as PlaybackState.Error
            ErrorPanel(
                message = error.message,
                failure = error.failure,
                mediaUri = uri,
                actionLabel = "重试",
                onAction = {
                    // 重试必须重载**失败的那一项**：错误可能来自选集切换而非路由入参，
                    // 无条件用路由 uri 会把"重试"变成"打开另一条文件"。
                    // 闸门（mediaOpened）不用拉回：装载入口会同步把状态降到 Buffering，
                    // 旧错误面板随之消失；若再次失败仍能重新浮出，不会把用户卡在黑屏。
                    val items = queue
                    val idx = queueIndex
                    if (items.isNotEmpty() && idx in items.indices) {
                        player.setQueue(items, idx, 0L)
                    } else {
                        player.prepare(uri, mimeType)
                    }
                    player.play()
                },
                onBack = navigator::popBackStack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 「播放结束」**不再是居中大弹窗**。
        //
        // 理由：它不是错误，只是"这一条放完了"这个正常事实。用模态大窗承接，
        // 一来把正常状态渲染成故障观感，二来盖住画面、逼用户先做一次选择才能继续看。
        // 现在它落进**画面内的轻量结束态**（见 [EndedOverlay]）：
        // 中央一个重播键 + 底下一行提示，点画面任意处或返回都不受阻；
        // 若是多集队列，media3 已经自动续播下一集，这里根本不会出现。
        //
        // 注意它**不在** `mediaOpened` 闸门外：装载完成才会真的 Ended，
        // 而闸门本身是为了防止"上一个媒体的 Ended 被误读"，两者互补。

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

        // ---- 底部弹层（设计稿 .sheet 体系）：字幕 / 播放选项 ----
        // 倍速不在这里：它改成贴在倍速钮上方的选择条（SpeedChoiceRow）。
        if (subtitleSheetVisible) {
            NpSheet(
                onDismiss = { subtitleSheetVisible = false },
                title = "字幕",
            ) {
                NpOptRow(
                    text = "关闭字幕",
                    selected = !subtitleLoaded,
                    onClick = {
                        player.setSubtitle(null)
                        scope.launch { settings.setSubtitleUri(mediaId, null) }
                        toast.show("字幕：关闭")
                        subtitleSheetVisible = false
                    },
                )
                NpOptRow(
                    text = "挂载外挂字幕（srt / vtt / ass）",
                    selected = subtitleLoaded,
                    onClick = {
                        subtitleSheetVisible = false
                        subtitlePicker.launch(arrayOf("*/*"))
                    },
                )
                if (savedSubtitle != null) {
                    Text(
                        text = "已挂载：" + savedSubtitle!!.substringAfterLast('/').substringBeforeLast('.'),
                        style = NpMonoSmall,
                        color = ink0.muted,
                        modifier = Modifier.padding(top = 8.dp, start = 12.dp),
                    )
                }
            }
        }
        if (moreSheetVisible) {
            NpSheet(
                onDismiss = { moreSheetVisible = false },
                title = "播放选项",
            ) {
                // 设计稿 .opt-grid：**真 4 列网格**（repeat(4,1fr)、gap 14/6）。
                // 顺序与条目照抄设计稿：画中画 / 锁定横屏 / 字幕 / 选集 / 收藏 /
                // 文件信息 / 倍速 / 移除——选集只在多集时出现，正好凑满 2 行。
                // 残行不足 4 个时靠左（CSS grid 行为），不能用 SpaceEvenly——那会把
                // 残行居中，列就对不上上一行了。
                val quickTiles = buildList {
                    add(
                        QuickTileSpec(NpIcon.Pip, "画中画") {
                            moreSheetVisible = false
                            enterPip()
                        },
                    )
                    add(
                        QuickTileSpec(
                            icon = NpIcon.Rotate,
                            label = if (orientationLock == OrientationLock.Landscape) "锁定中" else "锁定横屏",
                            selected = orientationLock == OrientationLock.Landscape,
                        ) {
                            orientationLock = if (orientationLock == OrientationLock.Landscape) {
                                OrientationLock.FollowSensor
                            } else {
                                OrientationLock.Landscape
                            }
                            toast.show(
                                if (orientationLock == OrientationLock.Landscape) "已锁定横屏" else "已解除锁定",
                            )
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.Subtitle, "字幕", selected = subtitleLoaded) {
                            moreSheetVisible = false
                            subtitleSheetVisible = true
                        },
                    )
                    if (queue.size > 1) {
                        add(
                            QuickTileSpec(NpIcon.List, "选集") {
                                moreSheetVisible = false
                                episodePanelVisible = true
                            },
                        )
                    }
                    add(
                        QuickTileSpec(
                            icon = NpIcon.Heart,
                            label = if (favorited) "已收藏" else "收藏",
                            selected = favorited,
                        ) {
                            favorited = !favorited
                            toast.show(if (favorited) "已加入收藏" else "已取消收藏")
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.Info, "文件信息") {
                            moreSheetVisible = false
                            fileInfoVisible = true
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.Speed, "倍速") {
                            moreSheetVisible = false
                            controlsVisible = true
                            speedPickerVisible = true
                        },
                    )
                    // 库侧删除尚未落地（与收藏同一批数据层缺口），
                    // 先给 danger 形态 + 诚实提示，不做假动作。
                    add(
                        QuickTileSpec(NpIcon.Trash, "移除", danger = true) {
                            toast.show("从库中移除尚未支持")
                        },
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    quickTiles.chunked(4).forEach { rowTiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowTiles.forEach { tile ->
                                NpQuickTile(
                                    icon = tile.icon,
                                    label = tile.label,
                                    selected = tile.selected,
                                    danger = tile.danger,
                                    onClick = tile.onClick,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - rowTiles.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
                // 设计稿顺序：opt-grid → 循环模式 → 屏幕与声音（亮度/音量）
                // 设计稿 .sheet-sec h4：15px/650——比 sheet 标题（.sheet h3, 17px）小一号，
                // 是"分区标题"而不是"第二个标题"。
                Text(
                    text = "循环模式",
                    style = NpTypography.titleSmall,
                    color = ink0.fg,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(ink0.fgSoft)
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        NpLoopMode.OFF to NpIcon.ListView,
                        NpLoopMode.ONE to NpIcon.Rotate,
                        NpLoopMode.SHUFFLE to NpIcon.Sort,
                        NpLoopMode.ALL to NpIcon.Sound,
                    ).forEach { (mode, icon) ->
                        val on = loopMode == mode
                        Box(
                            modifier = Modifier
                                // 设计稿 .loop-seg button：44×38 r999（比正方稍扁）
                                .size(width = 44.dp, height = 38.dp)
                                .clip(RoundedCornerShape(NpRadius.chip))
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .npPressScale(onClick = {
                                    session.setLoopMode(mode)
                                    toast.show("循环模式：" + loopLabel(mode))
                                }, pressedScale = 0.94f),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = loopLabel(mode),
                                tint = if (on) MaterialTheme.colorScheme.onPrimary else ink0.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                // 设计稿 .sheet-sec「屏幕与声音」：亮度 / 音量两条滑杆
                Text(
                    text = "屏幕与声音",
                    style = NpTypography.titleSmall,
                    color = ink0.fg,
                    modifier = Modifier.padding(top = 16.dp),
                )
                // 面板变体（onDark=false）：sheet 是浅色 surface，滑杆行必须取主题字色，
                // 否则沿用 fs-panel 的白色会在浅底上整片消失（实测：标签/数值全不见）。
                FpSlider(
                    icon = NpIcon.Moon,
                    label = "亮度",
                    value = brightnessPercent,
                    onValueChange = ::setBrightnessPercent,
                    onDark = false,
                )
                FpSlider(
                    icon = NpIcon.Sound,
                    label = "音量",
                    value = volumePercent,
                    onValueChange = ::setVolumePercent,
                    onDark = false,
                )
                // 设计稿 .check-row：「允许屏幕截图」开关（关 = FLAG_SECURE 禁止截屏/录屏）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(NpRadius.tile))
                        .clickable {
                            screenshotBlocked = !screenshotBlocked
                            toast.show(if (screenshotBlocked) "已禁止屏幕截图" else "已允许屏幕截图")
                        }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 设计稿 .check-row：14px 主题字色 + 顶边分隔线。这里不能沿用
                    // fs-panel 的白色——sheet 是浅色 surface，白字会整片消失。
                    Icon(
                        imageVector = NpIcon.Info,
                        contentDescription = null,
                        tint = ink0.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "允许屏幕截图",
                        style = NpTypography.bodyMedium,
                        color = ink0.fg,
                        modifier = Modifier.weight(1f),
                    )
                    NpSwitch(
                        checked = !screenshotBlocked,
                        onCheckedChange = { allow ->
                            screenshotBlocked = !allow
                            toast.show(if (screenshotBlocked) "已禁止屏幕截图" else "已允许屏幕截图")
                        },
                    )
                }
            }
        }

        // C18：文件信息卡（真实元数据，替代原先只报标题+编码的 toast）
        if (fileInfoVisible) {
            FileInfoCard(
                title = headerTitle,
                item = currentItem,
                durationMs = durationMs,
                container = headerContainer,
                onDismiss = { fileInfoVisible = false },
            )
        }

        // 操作反馈（设计稿 .toast）
        NpToastHost(state = toast, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * C18 文件信息卡：标题 / 大小 / 分辨率 / 编码 / 文件夹 / 时长 的真实值。
 *
 * 用居中卡片而不是 sheet：它是**只读查看**，不需要底部大面板的权重；
 * 点任意处关闭（scrim 即关闭手势）。
 */
@Composable
private fun FileInfoCard(
    title: String,
    item: MediaItem?,
    durationMs: Long,
    container: String?,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(NpRadius.sheet))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, LocalNpInk.current.border, RoundedCornerShape(NpRadius.sheet))
                .padding(20.dp),
        ) {
            Text(
                text = "文件信息",
                style = NpTypography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            val rows = buildList {
                add("标题" to title)
                item?.sizeBytes?.takeIf { it > 0 }?.let { add("大小" to formatFileSize(it)) }
                item?.resolutionLabel?.takeIf { it.isNotEmpty() }?.let { add("分辨率" to it) }
                add("编码" to (container ?: "未知"))
                item?.folder?.takeIf { it.isNotBlank() }?.let { add("文件夹" to it) }
                if (durationMs > 0) add("时长" to formatTimestamp(durationMs))
            }
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = label,
                        style = NpTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        text = value,
                        style = NpMonoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 画面内的「播放结束」轻量态。
 *
 * 与错误面板的分工：错误是**异常**（需要解释原因、给出重试/外部打开/复制地址多条出路），
 * 所以用居中的 `ErrorPanel`；"放完了"是**正常终点**，只需要一个"重播"和一句提示，
 * 用户还能继续看画面、继续点其他地方。
 *
 * 视觉沿用设计稿 `.bigplay` / `.gest-hint` 的深色半透明丸形：
 * 中央 64dp 重播键 + 下方一行等宽小字，全部压在画面之上而非遮住整屏。
 */
@Composable
private fun EndedOverlay(
    onReplay: () -> Unit,
    hasNext: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(NpRadius.chip))
                    .npPressScale(onClick = onReplay, pressedScale = 0.92f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NpIcon.Rotate,
                    contentDescription = "重新播放",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = "已播放完",
                style = NpMonoSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .padding(top = NpSpace.md)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // 队列里还有下一集时给一条显式出路：自动连播是所有集的默认行为，
            // 但用户手动点进来看单集的场景下，"下一集"仍然应该是可点的。
            if (hasNext) {
                Text(
                    text = "下一集",
                    style = NpMonoSmall,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = NpSpace.sm)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .npPressScale(onClick = onNext, pressedScale = 0.95f)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = NpRadius.sheet, topEnd = NpRadius.sheet),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 只占下半屏：上半留出画面，用户能边看边选
                .fillMaxHeight(0.55f)
                // E49：横屏手势导航条可能在左/右，用 safeDrawing 而非只认底部
                .windowInsetsPadding(WindowInsets.safeDrawing),
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
    // 设计稿 .track .knob：accent 圆，12dp，拖动长大到 18dp（此前白色 11/16）
    Box(
        modifier = Modifier
            .size(if (dragging) 18.dp else 12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** B 站式进度条轨道：细线圆角，拖动时略加粗；fill 比例与滑块位置由 Slider 保证一致 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTrack(sliderState: SliderState, dragging: Boolean) {
    val activeColor = MaterialTheme.colorScheme.primary
    // 压在视频上：未播段用白色半透明
    val inactiveColor = Color.White.copy(alpha = 0.28f)
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
            color = inactiveColor,
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = activeColor,
            size = Size(size.width * fraction, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/**
 * 进度条行（设计稿 `.track`）：**独占一行**的 3dp 细线轨道 + 12dp accent 圆 knob。
 *
 * 时间码不在这行——设计稿把 `.track` 与 `.time-row` 分成上下两行，滑块因此拿到整幅
 * 宽度；挤在同一行里会让轨道只剩中间一小段（此前正是这样，与设计稿不符）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTrackRow(
    displayPosition: Float,
    maxPosition: Float,
    userDragging: Boolean,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = displayPosition.coerceIn(0f, maxPosition),
        onValueChange = onScrubChange,
        onValueChangeFinished = onScrubEnd,
        valueRange = 0f..maxPosition,
        thumb = { PlaybackThumb(dragging = userDragging) },
        track = { sliderState ->
            PlaybackTrack(sliderState, dragging = userDragging)
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 时间行内的图标钮（设计稿 `.time-row .bar-ctl`）：38dp 方形、9dp 圆角、白色描边图标，
 * 激活态转 accent。
 *
 * 压在视频上的是**纯图标**而不是文字胶囊——设计稿这条时间行右侧依次是
 * 字幕钮 / 倍速钮 / 总时长 / 全屏钮（§4.1），"全屏"此前被做成信息区的一颗文字胶囊，
 * 位置与形态都不对。
 */
@Composable
private fun BarCtlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 连播传输键（上一集 / 播放暂停 / 下一集，§8.2）。
 *
 * 无上/下一集时按钮**禁用而非隐藏**：位置固定，避免误触。按钮走 session
 * （与迷你条、通知栏同一份队列状态）。倍速/字幕/选集不在这里重复——它们归控制条。
 */
@Composable
private fun TransportGroup(
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val tint = Color.White
    val disabledTint = tint.copy(alpha = 0.35f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onPrevious,
            enabled = hasPrevious,
            modifier = Modifier.size(NpDimens.transportButton),
        ) {
            Icon(
                imageVector = NpIcon.SkipPrevious,
                contentDescription = "上一集",
                tint = if (hasPrevious) tint else disabledTint,
            )
        }
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(animationSpec = silkSpring(Motion.Field)) +
                    scaleIn(initialScale = 0.7f))
                    .togetherWith(
                        fadeOut(animationSpec = silkSpring(Motion.Field)) +
                            scaleOut(targetScale = 0.7f),
                    )
            },
            label = "playPause",
        ) { playing ->
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(NpDimens.transportButton),
            ) {
                if (playing) {
                    PauseGlyph(tint = tint)
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = tint,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        IconButton(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier.size(NpDimens.transportButton),
        ) {
            Icon(
                imageVector = NpIcon.SkipNext,
                contentDescription = "下一集",
                tint = if (hasNext) tint else disabledTint,
            )
        }
    }
}

/**
 * 倍速选择条：点倍速钮后**贴在钮上方**列出可选倍速。
 *
 * 不用底部弹层——调速只有一排选项，弹层会把整个画面盖住，用户还得把手指
 * 挪到屏幕底下。容器用主题实底（不是半透明），这样压在视频上、
 * 以及浅色主题的仅听音频页里都读得清。
 */
@Composable
internal fun SpeedChoiceRow(
    speed: Float,
    onPick: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NpRadius.button))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, ink.border, RoundedCornerShape(NpRadius.button))
            .padding(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SPEED_PRESETS.forEach { preset ->
                val on = kotlin.math.abs(preset - speed) < 0.001f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onPick(preset) }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = speedLabel(preset),
                        style = NpMono.copy(fontSize = 12.sp),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else ink.fg,
                    )
                }
            }
        }
        // ExoPlayer 经 Sonic 变速默认保音调（PlaybackService.setEnableAudioTrackPlaybackParams）
        Text(
            text = "变速保持音调",
            style = NpMono.copy(fontSize = 11.sp),
            color = ink.muted,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

/**
 * 底部控制层（进度 + 传输件）：压在视频上，用上下渐变 scrim + 白字
 * （横向全屏与竖屏视频区共用）。
 */
@Composable
private fun PlayerBottomControls(
    visible: Boolean,
    applyInsets: Boolean,
    showTransport: Boolean,
    positionMs: Long,
    durationMs: Long,
    dragPositionMs: Float,
    userDragging: Boolean,
    displayPosition: Float,
    maxPosition: Float,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    speed: Float,
    speedPickerVisible: Boolean,
    onToggleSpeedPicker: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    subtitleLoaded: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPickSubtitle: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idleColor = Color.White.copy(alpha = 0.9f)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (applyInsets) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = NpSpace.lg),
        ) {
            // 行 1：进度条独占一行（设计稿 `.track`）
            PlaybackTrackRow(
                displayPosition = displayPosition,
                maxPosition = maxPosition,
                userDragging = userDragging,
                onScrubChange = onScrubChange,
                onScrubEnd = onScrubEnd,
                modifier = Modifier.padding(top = 6.dp),
            )
            // 倍速选择条：贴在倍速钮上方展开（替代原底部弹层——调速不该
            // 把整个画面盖住，也不该让用户的手从控制条跑到屏幕底下）。
            AnimatedVisibility(
                visible = speedPickerVisible,
                enter = fadeIn(animationSpec = silkSpring(Motion.Overlay)) +
                    slideInVertically(animationSpec = silkSpring(Motion.Overlay)) { it },
                exit = fadeOut(animationSpec = silkSpring(Motion.OverlayExit)) +
                    slideOutVertically(animationSpec = silkSpring(Motion.OverlayExit)) { it },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SpeedChoiceRow(
                        speed = speed,
                        onPick = onPickSpeed,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // 行 2：控制条——时间码 | 居中的传输键 | 字幕·倍速·总时长·全屏。
            // **三组共用一条垂直中心线**：传输键原先自成一排，比右侧图标低半格，
            // 播放栏因此白白多占一行高（横屏上视频本就吃紧，控制层越薄越好）。
            // 左右两个等权槽把中间那组顶到真居中——横向窗口最窄也有 ~500dp，
            // 容得下右侧图标组，不会出现等权槽不够宽而裁切的情况。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = formatTimestamp(if (userDragging) dragPositionMs.toLong() else positionMs),
                        // 设计稿 `.time-row{font-size:12px}`——比 NpMono 默认 13sp 小一号，
                        // 与下方 pinfo 的 mono 行视觉同级（时间码不该比元数据更抢眼）。
                        style = NpMono.copy(fontSize = 12.sp),
                        color = if (userDragging) MaterialTheme.colorScheme.onPrimary else idleColor,
                        modifier = if (userDragging) {
                            Modifier
                                .clip(RoundedCornerShape(NpRadius.chip))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        } else {
                            Modifier
                        },
                    )
                }
                if (showTransport) {
                    TransportGroup(
                        isPlaying = isPlaying,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPrevious = onPrevious,
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BarCtlButton(
                            icon = NpIcon.Subtitle,
                            contentDescription = "字幕",
                            onClick = onPickSubtitle,
                            active = subtitleLoaded,
                        )
                        BarCtlButton(
                            icon = NpIcon.Speed,
                            contentDescription = "倍速播放",
                            onClick = onToggleSpeedPicker,
                            active = speed != 1f || speedPickerVisible,
                        )
                        Text(
                            text = formatTimestamp(durationMs),
                            style = NpMono,
                            color = idleColor,
                        )
                        BarCtlButton(
                            icon = NpIcon.Expand,
                            contentDescription = "全屏切换",
                            onClick = onToggleFullscreen,
                        )
                    }
                }
            }
            Box(modifier = Modifier.height(NpSpace.sm))
        }
    }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = silkSpring(Motion.Overlay)) +
                slideInVertically(animationSpec = silkSpring(Motion.Overlay)) { it },
            exit = fadeOut(animationSpec = silkSpring(Motion.OverlayExit)) +
                slideOutVertically(animationSpec = silkSpring(Motion.OverlayExit)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                    ),
                ),
        ) {
            content()
        }
    }
}

/**
 * 顶部控制层：返回 + 标题（+ 可选右侧图标），压在视频上（渐变 scrim + 白字）。
 *
 * [trailing] 的图标与底栏右端图标**同一条竖基线**（都用 NpSpace.lg 收边），
 * 这样上下两栏读起来是一套控件，而不是两块各自为政的悬浮物。
 */
@Composable
private fun PlayerTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    applyInsets: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = silkSpring(Motion.Overlay)) +
            slideInVertically(animationSpec = silkSpring(Motion.Overlay)) { -it },
        exit = fadeOut(animationSpec = silkSpring(Motion.OverlayExit)) +
            slideOutVertically(animationSpec = silkSpring(Motion.OverlayExit)) { -it },
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (applyInsets) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(start = 8.dp, end = NpSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(NpDimens.transportButton)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

/**
 * 中央"继续播放"钮（设计稿 `.bigplay`）：64dp 玻璃圆 + 白三角。
 * 只在暂停时出现——播放中它不该占着画面。
 */
@Composable
private fun CenterResumeButton(
    visible: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = silkSpring(Motion.Field)) +
            scaleIn(initialScale = 0.86f, animationSpec = silkSpring(Motion.Field)),
        exit = fadeOut(animationSpec = silkSpring(Motion.Field)) +
            scaleOut(targetScale = 0.86f, animationSpec = silkSpring(Motion.Field)),
        modifier = modifier,
    ) {
        NpGlass(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            elevation = 0.dp,
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * 手势反馈胶囊（"亮度 62%" / "音量 40%" / "快进 10 秒"）——屏幕中央，1.2s 自动消失。
 *
 * 没有它，滑动调节就是"盲调"：用户不知道当前值，也不知道手势是否被识别。
 */
@Composable
private fun GestureHintPill(text: String?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Field)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Field)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = text.orEmpty(),
                style = NpMono.copy(fontSize = 13.sp),
                color = Color.White,
            )
        }
    }
}

/**
 * 倍速常驻胶囊（§4.6）：任何非 1.0x 状态在左上角常驻，**不随控制层显隐**——
 * "我现在不是正常速度"必须永远看得见。
 */
@Composable
private fun SpeedPill(speed: Float, modifier: Modifier = Modifier) {    if (speed == 1f) return
    Box(
        modifier = modifier
            .padding(start = NpSpace.lg, top = 56.dp)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "%.2fx".format(speed).removeSuffix("0").removeSuffix("."),
            style = NpMono,
            color = Color.White,
        )
    }
}

/** 播放页主操作行的图标族（设计稿 .pactions 的三个 `.btnp`） */
private enum class ActionGlyph { More, Audio, Fav }

/**
 * 播放页主操作按钮（设计稿 `.btnp`）：**圆形图标 + 11px 文字**，等分横排。
 *
 * 为什么不是"大实底按钮"：播放页的主操作已经由画面中央的大播放钮承担，
 * 底部这排是"轻动作"——实底会把 accent"一色一义"的语义（只给"正在播放"
 * 与主操作）稀释掉。选中态用 accent 文字色，不加底色。
 */
@Composable
private fun PlayerActionButton(
    glyph: ActionGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val ink = LocalNpInk.current
    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = NpSpace.sm),
    ) {
        when (glyph) {
            ActionGlyph.More -> Icon(
                imageVector = NpIcon.Dots,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            ActionGlyph.Audio -> Icon(
                imageVector = NpIcon.Headphones,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            ActionGlyph.Fav -> Icon(
                imageVector = NpIcon.Heart,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 信息区的选集行（§5.8 列表行语言）：选中 = 3dp accent 竖条 + accent 文字 + 静态声柱。
 * 声柱是**静态**的——"循环动画"在禁区清单里（呼吸灯/旋转背景同族）。
 */
@Composable
private fun EpisodeRow(
    index: Int,
    title: String,
    durationMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ink = LocalNpInk.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ink.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = NpSpace.lg, vertical = 12.dp),
    ) {
        // 选中标记：3×20dp accent 竖条（颜色之外的第二种表达，§0.1 原则 3）
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Text(
            text = "${index + 1}",
            style = NpMono,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(start = NpSpace.sm, end = NpSpace.md),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            PlayingBars(modifier = Modifier.padding(horizontal = NpSpace.sm))
        }
        if (durationMs > 0) {
            Text(
                text = formatTimestamp(durationMs),
                style = NpMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 正在播放的静态声柱（三根，高度不等）——表达状态，不做循环动画 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(5.dp, 13.dp, 9.dp).forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 视频舞台：画面 + 字幕层 + 覆盖层扩展点 + 缓冲指示。
 * 两种形态共用——形态差异只在"舞台外"的版式，不重复实现播放链路。
 */
@Composable
private fun VideoStage(
    player: VideoPlayer,
    videoAspectRatio: Float,
    cues: List<SubtitleCue>,
    overlays: List<PlaybackOverlay>,
    overlayMedia: MediaItem,
    navigator: Navigator,
    state: PlaybackState,
    userDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 视频画面：按视频分辨率比例渲染，黑边交给布局而非拉伸。
        // 竖向片（比例 < 1）改「先匹配高度」：横向窗口里默认的先宽后高会被
        // 容器高度上限截断，宽度却仍按容器给，画面被压扁（平板横屏放 9:16 实测如此）。
        Box(
            modifier = Modifier.then(
                if (videoAspectRatio > 0f) {
                    Modifier.aspectRatio(
                        videoAspectRatio,
                        matchHeightConstraintsFirst = videoAspectRatio < 1f,
                    )
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
        // 覆盖层扩展点：字幕/弹幕等插件动态挂载（E23）
        overlays.forEach { overlay ->
            overlay.Render(
                PlaybackContext(
                    media = overlayMedia,
                    player = player,
                    navigator = navigator,
                ),
            )
        }
        // 缓冲指示
        if (state == PlaybackState.Buffering && !userDragging) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

/** 双竖条暂停图形（material-icons-core 不含 Pause，手绘保持视觉一致） */
@Composable
private fun PauseGlyph(
    tint: Color = Color.White,
    barWidth: Dp = 5.dp,
    barHeight: Dp = 26.dp,
) {
    Row {
        Box(
            modifier = Modifier
                .size(width = barWidth, height = barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Box(modifier = Modifier.size(width = 8.dp, height = 1.dp))
        Box(
            modifier = Modifier
                .size(width = barWidth, height = barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
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
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (actionLabel != null && onAction != null) {
                ErrorPanelButton(text = actionLabel, primary = true, onClick = onAction)
            }
            if (mediaUri != null) {
                ErrorPanelButton(
                    text = "用其他播放器打开",
                    onClick = {
                        if (!openExternally(context, mediaUri)) {
                            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                ErrorPanelButton(
                    text = "复制地址",
                    onClick = {
                        clipboard.setText(AnnotatedString(mediaUri))
                        Toast.makeText(context, "已复制文件地址", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            ErrorPanelButton(text = "返回媒体库", onClick = onBack)
        }
    }
}

/**
 * 错误/结束面板的按钮——**不用 Material 的 `Button`**。
 *
 * `Button` 自带 40dp 高、`primary` 实底与 M3 的涟漪/形状，与设计稿 `.lf-btn`
 * （丸形、`.list-foot .lf-btn.primary` 为 accent 实底 + 反色字）不是一套语言；
 * 混用会让这个面板在三处按钮上看起来像"另一个 App 弹出来的对话框"。
 */
@Composable
private fun ErrorPanelButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = Modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(if (primary) ink.accent else Color.White.copy(alpha = 0.16f))
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(NpRadius.chip),
                    )
                },
            )
            .npPressScale(onClick = onClick, pressedScale = 0.97f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NpTypography.labelLarge,
            color = if (primary) ink.bg else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * 常驻 playerbar（设计稿 `.playerbar`）：玻璃条 + 返回 + 标题/mono 副标题。
 * **不随控制层自动隐藏**——播放中的返回出口永远在（03 审计 #1 的核心修复）。
 */
@Composable
private fun PlayerBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .npGlassSurface(ink, topHighlight = true, bottomHairline = true)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NpIconButton(
            icon = NpIcon.ArrowLeft,
            contentDescription = "返回",
            onClick = onBack,
            size = 38.dp,
            tint = ink.fg,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = NpTypography.titleLarge.copy(fontSize = 16.sp),
                color = ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = NpMonoSmall,
                    color = ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 全屏播放选项右抽屉（设计稿 `.fs-panel`）：宽 min(330dp,78vw)、黑 78% 渐变左缘淡出、
 * 左下角 18dp 圆角，fade + 右滑 24dp 进场（Motion.Drawer / NpEasing.Drawer）。
 */
@Composable
private fun FullscreenOptionsPanel(
    visible: Boolean,
    speedText: String,
    subtitleLoaded: Boolean,
    loopMode: NpLoopMode,
    brightnessPercent: Int,
    volumePercent: Int,
    favorited: Boolean,
    hasSubtitles: Boolean,
    queueSize: Int,
    queueIndex: Int,
    onDismiss: () -> Unit,
    onLoopMode: (NpLoopMode) -> Unit,
    onSpeed: () -> Unit,
    onSubtitle: () -> Unit,
    onEpisodes: () -> Unit,
    onBrightness: (Int) -> Unit,
    onVolume: (Int) -> Unit,
    onPip: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { shown = visible }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(Motion.Drawer, easing = NpEasing.Silk)) +
            slideInHorizontally(animationSpec = tween(Motion.Drawer, easing = NpEasing.Drawer)) { it / 4 },
        exit = fadeOut(animationSpec = tween(if (reduceMotion) 1 else Motion.Drawer)) +
            slideOutHorizontally(animationSpec = tween(if (reduceMotion) 1 else Motion.Drawer)) { it / 4 },
        modifier = modifier,
    ) {
        val widthPx = LocalConfiguration.current.screenWidthDp
        val panelWidth = minOf(330, widthPx * 78 / 100).dp
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(panelWidth)
                    .clip(RoundedCornerShape(bottomStart = 18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.42f),
                                Color.Black.copy(alpha = 0.78f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "播放选项",
                        style = NpTypography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$speedText · ${"%.0f".format(brightnessPercent.toFloat())}%",
                        style = NpMonoSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    NpIconButton(
                        icon = NpIcon.Close,
                        contentDescription = "关闭面板",
                        onClick = onDismiss,
                        size = 32.dp,
                        iconSize = 16.dp,
                        tint = Color.White,
                    )
                }
                FpSectionTitle("循环模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        NpLoopMode.OFF to NpIcon.ListView,
                        NpLoopMode.ONE to NpIcon.Rotate,
                        NpLoopMode.SHUFFLE to NpIcon.Sort,
                        NpLoopMode.ALL to NpIcon.Sound,
                    ).forEach { (mode, icon) ->
                        val on = loopMode == mode
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(11.dp))
                                .npPressScale(onClick = { onLoopMode(mode) }, pressedScale = 0.94f),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = loopLabel(mode),
                                tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
                FpSectionTitle("画面与声音")
                FpSlider("亮度", NpIcon.Moon, brightnessPercent, onBrightness)
                FpSlider("音量", NpIcon.Sound, volumePercent, onVolume)
                FpSectionTitle("播放")
                FpRow(icon = NpIcon.Speed, label = "播放倍速", value = speedText, onClick = onSpeed)
                FpRow(
                    icon = NpIcon.Subtitle,
                    label = "字幕",
                    value = if (subtitleLoaded) "已挂载" else "关闭",
                    active = subtitleLoaded,
                    onClick = onSubtitle,
                )
                if (queueSize > 1) {
                    FpRow(
                        icon = NpIcon.List,
                        label = "选集",
                        value = "${queueIndex + 1} / $queueSize",
                        onClick = onEpisodes,
                    )
                }
                FpSectionTitle("更多")
                FpRow(icon = NpIcon.Pip, label = "画中画", onClick = onPip)
                FpRow(
                    icon = NpIcon.Heart,
                    label = if (favorited) "已收藏" else "收藏",
                    active = favorited,
                    onClick = onFavorite,
                )
            }
        }
    }
}

/** fs-panel 分区小标题（设计稿 .fp-t：mono 11sp 大写 55% 白） */
@Composable
private fun FpSectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = NpEyebrow,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
}

/** fs-panel 行（设计稿 .fp-row）：图标 + 文字 + 右侧 mono 值，on 态 accent */
@Composable
private fun FpRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = NpTypography.bodyMedium,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(text = value, style = NpMonoSmall, color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f))
        }
    }
}

/** `.opt-grid` 的一个圆 tile（图标 / 标签 / 选中 / danger / 动作） */
private data class QuickTileSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * fs-panel / more sheet 共用的滑杆行（设计稿 .slider-row）。
 *
 * [onDark] = true 时按 fs-panel（黑底抽屉）取白；= false 时按 sheet（浅色 surface）
 * 取主题字色——两处底色相反，颜色必须跟着变，否则浅底上的白标签会整片消失。
 */
@Composable
private fun FpSlider(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    onValueChange: (Int) -> Unit,
    onDark: Boolean = true,
) {
    val labelColor = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor = if (onDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = subColor,
                modifier = Modifier.size(18.dp),
            )
            Text(text = label, style = NpTypography.bodyMedium, color = labelColor)
            Box(modifier = Modifier.weight(1f))
            Text(text = "$value", style = NpMonoSmall, color = subColor)
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 舞台手势（设计稿 .gest-hint / §8 手势清单）：
 * - 点按 = 切换控制层；
 * - 长按 ≥420ms = 2.0x 快进（松手恢复原速）；
 * - 双击左/右半屏 = 快退 / 快进 10 秒（设置页「双击动作」）；
 * - 左右滑动 = 左半屏调亮度、右半屏调音量（设置页「启用音量/亮度手势」关闭时整组禁用）。
 *
 * 手势开关只影响"滑动 + 双击"这两组：点按与长按是控制层/倍速的基础交互，
 * 不随偏好关闭——关掉手势的用户仍然需要能呼出控制层。
 */
private fun Modifier.npStageGestures(
    gesturesEnabled: Boolean,
    onTap: () -> Unit,
    onBoostStart: () -> Unit,
    onBoostEnd: () -> Unit,
    onDoubleTap: (forward: Boolean) -> Unit,
    onSwipeBrightness: (deltaPercent: Float) -> Unit,
    onSwipeVolume: (deltaPercent: Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                if (gesturesEnabled) onDoubleTap(offset.x > size.width / 2f)
            },
            onLongPress = { onBoostStart() },
            onPress = {
                // 长按未触发就松手（普通点按）时无需恢复；长按后松手走这里恢复原速
                tryAwaitRelease()
                onBoostEnd()
            },
        )
    }
    .pointerInput(gesturesEnabled) {
        if (!gesturesEnabled) return@pointerInput
        var lastX = 0f
        detectHorizontalDragGestures(
            onDragStart = { lastX = it.x },
            onDragEnd = {},
        ) { change, dragAmount ->
            change.consume()
            // 拖动距离按"屏幕宽度的百分比"换算（满宽 ≈ 100%），
            // 左半屏起手调亮度、右半屏调音量——与主流播放器一致的位置语义
            val delta = dragAmount / size.width * 100f
            if (lastX < size.width / 2f) onSwipeBrightness(delta) else onSwipeVolume(delta)
        }
    }

/** 倍速文本（1.0x / 1.25x …） */
private fun speedLabel(speed: Float): String =
    "%.2fx".format(speed).removeSuffix("0").removeSuffix(".")

/** 循环模式文案 */
private fun loopLabel(mode: NpLoopMode): String = when (mode) {
    NpLoopMode.OFF -> "顺序播放"
    NpLoopMode.ONE -> "单集循环"
    NpLoopMode.ALL -> "列表循环"
    NpLoopMode.SHUFFLE -> "随机播放"
}

/** 允许进入播放/探测/外发链路的 scheme 白名单（E57/E76）。
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

/**
 * 深链只带 uri 时补查真实 MIME（§7.4）。
 *
 * content:// 地址没有扩展名，路由又不带 mime，页头容器名此前只能退到默认值 video/mp4。
 * 这里向 MediaStore 问一次 MIME_TYPE 作为显示真值；查不到（非 MediaStore 来源、
 * 无权限、file:// 等）返回 null，调用方退回原有取值链。
 */
private suspend fun resolveMimeType(context: android.content.Context, uri: String): String? =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver
                .query(
                    uri.toUri(),
                    arrayOf(android.provider.MediaStore.MediaColumns.MIME_TYPE),
                    null, null, null,
                )
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.d("PlayerScreen", "resolveMimeType 查询失败 $uri", e)
            null
        }
    }

/** 深链只带 uri 时的展示名兜底：MediaStore 显示名 → uri 末段 */
private suspend fun resolveDisplayName(context: android.content.Context, uri: String): String =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver
                .query(
                    uri.toUri(),
                    arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.toUri().lastPathSegment.orEmpty()
        } catch (e: SecurityException) {
            // E70 修复：此前与"查不到名字"一视同仁地吞掉，权限问题被伪装成了正常兜底。
            // 深链传入未授权的 content:// 时最常触发这条路径（见 E57）——保留诊断信息。
            Log.d("PlayerScreen", "resolveDisplayName 无权限访问 $uri", e)
            uri.toUri().lastPathSegment.orEmpty()
        } catch (e: Exception) {
            Log.d("PlayerScreen", "resolveDisplayName 查询失败 $uri", e)
            uri.toUri().lastPathSegment.orEmpty()
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
