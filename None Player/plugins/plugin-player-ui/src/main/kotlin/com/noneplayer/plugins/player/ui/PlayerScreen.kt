package com.noneplayer.plugins.player.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaNaturalOrder
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpHaptic
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
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpMaskFade
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npItemFadeSpec
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.rememberNpHaptics
import com.noneplayer.core.ui.rememberReduceMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.core.ui.rememberNpToastState
import com.noneplayer.core.ui.silkSpring
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.NpChapter
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
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
import com.noneplayer.plugins.player.ui.controls.NpSeekBar
import com.noneplayer.plugins.player.ui.controls.StatusClockBattery
import com.noneplayer.plugins.player.ui.gesture.GESTURE_INDICATOR_HOLD_MS
import com.noneplayer.plugins.player.ui.gesture.SeekGestureBubble
import com.noneplayer.plugins.player.ui.gesture.StageTapZone
import com.noneplayer.plugins.player.ui.gesture.StageVerticalMode
import com.noneplayer.plugins.player.ui.gesture.VerticalAdjustIndicator
import com.noneplayer.plugins.player.ui.gesture.npStageGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** 倍速预设（设计稿 `.speeds` 的档位与顺序：0.5x → 2.0x） */
internal val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** 拖动预览 seek 的最小间隔：更密会在本地大文件上反复解码，更疏则画面不跟手 */
private const val SCRUB_PREVIEW_INTERVAL_MS = 200L

/** 播放页自动隐藏控制层延时 */
private const val CONTROLS_AUTO_HIDE_MS = 3500L

/** 「已为您定位到 xx:xx」续播提示的停留时长（B 站同款：亮一下即收，不挡住看片） */
private const val RESUME_NOTICE_MS = 3_200L

/** 舞台双击的 seek 步长（§4.3：左右 1/3 = ∓/±10 秒） */
private const val GESTURE_SEEK_STEP_MS = 10_000L

// 键盘两档 seek / 音量步进与静音兜底档位在 SessionKeySteps（E99：三栏浏览态
// 的会话快捷键要与本页共用同一张表和同一份数字，两处各写迟早会漂）

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
    /** 「从头播放」（长按菜单）：不恢复断点，从 0 起播 */
    fromStart: Boolean = false,
    /**
     * "我要全屏"意图（详情页「全屏观看」/ 右栏 ⛶ = 路由 `fs=1`）：
     * 落地即进全屏——竖窗先请求横向，横窗（平板自然方向）本就是全屏。
     * 假全屏（横窗版式 + 系统栏可见的中间态）已删除：全屏只有沉浸一种（2026-10-07）。
     */
    immersiveEntry: Boolean = false,
) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { kernel.services.get<AppSettings>() }
    // §8.3：断点/历史唯一读写口（引擎装载恢复断点、退页落盘都走它）
    val history = remember { kernel.services.get<HistoryRepository>() }

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
    // E88 留尾：无引擎（插件未启用 / 未知 mime）不再主线程硬抛闪退——
    // 渲染体面失败面板后早退；session.engineError 携带工厂给出的原因。
    // 早退是稳定的（同一 mime 恒 null）：其余组合分支不会因它来回挂卸。
    val engineError by session.engineError.collectAsStateWithLifecycle()
    if (player == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ErrorPanel(
                message = engineError ?: "无法创建播放引擎",
                onBack = navigator::popBackStack,
            )
        }
        return
    }

    // 退页收口（E28 语义保留，但**不再 release 播放器**）：
    // 位置在会话失效前同步读取，落盘用 NonCancellable 协程——它会活过本组合的取消，
    // 但不阻塞主线程（runBlocking 会在每次退页时卡住退出动画，扫描期间尤其明显）。
    DisposableEffect(mediaId, backgroundPlay) {
        onDispose {
            // 退页落盘交给会话的唯一写入方（§8.3：进度落 Room 历史；门槛/看完语义
            // 收在 HistoryRepository.shouldPersist）。落的是**当前真正在播的那一项**：
            // 页内切过集之后路由 mediaId 已不是会话当前项。
            session.persistPosition()
            // 设置页「后台播放」关闭 → 退页即停（并清掉迷你条）；
            // 打开则播放继续在后台服务里跑，迷你条据此持续可见、点回来无缝续播（L0）。
            // 关闭档必须走 stop() 而不是 pause()：pause 只停画面，队列/通知/迷你条都还在，
            // 退页后会残留一条"暂停条"，与「后台播放」关掉的语义不符（L9）。
            // stop() 内部同时清 media3 队列，系统媒体通知随迷你条一起收（L8）。
            if (!backgroundPlay) session.stop()
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
    // 弹层提示的状态位提前到这里：装载链的"字幕已失效"与方向请求被系统驳回
    // 都要靠它说清原因（都在下面更靠前的 LaunchedEffect 里触发）。
    val toast = rememberNpToastState()

    // 本页是否已完成首个媒体的装载。
    //
    // 用来把"装载中"与"真的播完了"分开：`LaunchedEffect` 里 `awaitReady()` 与
    // 队列解析都要花时间，这段时间组合已经在渲染了，而引擎状态仍是**上一个**媒体
    // 留下的值。没有这个闸门时，进新视频会先闪一屏"播放结束"（上一个视频正常播完）
    // 或旧错误面板，再开始播放。
    var mediaOpened by remember(uri) { mutableStateOf(false) }

    /**
     * 左上角「已为您定位到 xx:xx」续播提示的落点（毫秒）；null = 不显示。
     *
     * 只在装载链**真的套用了断点**时由 `ensureSessionPlaying` 的返回值写入，
     * [RESUME_NOTICE_MS] 之后自动清空——不做常驻胶囊（B 站同款：提示一次就收）。
     */
    var resumeNoticeMs by remember(uri) { mutableStateOf<Long?>(null) }

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
        // 2) 装载并起播——与「内嵌播放器」共用同一条链（SessionBootstrap）：
        //    队列 / 断点 / 字幕记忆 / 倍速的语义只有一处，避免两处漂移。
        val started = ensureSessionPlaying(
            context = context,
            registry = kernel.registry,
            settings = settings,
            history = history,
            player = player,
            session = session,
            item = MediaItem(
                id = mediaId,
                sourceId = "",
                title = resolvedTitle,
                uri = uri,
                mimeType = mimeType,
                coverUri = cover,
            ),
            restorePosition = !fromStart,
            // E104：进播放页不再无条件起播——**会话是真相，页面只是显示它**。
            // 暂停态进来保持暂停、结束态停在结束画面（让用户自己按播放/重播）；
            // 唯一"要它播"的显式入口是「从头播放」（from=start，见上一行）与新装载
            // （不同媒体 / 新会话，那条分支照旧 play）。此前"进页一律 play()"与类 PC
            // 右栏的被动语义正好相反，来回按 F/⛶ 会读成"暂停只在浏览侧有效"。
            autoPlay = fromStart,
            onSubtitleMatched = { hit -> scope.launch { settings.setSubtitleUri(mediaId, hit) } },
        )
        // 只有真的从断点起播才提示（从头播放/会话本来就在播同一媒体都不会走到这里）
        if (started.resumedFromMs > 0L) resumeNoticeMs = started.resumedFromMs
        // L7：记忆里的外挂字幕打不开（文件被移走 / 授权失效）时说一声——
        // 此前是静默降级，用户只看到"没字幕"，以为是自己没挂上
        if (started.subtitleMissing) toast.show("字幕文件已失效，请重新挂载")
        mediaOpened = true
    }

    // 续播提示的倒计时：到点自行收起，不需要用户操作
    LaunchedEffect(resumeNoticeMs) {
        if (resumeNoticeMs != null) {
            delay(RESUME_NOTICE_MS)
            resumeNoticeMs = null
        }
    }

    val state by player.state.collectAsStateWithLifecycle()
    // §9.2（审核文档 §11 反例①）：位置**不在本页顶层解包**。
    // `val positionMs by …` 会让每次进度上报（约 250ms 一跳）都重组整个 PlayerScreen，
    // 连带视频舞台、信息区、弹层全部重算。这里只保留 [State] 对象，
    // 由真正需要它的叶子组件（seek 轨、气泡、章节面板、续播提示）各自解包。
    val positionState = player.positionMs.collectAsStateWithLifecycle()
    val durationState = player.durationMs.collectAsStateWithLifecycle()
    val durationMs by durationState
    // S5 缓冲段可视化：进度条三段式的"已缓冲"段数据源
    val bufferedPositionMs by player.bufferedPositionMs.collectAsStateWithLifecycle()
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
    val headerTitle = currentItem?.cardTitle?.takeIf { it.isNotBlank() } ?: displayTitle
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
    // **全屏 ≡ 沉浸**（2026-10-07 用户裁定：假全屏整组删除）：横向窗口一律隐藏系统栏，
    // 不再有"横窗版式 + 系统栏可见"的中间态。竖窗（手机竖持 / 平板竖持 / 窗口化）
    // 是普通的竖屏播放页，不叫全屏，也就没有"看得见小白条的全屏"。
    val isImmersive = isFullscreen
    var orientationLock by rememberSaveable { mutableStateOf(OrientationLock.FollowSensor) }

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
    // **"退出全屏"必须能真的退掉**，否则返回键会被无限吞掉：`isFullscreen`（= 窗口横向）
    // 在平板横持 / 手机横持时恒为 true、`BackHandler` 恒使能，而 `setFullscreen(false)`
    // 只是**请求**竖向，系统未必照办（平板自然方向本就是横向 / 手机横持时被传感器拉回）
    // ——若把"请求发出去了"当成"全屏退掉了"，每次返回都在做同一个不留结果的动作。
    // 实测两机型连按 6 次都退不出播放页，用户被关在里面。
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
    // fs=1（详情「全屏观看」/ 右栏 ⛶）：落地即全屏——竖窗先请求横向，
    // 横窗（平板自然方向）本来就是全屏，无需请求。
    LaunchedEffect(immersiveEntry) {
        if (immersiveEntry && windowIsPortrait) setFullscreen(true)
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
    // ---- 锁屏锁定键（批次 2 / §4.2 顶栏 🔒）----
    // 锁定语义：屏蔽**全部**舞台手势、返回键与键盘，仅顶部解锁钮可点——口袋/带娃
    // 场景防误触；控制层与全部弹层的收口见下方 LaunchedEffect（那里各弹层状态才已声明）。
    var stageLocked by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isFullscreen) { exitFullscreenOrLeave() }
    // 锁定态吞掉返回（后注册的 BackHandler 优先）：解锁钮是唯一出口，
    // 防止口袋里按返回悄悄离开播放页
    BackHandler(enabled = stageLocked) { }

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
    /**
     * 「全屏切换」钮（控制层右下角，与 §8.4 的 `F` 共用）——按当前语境给唯一解：
     * 竖窗 = 进全屏（请求横向）；手机横窗全屏 = 锁竖屏退全屏、留在播放页；
     * 类 PC 横窗全屏 = 回到浏览（右栏续播同一会话）。没有第三种"窗口化横屏"可去。
     */
    fun toggleFullscreen() {
        when {
            !isFullscreen -> setFullscreen(true)
            isDesktopLike -> navigator.popBackStack()
            else -> requestExitFullscreen()
        }
        controlsVisible = true
    }
    // 选集面板（§8.3）：控制层之上的模态浮层，选中即跳集并关闭
    var episodePanelVisible by remember { mutableStateOf(false) }

    // ---- 全屏下拉选项菜单（本轮：替换右抽屉 fs-panel / 底部弹层 / 横向倍速条）----
    // 菜单锚在触发它的按钮上（矩形来自 onGloballyPositioned）：底栏按钮向上展开、
    // 顶栏按钮向下展开；菜单内部用「推进-返回」两页切换（drill-in）。
    var fsMenuPage by remember { mutableStateOf<FsMenuPage?>(null) }
    var fsMenuRoot by remember { mutableStateOf(FsMenuPage.MAIN) }
    var fsMenuAnchor by remember { mutableStateOf<FsMenuAnchor?>(null) }
    var dotsAnchor by remember { mutableStateOf<Rect?>(null) }
    var episodesAnchor by remember { mutableStateOf<Rect?>(null) }
    var speedAnchor by remember { mutableStateOf<Rect?>(null) }
    var subtitleAnchor by remember { mutableStateOf<Rect?>(null) }
    var seekBarAnchor by remember { mutableStateOf<Rect?>(null) }

    fun openFsMenu(
        page: FsMenuPage,
        anchor: Rect?,
        preferAbove: Boolean = false,
        /** 向上展开时菜单的下沿参照（进度条行）；不传则贴着触发控件顶 */
        aboveLimit: Rect? = null,
    ) {
        val rect = anchor ?: return
        fsMenuRoot = page
        fsMenuAnchor = FsMenuAnchor(
            rect,
            alignEnd = true,
            preferAbove = preferAbove,
            aboveLimitRect = if (preferAbove) aboveLimit else null,
        )
        fsMenuPage = page
    }

    /** 子页返回：从 ⋯ 一级页推进来的回一级页；直接打开的根页（底栏倍速/字幕）则收起 */
    fun leaveFsMenuPage() {
        fsMenuPage = if (fsMenuPage != null && fsMenuPage != fsMenuRoot) FsMenuPage.MAIN else null
    }

    fun closeFsMenu() {
        fsMenuPage = null
    }

    // §9.2：拖动量与 seek 目标都留着 [State] 对象引用——它们要在绘制层纪律下
    // 传给叶子（seek 轨、气泡、章节面板）自行解包，而不是在版式层读成值。
    val userDraggingState = remember { mutableStateOf(false) }
    var userDragging by userDraggingState
    val dragPositionMsState = remember { mutableFloatStateOf(0f) }
    var dragPositionMs by dragPositionMsState
    // 拖动释放后的目标位置：seek 完成前进度条停在目标处，避免"回跳再快进"的跳变感
    val pendingSeekMsState = remember { mutableStateOf<Long?>(null) }
    var pendingSeekMs by pendingSeekMsState
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
                // 手动选择的是刚拿到的文件，源必然存在——只取转码后的地址（L7 的
                // sourceMissing 只对"记忆里的地址"有意义）
                player.setSubtitle(prepareSubtitleUri(context, uriResult).uri)
                // 记忆原始地址：缓存目录可能被清理，恢复时重新转码
                settings.setSubtitleUri(mediaId, uriResult.toString())
            }
            controlsVisible = true
        }
    }

    // §8.4（E50 P1）`Ctrl+O` 打开文件：SAF 单文件选择器，纯本地零网络。
    // 选中后**导航**到 `player?…` 而不是就地换源——与外部 ACTION_VIEW 唤端、迷你条
    // 回跳共用同一条装载链（队列/断点/字幕记忆/倍速），这里不另起一份 prepare。
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        // 持久读权限：没有它，进程重启后这个地址再也打不开（字幕选择器同一处理）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch {
            val uriString = picked.toString()
            val mime = runCatching { context.contentResolver.getType(picked) }.getOrNull()
                ?: resolveMimeType(context, uriString)
                ?: "video/mp4"
            navigator.navigate(
                "player?uri=${encodeRouteParam(uriString)}" +
                    "&title=${encodeRouteParam(resolveDisplayName(context, uriString))}" +
                    "&mime=${encodeRouteParam(mime)}",
            )
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
    // 类 PC / **接了指针设备**：控制层常驻（§13.6 与 §8.4——有鼠标时"3.5s 自动消失"是反模式，
    // 用户想点某个钮还得先晃一下把栏唤回来），
    // 两个例外：① 显式切入沉浸全屏（"专心看片"的意图，控制层随系统栏一起隐去）；
    // ② 锁定态（屏幕上只有解锁钮，它必须会自行隐去——"一直挂着"是用户实报的问题）。
    // 菜单开着时同样不隐藏：菜单锚在触发按钮上，控制层一隐，锚点就没了着落。
    val pointerAttached = isDesktopLike || windowInfo.isHoverable
    LaunchedEffect(
        controlsVisible,
        state,
        userDragging,
        pointerAttached,
        isImmersive,
        fsMenuPage,
        stageLocked,
    ) {
        if (pointerAttached && !isImmersive && !stageLocked) return@LaunchedEffect
        if (fsMenuPage != null) return@LaunchedEffect
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
    // §9.2：整条判定收进 derivedStateOf——它只在有人读时重算，且把"位置变了"的
    // 通知面缩到真正显示进度的那几个组件，不再惊动本页版式层。
    val maxPosition = durationMs.coerceAtLeast(1L).toFloat()
    val displayPosition = remember {
        derivedStateOf {
            val pending = pendingSeekMsState.value
            when {
                userDraggingState.value -> dragPositionMsState.value
                pending != null && abs(positionState.value - pending) > 1_500 -> pending.toFloat()
                else -> positionState.value.coerceAtMost(durationState.value).toFloat()
            }
        }
    }
    // seek 目标对账：位置追平目标后把进度条交还给真实播放位置。
    // 旧写法是 `LaunchedEffect(positionMs) { … }`——把位置当 key 读，等于让本页
    // 每 250ms 重启一次这个 effect（§9.2 反例）。改为协程内直接收 Flow。
    LaunchedEffect(player) {
        player.positionMs.collect { position ->
            val pending = pendingSeekMsState.value
            if (pending != null && abs(position - pending) <= 1_500) {
                pendingSeekMsState.value = null
            }
        }
    }

    val hasPrevious = queueIndex > 0
    val hasNext = queueIndex in 0 until (queue.size - 1)
    val speedText = "%.2fx".format(speed).removeSuffix("0").removeSuffix(".")
    // 触觉入口（§7.5 语义映射）：声明在第一个用到它的局部函数之前——局部函数只能
    // 捕获声明处已在作用域内的量。
    val haptics = rememberNpHaptics()
    fun togglePlay() {
        haptics.performHaptic(NpHaptic.Tap)
        if (state == PlaybackState.Playing) player.pause() else player.play()
    }

    // ---- 设计稿播放器交互面（sheet / 仅听音频 / 循环模式）----
    val ink0 = LocalNpInk.current
    val audioOnly by session.audioOnly.collectAsStateWithLifecycle()
    val loopMode by session.loopMode.collectAsStateWithLifecycle()
    var speedPickerVisible by remember { mutableStateOf(false) }
    var subtitleSheetVisible by remember { mutableStateOf(false) }
    var moreSheetVisible by remember { mutableStateOf(false) }

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
    // 收藏：与库页长按菜单、我的页横排**同一个仓库**（Room favorites）——此前这里是
    // rememberSaveable 占位 + 「已加入收藏」toast，而「我的」页早已是真实列表：
    // 同一件事两处各说各话（L3）。现在开关只写仓库，是否已收藏由 favoriteKeys 派生。
    val favoriteKeys by history.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val favoriteItem = queue.getOrNull(queueIndex)
    val favorited = favoriteItem != null && HistoryEntry.keyOf(favoriteItem) in favoriteKeys
    fun toggleFavorite() {
        val target = favoriteItem ?: return
        scope.launch {
            val now = history.toggleFavorite(target)
            toast.show(if (now) "已加入收藏" else "已取消收藏")
        }
    }
    // 长按 2.0x 快进（设计稿 .gest-hint）：进入时记住原速，松手恢复
    var boostHintVisible by remember { mutableStateOf(false) }
    var boostPreviousSpeed by remember { mutableStateOf(1f) }
    // 亮度（window attr 0.01..1 映射为 1..100）/ 音量（AudioManager STREAM_MUSIC）
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
    // §9.2（审核文档 §11 反例②）：亮度/音量在竖滑里**每帧**都写。
    // 解包成 `var … by …` 只在需要的地方留着 State 句柄（HUD 自身、选项菜单滑杆），
    // 版式层不再因为一次滑动而整体重组。
    val brightnessState = rememberSaveable {
        mutableIntStateOf(
            ((activityWindow?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                .takeIf { it in 0.01f..1f }
                ?.times(100)?.toInt() ?: 62),
        )
    }
    var brightnessPercent by brightnessState
    val volumeState = rememberSaveable {
        mutableIntStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / maxVolume)
    }
    var volumePercent by volumeState
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
            // 与右栏兜底派发共用同一条换算（E101 观察②：截断会让 ±5% 步进偶发无效果）
            volumeIndexFor(maxVolume, volumePercent),
            0,
        )
    }
    // §8.4 `M` 静音：记住静音前的读数，再按一次还回去。
    // 还 0 会让"静音"变成"永久失声"，所以没记到值时给一个可听见的兜底档位。
    var lastAudiblePercent by rememberSaveable { mutableIntStateOf(0) }
    fun toggleMute() {
        if (volumePercent > 0) {
            lastAudiblePercent = volumePercent
            setVolumePercent(0)
            gestureHint = "已静音"
        } else {
            setVolumePercent(if (lastAudiblePercent > 0) lastAudiblePercent else SessionKeySteps.VOLUME_RESTORE_PERCENT)
            gestureHint = "已取消静音"
        }
        controlsVisible = true
    }
    // ---- 手势层（批次 2 / §4.3 状态机 + 指示器）----
    /** 撞边界（M10）：§6 要的是 `REJECT`，不是"再响一次点击"。 */
    fun rejectHaptic() {
        haptics.performHaptic(NpHaptic.Reject)
    }

    /**
     * 双击 ∓/±10 秒：撞到 0/末尾边界给一次拒绝触觉，成功则给一次刻度触觉
     * （§4.3「越过边界 REJECT 同款 tick」）。
     *
     * 两条反馈都在**这里**下发：键盘（C19 ←/→）与舞台双击走的是同一个入口，
     * 放在调用方会让键盘侧只剩 `Reject`、点完一声不响。
     *
     * [stepMs] 让键盘档位（§8.4：←/→ 5 秒、Shift 30 秒）与舞台双击（10 秒）共用同一条
     * 边界/触觉/提示逻辑——步长是唯一差异，别在这里再开一份 if。
     */
    fun seekByGesture(forward: Boolean, stepMs: Long = GESTURE_SEEK_STEP_MS) {
        val raw = player.positionMs.value + if (forward) stepMs else -stepMs
        val target = raw.coerceIn(0L, durationMs.coerceAtLeast(0L))
        if (target != raw) rejectHaptic() else haptics.performHaptic(NpHaptic.Tick)
        player.seekTo(target)
        val seconds = (stepMs / 1000L).toInt()
        gestureHint = if (forward) "快进 $seconds 秒" else "快退 $seconds 秒"
    }

    /** 双击分区（§4.3）：左右 1/3 = 快退/快进 10 秒；**中心 = 播放/暂停**。 */
    fun onStageDoubleTap(zone: StageTapZone) {
        when (zone) {
            StageTapZone.Back -> seekByGesture(false)
            StageTapZone.Forward -> seekByGesture(true)
            StageTapZone.Center -> togglePlay()
        }
    }

    // 横滑 seek（§4.3）：拖动中视频照常播放，中央气泡只呈现"松手会去哪"；
    // 松手才真正 seekTo（与进度条拖动的"关键帧预览"是两条路径，互不混用）
    var seekGestureActive by remember { mutableStateOf(false) }
    // 目标位置每帧都在写（§9.2 同源问题）：留句柄给气泡自身读
    val seekGestureTargetMsState = remember { mutableFloatStateOf(0f) }
    var seekGestureTargetMs by seekGestureTargetMsState
    var seekGestureEdgeTicked by remember { mutableStateOf(false) }
    fun onSeekGestureStart() {
        // 与长按 3x 的竞争收口：拖动一开始就恢复原速——"3x 快进 + seek"叠加没有任何合理语义
        if (boostHintVisible) {
            player.setSpeed(boostPreviousSpeed)
            boostHintVisible = false
        }
        controlsVisible = true
        seekGestureTargetMs = positionState.value.toFloat()
        seekGestureEdgeTicked = false
        seekGestureActive = true
        // M7（§7.5）：横滑 scrub 与进度条拖动是同一套手感——按住 `Long`、松手 `Tick`
        haptics.performHaptic(NpHaptic.Long)
    }
    fun onSeekGestureDelta(deltaMs: Float) {
        val raw = seekGestureTargetMs + deltaMs
        val clamped = raw.coerceIn(0f, maxPosition)
        if (raw != clamped && !seekGestureEdgeTicked) {
            rejectHaptic()
            seekGestureEdgeTicked = true
        } else if (raw in 0f..maxPosition) {
            // 拉回界内后重新武装边界 tick（同一次拖动里反复撞边界各 tick 一次）
            seekGestureEdgeTicked = false
        }
        seekGestureTargetMs = clamped
    }
    fun onSeekGestureEnd() {
        seekGestureActive = false
        haptics.performHaptic(NpHaptic.Tick)
        val target = seekGestureTargetMs.toLong()
        pendingSeekMs = target
        player.seekTo(target)
    }

    // 竖滑亮度/音量指示器（§4.3：160dp 竖条 + 图标 + 百分比，900ms 无更新淡出）
    var verticalGesture by remember { mutableStateOf<StageVerticalMode?>(null) }
    var verticalEdgeRejected by remember { mutableStateOf(false) }
    // 停留计时**不挂组合**：旧写法是 `LaunchedEffect(verticalGesture, verticalGestureTickMs)`，
    // 而那个 tick 每帧都写、又当 key 读——等于把整块舞台拖进每帧重组（§9.2 反例②）。
    // 现在只由手势事件重启一个协程，时间戳本身不再进入组合期。
    val verticalHoldJob = remember { mutableStateOf<Job?>(null) }
    fun restartVerticalGestureHold() {
        verticalHoldJob.value?.cancel()
        verticalHoldJob.value = scope.launch {
            delay(GESTURE_INDICATOR_HOLD_MS)
            verticalGesture = null
        }
    }
    fun onVerticalGestureStart(mode: StageVerticalMode) {
        if (boostHintVisible) {
            player.setSpeed(boostPreviousSpeed)
            boostHintVisible = false
        }
        verticalGesture = mode
        verticalEdgeRejected = false
        restartVerticalGestureHold()
    }
    fun onVerticalGestureDelta(mode: StageVerticalMode, deltaPercent: Float) {
        // 上滑 = 增大（市面播放器一致的手感）：pointer 的 y 轴向下为正，这里取负
        val (current, floor, ceil) = when (mode) {
            StageVerticalMode.Brightness -> Triple(brightnessPercent, 1, 100)
            StageVerticalMode.Volume -> Triple(volumePercent, 0, 100)
        }
        val raw = (current - deltaPercent).roundToInt()
        // M10 边界（§7.5 `Reject`）：顶到端点各响一次，滑回区间内重新武装
        if (raw != raw.coerceIn(floor, ceil)) {
            if (!verticalEdgeRejected) {
                verticalEdgeRejected = true
                rejectHaptic()
            }
        } else verticalEdgeRejected = false
        when (mode) {
            StageVerticalMode.Brightness -> setBrightnessPercent(raw)
            StageVerticalMode.Volume -> setVolumePercent(raw)
        }
        restartVerticalGestureHold()
    }

    fun enterPip() {
        // §5.1：仅听音频与 PiP 互斥——小窗里没有画面，进入只是个小黑框
        if (session.audioOnly.value) {
            toast.show("仅听音频模式下没有画面可显示")
            return
        }
        val a = context as? ComponentActivity ?: return
        // L10①：反馈诚实性——`enterPictureInPictureMode` 失败会返回 false（设备/系统
        // 不允许时也可能抛异常），此前无条件 toast「已进入画中画」，用户看不到小窗却被
        // 告知成功。比例改用真实视频宽高比（与参数跟随同一口径），竖视频不再塞进 16:9。
        val ratio = videoAspectRatio.takeIf { it > 0f } ?: 16f / 9f
        val entered = runCatching {
            a.enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(
                        android.util.Rational((ratio * 1000).roundToInt().coerceAtLeast(1), 1000),
                    )
                    .setActions(pipActions(context, state == PlaybackState.Playing))
                    .build(),
            )
        }.getOrDefault(false)
        toast.show(if (entered) "已进入画中画" else "画中画不可用")
    }

    // 锁定语义：屏蔽**全部**舞台手势、返回键与键盘，舞台上仅解锁钮可点——
    // 口袋/带娃场景防误触。控制层与全部弹层的收口在下方 LaunchedEffect（那里
    // 各弹层状态才已声明）。
    // 锁定瞬间让解锁钮**先亮一下**（controlsVisible=true 只是它的载体：锁定态下
    // 顶/底控制层都被 !stageLocked 挡住，屏幕上只多出解锁钮）——随后由自动隐藏
    // 守护按同一节拍收起；之后单指点按即可再唤出（见 [stageGestures] 的 onTap）。
    LaunchedEffect(stageLocked) {
        if (stageLocked) {
            controlsVisible = true
            episodePanelVisible = false
            fsMenuPage = null
            speedPickerVisible = false
            subtitleSheetVisible = false
            moreSheetVisible = false
            fileInfoVisible = false
        }
    }

    /**
     * 两种形态的舞台手势接线（§4.3）——收敛成一处，避免双份漂移。
     * 锁定态（[stageLocked]）禁用全部手势；**点按例外**：它是解锁钮的唤出入口
     * （锁定后屏幕只剩解锁钮，不许点按就等于没有出口；除显隐解锁钮外不触发任何动作）。
     */
    fun stageGestures(): Modifier =
        Modifier.npStageGestures(
            gesturesEnabled = gesturesEnabled && !stageLocked,
            onTap = { controlsVisible = !controlsVisible },
            onDoubleTap = ::onStageDoubleTap,
            onLongPressStart = {
                boostPreviousSpeed = speed
                player.setSpeed(3f)
                boostHintVisible = true
                // M9（§7.5 `Long`）：生效与松开各一次——按住那一下只说明"要提速"，
                // 松手恢复原速同样要回执，否则用户不知道 3x 有没有退掉。
                haptics.performHaptic(NpHaptic.Long)
            },
            onLongPressEnd = {
                if (boostHintVisible) {
                    player.setSpeed(boostPreviousSpeed)
                    boostHintVisible = false
                    haptics.performHaptic(NpHaptic.Long)
                }
            },
            onSeekStart = ::onSeekGestureStart,
            onSeekDelta = ::onSeekGestureDelta,
            onSeekEnd = ::onSeekGestureEnd,
            onVerticalStart = ::onVerticalGestureStart,
            onVerticalDelta = ::onVerticalGestureDelta,
        )

    // ================= 批次 3：轨道 / 章节 / 画面 / 偏移 / PiP / 睡眠 / 截图 =================

    // 内嵌轨道与章节（引擎经 onTracksChanged/onMetadata 推送；面板据此渲染）
    val tracks by player.tracks.collectAsStateWithLifecycle()
    val chapters by player.chapters.collectAsStateWithLifecycle()
    // 字幕三态文案：外挂优先，其次内嵌轨被选中（CC 钮点亮与面板取值同一真相）
    val subtitleText = when {
        subtitleLoaded -> "已挂载"
        tracks.any { it.type == NpTrackType.TEXT && it.selected } -> "内嵌"
        else -> "关闭"
    }
    val subtitleOn = subtitleText != "关闭"

    // 画面显示记忆（§2.4 比例 / 镜像 / 旋转，per-media 落盘）
    var scaleMode by remember(mediaId) { mutableStateOf(VideoScaleMode.FIT) }
    var mirrored by remember(mediaId) { mutableStateOf(false) }
    var rotation by remember(mediaId) { mutableIntStateOf(0) }
    LaunchedEffect(mediaId) {
        val (mode, mirror, rot) = parseDisplaySettings(settings.displaySettings(mediaId).first())
        scaleMode = mode
        mirrored = mirror
        rotation = rot
    }
    fun updateDisplaySettings(
        mode: VideoScaleMode = scaleMode,
        mirror: Boolean = mirrored,
        rot: Int = rotation,
    ) {
        scaleMode = mode
        mirrored = mirror
        rotation = rot
        scope.launch { settings.setDisplaySettings(mediaId, serializeDisplaySettings(mode, mirror, rot)) }
    }
    // 裁剪是引擎侧缩放模式（np.crop 命令）；比例走布局，镜像/旋转走画面 View 变换
    LaunchedEffect(scaleMode) { player.setVideoCrop(scaleMode == VideoScaleMode.CROP) }

    // 字幕偏移（E43）：±1000ms / 50ms 步进，正数 = 延后。会话级（不跨进程记忆）
    var subtitleOffsetMs by remember { mutableLongStateOf(0L) }
    fun adjustSubtitleOffset(deltaMs: Long) {
        subtitleOffsetMs = (subtitleOffsetMs + deltaMs).coerceIn(-1_000L, 1_000L)
        player.setSubtitleOffsetMs(subtitleOffsetMs)
    }

    // 面板开关
    var audioTrackSheetVisible by remember { mutableStateOf(false) }
    var pictureSheetVisible by remember { mutableStateOf(false) }
    var chaptersPanelVisible by remember { mutableStateOf(false) }

    // 睡眠定时：绝对到期时间（epoch ms），0 = 关。到期暂停播放。
    var sleepTimerEndsAtMs by rememberSaveable { mutableLongStateOf(0L) }
    val sleepLabel = if (sleepTimerEndsAtMs > 0) {
        "睡眠 ${((sleepTimerEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1)}分"
    } else {
        "睡眠定时"
    }

    /** 睡眠定时两态开关（30 分钟档）：档位扩展留给设置页 */
    fun toggleSleepTimer() {
        if (sleepTimerEndsAtMs > 0) {
            sleepTimerEndsAtMs = 0
            toast.show("睡眠定时已取消")
        } else {
            sleepTimerEndsAtMs = System.currentTimeMillis() + 30 * 60_000
            toast.show("将在 30 分钟后暂停")
        }
    }
    LaunchedEffect(sleepTimerEndsAtMs) {
        val endsAt = sleepTimerEndsAtMs
        if (endsAt <= 0) return@LaunchedEffect
        val remain = endsAt - System.currentTimeMillis()
        if (remain > 0) delay(remain)
        if (sleepTimerEndsAtMs > 0 && System.currentTimeMillis() >= sleepTimerEndsAtMs) {
            sleepTimerEndsAtMs = 0
            player.pause()
            toast.show("睡眠定时：已暂停播放")
        }
    }

    // 截图（TextureView 取当前帧，落 Pictures/None Player）
    val surfaceViewRef = remember { mutableStateOf<TextureView?>(null) }
    fun screenshot() {
        val view = surfaceViewRef.value
        if (view == null || view.width == 0 || view.height == 0) {
            toast.show("画面尚未就绪，稍后再试")
            return
        }
        val bitmap = runCatching { view.getBitmap(view.width, view.height) }.getOrNull()
        if (bitmap == null) {
            toast.show("截图失败")
            return
        }
        runCatching {
            scope.launch(Dispatchers.IO) {
                val values = android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "noneplayer_${System.currentTimeMillis()}.png",
                    )
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/None Player")
                }
                val saved = context.contentResolver
                    .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?.let { u ->
                        context.contentResolver.openOutputStream(u)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            u
                        }
                    }
                withContext(Dispatchers.Main) {
                    toast.show(if (saved != null) "截图已保存到相册" else "截图保存失败")
                }
            }
        }.onFailure { toast.show("截图失败") }
    }

    // PiP（§5.3）：竖屏比例小窗、播放中自动进入（API 31+ autoEnter）、仅听音频时禁用
    var inPipMode by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            inPipMode = info.isInPictureInPictureMode
        }
        act.addOnPictureInPictureModeChangedListener(listener)
        onDispose { act.removeOnPictureInPictureModeChangedListener(listener) }
    }
    // PiP 中控制层与悬浮元素全部收起（§5.3「控制层在 PiP 中自动隐藏」）
    LaunchedEffect(inPipMode) {
        if (inPipMode) {
            controlsVisible = false
            fsMenuPage = null
            episodePanelVisible = false
            audioTrackSheetVisible = false
            pictureSheetVisible = false
            chaptersPanelVisible = false
            speedPickerVisible = false
        }
    }
    // 参数跟随：比例跟随视频、播放中（且非仅音频）才自动进入；暂停即收回 autoEnter，
    // 用户上滑回桌面时停在暂停画面上不该弹一个小窗
    LaunchedEffect(videoAspectRatio, state, audioOnly, inPipMode) {
        val act = activity ?: return@LaunchedEffect
        if (inPipMode) return@LaunchedEffect
        runCatching {
            val ratio = videoAspectRatio.takeIf { it > 0f } ?: 16f / 9f
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational((ratio * 1000).roundToInt().coerceAtLeast(1), 1000))
                .setActions(pipActions(context, state == PlaybackState.Playing))
            // 这两个 setter 是 API 31+（minSdk 26），低版本直接调会 NoSuchMethodError；
            // 不设置即退回用户手动拖入小窗，PiP 本身仍可用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(state == PlaybackState.Playing && !audioOnly)
                    .setSeamlessResizeEnabled(false)
            }
            act.setPictureInPictureParams(builder.build())
        }
    }

    // 仅听音频 per-media 记忆（§5.1）：进同一文件直接进入音频形态
    val savedAudioOnly by settings.audioOnlyPreferred(mediaId).collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(mediaId, savedAudioOnly) {
        if (savedAudioOnly && !session.audioOnly.value) session.setAudioOnly(true)
    }
    fun toggleAudioOnlyWithMemory() {
        val target = !session.audioOnly.value
        session.setAudioOnly(target)
        scope.launch { settings.setAudioOnlyPreferred(mediaId, target) }
        toast.show(if (target) "已进入仅听音频模式" else "已切回视频播放")
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
    // E102：告诉会话兜底"播放页在场"——方向键归本页处理（带手势提示与边界触觉），
    // 窗口层的兜底派发主动让位
    DisposableEffect(Unit) {
        PlayerPagePresence.active = true
        onDispose { PlayerPagePresence.active = false }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                // §8.4：`L` 锁定/解锁。**排在"锁定吞键"之前**——否则锁死之后键盘再也解不开，
                // 出口只剩屏幕中央那颗解锁钮（触控用户找得到，键鼠用户找不到）。
                if (event.key == Key.L) {
                    stageLocked = !stageLocked
                    controlsVisible = true
                    return@onPreviewKeyEvent true
                }
                // 锁定态吞掉全部按键（与手势屏蔽同语义）：键盘也是误触面
                if (stageLocked) return@onPreviewKeyEvent true
                // §8.4（E50 P1）九件：空格、←/→ ±5s、Shift+←/→ ±30s、↑/↓ 音量 ±5、
                // M 静音、F 全屏、L 锁定、Esc 退层、Ctrl+O 打开文件。
                // `,`/`.` 逐帧与 D-pad 方向导航按 §12 归 P2（§11 第 6 条）。
                // 会话级四件读 SessionKeys 的共用映射表（E99）：三栏浏览态那份实现
                // 用的是同一张表与同一份步进数字，两处不会再各写一份漂掉。
                // `Key.keyCode` 是 Long（Compose 1.7 起），映射表按 KeyEvent 的 Int 键码写。
                // E102 修正：**不能用 Compose 的 `Key.keyCode`**——它在 Android 上是`androidKeyCode shl 32`，`.toInt()` 截出来恒为 0，共享键在这里会全部静默失效
                // （E99 回合埋的坑，一直被窗口层兜底无声盖住）。取平台键码走 native。
                when (val command = routeSessionKey(event.nativeKeyEvent.keyCode, event.isShiftPressed)) {
                    SessionKeyCommand.PlayPause -> {
                        togglePlay()
                        return@onPreviewKeyEvent true
                    }
                    is SessionKeyCommand.Seek -> {
                        seekByGesture(
                            forward = command.deltaMs >= 0,
                            stepMs = if (command.deltaMs < 0) -command.deltaMs else command.deltaMs,
                        )
                        return@onPreviewKeyEvent true
                    }
                    is SessionKeyCommand.Volume -> {
                        // 音量走系统流，与右侧竖滑手势同一个 setVolumePercent（不另起一路）
                        setVolumePercent(volumePercent + command.deltaPercent)
                        return@onPreviewKeyEvent true
                    }
                    SessionKeyCommand.Mute -> {
                        toggleMute()
                        return@onPreviewKeyEvent true
                    }
                    null -> Unit
                }
                // 页面级三件（浏览态没有对应能力，不进共用表）：F 全屏、Ctrl+O 打开文件、Esc 退层
                when (event.key) {
                    Key.F -> {
                        toggleFullscreen()
                        true
                    }
                    Key.O -> {
                        if (event.isCtrlPressed) {
                            mediaPicker.launch(arrayOf("video/*", "audio/*"))
                            true
                        } else {
                            false
                        }
                    }
                    Key.Escape -> {
                        // 与系统返回键同序（§5.5 返回链）：先关最上层弹层，再退全屏，最后离页。
                        // 规格那行写的是"退全屏→关弹层→返回"，但返回键的实际次序是弹层在前
                        // （NpSheet/菜单的 BackHandler 注册得更晚、优先级更高）——Esc 若与返回键
                        // 反着来，同一层界面会有两套退出顺序，这里跟随已在跑的返回链。
                        when {
                            fsMenuPage != null -> closeFsMenu()
                            speedPickerVisible -> speedPickerVisible = false
                            episodePanelVisible -> episodePanelVisible = false
                            chaptersPanelVisible -> chaptersPanelVisible = false
                            subtitleSheetVisible -> subtitleSheetVisible = false
                            audioTrackSheetVisible -> audioTrackSheetVisible = false
                            pictureSheetVisible -> pictureSheetVisible = false
                            fileInfoVisible -> fileInfoVisible = false
                            moreSheetVisible -> moreSheetVisible = false
                            else -> exitFullscreenOrLeave()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // M23：音频页 ↔ 视频页整页切换原先是 `if` 硬切——点「仅听音频」整屏啪地换成黑胶，
        // 像界面崩了一下。换成 `Scheme`(320) 交叉淡入：两页重叠期间视频 surface 仍在，
        // 不做位移（整页位移会把两条版式拉成一次"翻页"，读起来像切应用而不是切模式）。
        // 时长读在组合期：transitionSpec 的 lambda 跑在 AnimatedContentScope，不是组合上下文。
        val schemeMs = npDuration(Motion.Scheme)
        AnimatedContent(
            targetState = audioOnly,
            transitionSpec = {
                fadeIn(tween(schemeMs, easing = NpEasing.Silk)) togetherWith
                    fadeOut(tween(schemeMs, easing = NpEasing.SilkExit))
            },
            label = "audioOnlyForm",
        ) { audioOnlyPage ->
        if (audioOnlyPage) {
            // 仅听音频整屏页（设计稿 `.audio`）：NetEase 风黑胶 + 控制排，
            // 覆盖所有形态；「切回视频」回到当前形态的播放版式
            AudioOnlyScreen(
                session = session,
                onBack = { toggleAudioOnlyWithMemory() },
                favorited = favorited,
                onToggleFavorite = { toggleFavorite() },
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
                        .then(stageGestures()),
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
                        controlsVisible = controlsVisible,
                        scaleMode = scaleMode,
                        mirrored = mirrored,
                        rotation = rotation,
                        onSurfaceView = { surfaceViewRef.value = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 暂停时的中央"继续"：64dp 玻璃圆（设计稿 .bigplay），
                    // 播放中不出现——它只在"用户可能需要我"的时刻存在。
                    // 锁定态不出现：那是舞台上的第二个可点元素，破坏"只有解锁钮可点"。
                    CenterResumeButton(
                        visible = state == PlaybackState.Paused && !userDragging && !inPipMode && !stageLocked,
                        onPlay = ::togglePlay,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 播完：画面内的轻量结束态（非模态，不遮全屏、不打断）；锁定态同上不收
                    if (state == PlaybackState.Ended && mediaOpened && !stageLocked) {
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
                    ResumePositionNotice(
                        resumeMs = resumeNoticeMs,
                        speed = speed,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    GestureHintPill(
                        text = gestureHint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 横滑 seek 气泡（§4.3）：拖动中视频照常播放，这里只呈现"松手会去哪"
                    if (seekGestureActive) {
                        SeekGestureBubbleHost(positionState, seekGestureTargetMsState)
                    }
                    // 竖滑亮度/音量指示条（§4.3）：在起手的那一侧显示
                    verticalGesture?.let { mode ->
                        VerticalAdjustHud(mode, brightnessState, volumeState, 28.dp)
                    }
                    // 锁定态（§4.2 🔒）：唯一可点元素是解锁钮
                    // 锁定徽标：随控制层显隐——锁定瞬间亮起（反馈 + 指出解锁位置），
                    // 随后按同一节拍自动隐去；锁定态下单指点按可随时把它唤回。
                    // （旧行为是"常驻不隐"，用户实报"已锁定按钮不会隐藏，一直存在"。）
                    StageLockBadgeHost(
                        visible = stageLocked && controlsVisible,
                        onUnlock = {
                            stageLocked = false
                            controlsVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End,
                                ),
                            )
                            .padding(16.dp),
                    )
                    // 假全屏删除后横向全屏只剩影院一种，控制层恒为"上+下"两条，
                    // 旧"底部集中"版式（类 PC 窗口化横屏专用）连同其边栏动作一并移除。
                    // ⋯（播放选项下拉）/ 选集。顶栏与底栏共用这一份实现：同材质、
                    // 同一条 scrim、同一套锚点回报，两处不会各写一份而漂移。
                    @Composable fun BarOptionButtons() {
                        BarCtlButton(
                            icon = NpIcon.Dots,
                            contentDescription = "播放选项",
                            onClick = {
                                controlsVisible = true
                                // 再点一次 ⋯ = 收起（子页里点 = 回一级页），与旧面板的开关语义一致
                                if (fsMenuPage == FsMenuPage.MAIN) {
                                    closeFsMenu()
                                } else {
                                    openFsMenu(FsMenuPage.MAIN, dotsAnchor)
                                }
                            },
                            active = fsMenuPage != null && fsMenuRoot == FsMenuPage.MAIN,
                            modifier = Modifier.onGloballyPositioned { dotsAnchor = it.boundsInWindow() },
                        )
                        if (queue.size > 1) {
                            BarCtlButton(
                                icon = NpIcon.QueueMusic,
                                contentDescription = "选集",
                                onClick = { openFsMenu(FsMenuPage.EPISODES, episodesAnchor) },
                                modifier = Modifier.onGloballyPositioned { episodesAnchor = it.boundsInWindow() },
                            )
                        }
                    }
                    PlayerTopBar(
                        // 锁定态里顶/底控制层整体不出——锁定后舞台上只剩解锁钮
                        visible = controlsVisible && !inPipMode && !stageLocked,
                        title = headerTitle,
                        onBack = {
                            exitFullscreenOrLeave()
                            controlsVisible = true
                        },
                        applyInsets = true,
                        // 自绘时钟电量只在**系统栏被收起**时出现——它存在的理由就是"没别的地方看时间"。
                        // 类 PC 窗口化（未沉浸）里系统栏还在，再画一份就是两个时钟并排、还各按各的进制。
                        showStatus = isImmersive,
                        locked = stageLocked,
                        onToggleLock = { stageLocked = true },
                        // 右上：⋯（播放选项下拉）/ 选集。**图标直接摆在顶栏里**，和底栏同一
                        // 材质、同一条 scrim、随控制层一起显隐。倍速不在这里出现：
                        // 底栏已有倍速钮，右上角再放一份是重复。
                        showActions = true,
                        trailing = { BarOptionButtons() },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    PlayerBottomControls(
                        visible = controlsVisible && !inPipMode && !stageLocked,
                        applyInsets = true,
                        showTransport = true,
                        leadingActions = null,
                        positionMs = positionState,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedPositionMs,
                        dragPositionMs = dragPositionMsState,
                        userDragging = userDragging,
                        displayPosition = displayPosition,
                        maxPosition = maxPosition,
                        onScrubStart = ::onScrubStart,
                        onScrubChange = {
                            onScrubStart()
                            onScrubProgress(it)
                        },
                        onScrubEnd = ::onScrubEnd,
                        isPlaying = state == PlaybackState.Playing,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        speed = speed,
                        // 全屏形态的倍速选择是下拉列表（横向选择条留给竖屏/仅听音频）
                        speedPickerVisible = fsMenuPage == FsMenuPage.SPEED,
                        inlineSpeedRow = false,
                        onToggleSpeedPicker = {
                            controlsVisible = true
                            // 再点一次倍速钮 = 收起
                            if (fsMenuPage == FsMenuPage.SPEED) {
                                closeFsMenu()
                            } else {
                                openFsMenu(FsMenuPage.SPEED, speedAnchor, preferAbove = true, aboveLimit = seekBarAnchor)
                            }
                        },
                        onPickSpeed = ::pickSpeed,
                        subtitleLoaded = subtitleOn,
                        onPrevious = { session.previous() },
                        onTogglePlay = ::togglePlay,
                        onNext = { session.next() },
                        onPickSubtitle = {
                            if (fsMenuPage == FsMenuPage.SUBTITLE) {
                                closeFsMenu()
                            } else {
                                openFsMenu(FsMenuPage.SUBTITLE, subtitleAnchor, preferAbove = true, aboveLimit = seekBarAnchor)
                            }
                        },
                        onToggleFullscreen = ::toggleFullscreen,
                        // 全屏形态恒处于全屏：按钮恒为「退出全屏」态（类 PC 点它 = 回浏览）
                        fullscreenActive = true,
                        onSpeedAnchor = { speedAnchor = it },
                        onSubtitleAnchor = { subtitleAnchor = it },
                        onSeekBarAnchor = { seekBarAnchor = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    // 全屏选项：锚定触发按钮的纵向下拉列表
                    //（本轮替换掉全高右抽屉 fs-panel 与各底部弹层；
                    //  竖屏形态的 NpSheet 体系不受影响）
                    fsMenuPage?.let { page ->
                        val anchor = fsMenuAnchor
                        if (anchor != null) {
                            FsDropdown(anchor = anchor, onDismiss = ::closeFsMenu) { dismiss ->
                                // 根页（直接从底栏/顶栏按钮打开的那页）的"返回"就是收起，
                                // 要走带动画的 dismiss；子页返回上一级是换页，不收菜单。
                                val back: () -> Unit = { if (page == fsMenuRoot) dismiss() else leaveFsMenuPage() }
                                when (page) {
                                    FsMenuPage.MAIN -> FsMainMenuContent(
                                        speed = speed,
                                        subtitleText = subtitleText,
                                        loopMode = loopMode,
                                        brightnessPercent = brightnessPercent,
                                        volumePercent = volumePercent,
                                        favorited = favorited,
                                        queueSize = queue.size,
                                        queueIndex = queueIndex,
                                        hasChapters = chapters.isNotEmpty(),
                                        sleepLabel = sleepLabel,
                                        sleepActive = sleepTimerEndsAtMs > 0,
                                        onClose = dismiss,
                                        onPage = { fsMenuPage = it },
                                        onLoopMode = { mode ->
                                            session.setLoopMode(mode)
                                            toast.show("循环模式：" + loopLabel(mode))
                                        },
                                        onBrightness = ::setBrightnessPercent,
                                        onVolume = ::setVolumePercent,
                                        onPip = {
                                            dismiss()
                                            enterPip()
                                        },
                                        onFavorite = {
                                            toggleFavorite()
                                        },
                                        onScreenshot = {
                                            dismiss()
                                            screenshot()
                                        },
                                        onSleep = ::toggleSleepTimer,
                                    )

                                    FsMenuPage.SPEED -> FsSpeedMenuContent(
                                        speed = speed,
                                        onPick = {
                                            dismiss()
                                            pickSpeed(it)
                                        },
                                        onBack = back,
                                    )

                                    FsMenuPage.SUBTITLE -> FsSubtitleMenuContent(
                                        subtitleOn = subtitleOn,
                                        subtitleLoaded = subtitleLoaded,
                                        savedSubtitleName = savedSubtitle
                                            ?.substringAfterLast('/')
                                            ?.substringBeforeLast('.'),
                                        textTracks = tracks.filter { it.type == NpTrackType.TEXT },
                                        offsetMs = subtitleOffsetMs,
                                        onOff = {
                                            player.setSubtitle(null)
                                            scope.launch { settings.setSubtitleUri(mediaId, null) }
                                            toast.show("字幕：关闭")
                                            dismiss()
                                        },
                                        onPickTrack = { track ->
                                            player.selectTrack(NpTrackType.TEXT, track.id)
                                            dismiss()
                                        },
                                        onMountExternal = {
                                            dismiss()
                                            subtitlePicker.launch(arrayOf("*/*"))
                                        },
                                        onOffset = ::adjustSubtitleOffset,
                                        onBack = back,
                                    )

                                    FsMenuPage.AUDIO -> FsAudioMenuContent(
                                        tracks = tracks.filter { it.type == NpTrackType.AUDIO },
                                        onPick = { track ->
                                            player.selectTrack(NpTrackType.AUDIO, track.id)
                                            dismiss()
                                        },
                                        onBack = back,
                                    )

                                    FsMenuPage.PICTURE -> FsPictureMenuContent(
                                        currentMode = scaleMode,
                                        mirrored = mirrored,
                                        rotation = rotation,
                                        onMode = { updateDisplaySettings(mode = it) },
                                        onMirror = { updateDisplaySettings(mirror = it) },
                                        onRotate = { updateDisplaySettings(rot = it) },
                                        onBack = back,
                                    )

                                    FsMenuPage.CHAPTERS -> FsChaptersMenuContent(
                                        chapters = chapters,
                                        positionMs = positionState,
                                        onSelect = { chapter ->
                                            player.seekTo(chapter.startMs)
                                            dismiss()
                                        },
                                        onBack = back,
                                    )

                                    FsMenuPage.EPISODES -> FsEpisodesMenuContent(
                                        queue = queue,
                                        currentIndex = queueIndex,
                                        onSelect = { index ->
                                            session.selectQueueIndex(index)
                                            dismiss()
                                            controlsVisible = false
                                        },
                                        onBack = back,
                                    )
                                }
                            }
                        }
                    }
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
                    // 常驻 playerbar（设计稿 `.playerbar`）：播放中的返回出口永远在。
                    // PiP 形态下整个活动窗口被缩进小窗——只留画面，不渲染外壳。
                    if (!inPipMode) {
                        PlayerBar(
                            title = headerTitle,
                            subtitle = listOf(
                                headerContainer,
                                currentItem?.folder ?: "本地文件",
                            ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            onBack = navigator::popBackStack,
                        )
                    }
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
                                .then(stageGestures()),
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
                                controlsVisible = controlsVisible,
                                scaleMode = scaleMode,
                                mirrored = mirrored,
                                rotation = rotation,
                                onSurfaceView = { surfaceViewRef.value = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            CenterResumeButton(
                                visible = state == PlaybackState.Paused && !userDragging && !inPipMode && !stageLocked,
                                onPlay = ::togglePlay,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            if (state == PlaybackState.Ended && mediaOpened && !stageLocked) {
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
                            ResumePositionNotice(
                                resumeMs = resumeNoticeMs,
                                speed = speed,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                            GestureHintPill(
                                text = gestureHint,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            // 长按 3x 提示胶囊（§4.3：中心胶囊"3x 快进中"，松手恢复原速）
                            // M9 提示胶囊：显隐落到 `Field`(150)。默认的 fadeIn()/fadeOut()
                            // 用的是 Material 曲线，和同一屏里的控制层/菜单不是同一条速度曲线。
                            androidx.compose.animation.AnimatedVisibility(
                                visible = boostHintVisible,
                                enter = fadeIn(animationSpec = npMotion(Motion.Field)),
                                exit = fadeOut(animationSpec = npMotion(Motion.Field)),
                                modifier = Modifier.align(Alignment.Center),
                            ) {
                                Text(
                                    text = "3x 快进中",
                                    style = NpMono,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(NpRadius.chip))
                                        .background(Color.Black.copy(alpha = 0.62f))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            // 横滑 seek 气泡 / 竖滑指示条 / 锁定徽标（与全屏形态同一套，§4.3）
                            if (seekGestureActive) {
                                SeekGestureBubbleHost(positionState, seekGestureTargetMsState)
                            }
                            verticalGesture?.let { mode ->
                                VerticalAdjustHud(mode, brightnessState, volumeState, 20.dp)
                            }
                            // 锁定徽标：随控制层显隐（与全屏形态同一语义）；
                            // 竖屏里视频区之外的信息区不受锁定影响，出口始终不止一个
                            StageLockBadgeHost(
                                visible = stageLocked && controlsVisible,
                                onUnlock = {
                                    stageLocked = false
                                    controlsVisible = true
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                            )
                            // 视频区底部：进度 + 时间 + 全屏（§4.1 的版式）；
                            // 顶栏已上移为常驻 playerbar，舞台内不再放返回键。
                            // 小窗视频区只有 16:9 一条，渐变带用 56dp（全屏默认 88dp），
                            // 否则暗带占掉小窗一半（用户实报）
                            PlayerBottomControls(
                                visible = controlsVisible && !inPipMode && !stageLocked,
                                applyInsets = false,
                                showTransport = false,
                                scrimHeight = 56.dp,
                                positionMs = positionState,
                                durationMs = durationMs,
                                bufferedPositionMs = bufferedPositionMs,
                                dragPositionMs = dragPositionMsState,
                                userDragging = userDragging,
                                displayPosition = displayPosition,
                                maxPosition = maxPosition,
                                onScrubStart = ::onScrubStart,
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
                                subtitleLoaded = subtitleOn,
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
                    // PiP 形态不渲染（小窗里只留画面）
                    if (!inPipMode) Column(
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
                                onClick = { toggleFavorite() },
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
                                onClick = { toggleAudioOnlyWithMemory() },
                                modifier = Modifier.weight(1f),
                            )
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
        }

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

        // ---- 章节抽屉（§12.5，批次 3）：无章节数据时入口隐藏，面板也不会出现 ----
        if (chaptersPanelVisible && chapters.isNotEmpty()) {
            BackHandler { chaptersPanelVisible = false }
            ChaptersPanel(
                chapters = chapters,
                positionMs = positionState,
                onSelect = { chapter ->
                    player.seekTo(chapter.startMs)
                    chaptersPanelVisible = false
                },
                onDismiss = { chaptersPanelVisible = false },
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
                    selected = !subtitleOn,
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
                // 内嵌字幕轨（批次 3）：MKV 多轨选择，走 TrackSelectionParameters 不重建媒体项
                val textTracks = tracks.filter { it.type == NpTrackType.TEXT }
                Text(
                    text = "内嵌字幕轨",
                    style = NpTypography.titleSmall,
                    color = ink0.fg,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 12.dp),
                )
                if (textTracks.isEmpty()) {
                    Text(
                        text = "此视频没有内嵌字幕轨",
                        style = NpMonoSmall,
                        color = ink0.muted,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                } else {
                    textTracks.forEach { track ->
                        TrackOptionRow(
                            label = track.label,
                            detail = null,
                            selected = track.selected,
                            onClick = {
                                player.selectTrack(NpTrackType.TEXT, track.id)
                                subtitleSheetVisible = false
                            },
                        )
                    }
                }
                // 字幕偏移（E43）：±1000ms / 50ms 步进；正数 = 字幕延后
                Text(
                    text = "字幕偏移",
                    style = NpTypography.titleSmall,
                    color = ink0.fg,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "提前",
                        style = NpMonoSmall,
                        color = ink0.muted,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (subtitleOffsetMs == 0L) "0ms" else
                                (if (subtitleOffsetMs > 0) "+" else "") + "${subtitleOffsetMs}ms",
                            style = NpMono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "延后",
                        style = NpMonoSmall,
                        color = ink0.muted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-50L to "−50ms", -250L to "−250ms", 250L to "+250ms", 50L to "+50ms").forEach { (delta, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(NpRadius.chip))
                                .background(ink0.fgSoft)
                                .clickable { adjustSubtitleOffset(delta) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = NpMonoSmall,
                                color = ink0.fg,
                            )
                        }
                    }
                }
            }
        }
        if (audioTrackSheetVisible) {
            NpSheet(
                onDismiss = { audioTrackSheetVisible = false },
                title = "音轨",
            ) {
                val audioTracks = tracks.filter { it.type == NpTrackType.AUDIO }
                if (audioTracks.isEmpty()) {
                    Text(
                        text = "此视频没有内嵌音轨",
                        style = NpMonoSmall,
                        color = ink0.muted,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                } else {
                    audioTracks.forEach { track ->
                        TrackOptionRow(
                            label = track.label,
                            detail = null,
                            selected = track.selected,
                            onClick = {
                                player.selectTrack(NpTrackType.AUDIO, track.id)
                                audioTrackSheetVisible = false
                            },
                        )
                    }
                }
            }
        }
        if (pictureSheetVisible) {
            NpSheet(
                onDismiss = { pictureSheetVisible = false },
                title = "画面",
            ) {
                PicturePanelContent(
                    currentMode = scaleMode,
                    mirrored = mirrored,
                    rotation = rotation,
                    onMode = { updateDisplaySettings(mode = it) },
                    onMirror = { updateDisplaySettings(mirror = it) },
                    onRotate = { updateDisplaySettings(rot = it) },
                )
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
                            // §5.1：仅听音频与 PiP 互斥——小窗无画面无意义
                            if (session.audioOnly.value) {
                                toast.show("仅听音频模式下没有画面可显示")
                            } else {
                                enterPip()
                            }
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.Sound, "音轨") {
                            moreSheetVisible = false
                            audioTrackSheetVisible = true
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.AspectRatio, "画面") {
                            moreSheetVisible = false
                            pictureSheetVisible = true
                        },
                    )
                    if (chapters.isNotEmpty()) {
                        add(
                            QuickTileSpec(NpIcon.List, "章节") {
                                moreSheetVisible = false
                                chaptersPanelVisible = true
                            },
                        )
                    }
                    add(
                        QuickTileSpec(NpIcon.Screenshot, "截图") {
                            moreSheetVisible = false
                            screenshot()
                        },
                    )
                    add(
                        QuickTileSpec(
                            icon = NpIcon.SleepTimer,
                            label = sleepLabel,
                            selected = sleepTimerEndsAtMs > 0,
                        ) {
                            toggleSleepTimer()
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
                    // 锁屏锁定键（§4.2）：锁定后屏蔽全部手势/返回/键盘，仅解锁钮可点。
                    // 全屏顶栏 🔒 与这里共用同一份 [stageLocked] 状态。
                    add(
                        QuickTileSpec(NpIcon.Lock, "锁屏") {
                            moreSheetVisible = false
                            stageLocked = true
                        },
                    )
                    add(
                        QuickTileSpec(NpIcon.Subtitle, "字幕", selected = subtitleOn) {
                            moreSheetVisible = false
                            subtitleSheetVisible = true
                        },
                    )
                    if (queue.size > 1) {
                        add(
                            QuickTileSpec(NpIcon.QueueMusic, "选集") {
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
                            toggleFavorite()
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
                    icon = NpIcon.Sun,
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
                    // L17：这个开关是**会话级**（`rememberSaveable`，退页即复原成"允许"），
                    // 副题如实标注生效范围，避免用户以为它像设置页开关一样持久
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "允许屏幕截图",
                            style = NpTypography.bodyMedium,
                            color = ink0.fg,
                        )
                        Text(
                            text = "仅本次播放有效",
                            style = NpTypography.bodySmall,
                            color = ink0.muted,
                        )
                    }
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
 * M18 入场：面板挂载后从 [from] 弹到 1、同时淡入（`bouncySpring(Switch)`）。
 *
 * 返回 State 而不是值，是为了让 `graphicsLayer` 在绘制期读——每一帧不触发重组。
 * 错误/结束原先是硬切（Q9）：一个"出事了"的面板直接拍在脸上，比淡入更让人慌。
 */
@Composable
private fun npPanelEntrance(from: Float): Pair<State<Float>, State<Float>> {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val spec = npMotion<Float>(Motion.Switch, bouncy = true)
    return animateFloatAsState(if (shown) 1f else from, spec, label = "panelScale") to
        animateFloatAsState(if (shown) 1f else 0f, spec, label = "panelAlpha")
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
    val (scale, entranceAlpha) = npPanelEntrance(0.96f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = entranceAlpha.value
            },
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
                // M24 增删动画：位移交给 animateItem 的 spring，淡入淡出用 `Switch`(220)
                // ——删掉的一集 fade 掉、下面的行跟着补位，比"瞬间消失"少一次视觉跳跃。
                val queueFade = npItemFadeSpec()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        val selected = index == currentIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .animateItem(fadeInSpec = queueFade, fadeOutSpec = queueFade)
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
    modifier: Modifier = Modifier,
) {
    // 38→32dp：原规格的裸值把竖屏小窗的控制层顶得过高（实报"占视频区一半"）；图标仍 20dp。
    // 32dp 是**视觉**不是**命中**——[npTouchTarget] 把槽位撑到 44dp（§14 #13 红线），
    // 这里没有底色，长出来的只有透明边距：行高不变，横向每钮多 12dp。
    // M1「补铺到底栏按钮」：这里此前是裸 `clickable`——既没有按压缩放也没有触觉，
    // 与同一排的其他钮不一致。0.97 是 §7.1 给图标按钮的形（卡片是 0.96）。
    Box(
        modifier = modifier
            .npTouchTarget(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .npPressScale(onClick = onClick, pressedScale = 0.97f),
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

/** 把控件在窗口中的位置回报给全屏下拉菜单当锚点；无回调时零开销 */
private fun anchorReporter(onAnchor: ((Rect) -> Unit)?): Modifier =
    if (onAnchor == null) Modifier
    else Modifier.onGloballyPositioned { onAnchor(it.boundsInWindow()) }

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
            modifier = Modifier.npTouchTarget(NpDimens.transportButton),
        ) {
            Icon(
                imageVector = NpIcon.SkipPrevious,
                contentDescription = "上一集",
                tint = if (hasPrevious) tint else disabledTint,
            )
        }
        // M6：播放/暂停图标 fade + scale 0.7↔1，走 `Field`。
        // `scaleIn(initialScale = 0.7f)` 不传 spec 会落回默认 spring（既不吃档位也不吃
        // Reduce Motion），四段必须同规格，否则淡入比缩放快，图标会"边变边飘"。
        val playToggleSpec = npMotion<Float>(Motion.Field)
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(animationSpec = playToggleSpec) +
                    scaleIn(initialScale = 0.7f, animationSpec = playToggleSpec))
                    .togetherWith(
                        fadeOut(animationSpec = playToggleSpec) +
                            scaleOut(targetScale = 0.7f, animationSpec = playToggleSpec),
                    )
            },
            label = "playPause",
        ) { playing ->
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.npTouchTarget(NpDimens.transportButton),
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
            modifier = Modifier.npTouchTarget(NpDimens.transportButton),
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
 * 竖滑亮度/音量 HUD（§4.3）：百分比只读在这一条指示器自身。
 *
 * 单独成组件是 §9.2 的硬要求：竖滑每帧写一次百分比，读在版式层就等于
 * "一次滑动把整块舞台（视频 surface + 顶栏 + 底栏）重组一遍"。
 */
@Composable
private fun BoxScope.VerticalAdjustHud(
    mode: StageVerticalMode,
    brightness: State<Int>,
    volume: State<Int>,
    sidePadding: Dp,
) {
    val onLeft = mode == StageVerticalMode.Brightness
    VerticalAdjustIndicator(
        mode = mode,
        percent = if (onLeft) brightness.value else volume.value,
        modifier = Modifier
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = sidePadding),
    )
}

/** 横滑 seek 气泡（§4.3）：目标与当前位置同样读在气泡自身，不牵动舞台版式。 */
@Composable
private fun BoxScope.SeekGestureBubbleHost(
    positionMs: State<Long>,
    targetMs: State<Float>,
) {
    val target = targetMs.value
    SeekGestureBubble(
        deltaMs = (target - positionMs.value).roundToLong(),
        targetMs = target.roundToLong(),
        modifier = Modifier.align(Alignment.Center),
    )
}

/**
 * 左上角续播提示（B 站同款「已为您定位到 xx:xx」）：进页时亮一次，几秒后自行淡出。
 *
 * 与旧的竖屏续播胶囊相比，这里**不跟着 positionMs 走**——提示的是"进页那一刻
 * 的定位结果"，播起来之后进度条自己会说话；常驻胶囊每 250ms 重排信息区、
 * 还会在每次播放时都挂着一条，既噪音又浪费（§9.2）。
 *
 * 倍速常驻胶囊（[SpeedPill]）同占左上角：它可见时本提示下移一行避让。
 */
@Composable
private fun ResumePositionNotice(
    resumeMs: Long?,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    // 退场动画期间 resumeMs 已置空——留住最后一次的值，避免淡出时文字跳成 0:00
    var lastMs by remember { mutableStateOf(0L) }
    LaunchedEffect(resumeMs) { if (resumeMs != null) lastMs = resumeMs }
    AnimatedVisibility(
        visible = resumeMs != null,
        enter = fadeIn(animationSpec = npMotion(Motion.Field)),
        exit = fadeOut(animationSpec = npMotion(Motion.Panel)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(start = NpSpace.lg, top = if (speed == 1f) 56.dp else 88.dp)
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = "已为您定位到 ${formatTimestamp(lastMs)}",
                style = NpMono,
                color = Color.White,
            )
        }
    }
}

/**
 * 底部控制层（进度 + 传输件）：压在视频上，用上下渐变 scrim + 白字
 * （横向全屏与竖屏视频区共用）。
 *
 * §9.2：进度三个量（实际位置 / 拖动量 / 显示位置）以 [State] 传入。
 * 本页每约 250ms 收到一次位置上报，解包成值会让整块版式跟着重组；
 * 现在订阅面收在这条控制层本身——它就是"seek 轨所在组件"，
 * 且控制层隐藏（沉浸式观看）时 AnimatedVisibility 不再订阅，代价归零。
 */
@Composable
private fun PlayerBottomControls(
    visible: Boolean,
    applyInsets: Boolean,
    showTransport: Boolean,
    positionMs: State<Long>,
    durationMs: Long,
    bufferedPositionMs: Long,
    dragPositionMs: State<Float>,
    userDragging: Boolean,
    displayPosition: State<Float>,
    maxPosition: Float,
    onScrubStart: () -> Unit,
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
    /** 内联倍速选择条（竖屏/仅听音频用）；全屏形态改下拉菜单后传 false */
    inlineSpeedRow: Boolean = true,
    /** ⛶ 的激活态（类 PC 沉浸全屏中） */
    fullscreenActive: Boolean = false,
    /** 触发控件的窗口位置回报（全屏下拉菜单的锚点） */
    onSpeedAnchor: ((Rect) -> Unit)? = null,
    onSubtitleAnchor: ((Rect) -> Unit)? = null,
    /** 进度条所在行的矩形（向上展开的下拉菜单以此为下限，永不遮住进度条） */
    onSeekBarAnchor: ((Rect) -> Unit)? = null,
    /**
     * 底栏后黑渐隐的高度。全屏形态 88dp；竖屏小窗的视频区只有 16:9 一条
     * （~230dp），固定大渐变会占掉小窗一半（用户实报），传 56dp。
     */
    scrimHeight: Dp = 88.dp,
    /**
     * §8.3 类 PC「控制层底部集中」：顶栏那批动作（返回 / 锁定 / ⋯ / 选集）改挂在本条
     * 左端。桌面窗口里鼠标常驻右下角，把动作摊到画面顶部等于让用户每次抬手跑半个屏幕。
     */
    leadingActions: (@Composable () -> Unit)? = null,
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
            // 行 1：进度条独占一行（设计稿 `.track`）——自绘三段式（已播/已缓冲/未缓冲）
            NpSeekBar(
                durationMs = durationMs,
                displayPositionMs = displayPosition.value,
                bufferedPositionMs = bufferedPositionMs,
                dragging = userDragging,
                onScrubStart = onScrubStart,
                onScrubChange = onScrubChange,
                onScrubEnd = onScrubEnd,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .then(anchorReporter(onSeekBarAnchor)),
            )
            // 倍速选择条：贴在倍速钮上方展开（替代原底部弹层——调速不该
            // 把整个画面盖住，也不该让用户的手从控制条跑到屏幕底下）。
            // 全屏形态改用下拉菜单后传 inlineSpeedRow=false，这条只服务竖屏/仅听音频。
            // M21：它是锚在钮上的紧凑菜单，走 `Field`（150）而不是控制层的 `Overlay`——
            // 点了要立刻有反应，280ms 在这里读起来是"卡了一下才出现"。
            // 位移开关读在组合期：`slideInVertically` 的 lambda 在过渡作用域里跑，拿不到组合上下文。
            val speedSlideAllowed = npDisplacementAllowed()
            AnimatedVisibility(
                visible = speedPickerVisible && inlineSpeedRow,
                enter = fadeIn(animationSpec = npMotion(Motion.Field)) +
                    slideInVertically(animationSpec = npMotion(Motion.Field)) {
                        if (speedSlideAllowed) it else 0
                    },
                exit = fadeOut(animationSpec = npMotion(Motion.Field)) +
                    slideOutVertically(animationSpec = npMotion(Motion.Field)) {
                        if (speedSlideAllowed) it else 0
                    },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (leadingActions != null) {
                            leadingActions()
                            Spacer(modifier = Modifier.width(NpSpace.sm))
                        }
                        Text(
                            text = formatTimestamp(
                                if (userDragging) dragPositionMs.value.toLong() else positionMs.value,
                            ),
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
                            modifier = anchorReporter(onSubtitleAnchor),
                        )
                        BarCtlButton(
                            icon = NpIcon.Speed,
                            contentDescription = "倍速播放",
                            onClick = onToggleSpeedPicker,
                            active = speed != 1f || speedPickerVisible,
                            modifier = anchorReporter(onSpeedAnchor),
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
                            active = fullscreenActive,
                        )
                    }
                }
            }
            Box(modifier = Modifier.height(NpSpace.xs))
        }
    }

    // 位移开关读在组合期（lambda 里不能再调 @Composable），淡入淡出不受影响
    val slideAllowed = npDisplacementAllowed()
    // §5.3：只挪 12dp，不是整条高度——整条滑出读起来像抽屉，12dp 是给淡入一点方向感
    val slidePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = npMotion(Motion.Overlay)) +
                slideInVertically(animationSpec = npMotion(Motion.Overlay)) {
                    if (slideAllowed) slidePx else 0
                },
            exit = fadeOut(animationSpec = npMotion(Motion.OverlayExit)) +
                slideOutVertically(animationSpec = npMotion(Motion.OverlayExit)) {
                    if (slideAllowed) slidePx else 0
                },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // 底栏后的黑渐隐（§4.5）：让画面在控件上方**逐渐**暗下去，
            // 而不是切出一条硬边。高度由 [scrimHeight] 按形态给——
            // 原先固定 160dp，平板横屏实报"底栏占比过大"、竖屏小窗实报
            // "渐变占视频区一半"，都源自这一个数。
            // 只有压在视频上的这四处允许纯黑——视频不是主题色（R11 规则）。
            Box(modifier = Modifier.fillMaxWidth()) {
                NpMaskFade(
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    size = scrimHeight,
                    fromTop = false,
                    startAlpha = 0.86f,
                    stops = 0.45f,
                )
                content()
            }
        }
    }
}

/**
 * 顶部控制层：返回 + 标题（+ 可选时钟电量/锁定键/右侧图标），压在视频上（渐变 scrim + 白字）。
 *
 * [trailing] 的图标与底栏右端图标**同一条竖基线**（都用 NpSpace.lg 收边），
 * 这样上下两栏读起来是一套控件，而不是两块各自为政的悬浮物。
 *
 * [showStatus]（§4.2 顶栏 56dp：`16:33 🔋41%`）：全屏沉浸下系统栏被隐藏，
 * 时间与电量由顶栏自绘。放在 trailing 图标之前——它是"环境信息"，不是"动作"。
 * [locked] / [onToggleLock]：锁屏锁定键（批次 2）。
 * [showActions] = false 时顶栏只剩标题与环境信息——§8.3 的类 PC「控制层底部集中」，
 * 动作由调用方下沉到底栏左端（鼠标和触控都落在同一条边上，不必在画面上下奔波）。
 */
@Composable
private fun PlayerTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    applyInsets: Boolean,
    modifier: Modifier = Modifier,
    showStatus: Boolean = false,
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    showActions: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    // 位移开关读在组合期（lambda 里不能再调 @Composable），淡入淡出不受影响
    val slideAllowed = npDisplacementAllowed()
    // §5.3：顶栏方向与底栏相反（自上方 12dp 落位）
    val slidePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = npMotion(Motion.Overlay)) +
            slideInVertically(animationSpec = npMotion(Motion.Overlay)) {
                if (slideAllowed) -slidePx else 0
            },
        exit = fadeOut(animationSpec = npMotion(Motion.OverlayExit)) +
            slideOutVertically(animationSpec = npMotion(Motion.OverlayExit)) {
                if (slideAllowed) -slidePx else 0
            },
        modifier = modifier.fillMaxWidth(),
    ) {
        // 顶栏后 100dp 黑渐隐（§4.5）：比底栏短——标题只有两行，
        // 更长的渐变会把画面顶部压成一条灰带，读起来像视频在"起雾"。
        Box(modifier = Modifier.fillMaxWidth()) {
            NpMaskFade(
                color = Color.Black,
                modifier = Modifier.align(Alignment.TopCenter),
                size = 100.dp,
                fromTop = true,
                startAlpha = 0.78f,
            )
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
                    .padding(start = if (showActions) 8.dp else NpSpace.md, end = NpSpace.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showActions) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.npTouchTarget(NpDimens.transportButton),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showStatus) {
                    StatusClockBattery(tint = Color.White)
                    if (showActions) {
                        BarCtlButton(
                            icon = if (locked) NpIcon.Lock else NpIcon.LockOpen,
                            contentDescription = if (locked) "已锁定" else "锁屏",
                            onClick = onToggleLock,
                        )
                    }
                }
                if (showActions) trailing()
            }
        }
    }
}

/**
 * 中央"继续播放"钮（设计稿 `.bigplay`）：64dp 玻璃圆 + 白三角。
 * 只在暂停时出现——播放中它不该占着画面。
 */
@Composable
internal fun CenterResumeButton(
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
        // 暗色玻璃 + 白图标（与锁定徽标/控制条同一套播放器覆盖层语言）。
        // 此前用 NpGlass 浅色玻璃 + 白 icon：浅色主题或浅色画面上是白底白图，
        // 图标整个隐形（用户实报"暂停按钮看不清"）。
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
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

/**
 * 手势反馈胶囊（"亮度 62%" / "音量 40%" / "快进 10 秒"）——屏幕中央，1.2s 自动消失。
 *
 * 没有它，滑动调节就是"盲调"：用户不知道当前值，也不知道手势是否被识别。
 * 竖滑亮度/音量已升级为竖条指示器（§4.3），本胶囊现只承载双击快进/快退的回执。
 */
@Composable
private fun GestureHintPill(text: String?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
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
 * 锁定态徽标（§4.2 🔒 / 批次 2「锁屏锁定键」）：锁定后舞台上**唯一**可点元素。
 *
 * **随控制层显隐**（本轮修正）：锁定瞬间亮起作反馈，随后按同一节拍自动隐去；
 * 锁定态下单指点按随时把它唤回（见 [PlayerScreen] 的 stageGestures）。
 * 旧行为是"常驻不隐"，用户实报"已锁定按钮不会隐藏，一直存在"。
 */
@Composable
private fun StageLockBadgeHost(
    visible: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 独立成组件的另一个原因：舞台嵌在 Column 里时，直接调 AnimatedVisibility 会解析到
    // ColumnScope 的重载而编译不过；这里没有隐式接收者，落到顶层重载。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
        modifier = modifier,
    ) {
        StageLockBadge(onUnlock = onUnlock)
    }
}

/** 44dp 玻璃圆「解锁」钮本体（外观与按压反馈） */
@Composable
private fun StageLockBadge(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .npPressScale(onClick = onUnlock, pressedScale = 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NpIcon.LockOpen,
            contentDescription = "解锁",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
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
 *
 * [scaleMode] 是批次 3「画面」面板的比例档（裁剪走引擎 np.crop）；[mirrored] /
 * [rotation] 直接落在画面 View 的变换上——字幕层是它的兄弟节点，不受变换影响。
 */
@Composable
internal fun VideoStage(
    player: VideoPlayer,
    videoAspectRatio: Float,
    cues: List<SubtitleCue>,
    overlays: List<PlaybackOverlay>,
    overlayMedia: MediaItem,
    navigator: Navigator,
    state: PlaybackState,
    userDragging: Boolean,
    /** 控制层是否可见——下发给覆盖层，让弹幕 pill 这类开关与控制栏同显隐 */
    controlsVisible: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    mirrored: Boolean = false,
    rotation: Int = 0,
    onSurfaceView: (TextureView?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 视频画面：按比例模式决定布局约束；黑边/裁剪行为随模式。
        // 竖向片（比例 < 1）在 FIT 下改「先匹配高度」：横向窗口里默认的先宽后高会被
        // 容器高度上限截断，宽度却仍按容器给，画面被压扁（平板横屏放 9:16 实测如此）。
        // CROP/拉伸 = 布局满窗（裁剪由引擎缩放模式完成 / 拉伸即用户显式选择变形）。
        val stageModifier = when (scaleMode) {
            VideoScaleMode.CROP, VideoScaleMode.STRETCH -> Modifier.fillMaxSize()
            else -> {
                val ratio = when (scaleMode) {
                    VideoScaleMode.R16_9 -> 16f / 9f
                    VideoScaleMode.R4_3 -> 4f / 3f
                    else -> videoAspectRatio
                }
                if (ratio > 0f) {
                    Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                } else {
                    Modifier.fillMaxSize()
                }
            }
        }
        Box(modifier = stageModifier) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { onSurfaceView(it) } },
                update = { view ->
                    player.attachDisplay(view)
                    // 镜像/旋转只翻画面：TextureView 的内容走正常渲染节点，View 变换
                    // 生效；字幕层是兄弟节点，镜像时字幕保持可读。
                    // （SurfaceView 做不到——它的画面由 SurfaceFlinger 独立合成，
                    //  批次 3 模拟器实测变换完全不落。）
                    view.scaleX = if (mirrored) -1f else 1f
                    view.rotation = rotation.toFloat()
                },
                onRelease = { onSurfaceView(null) },
                modifier = Modifier.fillMaxSize(),
            )
            // E1：字幕浮层（外挂 + 内嵌轨道统一出口），在画面变换之外
            SubtitleLayer(cues = cues, modifier = Modifier.fillMaxSize())
            // 覆盖层扩展点：弹幕等插件动态挂载（E23）。
            // 必须挂在**视频画面内框**（与字幕层同界）：挂到外层容器时弹幕会铺满
            // 整个窗格/整屏——类 PC 右栏里盖到顶栏与状态栏、宽片黑边上也飘字
            // （平板实拍：弹幕文字越过视频上缘压到应用标题区）。
            overlays.forEach { overlay ->
                overlay.Render(
                    PlaybackContext(
                        media = overlayMedia,
                        player = player,
                        navigator = navigator,
                        controlsVisible = controlsVisible,
                    ),
                )
            }
        }
        // 缓冲指示（M22）：原先 `if` 挂载=硬切，网络一顿一停时圆环是"啪"地闪现，
        // 读起来像播放器 itself 出了问题。进 150 / 出 120 淡入淡出。
        // 曲线不按 M22 字面的 linear——§3.8 把线性例外限定在"缓冲环旋转"等四类连续量上，
        // 显隐是一次性状态变化，必须走 Silk；线性留给环自身的旋转。
        AnimatedVisibility(
            visible = state == PlaybackState.Buffering && !userDragging,
            enter = fadeIn(tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
            exit = fadeOut(tween(npDuration(Motion.ReduceFade), easing = NpEasing.SilkExit)),
        ) {
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
internal fun PauseGlyph(
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
    val (scale, entranceAlpha) = npPanelEntrance(0.96f)
    // M18（§7.5 `Reject`）：错误面板出现的那一帧响一次——它与入场动画同时起步，
    // 是"停下来告诉你坏了"的面，不响就等于把错误做成了静默的视觉变化。
    val errorHaptics = rememberNpHaptics()
    LaunchedEffect(Unit) { errorHaptics.performHaptic(NpHaptic.Reject) }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = entranceAlpha.value
            }
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

/** 倍速文本（1.0x / 1.25x …） */
internal fun speedLabel(speed: Float): String =
    "%.2fx".format(speed).removeSuffix("0").removeSuffix(".")

/** 循环模式文案 */
internal fun loopLabel(mode: NpLoopMode): String = when (mode) {
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

internal fun isPlayableUriScheme(uriString: String): Boolean =
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
 * 路由查询参数编码（与迷你条、右栏、服务侧深链同一套；标题里带 `&`/空格是常态）。
 *
 * `URLEncoder` 把空格写成 `+`，查询参数按 URI 规范解码时它会原样留在标题里——
 * 统一改用 `Uri.encode(value, "")`（E55：允许集收到"仅 unreserved 字符"）。
 */
private fun encodeRouteParam(value: String): String = android.net.Uri.encode(value, "")

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
internal suspend fun resolveFolderQueue(
    registry: ExtensionRegistry,
    uri: String,
    mediaId: String,
): List<MediaItem> {
    // 批次 8（E88 连带修复）：此前无 id 时返回一个**硬编 video/mp4** 的假条目——
    // 该假条目会一路污染到引擎的起播探测（expectedMime），让音频文件被当成
    // "缺视频轨的视频"拦成失败。改为返回空队列：上游 ensureSessionPlaying 拿到
    // 空队列会让 `session.open` 用路由条目本身走单项队列（mime 是路由带来的真值）。
    if (mediaId.isEmpty()) return emptyList()
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
                    // 自然序（E42）：与媒体库排序**同一个比较器**（core:media NaturalOrder），
                    // 队列顺序必须与用户看到的顺序一致，否则"下一集"会跳错
                    .sortedWith(MediaNaturalOrder)
                val result = if (siblings.none { it.id == self.id }) siblings + self else siblings
                return@withContext result
            }
            emptyList<MediaItem>()
        }.getOrElse { emptyList() }
    }
}

/** 等媒体来源把"本项"扫出来的上限；超过则退回单项队列（绝不卡住进页） */
private const val SOURCE_WAIT_MS = 3_000L

// ---- PiP 动作（§5.3）：经标准 MEDIA_BUTTON 事件驱动会话，引擎无关 ----

private const val PIP_REQUEST_CODE_BASE = 2000

private fun pipActions(context: Context, playing: Boolean): List<RemoteAction> {
    fun mediaButton(keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            `package` = context.packageName
            putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
            )
        }
        return PendingIntent.getService(
            context,
            PIP_REQUEST_CODE_BASE + keyCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun icon(resId: Int) = android.graphics.drawable.Icon.createWithResource(context, resId)
    return listOf(
        RemoteAction(
            icon(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "暂停" else "播放",
            if (playing) "暂停播放" else "继续播放",
            mediaButton(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY),
        ),
        RemoteAction(
            icon(android.R.drawable.ic_media_next),
            "下一集",
            "播放下一集",
            mediaButton(KeyEvent.KEYCODE_MEDIA_NEXT),
        ),
    )
}

/** 同名字幕优先级（§12.6 同名挂载）：ASS 特效最全，VTT 兜底 */
private val SIBLING_SUBTITLE_EXTENSIONS = listOf("ass", "ssa", "srt", "vtt")

/**
 * 同名字幕自动挂载（批次 3）：查 MediaStore Files 表找**同目录、同基名**的字幕文件，
 * 按优先级取第一条。视频在 MediaStore 的 RELATIVE_PATH（Q+）与字幕所在目录比对；
 * 查不到（非 MediaStore 来源 / 无字幕）返回 null，调用方保持现状。
 */
internal suspend fun resolveSiblingSubtitle(context: Context, uri: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val videoMeta = resolver
                .query(
                    uri.toUri(),
                    arrayOf(
                        android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    ),
                    null, null, null,
                )
                ?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
                ?: return@runCatching null
            val displayName = videoMeta.first ?: return@runCatching null
            val base = displayName.substringBeforeLast('.', displayName)
            if (base.isBlank()) return@runCatching null
            // LIKE 通配符转义：文件名里的 % _ 会扩大匹配面
            val escapedBase = base
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val selection = SIBLING_SUBTITLE_EXTENSIONS
                .joinToString(" OR ") { "_display_name LIKE ? ESCAPE '\\'" } + " AND relative_path = ?"
            val args = SIBLING_SUBTITLE_EXTENSIONS.map { "$escapedBase.$it" } + (videoMeta.second ?: "")
            resolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(
                    android.provider.MediaStore.Files.FileColumns._ID,
                    android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                ),
                selection,
                args.toTypedArray(),
                null,
            )?.use { c ->
                var best: String? = null
                var bestPrio = Int.MAX_VALUE
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val prio = SIBLING_SUBTITLE_EXTENSIONS.indexOf(ext).let { if (it < 0) Int.MAX_VALUE else it }
                    if (prio < bestPrio) {
                        bestPrio = prio
                        best = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Files.getContentUri("external"),
                            c.getLong(0),
                        ).toString()
                    }
                }
                best
            }
        }.getOrNull()
    }
