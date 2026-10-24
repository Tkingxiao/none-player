package com.noneplayer.plugins.player.ui

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpCoverFlight
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDuration
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.ui.api.PlaybackContext
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import com.noneplayer.kernel.ui.api.npMorphPart
import kotlin.math.roundToInt

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
 * M2 共享封面飞行帧：从卡片机位补间到舞台（挂在舞台 Box 内，坐标相对舞台）。
 *
 * 动画值只读在**布局/绘制层**（`layout {}` 与 `graphicsLayer {}`）——逐帧只重排那块
 * 贴图，不重组整页（§9.2 同一条纪律）。`progress` 末段 15% 行程读作淡出，把画面让给
 * 已经出帧的视频。
 */
@Composable
internal fun CoverFlightOverlay(
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
