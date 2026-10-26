package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpStableWidth
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.ui.NpMaskFade
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.kernel.ui.api.NpMorphKey
import com.noneplayer.kernel.ui.api.npMorphPart
import com.noneplayer.plugins.player.ui.controls.NpSeekBar
import com.noneplayer.plugins.player.ui.controls.StatusClockBattery
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
 * 排不下时按 [playerBarLayout] 的档位退让：先省掉总时长换回单行。
 * 2026-10-23 用户再裁定：**窄屏全屏也不拆行**，全屏路径一律 `preferSingleRow = true`；
 * 只有系统字号 > 1.3 或连"省掉总时长的一行"都放不下时才兜底拆行（极端窄窗）。
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

@OptIn(ExperimentalComposeUiApi::class) // A27：focusRestorer 在 Compose 1.7.6 仍标实验
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
    /** E235：章节起点（ms）——进度条画刻度并点按吸附，空表 = 无章节 */
    chapterStartsMs: List<Long> = emptyList(),
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
                // 白得的命中面积（轨道本身仍按 trackHeight 居中画，视觉不变）。
                // A27（2026-10-15 订正）：拆成两行那一路不再退回 18dp——NpSeekBar 自身夹取
                // 到 NpDimens.touchTarget，两路命中高都 ≥44dp（触控带变高只加透明命中层，
                // 轨道仍按 trackHeight 居中，视觉不变）。
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
                            chapterStartsMs = chapterStartsMs,
                            modifier = Modifier.fillMaxWidth().anchorReporter(onSeekBarAnchor),
                        )
                    }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // A27：底栏是一个焦点组，焦点在组内控件间停留、离开后回来时
                    // 还原到上次停留的那颗按钮（focusRestorer：记住组内持焦子节点）。
                    .focusRestorer()
                    .focusGroup()
                    .then(
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
                    chapterStartsMs = chapterStartsMs,
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
@OptIn(ExperimentalComposeUiApi::class) // A27：focusRestorer 在 Compose 1.7.6 仍标实验
@Composable
internal fun PlayerTopBar(
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
                    // A27：顶栏是独立焦点组——方向键/ Tab 进出顶栏时还原到上次停留的
                    // 那颗按钮（focusRestorer 记住组内持焦子节点），不会每次都回到返回键。
                    .focusRestorer()
                    .focusGroup()
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
 * 常驻 playerbar（设计稿 `.playerbar`）：玻璃条 + 返回 + 标题/mono 副标题。
 * **不随控制层自动隐藏**——播放中的返回出口永远在（03 审计 #1 的核心修复）。
 */
@Composable
internal fun PlayerBar(
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
