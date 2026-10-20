package com.noneplayer.plugins.player.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.DEFAULT_DOUBLE_TAP_STEP_MS
import com.noneplayer.core.data.DEFAULT_SUBTITLE_COLOR_ARGB
import com.noneplayer.core.data.DEFAULT_SUBTITLE_MAX_LINES
import com.noneplayer.core.data.SUBTITLE_COLOR_OPTIONS
import com.noneplayer.core.data.SUBTITLE_DELAY_STEPS_MS
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MAX
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MIN
import com.noneplayer.core.data.SUBTITLE_SCALE_MAX
import com.noneplayer.core.data.SUBTITLE_SCALE_MIN
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MAX
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MIN
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.MediaNaturalOrder
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.noneplayer.core.ui.NpCoverFlight
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.NpQuickTile
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpStableWidth
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
import com.noneplayer.core.media.AviDuration
import com.noneplayer.core.ui.LocalNpReduceMotion
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npQuality
import com.noneplayer.core.ui.npItemFadeSpec
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.rememberNpHaptics
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.core.ui.rememberNpToastState
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.NpMediaButtonAction
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.PlayerConnection
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.VideoPlayerFactory
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.NpDetailRequest
import com.noneplayer.kernel.ui.api.PlaybackContext
import com.noneplayer.kernel.ui.api.PlaybackMenuContributor
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import com.noneplayer.kernel.ui.api.NpFormHandoff
import com.noneplayer.kernel.ui.api.NpMorph
import com.noneplayer.kernel.ui.api.NpMorphKey
import com.noneplayer.kernel.ui.api.npMorphPart
import com.noneplayer.plugins.player.ui.brightness.rememberWindowBrightness
import com.noneplayer.plugins.player.ui.controls.NpSeekBar
import com.noneplayer.plugins.player.ui.controls.StatusClockBattery
import com.noneplayer.plugins.player.ui.gesture.GESTURE_INDICATOR_HOLD_MS
import com.noneplayer.plugins.player.ui.gesture.STAGE_BOTTOM_DEAD_ZONE_DP
import com.noneplayer.plugins.player.ui.gesture.STAGE_EDGE_MIN_DP
import com.noneplayer.plugins.player.ui.gesture.STAGE_TOP_DEAD_ZONE_DP
import com.noneplayer.plugins.player.ui.gesture.SeekGestureBubble
import com.noneplayer.plugins.player.ui.gesture.StageGestureZones
import com.noneplayer.plugins.player.ui.gesture.StageTapZone
import com.noneplayer.plugins.player.ui.gesture.StageVerticalMode
import com.noneplayer.plugins.player.ui.gesture.VerticalAdjustIndicator
import com.noneplayer.plugins.player.ui.gesture.VerticalSwipeAccumulator
import com.noneplayer.plugins.player.ui.gesture.clampSeekTargetMs
import com.noneplayer.plugins.player.ui.gesture.npStageGestures
import com.noneplayer.plugins.player.ui.gesture.stageDpAtLeast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** User-selected six presets, shared by compact and fullscreen menus. */
internal val SPEED_PRESETS = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f)

/** 竖屏未全屏：倍速钮上方只弹这五档。横屏菜单才给 3x 和微调。 */
internal val SPEED_PORTRAIT_PRESETS = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)

/** 微调步进与合法区间（L14：0.05 一档；上限 4x 与长按 boost 的 3x 留余量） */
internal const val SPEED_FINE_STEP = 0.05f
internal val SPEED_FINE_RANGE = 0.25f..4.0f

/** 拖动预览 seek 的最小间隔：更密会在本地大文件上反复解码，更疏则画面不跟手 */
private const val SCRUB_PREVIEW_INTERVAL_MS = 200L

/** 播放页自动隐藏控制层延时 */
private const val CONTROLS_AUTO_HIDE_MS = 3500L

/**
 * 底栏「全半透明」底板的不透明度（用户实报"控制栏的颜色不够清楚，不要做成渐变，
 * 做成全半透明的颜色"）。0.62 是原渐变按 §14 #9 红线调过的中段 alpha——整条均匀使用，
 * 白字对纯白画面仍 ≥5:1；更透会在亮画面上重新跌破 4.5:1。
 */
private const val BOTTOM_BAR_PLATE_ALPHA = 0.62f

/** 底栏入场位移：自视频/屏幕下缘升起的方向感 */
private val BAR_ENTER_SLIDE = 12.dp

/** 底栏退场位移（比入场更短——退场要更快离开，§3.9） */
private val BAR_EXIT_SLIDE = 8.dp

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
    audioOnlyEntry: Boolean? = null,
    manualPipEntry: Boolean = false,
    /**
     * false = **只装载、不起播**（路由 `play=0`）。
     *
     * 转屏交接自横屏剧集详情页时用：那一档本来就没在播，落点就该是"竖屏播放页的未播放态"，
     * 而不是被人替按了播放键。用户自己点进来的路径恒为 true（媒体库/迷你条/右栏 ⛶）。
     */
    startPlaying: Boolean = true,
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
    val screenshotBlocked by settings.screenshotBlocked.collectAsStateWithLifecycle(initialValue = false)
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
    // REV-10：播放服务连接状态（被系统回收 / 正在重连 / 重连失败）。只驱动顶部一条
    // 轻横幅——不参与布局、不接管手势，画面照常可交互。
    val connection by session.connection.collectAsStateWithLifecycle()
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
    val latestBackgroundPlay by rememberUpdatedState(backgroundPlay)
    DisposableEffect(mediaId) {
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
            if (!latestBackgroundPlay) session.stop()
        }
    }

    // 深链/通知入口（E2/E3）可能只携带 uri：标题与封面先用兜底解析补齐，
    // 保证媒体通知有标题封面、播放页标题栏不空
    var displayTitle by remember(uri) { mutableStateOf(title) }
    var artworkUri by remember(uri) { mutableStateOf<String?>(null) }

    // M2 共享封面（§6）：媒体库卡片点击时记下的"机位"，本页取走并把封面从那儿补间到舞台
    // （`Shared` 300ms + `Emphasized`——规格里该曲线只给共享元素与主播放键）。
    // 档位（§9.6 `sharedElement`）与 Reduce Motion 已在起飞侧拦过，这里只认 pending。
    val coverTakeoff = remember(mediaId) {
        if (mediaId.isEmpty()) null else NpCoverFlight.take(mediaId)
    }
    val coverFlight = remember { Animatable(1f) }
    val coverMorphMs = npDuration(Motion.Shared)
    val coverFadeMs = npDuration(Motion.ReduceFade)
    var stageRectInWindow by remember { mutableStateOf<Rect?>(null) }
    val surfaceViewRef = remember { mutableStateOf<TextureView?>(null) }
    // 本页在栈上时，形态变化由本页自己适配（见 `LaunchedEffect(isDesktopLike)` 那段），
    // 外壳的转屏交接不得再推第二个播放页上来。
    DisposableEffect(Unit) {
        NpFormHandoff.notePlayerPage(true)
        onDispose { NpFormHandoff.notePlayerPage(false) }
    }
    // 转横屏（进入类 PC 档）时画面归右栏，外壳在**第一帧横屏画出来之前**先来这里取走
    // 最后一帧与它此刻的矩形，再调 `retire` 退本页（见下面登记处）。抓帧在 FLAG_SECURE
    // 下拿不到画面，那就交不出东西——交接退回纯黑封面，也别把一张黑图当成画面飞过去。
    val handoffToken = remember { Any() }
    LaunchedEffect(coverTakeoff) {
        coverTakeoff ?: return@LaunchedEffect
        coverFlight.snapTo(0f)
        // 等舞台量出目标矩形（首帧之内）再起飞；量不到就不飞（比飞错位置体面）。
        // A6：用 snapshotFlow 等首帧，替代 `while (null) delay(16)` 的轮询空转。
        snapshotFlow { stageRectInWindow }.filterNotNull().first()
        coverFlight.animateTo(1f, tween(coverMorphMs, easing = NpEasing.Emphasized))
        // 落位后短暂停留，再把封面淡出让位给已经出帧的视频（末段 15% 行程读作淡出）。
        // easing 不能省：不写就落 Compose 默认的 FastOutSlowIn，那是 §3.8 曲线集之外的
        // 第 6 条（E119 普查订正：§14 #1 曾记"曲线绕过 0 处"，这一处是漏网的）。
        coverFlight.animateTo(1.15f, tween(coverFadeMs, easing = NpEasing.SilkExit))
    }
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
    // 右栏转屏交过来时会话已经在播这一条。装载协程至少要等一帧，这段时间若整页只画
    // 转圈，竖屏第一帧就是一块白——系统转屏截图会把它收进去。同一条媒体直接出播放页。
    var mediaOpened by remember(uri) {
        mutableStateOf(
            session.nowPlayingSummary.value?.item?.let { item ->
                (mediaId.isNotEmpty() && item.id == mediaId) || item.uri == uri
            } == true,
        )
    }

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
    LaunchedEffect(uri, audioOnlyEntry) {
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
        // 1) 解析展示名（同一协程内先于装载，保证通知拿到元数据）
        val resolvedTitle = title.ifBlank { resolveDisplayName(context, uri) }
        displayTitle = resolvedTitle
        // 深链（无 id、无 mime）时补查 MediaStore 的真实 MIME，供页头容器名显示（§7.4）
        if (mediaId.isEmpty()) resolvedMimeType = resolveMimeType(context, uri)
        // 2) 装载并起播——与「内嵌播放器」共用同一条链（SessionBootstrap）：
        //    队列 / 断点 / 字幕记忆 / 倍速的语义只有一处，避免两处漂移。
        //
        // 封面不再在装载**之前**单独查：`resolveCoverUri` 会把整个媒体来源快照拉一遍
        // 再线性找 id，而下面的装载链为了解析同文件夹队列已经拉过同一份快照——两趟全量
        // 扫描串在"点播放 → 出画"之间。封面只在播放结束画面当海报用，不是出画所需，
        // 改由装载结果回传（见 SessionStart.coverUri）。
        val started = session.startPlayback(
            item = MediaItem(
                id = mediaId,
                sourceId = "",
                title = resolvedTitle,
                uri = uri,
                mimeType = resolvedMimeType ?: mimeType,
            ),
            restorePosition = !fromStart,
            // E104：进播放页不再无条件起播——**会话是真相，页面只是显示它**。
            // 暂停态进来保持暂停、结束态停在结束画面（让用户自己按播放/重播）；
            // 唯一"要它播"的显式入口是「从头播放」（from=start，见上一行）与新装载
            // （不同媒体 / 新会话，那条分支照旧 play）。此前"进页一律 play()"与类 PC
            // 右栏的被动语义正好相反，来回按 F/⛶ 会读成"暂停只在浏览侧有效"。
            autoPlay = fromStart,
            prepareOnly = !startPlaying,
            audioOnlyMode = audioOnlyEntry,
        )
        // 封面：装载链从权威队列项带回；拿不到（会话已在播同一媒体 / 队列里没有本项）
        // 才回退自查——此时**已经在播**，这一趟扫描不挡出画。
        artworkUri = started.coverUri ?: resolveCoverUri(kernel.registry, mediaId)
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
    // S5 缓冲段可视化：进度条三段式的"已缓冲"段数据源。
    // A6：与位置同一条纪律——页根**不解包**，只把 [State] 交给底栏，
    // 由 NpSeekBar 在绘制相位读 `.value`（缓冲前沿变化只重画，不重组）。
    val bufferedState = player.bufferedPositionMs.collectAsStateWithLifecycle()
    val videoAspectRatio by player.videoAspectRatio.collectAsStateWithLifecycle()
    // A6：Cue 也不在页根解包——订阅点下沉到 PlayerSubtitleLayer（见 SubtitleLayer.kt）。
    // 旧写法每来一组字幕重组整页，PGS 一秒好几次；无字幕的片子测不出来，一直没暴露。
    // 队列与下标提到组合顶层：底部控制层（上/下一集按钮）与选集面板两处都要用，
    // 放在底栏 AnimatedVisibility 内部会让面板拿不到（且底栏隐藏时值也读不到）。
    val queue by session.queue.collectAsStateWithLifecycle()
    val queueIndex by session.index.collectAsStateWithLifecycle()
    // MediaStore 对 AVI 经常给 0 时长。选集行先用播放器已经知道的时长，
    // 其余 AVI 再读文件头，不把整表重扫挡在显示前面。
    val headerDuration = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    LaunchedEffect(queue) {
        val missing = queue.filter { item ->
            item.durationMs <= 0L &&
                headerDuration[item.id] == null &&
                (item.title.endsWith(".avi", ignoreCase = true) ||
                    item.mimeType.contains("avi", ignoreCase = true))
        }
        if (missing.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            missing.mapNotNull { item ->
                val ms = runCatching {
                    context.contentResolver.openInputStream(item.uri.toUri())?.use { AviDuration.read(it) }
                }.getOrNull() ?: 0L
                if (ms > 0L) item.id to ms else null
            }
        }
        found.forEach { (id, ms) -> headerDuration[id] = ms }
    }
    // 页头（playerbar / pinfo / 文件信息卡）以**会话当前项**为准而不是路由入参：
    // 选集切换或底栏 ⏭ 之后，标题与容器必须立刻换成新一集，
    // 而不是停在进页时那一条（路由 uri 不会因子会话内切集而改变）。
    val currentItem = queue.getOrNull(queueIndex)
    fun queueDuration(item: com.noneplayer.core.media.MediaItem): Long {
        if (item.durationMs > 0L) return item.durationMs
        headerDuration[item.id]?.let { if (it > 0L) return it }
        if (item.id == currentItem?.id && durationMs > 0L) return durationMs
        return 0L
    }
    /**
     * per-media 记忆的键（A2-8）：`sourceId:mediaId`，与 Room 的 `media_key` 同一约定。
     *
     * 优先用会话已落定的权威条目（带真 sourceId）；只有路由 id 时按当前唯一来源补前缀
     * （与 [AppSettings.migratePerMediaKeyNamespaceOnce] 对旧键的推断一致）。
     * 两者都拿不到 → 空串，**空键一律不读不写**：E109 实测读空键会命中历史遗留的
     * 孤儿桶（脏值还会被写回真键扩散）。
     */
    val mediaKey = currentItem
        ?.takeIf { it.uri == uri && it.id.isNotEmpty() }
        ?.let { mediaMemoryKey(it.sourceId, it.id) }
        ?: mediaId.takeIf { it.isNotEmpty() }?.let { mediaMemoryKey("", it) }
            .orEmpty()
    // A14：小窗源矩形的内接矩形要读缩放模式，声明提前到 PiP 辅助函数之前。
    var scaleMode by remember(mediaId) { mutableStateOf(VideoScaleMode.FIT) }
    var mirrored by remember(mediaId) { mutableStateOf(false) }
    var rotation by remember(mediaId) { mutableIntStateOf(0) }
    // 转横屏（进入类 PC 档）时画面归右栏，外壳在**第一帧横屏画出来之前**调 `retire`
    // 退本页。不这么做，那一帧就是"播放内容被全屏放大成横屏版式"（用户实报"生硬且奇怪"）。
    // 以前这里还要先抓一帧交给幽灵层飞，现在不用：画面是同一个 TextureView，
    // 由整屏 morph 把它从竖屏的位置搬到横屏右栏的位置（E133，见 NpMorph）。
    // 登记在 `currentItem` 之后：退页时左栏要落到正在播的那条，否则横屏里左栏还停在别处——
    // 交接侧在退页之前顺手带走这一步，本页下面那条兜底的 `LaunchedEffect` 就不必再跑一遍。
    // 退页动作注册一次（`handoffToken` 不变），但**当前项要读最新的**：
    // 注册发生在首帧，那一刻队列还没解析出来；直接捕获 `currentItem` 会永远拿到 null，
    // 于是横屏左栏收不到 `NpDetailRequest`，停在媒体库首页而不是剧集页。
    val latestItemForHandoff = rememberUpdatedState(currentItem)
    DisposableEffect(handoffToken) {
        NpFormHandoff.bindStageRetire(handoffToken) {
            latestItemForHandoff.value?.let { NpDetailRequest.request(it) }
            navigator.popBackStack()
        }
        onDispose { NpFormHandoff.releaseStageRetire(handoffToken) }
    }
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
    // A14：小窗态要早于方向/版式判定声明——方向 effect 与小窗分支都读它。
    var inPipMode by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
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
    LaunchedEffect(orientationLock, inPipMode) {
        if (inPipMode) return@LaunchedEffect // A14：小窗内冻结方向锁
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
    LaunchedEffect(orientationLock, windowIsPortrait, inPipMode) {
        if (inPipMode) return@LaunchedEffect // A14：小窗的 9:16 不是"竖屏版式"
        if (orientationLock == OrientationLock.Portrait && windowIsPortrait) {
            orientationLock = OrientationLock.FollowSensor
        }
    }
    // 离开播放页恢复系统默认方向（全屏方向不残留到其他页面）
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // 用户点出来的「全屏」要告诉外壳：平板横过来在类 PC 档本应换成"左栏 + 右栏"，
    // 但这一下是用户要的全屏，整屏归本页——否则转横的瞬间播放页就被退掉、
    // 画面缩进右栏，读起来就是"点全屏没反应"（2026-10-15 真机实报）。
    DisposableEffect(orientationLock) {
        val fullscreenIntent = orientationLock == OrientationLock.Landscape
        NpFormHandoff.noteStageFullscreen(fullscreenIntent)
        onDispose { NpFormHandoff.noteStageFullscreen(false) }
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
    LaunchedEffect(isFullscreen, inPipMode) {
        if (inPipMode) return@LaunchedEffect // A14：小窗窗口是竖的，别把横屏锁清掉
        if (!isFullscreen) orientationLock = OrientationLock.FollowSensor
    }
    // fs=1（详情「全屏观看」/ 右栏 ⛶）：落地即全屏——竖窗先请求横向，
    // 横窗（平板自然方向）本来就是全屏，无需请求。
    LaunchedEffect(immersiveEntry, inPipMode) {
        if (!inPipMode && immersiveEntry && windowIsPortrait) setFullscreen(true)
    }

    /**
     * 类 PC 档的「交还浏览态」：先让整屏 morph 起飞（冻结当前这批部件矩形），再退掉本页。
     * 右栏靠"会话有没有当前项"自行出现（`DetailPaneProvider.isOccupied`），无需传参。
     *
     * 这里不换窗口形态，但**版式**换了：画面从整屏收到右栏、标题与按钮各归各位。
     * 以前靠抓帧补间，现在由这些节点自己飞（E133）——所以也不再需要防截屏的例外。
     */
    fun returnToBrowsePane() {
        if (isDesktopLike && !session.audioOnly.value) NpMorph.begin()
        navigator.popBackStack()
    }

    /**
     * 「返回类」出口（系统返回键 / Esc / 左上角返回箭头）：**保证能出去**。
     * 有全屏可退 → 退全屏留在播放页；否则 → 离开播放页。
     */
    fun exitFullscreenOrLeave() {
        if (orientationLock != OrientationLock.Landscape) {
            returnToBrowsePane()
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
            isDesktopLike -> exitFullscreenOrLeave()
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
    // 擦洗会话：拖动开始时是否在播（松手后恢复）、起手时的真实位置（取消时回滚），
    // 以及预览 seek 限流时间戳
    var scrubWasPlaying by remember { mutableStateOf(false) }
    var scrubStartMs by remember { mutableLongStateOf(0L) }
    var lastPreviewSeekAtMs by remember { mutableLongStateOf(0L) }
    // B2 修复：倍速以会话实际值为准（后台会话保持的倍速在重进后仍正确显示）
    val speed by player.speed.collectAsStateWithLifecycle()
    val savedSubtitle by settings.subtitleUri(mediaKey).collectAsStateWithLifecycle(initialValue = null)
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
                val prepared = prepareSubtitleUri(context, uriResult)
                player.setSubtitle(prepared.uri, prepared.format)
                // 记忆原始地址：缓存目录可能被清理，恢复时重新转码
                settings.setSubtitleUri(mediaKey, uriResult.toString())
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
        // 记下起手位置：拖动期间的预览 seek 会真的把引擎挪走，取消时要能放回这里
        scrubStartMs = player.positionMs.value
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

    /**
     * 拖动被**取消**（系统边缘返回 / 父节点拦截手势）——E129 / 审查 #3。
     *
     * 与 [onScrubEnd] 的关键差别是**不提交新位置**：用户取消一次手势，不该被解释成
     * "确认跳到最后一帧拖到的位置"。但拖动期间的预览 seek 已经把引擎真的挪走了，
     * 所以这里不是"原地不动"，而是**回滚**——把引擎放回起手位置。
     *
     * 清理必须彻底：`userDragging` 要落回 false，否则控制层自动隐藏守护会一直认为
     * "用户还在拖"，控制条从此不再自动收起（这正是取消路径最容易留下的卡死）。
     */
    fun onScrubCancel() {
        if (!userDragging) return
        player.setScrubbing(false)
        // 清掉"钉住目标位置"，进度条交还给真实播放位置
        pendingSeekMs = null
        dragPositionMs = scrubStartMs.toFloat()
        player.seekTo(scrubStartMs)
        if (scrubWasPlaying) player.play()
        userDragging = false
    }

    // A7#8：手势进行中不按旧节拍收起控制层。两个手势态提前声明（自动隐藏守护要读，
    // 而 Kotlin 局部变量不能先用后声明）。
    var seekGestureActive by remember { mutableStateOf(false) }
    var verticalGesture by remember { mutableStateOf<StageVerticalMode?>(null) }

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
        // A7#8：手势进行中也算"用户正在操作"，旧 key 不包含手势态，
        // 长按 3x/横滑 seek 期间控制层会按旧节拍自行收起。
        seekGestureActive,
        verticalGesture,
    ) {
        if (pointerAttached && !isImmersive && !stageLocked) return@LaunchedEffect
        if (fsMenuPage != null) return@LaunchedEffect
        // 手势进行中不收起；手势结束（key 变回 false/null）时重新按完整节拍计时。
        if (seekGestureActive || verticalGesture != null) return@LaunchedEffect
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

    // 插件贡献的选项行（2026-10-12 用户裁定：弹幕开关进「播放选项」表）。
    // 与 overlays 同法响应式收集——插件启用后即刻出现在菜单里。
    val menuContributors by remember { kernel.registry.observe<PlaybackMenuContributor>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackMenuContributor>())

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
                userDraggingState.value -> dragPositionMsState.floatValue
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
    val sessionAudioOnly by session.audioOnly.collectAsStateWithLifecycle()
    val audioOnly = sessionAudioOnly || currentItem?.kind == MediaKind.AUDIO ||
        (!mediaOpened && (audioOnlyEntry == true || mimeType.startsWith("audio/")))
    // 仅听页的系统返回也离开播放页。后注册，优先于「横屏先退全屏」。
    BackHandler(enabled = audioOnly && !stageLocked) { navigator.popBackStack() }
    val loopMode by session.loopMode.collectAsStateWithLifecycle()
    var speedPickerVisible by remember { mutableStateOf(false) }
    // 双击步长（L14）：设置页可改，默认 10s（批次 2 的既有手感）
    val doubleTapStepMs by settings.doubleTapStepMs
        .collectAsStateWithLifecycle(initialValue = DEFAULT_DOUBLE_TAP_STEP_MS)
    var subtitleSheetVisible by remember { mutableStateOf(false) }
    var moreSheetVisible by remember { mutableStateOf(false) }
    // 审查批次 B1：这三个原本声明在"锁定"清理块之后，而统一收口函数必须能引用
    // 全部弹层状态——局部函数引用晚于自己声明的变量会直接编译不过。
    // 所有"弹层开关"集中在这里，收口函数与新增状态都在同一处可见。
    var audioTrackSheetVisible by remember { mutableStateOf(false) }
    var pictureSheetVisible by remember { mutableStateOf(false) }
    var chaptersPanelVisible by remember { mutableStateOf(false) }

    /** 选倍速：落到播放器 + 持久化 + 回执，并收起选择条 */
    fun pickSpeed(preset: Float) {
        player.setSpeed(preset)
        scope.launch { settings.setPlaybackSpeed(preset) }
        toast.show("倍速：" + speedLabel(preset))
        speedPickerVisible = false
    }

    /** 0.05 微调（L14）：按 0.05 网格取整，避免连点后漂出 1.0000001 这种值 */
    fun nudgeSpeed(delta: Float) {
        val stepped = ((speed + delta) * 20f).roundToInt() / 20f
        pickSpeed(stepped.coerceIn(SPEED_FINE_RANGE))
    }

    // C18：文件信息卡（标题/大小/分辨率/编码/文件夹/时长 的真实值）
    var fileInfoVisible by remember { mutableStateOf(false) }
    // C18：允许屏幕截图（FLAG_SECURE 取反）。默认关闭 = 允许截图，
    // 打开"禁止"时窗口不可被截图/录屏（与设计稿 check-row 同一语义）。
    // A1-6：值改**持久**（此前是页内 `rememberSaveable`，退页即复原成"允许"——
    // 隐私开关被静默清掉比"默认允许"更糟）。生效范围不变：仍只挂播放页窗口。
    fun setScreenshotBlocked(blocked: Boolean) {
        scope.launch { settings.setScreenshotBlocked(blocked) }
        // toast 报的是**目标值**：状态要等 DataStore 回流才更新，读它会慢一拍说反话
        toast.show(if (blocked) "已禁止屏幕截图" else "已允许屏幕截图")
    }
    DisposableEffect(activityWindow, screenshotBlocked) {
        val window = activityWindow
        // 必须捕获本次副作用的值：直接读委托属性会在 onDispose 时取到最新值，
        // 开关从「禁止」恢复「允许」时释放分支读到 false，FLAG_SECURE 永久残留。
        val blocked = screenshotBlocked
        if (window != null && blocked) WindowSecureFlag.acquire(window)
        onDispose {
            if (window != null && blocked) WindowSecureFlag.release(window)
        }
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
    // 长按 3× 快进（设计稿 .gest-hint）：进入时记住原速，松手恢复
    var boostHintVisible by remember { mutableStateOf(false) }
    var boostPreviousSpeed by remember { mutableStateOf(1f) }
    /** 长按临时提速的倍率（§4.3 手势表：3×） */
    val boostSpeed = 3f
    /**
     * 结束临时提速。
     *
     * A1-5：**只在倍速仍是 boost 设进去的那一档时才回滚**。boost 期间用户从倍速面板
     * 或快捷键改过倍速的话，那份新选择才是他的意图——无条件写回起手时的旧快照，
     * 等于把他的操作吞掉（此前正是这样：面板刚选的 1.5x 一松手变回 1.0x）。
     */
    fun endSpeedBoost(withHaptic: Boolean) {
        if (!boostHintVisible) return
        if (speed == boostSpeed) player.setSpeed(boostPreviousSpeed)
        boostHintVisible = false
        if (withHaptic) haptics.performHaptic(NpHaptic.Long)
    }
    // 亮度（window attr 0.01..1 映射为 1..100）/ 音量（AudioManager STREAM_MUSIC）
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
    // A8：亮度读数/捕获/恢复统一走 rememberWindowBrightness（与内嵌播放器共用同一助手）。
    // 旧实现把"窗口无覆盖(-1f)"当成读不到、兜底写死 62%——用户还没滑窗口就被覆盖成 62%，
    // 且离开播放页没有任何恢复路径。助手内：进页捕获原值（含 NONE）、离页精确写回、
    // 读数优先窗口 override 再读系统亮度、都读不到才用中性 50%。
    val windowBrightness = rememberWindowBrightness(activity)
    val brightnessState = windowBrightness.percent
    var brightnessPercent by brightnessState
    val volumeState = rememberSaveable {
        mutableIntStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / maxVolume)
    }
    var volumePercent by volumeState
    fun setBrightnessPercent(value: Int) {
        // clamp 到 1..100、"平台现值相同不写"、UI 状态回写都在助手内完成（A8 同一份策略，
        // 内嵌播放器滑杆与舞台竖滑共用）。
        windowBrightness.setPercent(value)
    }
    fun setVolumePercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        val previousIndex = volumeIndexFor(maxVolume, volumePercent)
        volumePercent = clamped
        val index = volumeIndexFor(maxVolume, clamped)
        // 同一档内继续滑不该再打 binder：`setStreamVolume` 每次还会拉起系统音量条，
        // 逐帧发就是"自己跟自己的 HUD 抢屏"。读数照写，落盘才判档。
        if (index == previousIndex) return
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            // 与右栏兜底派发共用同一条换算（E101 观察②：截断会让 ±5% 步进偶发无效果）
            index,
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
        // A7#7：上限读**回调执行时**的时长——`durationMs` 是组合期快照，AVI 时长回填、
        // 切集之后旧值会把目标夹进旧片长（甚至为 0）。
        val target = raw.coerceIn(0L, durationState.value.coerceAtLeast(0L))
        if (target != raw) rejectHaptic() else haptics.performHaptic(NpHaptic.Tick)
        player.seekTo(target)
        val seconds = (stepMs / 1000L).toInt()
        gestureHint = if (forward) "快进 $seconds 秒" else "快退 $seconds 秒"
    }

    // M8 半屏水波（§6/§9.6）：双击快进/快退时从**点的那一侧**扩散到半屏。
    // 与 M6 的中央水波共用 `centerBubble` 门控（High 档；§9.4 播放中自动降档会关掉
    // 装饰类，所以真正会看到的是"暂停时双击预览落点"这一路）。时长读在组合期。
    val seekWaveOn = npQuality().centerBubble
    val seekWaveMs = npDuration(Motion.Bubble)
    val seekWave = remember { Animatable(0f) }
    var seekWaveFromRight by remember { mutableStateOf(false) }
    // 水波**是否正在播放**：只由动画期间为 true。
    // 此前绘制只看 `centerBubble` 档位不看状态，而静息帧里 `seekWave` 停在 0f——
    // alpha=0.34、scale=0.35 的**空环常驻在左半屏**；播放中自动降档掩盖了它，
    // 一暂停升回 High 档，左侧就浮出一个小圆圈（用户实报）。
    var seekWaveActive by remember { mutableStateOf(false) }

    /**
     * 双击分区（§4.3）：左右 1/3 = 快退/快进、**中心 = 播放/暂停**。
     * 步长可配（L14，设置页「手势 → 双击动作」，默认 10s）；横滑 seek 与键盘
     * `←/→` 不受它影响——那是 §8.4 钉死的 ±5s / ±30s 语义。
     */
    fun onStageDoubleTap(zone: StageTapZone) {
        when (zone) {
            StageTapZone.Back -> seekByGesture(false, doubleTapStepMs)
            StageTapZone.Forward -> seekByGesture(true, doubleTapStepMs)
            StageTapZone.Center -> togglePlay()
        }
        if (zone != StageTapZone.Center && seekWaveOn) {
            seekWaveFromRight = zone == StageTapZone.Forward
            scope.launch {
                seekWaveActive = true
                seekWave.snapTo(0f)
                seekWave.animateTo(1f, tween(seekWaveMs, easing = NpEasing.Emphasized))
                seekWaveActive = false
            }
        }
    }

    // 横滑 seek（§4.3）：拖动中视频照常播放，中央气泡只呈现"松手会去哪"；
    // 松手才真正 seekTo（与进度条拖动的"关键帧预览"是两条路径，互不混用）
    // 状态本身声明在自动隐藏守护之前（A7#8），这里不再重复声明。
    // 目标位置每帧都在写（§9.2 同源问题）：留句柄给气泡自身读
    val seekGestureTargetMsState = remember { mutableFloatStateOf(0f) }
    var seekGestureTargetMs by seekGestureTargetMsState
    var seekGestureEdgeTicked by remember { mutableStateOf(false) }
    fun onSeekGestureStart() {
        // 与长按 3x 的竞争收口：拖动一开始就恢复原速——"3x 快进 + seek"叠加没有任何合理语义
        endSpeedBoost(withHaptic = false)
        controlsVisible = true
        seekGestureTargetMs = positionState.value.toFloat()
        seekGestureEdgeTicked = false
        seekGestureActive = true
        // M7（§7.5）：横滑 scrub 与进度条拖动是同一套手感——按住 `Long`、松手 `Tick`
        haptics.performHaptic(NpHaptic.Long)
    }
    fun onSeekGestureDelta(deltaMs: Float) {
        // A7#7：夹取上限读回调执行时的时长（组合期 `maxPosition` 可能是旧片长）；
        // 时长未知（<=0）时不设上限，只保证非负。
        val durationNowMs = durationState.value
        val raw = seekGestureTargetMs + deltaMs
        val clamped = clampSeekTargetMs(raw, durationNowMs)
        val withinRange = if (durationNowMs > 0L) raw in 0f..durationNowMs.toFloat() else raw >= 0f
        if (raw != clamped && !seekGestureEdgeTicked) {
            rejectHaptic()
            seekGestureEdgeTicked = true
        } else if (withinRange) {
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
    // `verticalGesture` 声明在自动隐藏守护之前（A7#8），这里不再重复声明。
    var verticalEdgeRejected by remember { mutableStateOf(false) }
    // E144-2：竖滑增量的浮点累加器。旧实现每帧 (current - delta).roundToInt()，
    // 慢滑时单帧增量 <0.5% 被取整抹平、current 又每帧读同一状态，数值完全不动。
    val verticalSwipeAccumulator = remember { VerticalSwipeAccumulator() }
    // 停留计时**不挂组合**：旧写法是 `LaunchedEffect(verticalGesture, verticalGestureTickMs)`，
    // 而那个 tick 每帧都写、又当 key 读——等于把整块舞台拖进每帧重组（§9.2 反例②）。
    //
    // 这里再收一步：上一版把"重启计时"做成每次指针事件 `cancel + launch` 一个协程，
    // 一次竖滑就是 60 次/秒的协程创建，而它做的只是"把到期时刻往后挪"。改成
    // **只写一个时间戳** + 一条自延后的循环：循环醒来时按最新时间戳重算剩余，
    // 到期点仍然精确落在"最后一次触碰 + 900ms"。
    val verticalLastTouchMs = remember { mutableLongStateOf(0L) }
    fun restartVerticalGestureHold() {
        verticalLastTouchMs.longValue = SystemClock.uptimeMillis()
    }
    LaunchedEffect(verticalGesture) {
        if (verticalGesture == null) return@LaunchedEffect
        while (true) {
            val remaining = GESTURE_INDICATOR_HOLD_MS - (SystemClock.uptimeMillis() - verticalLastTouchMs.longValue)
            if (remaining <= 0L) break
            delay(remaining)
        }
        verticalGesture = null
    }
    fun onVerticalGestureStart(mode: StageVerticalMode) {
        endSpeedBoost(withHaptic = false)
        verticalEdgeRejected = false
        // E129（审查 #7）/ A8：竖滑的起点必须读**平台当前**值，而不是进页时那份本地快照。
        // 音量：用户先用实体音量键调过档、再来竖滑时，旧快照会让 HUD 从过期值起算
        // （表现为"一滑就跳一大截"，且指示条与听感对不上）。
        // 亮度：旧实现进页时把"无覆盖"兜底成 62%，竖滑第一帧 HUD 就显示假值。
        // 竖滑**起手这一帧**是唯一正确的重读时机：每帧读会把用户自己滑动造成的回写
        // 读回来，两边互相打架。
        when (mode) {
            StageVerticalMode.Brightness -> windowBrightness.refreshFromPlatform()
            StageVerticalMode.Volume -> {
                val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume
                if (actual != volumePercent) volumePercent = actual
            }
        }
        // E144-2：累加器用**刷新后的平台值**播种——慢滑的单帧小增量要靠它累积，
        // 用旧快照播种会让"起手第一帧"仍按过期值算。
        verticalSwipeAccumulator.start(
            when (mode) {
                StageVerticalMode.Brightness -> brightnessPercent
                StageVerticalMode.Volume -> volumePercent
            },
        )
        verticalGesture = mode
        restartVerticalGestureHold()
    }
    fun onVerticalGestureDelta(mode: StageVerticalMode, deltaPercent: Float) {
        // 上滑 = 增大（市面播放器一致的手感）：pointer 的 y 轴向下为正，这里取负。
        // E144-2：增量交给浮点累加器，慢滑（单帧 0.1%–0.3%）也能累计出可见变化；
        // 取整只发生在回写目标值时。
        val (floor, ceil) = when (mode) {
            StageVerticalMode.Brightness -> 1 to 100
            StageVerticalMode.Volume -> 0 to 100
        }
        val step = verticalSwipeAccumulator.onDelta(deltaPercent, floor, ceil)
        // M10 边界（§7.5 `Reject`）：顶到端点各响一次，滑回区间内重新武装
        if (step.edgeHit) {
            if (!verticalEdgeRejected) {
                verticalEdgeRejected = true
                rejectHaptic()
            }
        } else verticalEdgeRejected = false
        when (mode) {
            StageVerticalMode.Brightness -> setBrightnessPercent(step.percent)
            StageVerticalMode.Volume -> setVolumePercent(step.percent)
        }
        restartVerticalGestureHold()
    }

    /**
     * A7#6：竖滑抬手立即收亮度/音量 HUD，不再等 [GESTURE_INDICATOR_HOLD_MS] 超时。
     *
     * 同时清零"最后触碰时刻"，避免残留时间戳影响下一次自延后循环的剩余量计算。
     */
    fun onVerticalGestureEnd() {
        verticalLastTouchMs.longValue = 0L
        verticalGesture = null
    }

    /**
     * A14：小窗参数的唯一写入点。
     *
     * 每次发布都带当前比例 / 动作 / 源矩形 / 无缝开关；键相同就不打 binder
     * （`PictureInPictureParams` 没有 equals，用比例+播放态+矩形自己拼键）。
     */
    var lastPipParamsKey by remember { mutableStateOf<String?>(null) }
    fun publishPipParams(
        act: ComponentActivity,
        sourceRect: android.graphics.Rect?,
    ): PictureInPictureParams {
        val playing = state == PlaybackState.Playing
        val ratio = pipAspectRatio(videoAspectRatio)
        val key = "$ratio|$playing|${sourceRect ?: "none"}"
        val params = manualPipParams(
            context,
            videoAspectRatio,
            playing,
            player::mediaButtonIntent,
            sourceRect,
        )
        if (key != lastPipParamsKey) {
            lastPipParamsKey = key
            runCatching { act.setPictureInPictureParams(params) }
        }
        return params
    }

    /**
     * A14：源矩形取**画面内容区**，再与窗口安全区/刘海求交。
     *
     * TextureView 在 FIT 下本身已被比例约束，但裁剪/拉伸档会铺满容器；这里按
     * 当前缩放模式算一次内接矩形，避免把黑边收进小窗。旋转 180° 不改宽高比，
     * 这里也不做 90° 式的交换。
     */
    fun contentRectForPip(): android.graphics.Rect? {
        val surface = surfaceViewRef.value ?: return null
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) return null
        val loc = IntArray(2)
        surface.getLocationInWindow(loc)
        val viewW = surface.width
        val viewH = surface.height
        val ratio = videoAspectRatio.takeIf { it.isFinite() && it > 0f } ?: (viewW.toFloat() / viewH)
        val (contentW, contentH) = pipContentSize(
            viewWidth = viewW,
            viewHeight = viewH,
            aspectRatio = ratio,
            fit = scaleMode == VideoScaleMode.FIT,
        )
        if (contentW <= 0 || contentH <= 0) return null
        val left = loc[0] + (viewW - contentW) / 2
        val top = loc[1] + (viewH - contentH) / 2
        val rect = android.graphics.Rect(left, top, left + contentW, top + contentH)
        val root = surface.rootView ?: return rect
        val insets = ViewCompat.getRootWindowInsets(root)
        if (insets != null) {
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val safe = android.graphics.Rect(
                bars.left,
                bars.top,
                root.width - bars.right,
                root.height - bars.bottom,
            )
            if (!rect.intersect(safe)) return null
        }
        return rect.takeIf { !it.isEmpty }
    }

    fun enterPip(): Boolean {
        // §5.1：仅听音频与 PiP 互斥——小窗里没有画面，进入只是个小黑框
        if (session.audioOnly.value) {
            toast.show("仅听音频模式下没有画面可显示")
            return false
        }
        val a = context as? ComponentActivity ?: return false
        // A14：FLAG_SECURE 与部分系统的 PiP 互斥；不要偷偷清用户的隐私开关，
        // 直接说清为什么进不去。
        if (screenshotBlocked) {
            toast.show("已开启禁止截屏，无法进入画中画")
            return false
        }
        // L10①：反馈诚实性——`enterPictureInPictureMode` 失败会返回 false（设备/系统
        // 不允许时也可能抛异常），此前无条件 toast「已进入画中画」，用户看不到小窗却被
        // 告知成功。比例用真实视频宽高比；源矩形取画面内容区（见 contentRectForPip）。
        val params = publishPipParams(a, contentRectForPip())
        val entered = runCatching { a.enterPictureInPictureMode(params) }.getOrDefault(false)
        toast.show(if (entered) "已进入画中画" else "画中画不可用")
        return entered
    }

    /**
     * **收起全部弹层**（审查批次 B1）。
     *
     * 此前"锁定"与"进 PiP"各自手写一份清理清单，且两份都不完整：锁定块漏了
     * 章节面板 / 音轨 / 画面，PiP 块漏了更多操作与文件信息。
     * 漏一项的后果不是"多留一个面板"那么轻——锁定态下播放页的 `BackHandler`
     * 会吞掉返回键（[stageLocked] 那条），残留面板既关不掉、又盖在锁定舞台上，
     * 与"锁定后屏幕上只有解锁钮可点"的承诺相反。
     *
     * 规则：任何"要独占屏幕"的状态切换（锁定 / PiP）都调这里，不各自维护清单；
     * 新增弹层状态时也只在这里加一行。
     *
     * 声明位置必须在调用它的 `LaunchedEffect` **之前**——Kotlin 局部函数不支持
     * 前向引用，放到文件后面会直接编译不过。
     */
    fun dismissAllOverlays() {
        fsMenuPage = null
        speedPickerVisible = false
        episodePanelVisible = false
        chaptersPanelVisible = false
        subtitleSheetVisible = false
        audioTrackSheetVisible = false
        pictureSheetVisible = false
        moreSheetVisible = false
        fileInfoVisible = false
    }

    // 锁定语义：屏蔽**全部**舞台手势、返回键与键盘，舞台上仅解锁钮可点——
    // 口袋/带娃场景防误触。
    // 锁定瞬间让解锁钮**先亮一下**（controlsVisible=true 只是它的载体：锁定态下
    // 顶/底控制层都被 !stageLocked 挡住，屏幕上只多出解锁钮）——随后由自动隐藏
    // 守护按同一节拍收起；之后单指点按即可再唤出（见 [stageGestures] 的 onTap）。
    LaunchedEffect(stageLocked) {
        if (stageLocked) {
            controlsVisible = true
            // 审查批次 B1：统一收口。旧清单漏了章节 / 音轨 / 画面三层，
            // 而锁定态的 BackHandler 会吞返回键 → 残留面板既盖在锁定舞台上又关不掉。
            dismissAllOverlays()
        }
    }

    // A7#2/#3：舞台死区宽度 = 「系统手势 inset」与固定下限的较大者。
    // 左右 max(24dp, systemGestures 左右 inset)；顶部 48dp；底部 max(48dp, systemGestures bottom inset)。
    // 用 systemGestures 而不是 safeDrawing：后者含状态栏/导航条常态高度，会把可操作区域吃掉
    // 太多；真正会"抢走边缘滑动"的是系统手势区本身。
    val systemGestureInsets = WindowInsets.systemGestures
    val gestureDensity = LocalDensity.current
    val gestureLayoutDirection = LocalLayoutDirection.current
    val stageGestureZones = remember(systemGestureInsets, gestureDensity, gestureLayoutDirection) {
        fun insetDp(px: Int): Float = with(gestureDensity) { px.toDp().value }
        StageGestureZones(
            leftDp = stageDpAtLeast(
                insetDp(systemGestureInsets.getLeft(gestureDensity, gestureLayoutDirection)),
                STAGE_EDGE_MIN_DP,
            ),
            rightDp = stageDpAtLeast(
                insetDp(systemGestureInsets.getRight(gestureDensity, gestureLayoutDirection)),
                STAGE_EDGE_MIN_DP,
            ),
            topDp = STAGE_TOP_DEAD_ZONE_DP,
            bottomDp = stageDpAtLeast(
                insetDp(systemGestureInsets.getBottom(gestureDensity)),
                STAGE_BOTTOM_DEAD_ZONE_DP,
            ),
        )
    }

    /**
     * 两种形态的舞台手势接线（§4.3）——收敛成一处，避免双份漂移。
     * 锁定态（[stageLocked]）只停"动作"（双击/长按/拖动），**点按例外**：它是解锁钮的
     * 唤出入口（锁定后屏幕只剩解锁钮，不许点按就等于没有出口）。
     */
    @Composable
    fun stageGestures(): Modifier =
        Modifier.npStageGestures(
            gesturesEnabled = gesturesEnabled,
            stageLocked = stageLocked,
            deadZones = stageGestureZones,
            onTap = { controlsVisible = !controlsVisible },
            onDoubleTap = ::onStageDoubleTap,
            onLongPressStart = {
                boostPreviousSpeed = speed
                player.setSpeed(boostSpeed)
                boostHintVisible = true
                // M9（§7.5 `Long`）：生效与松开各一次——按住那一下只说明"要提速"，
                // 松手恢复原速同样要回执，否则用户不知道 3x 有没有退掉。
                haptics.performHaptic(NpHaptic.Long)
            },
            onLongPressEnd = { endSpeedBoost(withHaptic = true) },
            onSeekStart = ::onSeekGestureStart,
            onSeekDelta = ::onSeekGestureDelta,
            onSeekEnd = ::onSeekGestureEnd,
            onVerticalStart = ::onVerticalGestureStart,
            onVerticalDelta = ::onVerticalGestureDelta,
            onVerticalEnd = ::onVerticalGestureEnd,
        )

    // ================= 批次 3：轨道 / 章节 / 画面 / 偏移 / PiP / 睡眠 / 截图 =================

    // 内嵌轨道与章节（引擎经 onTracksChanged/onMetadata 推送；面板据此渲染）
    val tracks by player.tracks.collectAsStateWithLifecycle()
    val chapters by player.chapters.collectAsStateWithLifecycle()
    val subtitleEnabled by settings.subtitleEnabled.collectAsStateWithLifecycle(initialValue = true)
    // 字幕三态文案：外挂优先，其次内嵌轨被选中（CC 钮点亮与面板取值同一真相）
    val subtitleText = when {
        !subtitleEnabled -> "关闭"
        subtitleLoaded -> "已挂载"
        tracks.any { it.type == NpTrackType.TEXT && it.selected } -> "内嵌"
        else -> "关闭"
    }
    val subtitleOn = subtitleText != "关闭"

    // 画面显示记忆（§2.4 比例 / 镜像 / 旋转，per-media 落盘）
    LaunchedEffect(mediaKey) {
        val (mode, mirror, rot) = parseDisplaySettings(settings.displaySettings(mediaKey).first())
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
        scope.launch { settings.setDisplaySettings(mediaKey, serializeDisplaySettings(mode, mirror, rot)) }
    }
    // 裁剪是引擎侧缩放模式（np.crop 命令）；比例走布局，镜像/旋转走画面 View 变换
    LaunchedEffect(scaleMode) { player.setVideoCrop(scaleMode == VideoScaleMode.CROP) }

    // ---- 字幕样式（E131：对齐参考示例的「字幕设置」面板）----
    // 全局偏好（跨媒体记忆），渲染层只读它；面板改动即落盘并立刻反映到画面。
    // 偏移仍是会话级（不跨进程记忆），与既有 E43 口径一致。
    val subtitleOffsetMs by session.subtitleOffsetMs.collectAsStateWithLifecycle()

    // 字幕偏移（E43）改由**绝对档位**设置（−1.0s / −0.1s / 0 / +0.1s / +1.0s）：
    // 竖屏「字幕设置」面板与全屏下拉菜单共用这一条出口，两处语义一致。
    // 旧实现是 ±50/±250ms 累加——档位语义下"点两次 −1.0s"会让人算不清当前值。
    fun setSubtitleOffset(targetMs: Long) {
        session.setSubtitleOffsetMs(targetMs)
    }

    val subtitleColorArgb by settings.subtitleColorArgb
        .collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_COLOR_ARGB)
    val subtitleScale by settings.subtitleScale.collectAsStateWithLifecycle(initialValue = 1f)
    val subtitleItalic by settings.subtitleItalic.collectAsStateWithLifecycle(initialValue = false)
    val subtitleVertical by settings.subtitleVerticalPosition
        .collectAsStateWithLifecycle(initialValue = SUBTITLE_VERTICAL_MAX)
    val subtitleBackground by settings.subtitleBackground
        .collectAsStateWithLifecycle(initialValue = false)
    val subtitleOutline by settings.subtitleOutline.collectAsStateWithLifecycle(initialValue = false)
    val subtitleMaxLines by settings.subtitleMaxLines
        .collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_MAX_LINES)
    val subtitleStyle = SubtitleStyle(
        enabled = subtitleEnabled,
        colorArgb = subtitleColorArgb,
        scale = subtitleScale,
        italic = subtitleItalic,
        verticalPosition = subtitleVertical,
        background = subtitleBackground,
        outline = subtitleOutline,
        maxLines = subtitleMaxLines,
    )
    LaunchedEffect(player, subtitleEnabled) { player.setTextTracksEnabled(subtitleEnabled) }

    // 睡眠定时：绝对到期时间（epoch ms），0 = 关。到期暂停播放。
    val sleepTimerEndsAtMs by session.sleepTimerEndsAtMs.collectAsStateWithLifecycle()
    val sleepLabel = if (sleepTimerEndsAtMs > 0) {
        "睡眠 ${((sleepTimerEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1)}分"
    } else {
        "睡眠定时"
    }

    /** 睡眠定时两态开关（30 分钟档）：档位扩展留给设置页 */
    fun toggleSleepTimer() {
        session.toggleSleepTimer()
        toast.show(if (session.sleepTimerEndsAtMs.value > 0) "将在 30 分钟后暂停" else "睡眠定时已取消")
    }

    // 截图（TextureView 取当前帧，落 Pictures/None Player）
    fun screenshot() {
        savePlaybackScreenshot(context, surfaceViewRef.value, scope, screenshotBlocked, toast::show)
    }

    // PiP is a manual playback action, never a side effect of background playback or Home.
    DisposableEffect(activity) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            inPipMode = info.isInPictureInPictureMode
        }
        act.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            act.removeOnPictureInPictureModeChangedListener(listener)
            // Parameters belong to the Activity, which outlives this playback screen.
            // A14：离开播放页做一次显式 disarm；播放页存活期间不再有别的写入点
            // （MainActivity 的 onResume/onUserLeaveHint 已不再重发空参数）。
            disarmPipParams(act)
        }
    }
    // A14：小窗期间冻结方向锁；退出后恢复进入前的用户意图，并把控制层拉回来。
    // 旧 effect 只有进入分支——点系统「展开」后 controlsVisible 永远停在 false，
    // 自动隐藏只能 true→false，没有路径把它拉回 true。
    var orientationLockBeforePip by remember { mutableStateOf<OrientationLock?>(null) }
    var wasInPip by remember { mutableStateOf(inPipMode) }
    LaunchedEffect(inPipMode) {
        if (inPipMode) {
            wasInPip = true
            orientationLockBeforePip = orientationLock
            // 小窗里不让方向请求和比例请求打架；冻结的是"用户意图"，不是当前窗口方向。
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controlsVisible = false
            // 审查批次 B1：改走统一收口——旧清单漏了更多操作与文件信息卡，
            // 小窗里它们会盖住本就只有巴掌大的画面
            dismissAllOverlays()
        } else if (wasInPip) {
            wasInPip = false
            controlsVisible = true
            orientationLockBeforePip?.let {
                orientationLock = it
                orientationLockBeforePip = null
            }
            // A14：从右栏菜单进小窗时，退出小窗回到三栏浏览态，而不是停在整屏播放页。
            if (manualPipEntry && isDesktopLike && !session.audioOnly.value) {
                returnToBrowsePane()
            }
        }
    }
    // Keep aspect and transport actions current only after the user is actually in PiP.
    // Publishing an aspect ratio while merely watching arms OEM "小窗 on Home" (MIUI/HyperOS),
    // and that Activity is the whole app — library and settings would enter PiP too.
    // A14：标志不再 rememberSaveable——旋转/路由复用后第二次点会完全无反应；
    // 只有真正进入成功才置位，失败清掉（toast 保持）。
    var pipEntryConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(manualPipEntry, mediaOpened, videoAspectRatio) {
        if (manualPipEntry && mediaOpened && videoAspectRatio > 0f && !pipEntryConsumed) {
            // Only an explicit inline menu action sets this flag; Home never does.
            // A14：比例还是 0 时先等真实值，别按 16:9 先成形再硬跳。
            withFrameMillis { }
            pipEntryConsumed = enterPip()
        }
    }
    // A14：小窗期间的参数更新走同一个写入点（publishPipParams），带上源矩形；
    // 旧实现这里重发时 sourceRect 默认 null，把进入时的 hint 清掉了。
    LaunchedEffect(videoAspectRatio, state, inPipMode, scaleMode) {
        if (!inPipMode) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        publishPipParams(act, contentRectForPip())
    }

    // ---- 转屏收口（2026-10-14 真机实报）----
    // Activity 声明了 configChanges，转屏不重建本页：弹层状态与"画面归谁"的判断都
    // 跨形态活下来，下面两类异常同出于此。
    var wasPortrait by remember { mutableStateOf(windowIsPortrait) }
    // ① 弹层：横屏里打开的「详情」卡 / 选集 / 选项表，转竖屏后会原样盖在新版式上，
    //    而竖屏直接进页时它们全是关着的（用户实报"视频详情外显"）。弹层本就锚在
    //    随版式搬走的按钮上，转屏即收起——与锁定、进 PiP 两处同一口径。
    LaunchedEffect(windowIsPortrait) {
        if (wasPortrait != windowIsPortrait) {
            wasPortrait = windowIsPortrait
            dismissAllOverlays()
        }
    }
    var wasDesktopLike by remember { mutableStateOf(isDesktopLike) }
    // ② 画面归属：类 PC 档的画面住在右栏，本页只是它的整屏分身。竖屏期间进的本页，
    //    窗口转横（进入类 PC 档）后仍占着整屏，读起来就是"转横屏直接全屏视频"，
    //    而这一档本该看到"详情 + 右栏"的横屏视频界面——把画面交还右栏、退掉本页。
    //    只认**边沿**：从右栏「⛶」进本页时窗口本就是类 PC，那次不该被弹回去。
    //    仅听音频不交接（整屏音频页是用户主动打开的）；装载未完成时不交接——
    //    `startPlayback` 挂在本页组合作用域上，此刻退页会把它一起取消。
    LaunchedEffect(isDesktopLike) {
        val entered = isDesktopLike && !wasDesktopLike
        wasDesktopLike = isDesktopLike
        // `currentItem != null` 与右栏的 `isOccupied()` 同一条判据：会话没有当前项
        // （装载失败、已被清空）时右栏根本不会出现，那时退本页就等于把人丢回列表——
        // 真机拿一条本机解不了的样本复现过（画面停在错误面板，转横屏直接回到首页）。
        if (!entered || !mediaOpened || currentItem == null || inPipMode || audioOnly) return@LaunchedEffect
        // 外壳（转屏回调）已经把这轮交接连退页一起发起了：这里再走一遍会多退一页，
        // 把媒体库首页也弹掉。兜底只在外壳没接手时生效。
        if (NpFormHandoff.tookOverToPane()) return@LaunchedEffect
        // 右栏刚把画面交过来的那几百毫秒里，窗口尺寸可能回跳一帧。那一帧不能 pop，
        // 否则竖屏播放页会立刻被拆掉，用户看见的是首页。
        if (NpFormHandoff.holdsPlayer()) return@LaunchedEffect
        // 这一下横向是用户点「全屏」要来的（平板竖屏点全屏）：留在本页铺满，
        // 不退页、也不把画面交还右栏。
        if (orientationLock == OrientationLock.Landscape) return@LaunchedEffect
        // 类 PC 档的视频界面是"左栏剧集页 + 右栏画面"。竖屏点卡片进来时媒体库并没有
        // 详情页状态（那一档压根不进详情页），交还前请它把左栏落到正在播的这条上。
        currentItem?.let { NpDetailRequest.request(it) }
        // 这是形态换了画面归属者，不是用户翻页：整页淡入淡出/位移必须免掉
        NpFormHandoff.mark()
        returnToBrowsePane()
    }

    // Preferences are restored once by the session, never collected back into live UI intent.
    fun toggleAudioOnlyWithMemory() {
        // 记忆由会话写（唯一写路径，迷你条与页内两处共用同一条），页面只给反馈
        session.toggleAudioOnly()
        toast.show(if (session.audioOnly.value) "已进入仅听音频模式" else "已切回视频播放")
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
                // 审查批次 D1：会话级按键**处理成功后一律唤回控制层**。
                // 旧写法四个分支各自 `return true`，都不动 controlsVisible：在触屏/无指针
                // 窗口里控制层已被 3.5s 自动隐藏，此时按空格暂停或方向键 seek，
                // 画面上没有任何持久可见的反馈（只有一闪而过的 gestureHint），
                // 用户不知道刚才那下生效了没有。F / L / Esc 本来就是这么做的，这里对齐。
                val sessionKeyHandled = when (
                    val command = routeSessionKey(event.nativeKeyEvent.keyCode, event.isShiftPressed)
                ) {
                    SessionKeyCommand.PlayPause -> {
                        togglePlay()
                        true
                    }
                    is SessionKeyCommand.Seek -> {
                        seekByGesture(
                            forward = command.deltaMs >= 0,
                            stepMs = if (command.deltaMs < 0) -command.deltaMs else command.deltaMs,
                        )
                        true
                    }
                    is SessionKeyCommand.Volume -> {
                        // 音量走系统流，与右侧竖滑手势同一个 setVolumePercent（不另起一路）
                        setVolumePercent(volumePercent + command.deltaPercent)
                        true
                    }
                    SessionKeyCommand.Mute -> {
                        toggleMute()
                        true
                    }
                    null -> false
                }
                if (sessionKeyHandled) {
                    controlsVisible = true
                    return@onPreviewKeyEvent true
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
                            audioOnly -> navigator.popBackStack()
                            else -> exitFullscreenOrLeave()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (!mediaOpened && rejectedUriError == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        // A14：小窗优先于横/竖版式。9:16 的 PiP 窗口是"竖向"，旧代码因此落进竖屏版式，
        // 画面只占 55% 高、上下是主题色；这里直接给一层黑底 + 铺满的舞台，
        // 不挂手势、字幕、弹幕、控制层与结束层。
        if (inPipMode) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoStage(
                    player = player,
                    videoAspectRatio = videoAspectRatio,
                    overlays = emptyList(),
                    overlayMedia = overlayMedia,
                    navigator = navigator,
                    state = state,
                    userDragging = false,
                    controlsVisible = false,
                    subtitleStyle = subtitleStyle,
                    inPip = true,
                    scaleMode = scaleMode,
                    mirrored = mirrored,
                    rotation = rotation,
                    onSurfaceView = { surfaceViewRef.value = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return@Box
        }
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
                // 返回上一页。切回全屏视频是「切回视频」的事，返回键不该做这个。
                onBack = navigator::popBackStack,
                onReturnToVideo = if (currentItem?.kind == MediaKind.AUDIO) null else {
                    { session.setAudioOnly(false) }
                },
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
                        .onGloballyPositioned { stageRectInWindow = it.boundsInWindow() }
                        .then(stageGestures()),
                ) {
                    VideoStage(
                        player = player,
                        videoAspectRatio = videoAspectRatio,
                        overlays = overlays,
                        overlayMedia = overlayMedia,
                        navigator = navigator,
                        state = state,
                        userDragging = userDragging,
                        controlsVisible = controlsVisible,
                        subtitleStyle = subtitleStyle,
                        scaleMode = scaleMode,
                        mirrored = mirrored,
                        rotation = rotation,
                        onSurfaceView = { surfaceViewRef.value = it },
                        // 画面**本体**参与整屏 morph：转屏交接的起飞与落点都是它。
                        // 它属于哪个形态由 `isDesktopLike` 说了算——过渡那几帧里
                        // 旧树量出来的矩形不是落点，登记时就要认得出。
                        morphKey = NpMorphKey.Video,
                        morphDesktop = isDesktopLike,
                        morphRotate = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // M2 共享封面：从卡片机位补间到舞台（挂在舞台内，坐标相对舞台）
                    CoverFlightOverlay(
                        takeoff = coverTakeoff,
                        model = artworkUri,
                        stageRect = stageRectInWindow,
                        wave = coverFlight,
                    )
                    // 暂停时的中央"继续"：64dp 玻璃圆（设计稿 .bigplay），
                    // 播放中不出现——它只在"用户可能需要我"的时刻存在。
                    // 锁定态不出现：那是舞台上的第二个可点元素，破坏"只有解锁钮可点"。
                    CenterResumeButton(
                        // 未播放态（横屏详情页 `play=0` 转竖屏的落点）读的就是 `Paused`：
                        // 引擎不再对外发 `Ready`（A5），"装载完没起播"是停着的就绪态。
                        visible = state == PlaybackState.Paused &&
                            !userDragging && !inPipMode && !stageLocked,
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
                    // M8 半屏水波：从双击那一侧扩散、500ms 淡出（放在胶囊之前 = 压在胶囊下面）。
                    // 只在**动画进行中**挂载：档位开着但没在播水波时，0f 静息帧会画出一个
                    // alpha 0.34/scale 0.35 的空环（暂停升回 High 档即浮现在左侧，用户实报）。
                    if (seekWaveOn && seekWaveActive) {
                        Box(
                            modifier = Modifier
                                .align(
                                    if (seekWaveFromRight) Alignment.CenterEnd
                                    else Alignment.CenterStart,
                                )
                                .fillMaxHeight(0.5f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    val p = seekWave.value
                                    val scale = 0.35f + 0.65f * p
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = (0.34f * (1f - p)).coerceAtLeast(0f)
                                }
                                .border(2.dp, Color.White, CircleShape),
                        )
                    }
                    GestureHintPill(
                        text = gestureHint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 横滑 seek 气泡（§4.3）：拖动中视频照常播放，这里只呈现"松手会去哪"
                    if (seekGestureActive) {
                        SeekGestureBubbleHost(positionState, seekGestureTargetMsState, durationMs)
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
                        visible = controlsVisible && !inPipMode,
                        locked = stageLocked,
                        onUnlock = {
                            stageLocked = !stageLocked
                            controlsVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.End,
                                ),
                            )
                            .padding(16.dp),
                    )
                    // 假全屏删除后横向全屏只剩影院一种，控制层恒为"上+下"两条，
                    // 旧"底部集中"版式（类 PC 窗口化横屏专用）连同其边栏动作一并移除。
                    // ⋯（播放选项下拉）/ 选集。顶栏与底栏共用这一份实现：同材质、
                    // 同一条 scrim、同一套锚点回报，两处不会各写一份而漂移。
                    @Composable fun BarOptionsButton() {
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
                    }
                    @Composable fun EpisodeButton() {
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
                        // 右上：⋯（播放选项下拉）/ 选集。**图标直接摆在顶栏里**，和底栏同一
                        // 材质、同一条 scrim、随控制层一起显隐。倍速不在这里出现：
                        // 底栏已有倍速钮，右上角再放一份是重复。
                        showActions = true,
                        trailing = {
                            EpisodeButton()
                            BarOptionsButton()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    PlayerBottomControls(
                        visible = controlsVisible && !inPipMode && !stageLocked,
                        applyInsets = true,
                        showTransport = true,
                        leadingActions = null,
                        positionMs = positionState,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedState,
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
                        onScrubCancel = ::onScrubCancel,
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
                        // 横向全屏是"单一全屏形态"，竖屏那条快捷排不在这里——
                        // 入口必须在这一排里给一个，否则横过来就切不到只听模式
                        onToggleAudioOnly = { toggleAudioOnlyWithMemory() },
                        // 全屏形态恒处于全屏，但**出口不是同一个**（E129 / 审查 #10）：
                        // 手机横窗是退出沉浸、留在播放页；类 PC 横窗走 popBackStack 回浏览。
                        // 两种结果给两枚不同的形状与名称，一个展开折角不再暗示两件事。
                        fullscreenActive = true,
                        // 用户裁定（2026-10-12）：退出全屏不再画 ✕/折角，**恢复为全屏图标**，
                        // 用激活色表达"当前正处全屏"——形状恒定（展开框=全屏），状态靠颜色读，
                        // 图标承诺与实际动作不再互相打架。
                        fullscreenIcon = NpIcon.Expand,
                        fullscreenLabel = if (isDesktopLike) "返回浏览" else "退出全屏",
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
                            FsDropdown(
                                anchor = anchor,
                                onDismiss = ::closeFsMenu,
                                page = page,
                            ) { targetPage, dismiss ->
                                // 根页（直接从底栏/顶栏按钮打开的那页）的"返回"就是收起，
                                // 要走带动画的 dismiss；子页返回上一级是换页，不收菜单。
                                val back: () -> Unit = { if (targetPage == fsMenuRoot) dismiss() else leaveFsMenuPage() }
                                when (targetPage) {
                                    FsMenuPage.MAIN -> FsMainMenuContent(
                                        loopMode = loopMode,
                                        brightnessPercent = brightnessState,
                                        volumePercent = volumeState,
                                        favorited = favorited,
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
                                        contributors = menuContributors,
                                        contributorMedia = overlayMedia,
                                        contributorPlayer = player,
                                    )

                                    FsMenuPage.SPEED -> FsSpeedMenuContent(
                                        speed = speed,
                                        onPick = {
                                            dismiss()
                                            pickSpeed(it)
                                        },
                                        onNudge = ::nudgeSpeed,
                                        onBack = back,
                                    )

                                    FsMenuPage.SUBTITLE -> FsSubtitleMenuContent(
                                        subtitleOn = subtitleEnabled,
                                        subtitleLoaded = subtitleLoaded,
                                        savedSubtitleName = savedSubtitle
                                            ?.substringAfterLast('/')
                                            ?.substringBeforeLast('.'),
                                        textTracks = tracks.filter { it.type == NpTrackType.TEXT },
                                        offsetMs = subtitleOffsetMs,
                                        colorArgb = subtitleColorArgb,
                                        onColor = { scope.launch { settings.setSubtitleColorArgb(it) } },
                                        scale = subtitleScale,
                                        onScale = { scope.launch { settings.setSubtitleScale(it) } },
                                        italic = subtitleItalic,
                                        onItalic = { scope.launch { settings.setSubtitleItalic(it) } },
                                        verticalPosition = subtitleVertical,
                                        onVerticalPosition = {
                                            scope.launch { settings.setSubtitleVerticalPosition(it) }
                                        },
                                        background = subtitleBackground,
                                        onBackground = {
                                            scope.launch { settings.setSubtitleBackground(it) }
                                        },
                                        outline = subtitleOutline,
                                        onOutline = {
                                            scope.launch { settings.setSubtitleOutline(it) }
                                        },
                                        maxLines = subtitleMaxLines,
                                        onMaxLines = {
                                            scope.launch { settings.setSubtitleMaxLines(it) }
                                        },
                                        // 开关（2026-10-12）：与竖屏「字幕设置」面板同一语义——
                                        // 关 = 渲染总开关 + 引擎卸载；开 = 恢复渲染 + 重挂记忆字幕
                                        onSetEnabled = { on ->
                                            scope.launch { settings.setSubtitleEnabled(on) }
                                            player.setTextTracksEnabled(on)
                                        },
                                        onPickTrack = { track ->
                                            scope.launch { settings.setSubtitleEnabled(true) }
                                            player.selectTrack(NpTrackType.TEXT, track.id)
                                            dismiss()
                                        },
                                        onMountExternal = {
                                            dismiss()
                                            subtitlePicker.launch(arrayOf("*/*"))
                                        },
                                        onOffset = ::setSubtitleOffset,
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
                            // 常驻栏的返回与标题和右栏栏头同名：转屏时它们各自飞到位
                            morphDesktop = isDesktopLike,
                        )
                    }
                    // 转屏 morph 期间这一格要被"拿出来"：抬到信息区之上（zIndex），
                    // 否则画面从上一形态飞过来时会被下方的信息区盖住——用户实报的
                    // "看起来是在窗口里旋转，而不是窗口浮在层上移动"。
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (NpMorph.isFlying) Modifier.zIndex(2f) else Modifier),
                    ) {
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
                                // 控制条从下缘滑入滑出。不裁切的话，半透明黑条会盖到视频下面的白底。
                                // **morph 期间必须放开**：这一层就是"框"，裁着它，飞行中的画面
                                // 只能在框里转，读不出"窗口浮在页面上移动"。
                                .then(if (NpMorph.isFlying) Modifier else Modifier.clipToBounds())
                                .onGloballyPositioned { stageRectInWindow = it.boundsInWindow() }
                                .then(stageGestures()),
                        ) {
                            VideoStage(
                                player = player,
                                videoAspectRatio = videoAspectRatio,
                                overlays = overlays,
                                overlayMedia = overlayMedia,
                                navigator = navigator,
                                state = state,
                                userDragging = userDragging,
                                controlsVisible = controlsVisible,
                                subtitleStyle = subtitleStyle,
                                scaleMode = scaleMode,
                                mirrored = mirrored,
                                rotation = rotation,
                                onSurfaceView = { surfaceViewRef.value = it },
                                // 同横屏形态：竖屏舞台也是同一个部件（横转竖时的落点）
                                morphKey = NpMorphKey.Video,
                                morphDesktop = isDesktopLike,
                                morphRotate = true,
                                modifier = Modifier.fillMaxSize(),
                            )
                            // M2 共享封面：从卡片机位补间到舞台（挂在舞台内，坐标相对舞台）
                            CoverFlightOverlay(
                                takeoff = coverTakeoff,
                                model = artworkUri,
                                stageRect = stageRectInWindow,
                                wave = coverFlight,
                            )
                            // 竖屏不放 64dp 中央暂停钮：它会挡住画面。播放/暂停在底栏左侧的小按钮。
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
                                SeekGestureBubbleHost(positionState, seekGestureTargetMsState, durationMs)
                            }
                            verticalGesture?.let { mode ->
                                VerticalAdjustHud(mode, brightnessState, volumeState, 20.dp)
                            }
                            // 锁定徽标：随控制层显隐（与全屏形态同一语义）；
                            // 竖屏里视频区之外的信息区不受锁定影响，出口始终不止一个
                            StageLockBadgeHost(
                                visible = controlsVisible && !inPipMode,
                                locked = stageLocked,
                                onUnlock = {
                                    stageLocked = !stageLocked
                                    controlsVisible = true
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(12.dp),
                            )
                            // 舞台在 Column 里：不写全名会解析到 ColumnScope 重载，编译失败。
                            androidx.compose.animation.AnimatedVisibility(
                                visible = controlsVisible && !inPipMode && !stageLocked,
                                enter = fadeIn(npMotion(Motion.Field)),
                                exit = fadeOut(npMotion(Motion.Field)),
                                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(start = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = headerTitle,
                                        style = NpTypography.bodyMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    BarCtlButton(
                                        icon = NpIcon.Dots,
                                        contentDescription = "播放选项",
                                        onClick = { moreSheetVisible = true },
                                    )
                                }
                            }
                            // 视频区底部：进度 + 时间 + 全屏（§4.1 的版式）；
                            // 顶栏已上移为常驻 playerbar，舞台内不再放返回键。
                            // 紧凑单行 + 全宽横条为全形态统一形态（2026-10-12 用户裁定，
                            // 见 [PlayerBottomControls]）。
                            PlayerBottomControls(
                                visible = controlsVisible && !inPipMode && !stageLocked,
                                applyInsets = false,
                                showTransport = false,
                                // 手机竖屏一条到底，不要再叠一行进度把画面盖住。
                                preferSingleRow = true,
                                seekTouchBand = 18.dp,
                                leadingActions = {
                                    IconButton(
                                        onClick = ::togglePlay,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        if (state == PlaybackState.Playing) {
                                            PauseGlyph(tint = Color.White, barWidth = 3.dp, barHeight = 14.dp)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "播放",
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                    }
                                },
                                positionMs = positionState,
                                durationMs = durationMs,
                                bufferedPositionMs = bufferedState,
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
                                onScrubCancel = ::onScrubCancel,
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
                        // HDR 标识（HDR10/HLG）：来自起播探测的容器声明；SDR/未探测为 null。
                        val hdrLabel by player.hdrLabel.collectAsStateWithLifecycle()
                        val meta = buildList {
                            current?.sizeBytes?.let { bytes ->
                                if (bytes > 0) add(formatFileSize(bytes))
                            }
                            current?.resolutionLabel
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { add(it) }
                            hdrLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
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
                        val actionScroll = rememberScrollState()
                        val largeActions = LocalDensity.current.fontScale > 1.3f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (largeActions) Modifier.horizontalScroll(actionScroll) else Modifier)
                                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val actionWidth = if (largeActions) Modifier else Modifier.weight(1f)
                            // 三个动作与横屏左栏那一横排同名（NpMorphKey.Fav/Info/Audio）：
                            // 转屏时它们不重画样式，只是各自飞到位（E133，用户明确要求）。
                            PlayerActionButton(
                                glyph = ActionGlyph.Fav,
                                label = if (favorited) "已收藏" else "收藏",
                                active = favorited,
                                onClick = { toggleFavorite() },
                                modifier = actionWidth.npMorphPart(NpMorphKey.Fav, desktop = isDesktopLike),
                            )
                            PlayerActionButton(
                                glyph = ActionGlyph.Info,
                                label = "详情",
                                onClick = { fileInfoVisible = true },
                                modifier = actionWidth.npMorphPart(NpMorphKey.Info, desktop = isDesktopLike),
                            )
                            PlayerActionButton(
                                glyph = ActionGlyph.Audio,
                                label = "仅听音频",
                                onClick = { toggleAudioOnlyWithMemory() },
                                modifier = actionWidth.npMorphPart(NpMorphKey.Audio, desktop = isDesktopLike),
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
                                // 设计稿 .epi-list：max-height 264 内部滚动（集数多时不再拉长信息区）。
                                // A6：内层改 LazyColumn——几十上百集时只组合视口内的行；
                                // 高度有上限，同轴嵌套滚动到边界会把手势交还外层信息区。
                                LazyColumn(
                                    modifier = Modifier
                                        .heightIn(max = 264.dp)
                                        .padding(bottom = NpSpace.sm),
                                ) {
                                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                                        EpisodeRow(
                                            index = index,
                                            title = item.title.ifBlank { item.id },
                                            durationMs = queueDuration(item),
                                            selected = index == queueIndex,
                                            onClick = { session.selectQueueIndex(index) },
                                            // 与横屏左栏的同名行是同一个部件：转屏时它自己飞到位
                                            morphKey = NpMorphKey.episode(index),
                                            morphDesktop = isDesktopLike,
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
        // 审查批次 C3：选集面板走统一进出场（旧实现条件挂载，硬切）
        PanelExitHost(
            visible = episodePanelVisible,
            onRemoved = { episodePanelVisible = false },
        ) { visible ->
            BackHandler { episodePanelVisible = false }
            EpisodePanel(
                visible = visible,
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
        // 审查批次 C3：与选集同一套进出场
        PanelExitHost(
            visible = chaptersPanelVisible && chapters.isNotEmpty(),
            onRemoved = { chaptersPanelVisible = false },
        ) { visible ->
            BackHandler { chaptersPanelVisible = false }
            ChaptersPanel(
                visible = visible,
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

        // ---- 底部弹层：字幕设置（E131，版式对齐用户给的参考示例）----
        // 倍速不在这里：它改成贴在倍速钮上方的选择条（SpeedChoiceRow）。
        if (subtitleSheetVisible) {
            NpSheet(
                onDismiss = { subtitleSheetVisible = false },
                title = "字幕设置",
            ) {
                val textTracks = tracks.filter { it.type == NpTrackType.TEXT }
                SubtitleSettingsPanel(
                    enabled = subtitleEnabled,
                    onEnabled = { on ->
                        scope.launch { settings.setSubtitleEnabled(on) }
                        player.setTextTracksEnabled(on)
                    },
                    colorArgb = subtitleColorArgb,
                    onColor = { scope.launch { settings.setSubtitleColorArgb(it) } },
                    scale = subtitleScale,
                    onScale = { scope.launch { settings.setSubtitleScale(it) } },
                    italic = subtitleItalic,
                    onItalic = { scope.launch { settings.setSubtitleItalic(it) } },
                    verticalPosition = subtitleVertical,
                    onVerticalPosition = { scope.launch { settings.setSubtitleVerticalPosition(it) } },
                    background = subtitleBackground,
                    onBackground = { scope.launch { settings.setSubtitleBackground(it) } },
                    outline = subtitleOutline,
                    onOutline = { scope.launch { settings.setSubtitleOutline(it) } },
                    maxLines = subtitleMaxLines,
                    onMaxLines = { scope.launch { settings.setSubtitleMaxLines(it) } },
                    offsetMs = subtitleOffsetMs,
                    onOffset = ::setSubtitleOffset,
                    statusText = subtitleStatusText(
                        enabled = subtitleEnabled,
                        subtitleLoaded = subtitleLoaded,
                        savedSubtitle = savedSubtitle,
                        embeddedLabel = textTracks.firstOrNull { it.selected }?.label,
                    ),
                    embeddedTracks = textTracks,
                    onPickEmbedded = { track ->
                        scope.launch { settings.setSubtitleEnabled(true) }
                        player.selectTrack(NpTrackType.TEXT, track.id)
                        subtitleSheetVisible = false
                    },
                    onPickExternal = {
                        subtitleSheetVisible = false
                        subtitlePicker.launch(arrayOf("*/*"))
                    },
                    onEmbeddedUnavailable = { toast.show("此视频没有内嵌字幕轨") },
                )
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
                    add(
                        QuickTileSpec(NpIcon.Info, "文件信息") {
                            moreSheetVisible = false
                            fileInfoVisible = true
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
                    NpLoopModeButtons.forEach { (mode, icon) ->
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
                // 面板变体（dark=false）：sheet 是浅色 surface，滑杆行取主题字色与 `ink.border`
                // 未填轨道；全屏 ⋯ 菜单那一套永远压在画面上，取白。两处共用同一实现，
                // 轨道粗细与拇指尺寸不再一份 3dp、一份 16dp。
                FsMenuSlider(
                    icon = NpIcon.Sun,
                    label = "亮度",
                    value = brightnessState,
                    onValueChange = ::setBrightnessPercent,
                    dark = false,
                )
                FsMenuSlider(
                    icon = NpIcon.Sound,
                    label = "音量",
                    value = volumeState,
                    onValueChange = ::setVolumePercent,
                    dark = false,
                )
                // 设计稿 .check-row：「允许屏幕截图」开关（关 = FLAG_SECURE 禁止截屏/录屏）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(NpRadius.tile))
                        .clickable { setScreenshotBlocked(!screenshotBlocked) }
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
                    // L17 → A1-6 收口：值已改持久（`AppSettings.screenshotBlocked`），
                    // 生效范围仍是播放页窗口（FLAG_SECURE 挂在本 activity 的窗口上）。
                    // 副题因此从"仅本次播放有效"改成如实标注持久。
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "允许屏幕截图",
                            style = NpTypography.bodyMedium,
                            color = ink0.fg,
                        )
                        Text(
                            text = "持久生效 · 限播放页；禁止时画中画不可用",
                            style = NpTypography.bodySmall,
                            color = ink0.muted,
                        )
                    }
                    NpSwitch(
                        checked = !screenshotBlocked,
                        onCheckedChange = { allow -> setScreenshotBlocked(!allow) },
                    )
                }
                // 插件贡献行（弹幕开关等）：与全屏 ⋯ 菜单同一契约，取浅色面板变体
                menuContributors.sortedBy { it.order }.forEach { contributor ->
                    contributor.RenderRows(
                        dark = false,
                        media = overlayMedia,
                        player = player,
                    )
                }
            }
        }

        // C18：文件信息卡（真实元数据，替代原先只报标题+编码的 toast）
        // 审查批次 C2：走统一进出场（旧实现是条件挂载，出现/消失都是硬切）
        PanelExitHost(
            visible = fileInfoVisible,
            onRemoved = { fileInfoVisible = false },
        ) { visible ->
            FileInfoCard(
                visible = visible,
                title = currentItem?.title?.ifBlank { null } ?: headerTitle,
                item = currentItem,
                durationMs = durationMs,
                container = headerContainer,
                onDismiss = { fileInfoVisible = false },
            )
        }

        // REV-10：播放服务断开/重连中的轻提示。放在根 Box 最上层（Toast 之前），
        // 不参与页面布局：竖屏/全屏/纯音频三种形态都覆盖，且不遮挡画面交互。
        ConnectionBanner(
            connection = connection,
            onRetry = session::retryConnection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                // 64dp 落在竖屏顶栏/全屏控制条之下，避免压住标题与返回键
                .padding(top = 64.dp),
        )

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
/**
 * 字幕状态行（E131）：`[没有字幕]` / `已挂载：<名>` / `内嵌：<轨名>`。
 *
 * 与参考示例的 `[没有字幕]` 同义——面板里改的是"样式"，这一行回答的是
 * "现在到底有没有字幕、来自哪儿"，避免用户调了半天颜色却发现根本没挂字幕。
 */
private fun subtitleStatusText(
    enabled: Boolean,
    subtitleLoaded: Boolean,
    savedSubtitle: String?,
    embeddedLabel: String?,
): String = when {
    // 总开关关掉时先说"已关闭"：此时引擎侧字幕已被卸载，再说"已挂载"是误导
    // （挂载记录仍在，是为了重新开启时能装回去）。真机实测中发现的口径问题。
    !enabled -> "[已关闭]"
    subtitleLoaded && savedSubtitle != null ->
        // SAF 文档 uri 的末段是**百分号编码**的 docId（`primary%3AMovies%2F…%2Fnp-long.srt`），
        // 直接按 `/` 切会切不开、把整串编码当文件名显示。先解码再取末段——
        // 顺带去掉扩展名（面板里显示的是"挂载了哪个字幕"，不是完整文件名）。
        "已挂载：" + android.net.Uri.decode(savedSubtitle)
            .substringAfterLast('/')
            .substringBeforeLast('.')
    embeddedLabel != null -> "内嵌：$embeddedLabel"
    else -> "[没有字幕]"
}

/** 字幕延时档位的显示文案（−1.0s / −0.1s / 0.0 / +0.1s / +1.0s）；竖屏面板与全屏下拉共用 */
internal fun subtitleDelayLabel(ms: Long): String = when {
    ms == 0L -> "0.0"
    else -> (if (ms > 0) "+" else "−") + "%.1f".format(kotlin.math.abs(ms) / 1000f) + "s"
}

/**
 * 字幕设置面板（E131）——版式对齐用户给的参考示例：
 * 开启字幕 / 字幕颜色 / 文字字幕大小 / 文字字幕用斜体 / 字幕延时 / 状态行 / 两个来源入口。
 *
 * 复用既有组件与令牌（`NpSwitch` / `NpDimens.touchTarget` / `NpRadius.chip` / Ink 令牌），
 * 不新造控件：颜色板是"命中 44dp 的透明盒 + 26dp 色点"，延时档是"命中 44dp 的胶囊"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSettingsPanel(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    colorArgb: Long,
    onColor: (Long) -> Unit,
    scale: Float,
    onScale: (Float) -> Unit,
    italic: Boolean,
    onItalic: (Boolean) -> Unit,
    verticalPosition: Float,
    onVerticalPosition: (Float) -> Unit,
    background: Boolean,
    onBackground: (Boolean) -> Unit,
    outline: Boolean,
    onOutline: (Boolean) -> Unit,
    maxLines: Int,
    onMaxLines: (Int) -> Unit,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
    statusText: String,
    embeddedTracks: List<NpTrack>,
    onPickEmbedded: (NpTrack) -> Unit,
    onPickExternal: () -> Unit,
    onEmbeddedUnavailable: () -> Unit,
) {
    val ink = LocalNpInk.current
    var showEmbedded by remember { mutableStateOf(false) }

    SubtitleSettingRow(label = "开启字幕") {
        NpSwitch(checked = enabled, onCheckedChange = onEnabled)
    }

    SubtitleSettingRow(label = "字幕颜色") {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SUBTITLE_COLOR_OPTIONS.forEach { argb ->
                val selected = argb == colorArgb
                Box(
                    modifier = Modifier
                        .size(NpDimens.touchTarget)
                        .clip(CircleShape)
                        .clickable { onColor(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(argb.toInt()))
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, ink.fg, CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }

    SubtitleSettingRow(label = "文字字幕大小") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = SUBTITLE_SCALE_MIN..SUBTITLE_SCALE_MAX,
                thumb = { NpMenuSliderThumb() },
                track = { state ->
                    NpMenuSliderTrack(
                        state = state,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = ink.border,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.1f".format(scale),
                style = NpMono,
                color = ink.muted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    SubtitleSettingRow(label = "文字字幕用斜体") {
        NpSwitch(checked = italic, onCheckedChange = onItalic)
    }

    // A12：竖直位置 / 背景条 / 描边 / 最大行数
    SubtitleSettingRow(label = "字幕垂直位置") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = verticalPosition,
                onValueChange = onVerticalPosition,
                valueRange = SUBTITLE_VERTICAL_MIN..SUBTITLE_VERTICAL_MAX,
                thumb = { NpMenuSliderThumb() },
                track = { state ->
                    NpMenuSliderTrack(
                        state = state,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = ink.border,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    verticalPosition <= 0.01f -> "顶部"
                    verticalPosition >= 0.99f -> "底部"
                    else -> "${(verticalPosition * 100).roundToInt()}%"
                },
                style = NpMono,
                color = ink.muted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    SubtitleSettingRow(label = "字幕背景条") {
        NpSwitch(checked = background, onCheckedChange = onBackground)
    }

    SubtitleSettingRow(label = "字幕描边") {
        NpSwitch(checked = outline, onCheckedChange = onOutline)
    }

    SubtitleSettingRow(label = "字幕最大行数") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (SUBTITLE_MAX_LINES_MIN..SUBTITLE_MAX_LINES_MAX).forEach { lines ->
                val selected = lines == maxLines
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = NpDimens.touchTarget)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(if (selected) ink.accentSoft else ink.fgSoft)
                        .clickable { onMaxLines(lines) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$lines",
                        style = NpMonoSmall,
                        color = if (selected) ink.accent else ink.fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    SubtitleSettingRow(label = "字幕延时") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SUBTITLE_DELAY_STEPS_MS.forEach { step ->
                val selected = step == offsetMs
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 44dp 命中红线（§14 #13）：与 D2 同一条判据
                        .heightIn(min = NpDimens.touchTarget)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(if (selected) ink.accentSoft else ink.fgSoft)
                        .clickable { onOffset(step) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = subtitleDelayLabel(step),
                        style = NpMonoSmall,
                        color = if (selected) ink.accent else ink.fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    Text(
        text = statusText,
        style = NpMonoSmall,
        color = ink.muted,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubtitleSourceButton(
            text = "选用视频内置字幕",
            modifier = Modifier.weight(1f),
            onClick = {
                if (embeddedTracks.isEmpty()) onEmbeddedUnavailable() else showEmbedded = !showEmbedded
            },
        )
        SubtitleSourceButton(
            text = "选用外挂字幕文件",
            modifier = Modifier.weight(1f),
            onClick = onPickExternal,
        )
    }

    if (showEmbedded && embeddedTracks.isNotEmpty()) {
        Text(
            text = "内嵌字幕轨",
            style = NpTypography.titleSmall,
            color = ink.fg,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        embeddedTracks.forEach { track ->
            TrackOptionRow(
                label = track.label,
                detail = null,
                selected = track.selected,
                onClick = { onPickEmbedded(track) },
            )
        }
    }
}

/** 设置行：左标签（+可选当前值），右控件。版式对齐参考示例的"标签—控件"两列。 */
@Composable
private fun SubtitleSettingRow(
    label: String,
    value: String? = null,
    content: @Composable () -> Unit,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NpDimens.touchTarget)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = NpTypography.bodyMedium,
            color = ink.fg,
            maxLines = 1,
        )
        if (value != null) {
            Text(
                text = value,
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            content()
        }
    }
}

/** 字幕来源入口按钮（参考示例底部两枚等宽按钮） */
@Composable
private fun SubtitleSourceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.button))
            .background(ink.fgSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NpTypography.bodySmall,
            color = ink.fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun FileInfoCard(
    /** 进出场驱动（审查批次 C2）：false 时播退场，由外层 [PanelExitHost] 撑到播完 */
    visible: Boolean = true,
    title: String,
    item: MediaItem?,
    durationMs: Long,
    container: String?,
    onDismiss: () -> Unit,
) {
    val (scale, alpha) = npPanelTransition(visible = visible, from = 0.96f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 遮罩透明度随进出场走（旧实现是恒定 0.55 的黑底，配上硬切就更突兀）
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
                .clip(RoundedCornerShape(NpRadius.sheet))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, LocalNpInk.current.border, RoundedCornerShape(NpRadius.sheet))
                // 审查批次 C2：卡片本体**消费点击**。旧实现里点标题/大小/编码这些
                // 文字也会冒泡到 scrim 把卡片关掉——用户正在看（或想选中复制）
                // 的那一刻被退掉，这是纯误触。
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* 有意吸收：卡片内的点击不关闭卡片 */ }
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "视频详情",
                    style = NpTypography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 显式出口：与"点遮罩关闭"并列，且给读屏一个可发现的动作名
                NpIconButton(
                    icon = NpIcon.Close,
                    contentDescription = "关闭文件信息",
                    onClick = onDismiss,
                )
            }
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
 * 模态卡/面板的**对称进出场**（审查批次 C2 / C3）。
 *
 * [npPanelEntrance] 只有入场：它靠"挂载即显示"驱动，没有退场状态机，
 * 所以面板只能瞬间卸载。结果是同一批浮层里 `NpSheet`/FS 菜单有优雅开合，
 * 文件信息卡与选集面板却"啪"地出现又"啪"地消失——产品内不一致。
 *
 * 这里改成以 [visible] 为唯一驱动：
 *  - 进场：scale [from] → 1 + alpha 0 → 1，走 `Switch` bouncy（与既有入场同手感）；
 *  - 退场：反向，走 `Field`(150) 加速离开（§3.9 进慢出快）；
 *  - 全部返回 State 供 `graphicsLayer` 在**绘制期**读，逐帧不重组面板内容；
 *  - 位移/缩放受 [npDisplacementAllowed] 门控，轻量档与 Reduce Motion 退化成纯淡。
 *
 * 调用方负责在退场动画走完之后才真正把面板移出组合树，否则面板会在退场途中直接消失。
 */
@Composable
private fun npPanelTransition(
    visible: Boolean,
    from: Float = 0.96f,
): Pair<State<Float>, State<Float>> {
    val scaleAllowed = npDisplacementAllowed()
    val enterSpec = npMotion<Float>(Motion.Switch, bouncy = true)
    val exitSpec = npMotion<Float>(Motion.Field)
    val scale: State<Float> = animateFloatAsState(
        targetValue = if (visible) 1f else if (scaleAllowed) from else 1f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "panelScale",
    )
    val alpha: State<Float> = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "panelAlpha",
    )
    return scale to alpha
}

/**
 * 退场播完再真正卸载（审查批次 C2 / C3）。
 *
 * 直接 `if (visible) { Panel() }` 会让退场动画没有机会播；
 * 这里先让 [visible] 落 false、把动画跑完，再回调 [onRemoved] 让调用方移除状态。
 * 期间 [content] 仍留在组合树里（只改 alpha/scale），触摸已由 [onDismiss] 屏蔽。
 */
@Composable
internal fun PanelExitHost(
    visible: Boolean,
    exitMs: Int = 160,
    onRemoved: () -> Unit,
    content: @Composable (visible: Boolean) -> Unit,
) {
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            present = true
        } else if (present) {
            delay(exitMs.toLong())
            present = false
            onRemoved()
        }
    }
    if (present) content(visible)
}

/**
 * 底部抽屉形态的进出场（审查批次 C3）：**自下方滑入 / 向下滑出**。
 *
 * 与居中卡片（[npPanelTransition] 用缩放）区分开——抽屉的运动语义是"从屏幕底部
 * 推上来"，缩放会读成"浮出来"，方向不对。
 * 位移受 [npDisplacementAllowed] 门控；轻量档/Reduce Motion 退化为纯淡入淡出。
 */
@Composable
private fun npSheetSlide(visible: Boolean): State<Float> {
    val enterSpec = npMotion<Float>(Motion.Panel)
    val exitSpec = npMotion<Float>(Motion.Field)
    return animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = if (visible) enterSpec else exitSpec,
        label = "sheetSlide",
    )
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
internal fun EpisodePanel(
    /** 进出场驱动（审查批次 C3） */
    visible: Boolean = true,
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 审查批次 C3：抽屉自下推入、向下退出（旧实现是硬切）
    val slide by npSheetSlide(visible)
    Box(
        modifier = modifier
            // 遮罩跟着进出场淡入淡出（旧实现恒 0.55，配合硬切更突兀）
            .graphicsLayer {
                alpha = (1f - slide).coerceIn(0f, 1f)
            }
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
                // 审查批次 C3：位移在**绘制期**读，逐帧只重绘不重组整个列表
                .graphicsLayer { translationY = size.height * slide }
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
internal fun BarCtlButton(
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
private fun Modifier.anchorReporter(onAnchor: ((Rect) -> Unit)?): Modifier =
    if (onAnchor == null) this else onGloballyPositioned { onAnchor(it.boundsInWindow()) }

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
                modifier = Modifier
                    .npTouchTarget(NpDimens.transportButton)
                    // E129（审查 #4）：播放态画的是自绘 `PauseGlyph`——它没有语义节点，
                    // 于是按钮只被读成"按钮"，读屏用户无从知道按下去会暂停。
                    // 名称挂在**按钮**上并按状态给（不挂在图标上：暂停态根本没有图标节点），
                    // 这也是 M3 对"同一个动作两种状态"的标准做法。
                    .semantics { contentDescription = if (playing) "暂停" else "播放" },
            ) {
                if (playing) {
                    PauseGlyph(tint = tint)
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        // 名称已由按钮节点给出（播放/暂停是同一动作的两种状态）；
                        // 图标再报一次会被读屏念两遍。
                        contentDescription = null,
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
@OptIn(ExperimentalLayoutApi::class)
internal fun SpeedChoiceRow(
    speed: Float,
    onPick: (Float) -> Unit,
    modifier: Modifier = Modifier,
    presets: List<Float> = SPEED_PRESETS,
    showCaption: Boolean = true,
) {
    val ink = LocalNpInk.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NpRadius.button))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, ink.border, RoundedCornerShape(NpRadius.button))
            .padding(6.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { preset ->
                val on = kotlin.math.abs(preset - speed) < 0.001f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onPick(preset) }
                        .heightIn(min = NpDimens.touchTarget)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = speedLabel(preset),
                        style = NpMono.copy(fontSize = 12.sp),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else ink.fg,
                    )
                }
            }
        }
        if (showCaption) {
            // ExoPlayer 经 Sonic 变速默认保音调（PlaybackService.setEnableAudioTrackPlaybackParams）
            Text(
                text = "变速保持音调",
                style = NpMono.copy(fontSize = 11.sp),
                color = ink.muted,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            )
        }
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
    durationMs: Long,
) {
    val target = targetMs.value
    SeekGestureBubble(
        deltaMs = (target - positionMs.value).roundToLong(),
        targetMs = target.roundToLong(),
        durationMs = durationMs,
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
        // E119：原先 enter=Field(150) / exit=Panel(180) 把 §3.9 的"退场比进场更短"写反了
        // （同族正例是 Overlay 280 / OverlayExit 220）。续看提示是"看一眼就走"的东西，
        // 进得完整、走得干脆才对。
        enter = fadeIn(animationSpec = npMotion(Motion.Panel)),
        exit = fadeOut(animationSpec = npMotion(Motion.Field)),
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
 * 底部控制层（进度 + 传输件）：压在视频上，用**全半透明纯色**底 + 白字
 * （横向全屏与竖屏视频区共用）。
 *
 * §9.2：进度三个量（实际位置 / 拖动量 / 显示位置）以 [State] 传入。
 * 本页每约 250ms 收到一次位置上报，解包成值会让整块版式跟着重组；
 * 现在订阅面收在这条控制层本身——它就是"seek 轨所在组件"，
 * 且控制层隐藏（沉浸式观看）时 AnimatedVisibility 不再订阅，代价归零。
 *
 * 形态（2026-10-12 用户裁定两条）：**横跨屏幕的完整横条、单行紧凑**——
 * 不做圆角浮动小窗；进度条与按钮共线一行，比原两行版省近一半高度。
 *
 * 排不下时按 [playerBarLayout] 的三档退让（2026-10-13 用户裁定）：先省掉总时长换回
 * 单行，两档都塞不下才把进度拆成单独一行。此前只有"塞不下就拆两行"一档，
 * 360dp 手机上预算 356dp 对 328dp 的可用宽，竖屏 215dp 高的画面被白白再吃掉一行。
 */

/** 底部控制条的排版档位，见 [playerBarLayout]。 */
internal enum class PlayerBarLayout {
    /** 单行：进度条夹在「已播」与「总时长」之间，选项钮排在右侧。 */
    SingleRow,

    /**
     * 单行、省掉总时长。
     *
     * 总时长在本页下方的信息区已有，而在窄窗里它值 42dp、换来的是整行画面不被盖住。
     */
    SingleRowWithoutDuration,

    /** 真的放不下（或系统字号 > 1.3）：进度单独一行，按钮行横滑。 */
    Split,
}

/**
 * 控制条怎么排：先试完整单行，再试省掉总时长的单行，都不行才拆两行。
 *
 * 宽度预算按固定档位估：触控键 44/颗、传输排 132、左右内边距与间距 40、
 * 进度条最短可拖长度 64；时间码按**这条片长的真实字符数**估（见 [npTimeCodeBudgetDp]）。
 *
 * [timeCodeChars] 传 `formatTimestamp(durationMs).length`：旧的固定 42dp 只够 `12:34`
 * 这种 5 字读数，片长过一小时就是 7~8 字——预算偏小会把放不下的行判成单行，
 * 真机上两枚时间码被省略号截断、进度条挤到拖不动（A29）。
 *
 * `preferSingleRow` 只跳过「宽度小于 560dp 就拆」这一条，挡不住大字号和真实放不下的情况。
 */
internal fun playerBarLayout(
    maxWidthDp: Float,
    fontScale: Float,
    preferSingleRow: Boolean,
    showTransport: Boolean,
    hasLeading: Boolean,
    iconButtonCount: Int,
    timeCodeChars: Int = 5,
): PlayerBarLayout {
    if (!maxWidthDp.isFinite() || maxWidthDp <= 0f) return PlayerBarLayout.SingleRow
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    if (scale > 1.3f) return PlayerBarLayout.Split
    if (!preferSingleRow && maxWidthDp < 560f) return PlayerBarLayout.Split
    val leading = when {
        showTransport -> 44f * 3f
        hasLeading -> 36f
        else -> 0f
    }
    val icons = 44f * iconButtonCount.coerceAtLeast(0)
    val chrome = 32f + 8f
    val minSeek = 64f
    val oneTime = npTimeCodeBudgetDp(timeCodeChars, scale)
    val body = leading + icons + chrome + minSeek
    return when {
        body + oneTime * 2f <= maxWidthDp -> PlayerBarLayout.SingleRow
        body + oneTime <= maxWidthDp -> PlayerBarLayout.SingleRowWithoutDuration
        else -> PlayerBarLayout.Split
    }
}

/**
 * 一枚时间码的宽度预算：等宽数字 12sp 的单字宽 = 0.6em = 7.2dp，再加 4dp 余量
 * 给字体替换（OEM 常把 monospace 换成自家等宽族，字宽略大）。
 */
internal fun npTimeCodeBudgetDp(chars: Int, fontScale: Float): Float =
    (7.2f * chars.coerceAtLeast(4) + 4f) * fontScale

/** 底栏时间码（设计稿 `.time-row{font-size:12px}`） */
private val NpBarTimeCode = NpMono.copy(fontSize = 12.sp)

/** 循环模式四档（A6：文件级常量，不在每次面板重组时新建列表） */
private val NpLoopModeButtons = listOf(
    NpLoopMode.OFF to NpIcon.ListView,
    NpLoopMode.ONE to NpIcon.Rotate,
    NpLoopMode.SHUFFLE to NpIcon.Sort,
    NpLoopMode.ALL to NpIcon.Sound,
)

/**
 * 已播时间码（A6）：独立重组域，位置上报只重算这一枚读数。
 *
 * 已播读数按"本片最长的时间码"定宽：跨过 9:59→10:00、59:59→1:00:00
 * 这些位数档时，行里唯一 `weight(1f)` 的孩子（进度条）不再被一点点吃掉。
 */
@Composable
private fun BarElapsedTimeCode(
    positionMs: State<Long>,
    dragPositionMs: State<Float>,
    userDragging: Boolean,
    durationText: String,
    idleColor: Color,
) {
    val elapsedText = formatTimestamp(
        if (userDragging) dragPositionMs.value.toLong() else positionMs.value,
    )
    NpStableWidth(
        reserve = if (durationText.length >= elapsedText.length) durationText else elapsedText,
        style = NpBarTimeCode,
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = elapsedText,
            // 设计稿 `.time-row{font-size:12px}`——两枚读数同一字号，
            // 此前已播 12sp、总时长 13sp，同一行里两种大小。
            style = NpBarTimeCode,
            color = if (userDragging) MaterialTheme.colorScheme.onPrimary else idleColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (userDragging) {
                Modifier.npReadoutChip(MaterialTheme.colorScheme.primary)
            } else {
                Modifier
            },
        )
    }
}

/**
 * 拖动读数的胶囊：画在文本外扩 8×3dp，**不占布局**。
 *
 * 原先这圈白底是用 `padding` 撑出来的，于是拖动起手的那一瞬间读数要多占 16dp 宽——
 * 进度条是这一行里唯一 `weight(1f)` 的孩子，那 16dp 全从它身上扣，松手又弹回来。
 */
private fun Modifier.npReadoutChip(color: Color): Modifier = drawBehind {
    val padH = 8.dp.toPx()
    val padV = 3.dp.toPx()
    val chipHeight = size.height + padV * 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-padH, -padV),
        size = Size(size.width + padH * 2f, chipHeight),
        cornerRadius = CornerRadius(chipHeight / 2f, chipHeight / 2f),
    )
}

@Composable
internal fun PlayerBottomControls(
    visible: Boolean,
    applyInsets: Boolean,
    showTransport: Boolean,
    positionMs: State<Long>,
    durationMs: Long,
    bufferedPositionMs: State<Long>,
    dragPositionMs: State<Float>,
    userDragging: Boolean,
    displayPosition: State<Float>,
    maxPosition: Float,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    /** 拖动被取消（系统边缘返回 / 父节点拦截）：**不 seek**，见 [NpSeekBar] 的说明 */
    onScrubCancel: () -> Unit,
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
    /**
     * 🎧「仅听音频」钮。**只有横向全屏形态传**（竖屏形态的入口在下方信息区的快捷排，
     * 同一能力不排两处）。此前横屏/平板根本没有入口——单一全屏形态里迷你条不在，
     * 用户横过来就再也切不到只听模式（任务文档 A1-1 的入口缺口）。
     */
    onToggleAudioOnly: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 内联倍速选择条（竖屏/仅听音频用）；全屏形态改下拉菜单后传 false */
    inlineSpeedRow: Boolean = true,
    /** ⛶ 的激活态（类 PC 沉浸全屏中） */
    fullscreenActive: Boolean = false,
    /**
     * ⛶ 钮的**形状与名称随形态给**（E129 / 审查 #10）。
     *
     * 同一颗钮在三种语境里对应三个不同结果：竖窗 = 进入全屏；横窗沉浸 = 退出全屏；
     * 类 PC 横窗 = 回到浏览。此前三处都画同一枚"向外展开"的折角、都叫"全屏切换"，
     * 于是图标承诺的动作（展开）在两种语境里与实际结果（退出 / 离开这一页）相反——
     * 用户没法从一个形状上预测会发生什么。
     */
    fullscreenIcon: ImageVector = NpIcon.Expand,
    fullscreenLabel: String = "进入全屏",
    /**
     * 进度条透明可交互槽的高度（E129 / 审查 #14）。
     *
     * 默认 [NpDimens.touchTarget]（44dp 红线）；**竖屏**那条路传
     * [NpDimens.seekTouchBand]（26dp）——竖窗的视频区只有 16:9 一条（~230dp），
     * 44dp 的槽会把控制层顶得过高（2026-10-05 真机实报"底栏占小窗一半"）。
     * 横窗/桌面的控制层压在全屏画面上，44dp 的余量是白得的，没有理由不给。
     */
    seekTouchBand: Dp = NpDimens.touchTarget,
    /** 触发控件的窗口位置回报（全屏下拉菜单的锚点） */
    onSpeedAnchor: ((Rect) -> Unit)? = null,
    onSubtitleAnchor: ((Rect) -> Unit)? = null,
    /** 进度条所在行的矩形（向上展开的下拉菜单以此为下限，永不遮住进度条） */
    onSeekBarAnchor: ((Rect) -> Unit)? = null,
    /**
     * §8.3 类 PC「控制层底部集中」：顶栏那批动作（返回 / 锁定 / ⋯ / 选集）改挂在本条
     * 左端。桌面窗口里鼠标常驻右下角，把动作摊到画面顶部等于让用户每次抬手跑半个屏幕。
     */
    leadingActions: (@Composable () -> Unit)? = null,
    trailingActions: (@Composable () -> Unit)? = null,
    /**
     * 平板右栏宽度经常低于 560dp，默认会把功能键折进横滑条，和全屏不是同一套。
     * 右栏传 true 时，只要单行还放得下就与全屏保持同一行。
     */
    preferSingleRow: Boolean = false,
) {
    val idleColor = Color.White.copy(alpha = 0.9f)
    val content: @Composable () -> Unit = {
        // 安全区不在这里消费：底部 inset 必须落在**黑色条底之下**（见下方 plate），
        // 否则条底被 inset 顶起，画面会从条下缘露出来一条（2026-10-13 用户实报"浮空"）。
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val speedSlideAllowed = npDisplacementAllowed()
            AnimatedVisibility(
                visible = speedPickerVisible && inlineSpeedRow,
                enter = fadeIn(animationSpec = npMotion(Motion.Field)) +
                    slideInVertically(animationSpec = npMotion(Motion.Field)) {
                        if (speedSlideAllowed) it / 2 else 0
                    },
                exit = fadeOut(animationSpec = npMotion(Motion.Field)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SpeedChoiceRow(
                        speed = speed,
                        onPick = onPickSpeed,
                        presets = SPEED_PORTRAIT_PRESETS,
                        showCaption = false,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            // Wide surfaces use one row; narrow windows separate timeline and scrollable commands.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = BOTTOM_BAR_PLATE_ALPHA))
                    // 底部/横向安全区收在**黑底之内**：黑条因此一直铺到视频/屏幕下缘，
                    // 手势条或刘海区也盖在同一块半透明黑上，条下不再露画面。
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
                    .padding(horizontal = NpSpace.lg)
                    // 原先这块留白挂在黑条**外面**，等于把条底垫高 4dp、条下露出画面；
                    // 收进条内后，留白照旧、条底与视频/屏幕下缘严丝合缝。
                    .padding(bottom = NpSpace.xs),
            ) {
                val controlScroll = rememberScrollState()
                val barLayout = playerBarLayout(
                    maxWidthDp = maxWidth.value,
                    fontScale = LocalDensity.current.fontScale,
                    preferSingleRow = preferSingleRow,
                    showTransport = showTransport,
                    hasLeading = leadingActions != null,
                    iconButtonCount = 3 +
                        (if (onToggleAudioOnly != null) 1 else 0) +
                        (if (trailingActions != null) 1 else 0),
                    timeCodeChars = formatTimestamp(durationMs).length,
                )
                val split = barLayout == PlayerBarLayout.Split
                val showDuration = barLayout == PlayerBarLayout.SingleRow
                // 单行时行高已由 44dp 的图标撑起来，进度条的触控带跟着吃满同样的高度是
                // 白得的命中面积（轨道本身仍按 trackHeight 居中画，视觉不变）；
                // 拆成两行时才用形态各自给的窄带（竖屏 18dp），否则黑条又要垫高一行。
                val inlineSeekBand = seekTouchBand.coerceAtLeast(NpDimens.touchTarget)
                Column {
                    if (split) {
                        NpSeekBar(
                            durationMs = durationMs,
                            displayPositionMs = displayPosition,
                            bufferedPositionMs = bufferedPositionMs,
                            dragging = userDragging,
                            onScrubStart = onScrubStart,
                            onScrubChange = onScrubChange,
                            onScrubEnd = onScrubEnd,
                            onScrubCancel = onScrubCancel,
                            touchBand = seekTouchBand,
                            modifier = Modifier.fillMaxWidth().anchorReporter(onSeekBarAnchor),
                        )
                    }
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    if (split) Modifier.horizontalScroll(controlScroll) else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (leadingActions != null) {
                    leadingActions()
                    Spacer(modifier = Modifier.width(NpSpace.sm))
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
                val durationText = formatTimestamp(durationMs)
                // A6：已播读数拆成独立叶子——位置每 250ms 一跳只重组这一枚时间码，
                // 不再让整行（传输键、进度条、倍速/字幕钮）跟着重组。
                BarElapsedTimeCode(
                    positionMs = positionMs,
                    dragPositionMs = dragPositionMs,
                    userDragging = userDragging,
                    durationText = durationText,
                    idleColor = idleColor,
                )
                if (!split) NpSeekBar(
                    durationMs = durationMs,
                    displayPositionMs = displayPosition,
                    bufferedPositionMs = bufferedPositionMs,
                    dragging = userDragging,
                    onScrubStart = onScrubStart,
                    onScrubChange = onScrubChange,
                    onScrubEnd = onScrubEnd,
                    // E129（审查 #3）：取消与提交是两个出口——被系统边缘返回打断的拖动不 seek
                    onScrubCancel = onScrubCancel,
                    touchBand = inlineSeekBand,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .anchorReporter(onSeekBarAnchor),
                )
                if (showDuration) {
                    Text(
                        text = durationText,
                        style = NpBarTimeCode,
                        color = idleColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BarCtlButton(
                    icon = NpIcon.Subtitle,
                    contentDescription = "字幕",
                    onClick = onPickSubtitle,
                    active = subtitleLoaded,
                    modifier = Modifier.anchorReporter(onSubtitleAnchor),
                )
                BarCtlButton(
                    icon = NpIcon.Speed,
                    contentDescription = "倍速播放",
                    onClick = onToggleSpeedPicker,
                    active = speed != 1f || speedPickerVisible,
                    modifier = Modifier.anchorReporter(onSpeedAnchor),
                )
                if (onToggleAudioOnly != null) {
                    BarCtlButton(
                        icon = NpIcon.Headphones,
                        contentDescription = "仅听音频",
                        onClick = onToggleAudioOnly,
                    )
                }
                trailingActions?.invoke()
                BarCtlButton(
                    icon = fullscreenIcon,
                    contentDescription = fullscreenLabel,
                    onClick = onToggleFullscreen,
                    active = fullscreenActive,
                )
            }
                }
            }
        }
    }

    // 位移开关读在组合期（lambda 里不能再调 @Composable），淡入淡出不受影响
    val slideAllowed = npDisplacementAllowed()
    val enterSlidePx = with(LocalDensity.current) { BAR_ENTER_SLIDE.roundToPx() }
    val exitSlidePx = with(LocalDensity.current) { BAR_EXIT_SLIDE.roundToPx() }
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = npMotion(Motion.Overlay)) +
                slideInVertically(animationSpec = npMotion(Motion.Overlay)) {
                    if (slideAllowed) enterSlidePx else 0
                },
            exit = fadeOut(animationSpec = npMotion(Motion.OverlayExit)) +
                slideOutVertically(animationSpec = npMotion(Motion.OverlayExit)) {
                    if (slideAllowed) exitSlidePx else 0
                },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // 「全半透明纯色」底 + **横跨屏幕的完整横条**（2026-10-12 用户裁定）：
            //  ① "控制栏的颜色不够清楚，不要做成渐变"——渐变上弱段的白图标在亮画面上
            //     只读得出 ~2:1（§14 #9 红线 4.5:1）。0.62 黑是原渐变中段按红线调过的
            //     alpha，整条均匀使用即"全半透明"，白字对纯白画面 ≥5:1。
            //  ② "不要做成圆角小浮动窗口，做成横跨屏幕的完整横条"——撤销上一版的
            //     悬浮板（留边 + 圆角），底边贴齐视频/屏幕下缘、左右通到屏幕边。
            //     入场仍从下缘升起（[BAR_ENTER_SLIDE]），全程不越过视频下边界。
            content()
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
                }
                if (showActions) trailing()
            }
        }
    }
}

/**
 * 中央"继续播放"钮（设计稿 `.bigplay`）：64dp 玻璃圆 + 白三角。
 * 只在暂停时出现——播放中它不该占着画面。
 *
 * **M6 水波**（§5.3/§9.6）：点按后一圈水波从钮后扩散并淡出（`Bubble` 500ms、
 * `Emphasized` 曲线——该曲线规格里"只给共享元素与主播放键"）。仅 High 档
 * （`centerBubble`），播放中自动降档与 Reduce Motion 下都不出现。
 * 动画值只读在 `graphicsLayer` 里（§9.2：逐帧只重绘，不重组）。
 */
@Composable
internal fun CenterResumeButton(
    visible: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleOn = npQuality().centerBubble && !LocalNpReduceMotion.current
    // 时长读在组合期：协程里拿不到 @Composable 的 npDuration（同 transitionSpec 那条约束）
    val bubbleMs = npDuration(Motion.Bubble)
    val scope = rememberCoroutineScope()
    val wave = remember { Animatable(0f) }
    fun playWithWave() {
        if (bubbleOn) {
            scope.launch {
                wave.snapTo(0f)
                wave.animateTo(1f, tween(bubbleMs, easing = NpEasing.Emphasized))
            }
        }
        onPlay()
    }
    // 槽位固定 124dp（水波的最大直径），按钮 64dp 居中——水波必须画在
    // AnimatedVisibility **之外**：点按会立刻让按钮隐去（播放开始），波纹要能自己走完。
    Box(modifier = modifier.size(124.dp), contentAlignment = Alignment.Center) {
        if (bubbleOn) {
            Box(
                modifier = Modifier
                    .size(124.dp)
                    // 水波：半径 64→124dp、整圈淡出——动画值只读在绘制层（§9.2）
                    .graphicsLayer {
                        val p = wave.value
                        val scale = (64f + 56f * p) / 124f
                        scaleX = scale
                        scaleY = scale
                        alpha = (0.5f * (1f - p)).coerceAtLeast(0f)
                    }
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = npMotion(Motion.Field)) +
                scaleIn(initialScale = 0.86f, animationSpec = npMotion(Motion.Field)),
            exit = fadeOut(animationSpec = npMotion(Motion.Field)) +
                scaleOut(targetScale = 0.86f, animationSpec = npMotion(Motion.Field)),
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
                    .clickable(onClick = ::playWithWave),
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
internal fun StageLockBadgeHost(
    visible: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = true,
) {
    // 独立成组件的另一个原因：舞台嵌在 Column 里时，直接调 AnimatedVisibility 会解析到
    // ColumnScope 的重载而编译不过；这里没有隐式接收者，落到顶层重载。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
        modifier = modifier,
    ) {
        StageLockBadge(onUnlock = onUnlock, locked = locked)
    }
}

/** 44dp 玻璃圆「解锁」钮本体（外观与按压反馈） */
@Composable
private fun StageLockBadge(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = true,
) {
    Box(
        modifier = modifier.npTouchTarget(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .npPressScale(onClick = onUnlock, pressedScale = 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (locked) NpIcon.Lock else NpIcon.LockOpen,
                contentDescription = if (locked) "解锁" else "锁屏",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
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
private enum class ActionGlyph { More, Audio, Fav, Info }

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
            ActionGlyph.Info -> Icon(
                imageVector = NpIcon.Info,
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
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 信息区的选集行：选中 = 3dp accent 竖条 + accent 文字 + 声柱。
 * 声柱只在正在播放的那一行起伏；减少动效时停在一组固定高度。
 */
@Composable
private fun EpisodeRow(
    index: Int,
    title: String,
    durationMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
    /** morph 部件名（横屏左栏同名的那一行）：转屏时它跟着行一起飞到位。 */
    morphKey: String? = null,
    morphDesktop: Boolean? = null,
) {
    val ink = LocalNpInk.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (morphKey == null) Modifier
                else Modifier.npMorphPart(morphKey, desktop = morphDesktop),
            )
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

/** 正在播放的声柱：三根错相起伏。减少动效时用固定高度。 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val reduceMotion = LocalNpReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val fractions = if (reduceMotion) {
        listOf(0.45f, 1f, 0.7f)
    } else {
        val transition = rememberInfiniteTransition(label = "playingBars")
        listOf(0 to 460, 160 to 620, 80 to 540).map { (offset, period) ->
            transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(period, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offset),
                ),
                label = "bar$offset",
            ).value
        }
    }
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp * fraction.coerceIn(0.28f, 1f))
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
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
    overlays: List<PlaybackOverlay>,
    overlayMedia: MediaItem,
    navigator: Navigator,
    state: PlaybackState,
    userDragging: Boolean,
    /** 控制层是否可见——下发给覆盖层，让弹幕 pill 这类开关与控制栏同显隐 */
    controlsVisible: Boolean = true,
    /** E131：字幕样式（颜色/大小/斜体/总开关），来自「字幕设置」面板 */
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /**
     * A14：小窗模式。只留画面——字幕、弹幕/覆盖层、缓冲圈都不画；
     * 手势层由调用方不挂（见 PiP 分支），这里管舞台内部的叠层。
     */
    inPip: Boolean = false,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    mirrored: Boolean = false,
    rotation: Int = 0,
    onSurfaceView: (TextureView?) -> Unit = {},
    /**
     * 参与整屏 morph 的部件名（[com.noneplayer.kernel.ui.api.NpMorphKey.Video]）。
     *
     * 两侧（竖屏播放页 / 横屏右栏）给**同一个名字**，画面才会从上一形态的位置飞到位；
     * 详情页里的内联画面传 null——它不是交接的当事画面，登记进去会抢走起点。
     */
    morphKey: String? = null,
    /** [morphKey] 属于哪个形态（过渡期间旧树量出来的矩形不是落点，见 [NpMorph.record]）。 */
    morphDesktop: Boolean? = null,
    /** 画面在飞行中是否自转回正（见 [npMorphPart] 的 `rotate`）。 */
    morphRotate: Boolean = false,
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
        Box(
            // morphKey 让这块画面自己参与整屏 morph：转屏时它不消失、不被替换，
            // 而是从上一形态里画面所在的位置飞到新版式的落点（E133）。
            // scale = true：画面是整块缩放的图像，尺寸差要跟着插值；文字与按钮只平移。
            modifier = morphKey?.let {
                stageModifier.npMorphPart(it, scale = true, desktop = morphDesktop, rotate = morphRotate)
            } ?: stageModifier,
        ) {
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
                onRelease = {
                    player.detachDisplay(it)
                    onSurfaceView(null)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!inPip) {
                // E1：字幕浮层（外挂 + 内嵌轨道统一出口），在画面变换之外
                // E131：样式由「字幕设置」面板驱动（颜色/大小/斜体/总开关）
                // A6：Cue 的订阅点在它自己这一层（PlayerSubtitleLayer），舞台不参与重组
                PlayerSubtitleLayer(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    style = subtitleStyle,
                    mediaUri = overlayMedia.uri,
                )
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
        }
        // 缓冲指示（M22）：原先 `if` 挂载=硬切，网络一顿一停时圆环是"啪"地闪现，
        // 读起来像播放器 itself 出了问题。进 150 / 出 120 淡入淡出。
        // 曲线不按 M22 字面的 linear——§3.8 把线性例外限定在"缓冲环旋转"等四类连续量上，
        // 显隐是一次性状态变化，必须走 Silk；线性留给环自身的旋转。
        AnimatedVisibility(
            visible = !inPip && state == PlaybackState.Buffering && !userDragging,
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

/**
 * 播放服务连接状态横幅（REV-10）——**轻提示，不阻塞画面**。
 *
 * Android 随时可能回收承载媒体会话的进程（后台内存压力、厂商省电、播放服务崩溃）。
 * 那之后画面会冻住/黑掉，而改造前用户只会看到"卡了"：既没有解释，也没有恢复入口。
 * 这里只在三种异常态浮一条顶部横幅：
 *
 * - [PlayerConnection.DISCONNECTED]：已断开 + 「重连」（引擎会重新 bind 控制器）
 * - [PlayerConnection.RECONNECTING]：正在重连——不给按钮，避免重复请求
 * - [PlayerConnection.FAILED]：重连失败 + 「重试」
 *
 * [PlayerConnection.CONNECTING]（正常启动过程）与 [PlayerConnection.CONNECTED]
 * 不显示。横幅挂在根 Box 顶层、不参与布局，只有按钮可点，画面交互不受影响。
 */
@Composable
private fun ConnectionBanner(
    connection: PlayerConnection,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (connection) {
        PlayerConnection.DISCONNECTED -> "播放服务已断开（可能被系统回收）"
        PlayerConnection.RECONNECTING -> "正在重连播放服务…"
        PlayerConnection.FAILED -> "重连失败"
        PlayerConnection.CONNECTING, PlayerConnection.CONNECTED -> ""
    }
    val actionLabel = when (connection) {
        PlayerConnection.DISCONNECTED -> "重连"
        PlayerConnection.FAILED -> "重试"
        else -> null
    }
    val ink = LocalNpInk.current
    AnimatedVisibility(
        visible = text.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(animationSpec = npMotion(Motion.Field)),
        exit = fadeOut(animationSpec = npMotion(Motion.Field)),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(NpRadius.chip))
                .padding(
                    start = 12.dp,
                    end = if (actionLabel != null) 6.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                style = NpMonoSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(ink.accent)
                        .npPressScale(onClick = onRetry, pressedScale = 0.97f)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = actionLabel,
                        style = NpTypography.labelLarge,
                        color = ink.bg,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ErrorPanel(
    message: String,
    modifier: Modifier = Modifier,
    failure: PlaybackFailure? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val (scale, entranceAlpha) = npPanelEntrance(0.96f)
    // M18（§7.5 `Reject`）：错误面板出现的那一帧响一次——它与入场动画同时起步，
    // 是"停下来告诉你坏了"的面，不响就等于把错误做成了静默的视觉变化。
    val errorHaptics = rememberNpHaptics()
    LaunchedEffect(Unit) { errorHaptics.performHaptic(NpHaptic.Reject) }
    Column(
        modifier = modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 300.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = entranceAlpha.value
            }
            .clip(RoundedCornerShape(NpRadius.card))
            .background(Color.Black.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(NpRadius.card))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failure != null) {
            Text(
                text = failure.title,
                style = NpTypography.titleSmall,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            failure.trackLine?.let { line ->
                Text(
                    text = line,
                    style = NpTypography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = failure.reason,
                style = NpTypography.bodySmall,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                text = message,
                style = NpTypography.bodyMedium,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorPanelButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                ErrorPanelButton(
                    text = actionLabel,
                    primary = true,
                    onClick = onAction,
                    modifier = Modifier.weight(1f),
                )
            }
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
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
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
    /** 本形态标签：与右栏栏头的 ✕ / 标题同名，转屏时它们是一个部件（见 [NpMorph]）。 */
    morphDesktop: Boolean? = null,
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
            modifier = Modifier.npMorphPart(NpMorphKey.Dismiss, desktop = morphDesktop),
        )
        Column(
            modifier = if (morphDesktop == null) {
                Modifier.weight(1f)
            } else {
                Modifier.weight(1f).npMorphPart(NpMorphKey.Title, desktop = morphDesktop)
            },
        ) {
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
        val parsed = uri.toUri()
        val inferred = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                parsed.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty(),
            )
        try {
            context.contentResolver
                .query(
                    uri.toUri(),
                    arrayOf(android.provider.MediaStore.MediaColumns.MIME_TYPE),
                    null, null, null,
                )
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: inferred
        } catch (e: Exception) {
            Log.d("PlayerScreen", "resolveMimeType 查询失败 $uri", e)
            inferred
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
    //
    // A1-2 放宽：只有 id 与 uri **都**为空才放弃。只带 uri 的深链（外部应用、
    // 修复前的通知）下面会按 uri 命中来源里的真条目——于是连播回来了，
    // 权威 id 也回来了（per-media 记忆不再挤进空键桶，见 `audio_only_` 孤儿键）。
    // 返回的是来源真条目，不是 E88 那个假 mime 条目，所以那条修复不受影响。
    if (mediaId.isEmpty() && uri.isEmpty()) return emptyList()
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
                val all = source.awaitSelfList(mediaId, uri)
                val self = all.firstOrNull { mediaId.isNotEmpty() && it.id == mediaId }
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

/**
 * 首帧**非空却仍不含本项**时的额外等待窗口。
 *
 * 来源的媒体列表是 StateFlow，且是"扫描完成一次性覆盖"（不是渐进追加）——非空即完整。
 * 所以"非空又不含本项"等于"本项确实不在这个来源里"（id 变了 / 深链指向别的来源），
 * 再等满 [SOURCE_WAIT_MS] 纯属白等：每个来源白等 3s，串起来就是进页几秒的转圈。
 */
private const val SOURCE_WAIT_SETTLED_MS = 400L

/**
 * 等到来源列表里出现"本项"（先 id 再 uri）为止，返回**那一刻的完整列表**。
 *
 * 冷启动（首帧是空列表，扫描未落库）值得等满 [SOURCE_WAIT_MS]；
 * 首帧非空却仍不含本项则只给 [SOURCE_WAIT_SETTLED_MS] 的窗口。
 * 超时一律回退**已经拿到的那份快照**——不再二次订阅（此前超时分支会重新
 * `observeMedia().first()`，等于把同一个等待又排一遍）。
 */
private suspend fun MediaSourceProvider.awaitSelfList(
    mediaId: String,
    uri: String,
): List<MediaItem> {
    fun matches(list: List<MediaItem>) =
        list.any { (mediaId.isNotEmpty() && it.id == mediaId) || it.uri == uri }

    val snapshot = observeMedia().first()
    if (matches(snapshot)) return snapshot
    val budget = if (snapshot.isEmpty()) SOURCE_WAIT_MS else SOURCE_WAIT_SETTLED_MS
    return withTimeoutOrNull(budget) { observeMedia().first { matches(it) } } ?: snapshot
}

// ---- PiP 动作（§5.3）：PendingIntent 归引擎（A2），UI 只贴图标与文案 ----

/** A14：离开播放页时的生命周期 disarm；播放期间的参数发布只走 publishPipParams。 */
private fun disarmPipParams(act: ComponentActivity) {
    runCatching {
        val builder = PictureInPictureParams.Builder().setActions(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false).setSeamlessResizeEnabled(true)
        }
        act.setPictureInPictureParams(builder.build())
    }
}

private fun manualPipParams(
    context: Context,
    aspectRatio: Float,
    playing: Boolean,
    mediaButtonIntent: (NpMediaButtonAction) -> Any?,
    sourceRect: android.graphics.Rect? = null,
): PictureInPictureParams {
    val ratio = pipAspectRatio(aspectRatio)
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational((ratio * 1000).roundToInt(), 1000))
        .setActions(pipActions(context, playing, mediaButtonIntent))
    // 手动进小窗的源矩形（lint PictureInPictureIssue 的落点之一）：过渡动画从视频
    // 区域收拢。自动进入保持关闭是产品决策——小窗只允许播放菜单手动入口
    // （MainActivity.onPictureInPictureRequested），lint 只要求两种新式调用在工程内并存。
    if (sourceRect != null && !sourceRect.isEmpty) {
        builder.setSourceRectHint(sourceRect)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // A14：无缝缩放打开——捏合/比例变化不再用一帧截图缩放（表现为顿一下）。
        // 自动进入仍关闭：小窗只允许播放菜单手动入口。
        builder.setAutoEnterEnabled(false).setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

/**
 * 小窗上的两颗动作按钮。
 *
 * 按键通路经 [VideoPlayer.mediaButtonIntent] 由引擎给出（A2）：UI 侧自己
 * `PendingIntent.getService` 造出来的 Intent 只有 action + package、没有组件，本包内
 * 没有 service 接得住，`send()` 静默无操作——按钮画得出来、点了没反应（模拟器尤其如此）。
 * 引擎没给通路时**不画这颗钮**，不留死控件。
 */
private fun pipActions(
    context: Context,
    playing: Boolean,
    mediaButtonIntent: (NpMediaButtonAction) -> Any?,
): List<RemoteAction> {
    fun icon(resId: Int) = android.graphics.drawable.Icon.createWithResource(context, resId)

    fun action(kind: NpMediaButtonAction, iconRes: Int, label: String, desc: String): RemoteAction? {
        val intent = mediaButtonIntent(kind) as? PendingIntent ?: return null
        return RemoteAction(icon(iconRes), label, desc, intent)
    }

    // A14：补上一集，到此为止（3 颗，不再加快退）。图标只在已进入小窗后更新。
    return listOfNotNull(
        action(
            NpMediaButtonAction.PREVIOUS,
            android.R.drawable.ic_media_previous,
            "上一集",
            "播放上一集",
        ),
        action(
            if (playing) NpMediaButtonAction.PAUSE else NpMediaButtonAction.PLAY,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "暂停" else "播放",
            if (playing) "暂停播放" else "继续播放",
        ),
        action(
            NpMediaButtonAction.NEXT,
            android.R.drawable.ic_media_next,
            "下一集",
            "播放下一集",
        ),
    )
}

/** 同名字幕优先级（§12.6 同名挂载）：ASS 特效最全，VTT 兜底 */
private val SIBLING_SUBTITLE_EXTENSIONS = listOf("ass", "ssa", "srt", "vtt")

/**
 * 同名字幕自动挂载（批次 3 / E140 重写）：找**同目录、同基名**的字幕文件，按
 * ASS > SSA > SRT > VTT 取第一条。
 *
 * **两条路并行**（谁先命中用谁），因为 MediaStore 那条在真机上恒为空集（E140 实测）：
 * 应用没有 `MANAGE_EXTERNAL_STORAGE`、只持有 `READ_MEDIA_VIDEO/AUDIO` 时，MediaProvider
 * 会把非媒体文件（`.ass` 落成 `application/octet-stream`）对 app 整体过滤掉——
 * `adb shell content query` 看得到，app 查回来 `count=0`。
 *
 * ① MediaStore `Files` 表：仍保留（别的设备/权限组合下可能有效）；
 * ② 已授权的 SAF 树（[authorizedTrees]）：把授权树的文档 id 折成相对路径，视频的
 *    `relative_path` 落在该树内（等于树根或为其子孙）时逐段下钻到视频所在目录，再列
 *    该目录的孩子按同名+扩展名取优先级最高的一条。授权过的目录正是本应用扫库的来源，
 *    这是主力路径。E144 之前只认「等于树根」，子目录里的视频永远挂不上。
 *
 * 查不到返回 null（无字幕 / 不在授权目录里），调用方保持现状。
 */
internal suspend fun resolveSiblingSubtitle(
    context: Context,
    uri: String,
    authorizedTrees: List<String> = emptyList(),
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val meta = resolver
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
        val displayName = meta.first?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val base = displayName.substringBeforeLast('.', displayName)
        if (base.isBlank()) return@runCatching null

        mediaStoreSibling(resolver, base, meta.second)?.let { return@runCatching it }
        val relativePath = meta.second?.trim('/') ?: return@runCatching null
        if (relativePath.isEmpty()) return@runCatching null
        safSibling(resolver, authorizedTrees, relativePath, base)
    }.getOrNull()
}

/**
 * ① MediaStore 侧。这里**顺带修一处真缺陷**：旧的
 * `A OR B OR C OR D AND relative_path = ?` 里 OR 比 AND 松，前三条分支不带目录约束，
 * 别的目录里的同名字幕也会命中——显式加括号，目录约束对四条分支一律生效。
 */
private fun mediaStoreSibling(
    resolver: android.content.ContentResolver,
    base: String,
    relativePath: String?,
): String? {
    val files = android.provider.MediaStore.Files.getContentUri("external")
    val selection = "(" +
        SIBLING_SUBTITLE_EXTENSIONS.joinToString(" OR ") { "_display_name = ?" } +
        ") AND relative_path = ?"
    val args = SIBLING_SUBTITLE_EXTENSIONS.map { "$base.$it" } + (relativePath ?: "")
    return resolver.query(
        files,
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
                best = android.content.ContentUris.withAppendedId(files, c.getLong(0)).toString()
            }
        }
        best
    }
}

/**
 * ② SAF 侧（E140 新增，主力路径）：授权树的文档 id 形如 `primary:Movies`，冒号后就是
 * 相对 external storage 的路径；与视频的 `relative_path` 相等才去列同级。用
 * `DocumentsContract` 列孩子拿文档 uri，结果对 app 可读（MediaStore 那条不行）。
 */
private fun safSibling(
    resolver: android.content.ContentResolver,
    authorizedTrees: List<String>,
    videoRelativePath: String,
    base: String,
): String? {
    for (tree in authorizedTrees) {
        val treeUri = runCatching { tree.toUri() }.getOrNull() ?: continue
        val treeDocId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: continue
        val treeRel = treeDocId.substringAfter(':', "").trim('/')
        val segments = subtitleSiblingDirectorySegments(treeRel, videoRelativePath) ?: continue
        // 授权树根 == 视频所在目录时零段；子目录视频逐段下钻拿目录文档 id（E144）。
        var dirDocId = treeDocId
        var walked = true
        for (name in segments) {
            val child = findChildDocumentId(resolver, treeUri, dirDocId, name)
            if (child == null) {
                walked = false
                break
            }
            dirDocId = child
        }
        if (!walked) continue
        val children = runCatching {
            android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        }.getOrNull() ?: continue
        val found = resolver.query(
            children,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            var best: String? = null
            var bestPrio = Int.MAX_VALUE
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.startsWith("$base.")) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                val prio = SIBLING_SUBTITLE_EXTENSIONS.indexOf(ext).let { if (it < 0) Int.MAX_VALUE else it }
                if (prio < bestPrio) {
                    bestPrio = prio
                    best = runCatching {
                        android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, c.getString(0))
                            .toString()
                    }.getOrNull()
                }
            }
            best
        }
        if (found != null) return found
    }
    return null
}

/**
 * 视频相对路径落在授权树里的**剩余目录段**（E144 修）。
 *
 * 旧判据是「视频相对路径 == 授权树相对路径」，等价于**只有直接躺在授权目录根下的视频
 * 才找同名兄弟字幕**。真机实测：授权 `Movies` 根后，`Movies/<番剧>/<合集>/<子目录>/x.mkv`
 * 这种正常媒体库结构一律漏挂（SAIO 两层嵌套的 01/.ass、02/.ass 全部没挂上，屏幕只有
 * 内嵌轨；而根下的 `np-wmv2.ass`、`1080p官方中字.ass` 正常）。这里把判据放宽成
 * 「等于树根或为其子孙」：
 * - 等于树根 → 空表（视频所在目录就是授权树根，不用往下钻）；
 * - 子孙 → 剩下的目录段，调用方逐段下钻到视频所在目录的文档 id；
 * - 无关路径 → null（授权树管不到这支视频）。
 *
 * 纯字符串判据，JVM 单测钉住（`SubtitleSiblingSegmentsTest`）。
 */
internal fun subtitleSiblingDirectorySegments(
    treeRelativePath: String,
    videoRelativePath: String,
): List<String>? {
    val tree = treeRelativePath.trim('/')
    val video = videoRelativePath.trim('/')
    // 授权的是存储根（如 `primary:` → 空串）：整条相对路径都是要下钻的段
    if (tree.isEmpty()) return video.split('/').filter { it.isNotEmpty() }
    if (video == tree) return emptyList()
    if (!video.startsWith("$tree/")) return null
    return video.removePrefix("$tree/").split('/').filter { it.isNotEmpty() }
}

/**
 * 在授权树里按**显示名**找直接子文档 id（E144：`subtitleSiblingDirectorySegments` 的
 * 下钻落点）。只找一层；找不到返回 null（目录被改名/移走，走别的授权树或放弃）。
 */
private fun findChildDocumentId(
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
        null, null, null,
    )?.use { c ->
        var hit: String? = null
        while (c.moveToNext()) {
            if (c.getString(1) == displayName) {
                hit = c.getString(0)
                break
            }
        }
        hit
    }
}.getOrNull()

/**
 * M2 共享封面飞行帧：从卡片机位补间到舞台（挂在舞台 Box 内，坐标相对舞台）。
 *
 * 动画值只读在**布局/绘制层**（`layout {}` 与 `graphicsLayer {}`）——逐帧只重排那块
 * 贴图，不重组整页（§9.2 同一条纪律）。`progress` 末段 15% 行程读作淡出，把画面让给
 * 已经出帧的视频。
 */
@Composable
private fun CoverFlightOverlay(
    takeoff: NpCoverFlight.Takeoff?,
    model: String?,
    stageRect: Rect?,
    wave: Animatable<Float, AnimationVector1D>,
) {
    if (takeoff == null || model == null || stageRect == null) return
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                val p = wave.value
                val from = takeoff.from
                val w = lerp(from.width, stageRect.width, p).roundToInt().coerceAtLeast(1)
                val h = lerp(from.height, stageRect.height, p).roundToInt().coerceAtLeast(1)
                val x = lerp(from.left - stageRect.left, 0f, p).roundToInt()
                val y = lerp(from.top - stageRect.top, 0f, p).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
            }
            .graphicsLayer { alpha = ((1.15f - wave.value) / 0.15f).coerceIn(0f, 1f) },
    )
}
