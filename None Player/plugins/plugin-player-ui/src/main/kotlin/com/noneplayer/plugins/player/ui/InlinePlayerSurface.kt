package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.plugins.player.ui.controls.NpSeekBar
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.InlinePlaybackProvider
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * 控制层只保留"看片必需"的四件：播放暂停、进度、时间、全屏；
 * 倍速/字幕/音轨/画面等选项在独占播放页里做（内嵌小窗里展开这些只会添乱）。
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

        // 装载/续播：同一媒体只续播（含 ENDED 退位），不同媒体走完整装载链
        LaunchedEffect(media.uri, autoPlay) {
            ensureSessionPlaying(
                context = context,
                registry = kernel.registry,
                settings = settings,
                history = history,
                player = player,
                session = session,
                item = media,
                autoPlay = autoPlay,
                onSubtitleMatched = { key, hit -> scope.launch { settings.setSubtitleUri(key, hit) } },
            )
        }

        val state by player.state.collectAsStateWithLifecycle()
        val videoAspectRatio by player.videoAspectRatio.collectAsStateWithLifecycle()
        val cues by player.cues.collectAsStateWithLifecycle()
        val overlays by remember { kernel.registry.observe<PlaybackOverlay>() }
            .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PlaybackOverlay>())

        var controlsVisible by remember { mutableStateOf(true) }
        var userDragging by remember { mutableStateOf(false) }
        var dragPositionMs by remember { mutableFloatStateOf(0f) }
        var scrubWasPlaying by remember { mutableStateOf(false) }

        // 播放中控制层自动隐藏；拖动进度条期间不隐藏。
        // §8.4：指针环境（类 PC 窗口 / 接着鼠标触控板）里不隐藏——内嵌栏常在一边陪着
        // 列表被浏览，栏一藏用户想换个倍速就得先点一下画面把栏唤回来。
        val windowInfo = LocalNpWindowInfo.current
        val pointerAttached = windowInfo.isDesktopLike || windowInfo.isHoverable
        LaunchedEffect(controlsVisible, state, userDragging, pointerAttached) {
            if (pointerAttached) return@LaunchedEffect
            if (controlsVisible && state == PlaybackState.Playing && !userDragging) {
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
            VideoStage(
                player = player,
                videoAspectRatio = videoAspectRatio,
                cues = cues,
                overlays = overlays,
                overlayMedia = media,
                navigator = navigator,
                state = state,
                userDragging = userDragging,
                modifier = Modifier.fillMaxSize(),
            )
            // 暂停时中央播放钮（与播放页同款玻璃大钮）
            CenterResumeButton(
                visible = !playing && !userDragging,
                onPlay = { player.play() },
                modifier = Modifier.align(Alignment.Center),
            )
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
                exit = fadeOut(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                        ),
                    ),
            ) {
                // E120：进度/时长/缓冲三项的订阅点收进控制层这个作用域。
                // 原先它们和 state/cues 一起解包在 InlinePlayerView 顶部，于是整棵
                // 内嵌子树每 250ms 重组一次（实测 43 次/12s）——AndroidView 虽被
                // Compose 跳过、没有真的重挂表面（同窗口 attachSurfaceTo 计 0 次），
                // 但控制层外的父作用域白跑，`Brush.verticalGradient` 与两条
                // enter/exit spec 每 tick 重新分配。§9.2 的纪律就是为这个立的。
                val positionMs by player.positionMs.collectAsStateWithLifecycle()
                val durationMs by player.durationMs.collectAsStateWithLifecycle()
                val bufferedPositionMs by player.bufferedPositionMs.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    NpSeekBar(
                        durationMs = durationMs,
                        displayPositionMs = if (userDragging) dragPositionMs else positionMs.toFloat(),
                        bufferedPositionMs = bufferedPositionMs,
                        dragging = userDragging,
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { if (playing) player.pause() else player.play() },
                            modifier = Modifier.npTouchTarget(34.dp),
                        ) {
                            if (playing) {
                                PauseGlyph(tint = Color.White, barWidth = 4.dp, barHeight = 16.dp)
                            } else {
                                Icon(
                                    imageVector = NpIcon.Play,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Text(
                            text = formatTimestamp(positionMs),
                            style = NpMono.copy(fontSize = 12.sp),
                            color = Color.White,
                        )
                        Box(modifier = Modifier.weight(1f))
                        Text(
                            text = formatTimestamp(durationMs),
                            style = NpMonoSmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        IconButton(
                            onClick = onFullscreen,
                            modifier = Modifier.npTouchTarget(34.dp),
                        ) {
                            Icon(
                                imageVector = NpIcon.Expand,
                                contentDescription = "全屏播放",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
