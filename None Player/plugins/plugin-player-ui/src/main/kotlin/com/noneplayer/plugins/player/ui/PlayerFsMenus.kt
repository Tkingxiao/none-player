package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.npClickable
import com.noneplayer.core.ui.npToggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.noneplayer.core.data.SUBTITLE_COLOR_OPTIONS
import com.noneplayer.core.data.SUBTITLE_DELAY_STEPS_MS
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MAX
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MIN
import com.noneplayer.core.data.SUBTITLE_SCALE_MAX
import com.noneplayer.core.data.SUBTITLE_SCALE_MIN
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MAX
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MIN
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMediaPalette
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpStableWidth
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.kernel.api.NpChapter
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.ui.api.PlaybackMenuContributor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏形态的选项菜单体系（本轮 E80 收口）。
 *
 * 替代原先的三种"占屏"形态：全高右抽屉（fs-panel）、底部弹层（NpSheet）、
 * 横向倍速选择条。全屏下所有选项一律是**锚定触发按钮的紧凑纵向下拉列表**：
 * 上下拉开、不遮进度条、不把画面盖成半屏；菜单内用「推进-返回」两页切换
 * （drill-in），点外部/返回键即收。
 *
 * 材质沿用播放器覆盖层的暗色语言（黑玻璃 + 白字 + accent 选中），
 * 与底栏/顶栏同一条视觉基线——不再出现浅色主题下"白面板压在视频上"的割裂。
 */

/** 菜单页：MAIN 是 ⋯ 的一级页，其余为子页（同一锚点内切换，带返回行） */
enum class FsMenuPage { MAIN, SPEED, SUBTITLE, AUDIO, PICTURE, CHAPTERS, EPISODES }

/**
 * 下拉锚点：触发控件在窗口中的边界 + 展开方向。
 *
 * [preferAbove] = true 时向**上**展开（底栏的倍速/字幕钮），否则向下（顶栏的 ⋯/选集）。
 * [aboveLimitRect] 给向上展开加一条"到此为止"的参照（传进度条所在行的矩形）：
 * 底栏按钮上方紧挨着进度条，菜单若贴着按钮顶展开会遮住进度条的右段——
 * 用户实报的"选项盖住进度条"在底栏侧的同型问题，这里以参照矩形统一收口。
 */
data class FsMenuAnchor(
    val rect: Rect,
    val alignEnd: Boolean = true,
    val preferAbove: Boolean = false,
    val aboveLimitRect: Rect? = null,
)

/**
 * 菜单容器：暗色玻璃 + 锚点定位 + 纵向滚动（超出可用高度时内部滚动）
 *
 * [content] 收到的 `dismiss` 是**带动画的收起**：菜单里的每一项都该走它而不是直接
 * 让父级置空页面——父级一置空 Popup 就当场卸载，退场动画没有机会播。
 */
@Composable
fun FsDropdown(
    anchor: FsMenuAnchor,
    onDismiss: () -> Unit,
    /**
     * 当前子页；过渡中的每个页面由 AnimatedContent 的目标状态独立渲染。
     *
     * 传入后，MAIN → 倍速/字幕/音轨…这类 drill-in 切换会走一段轻量过场，
     * 而不是 `when(page)` 直接换内容。此前面板整体有入场/退场动画，
     * 唯独页与页之间是硬切，用户点"倍速"时内容瞬间跳变。
     *
     * 方向按**子页深度**推断：进子页自右来、回主页自左来；
     * 同级互换沿用进入子页的方向。
     * 遵守项目既有管道：位移受 [npDisplacementAllowed] 门控，轻量档/Reduce Motion
     * 自动退化成短淡入淡出；时长走 `Field`(150) 与容器同一条速度。
     */
    page: FsMenuPage = FsMenuPage.MAIN,
    content: @Composable ColumnScope.(page: FsMenuPage, dismiss: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val gap = 10.dp
    val margin = 12.dp
    val gapPx = with(density) { gap.roundToPx() }
    val marginPx = with(density) { margin.roundToPx() }
    val geometry = with(density) {
        fsMenuGeometry(
            window = IntSize(screenWidth.roundToPx(), screenHeight.roundToPx()),
            anchor = anchor.rect.toMenuIntRect(),
            aboveLimit = anchor.aboveLimitRect?.top?.roundToInt(),
            preferAbove = anchor.preferAbove,
            preferredWidth = 284.dp.roundToPx(),
            minimumRowHeight = 48.dp.roundToPx(),
            gap = gapPx,
            margin = marginPx,
        )
    }
    val maxHeight = with(density) { geometry.maxHeight.toDp() }
    val menuWidth = with(density) { geometry.width.toDp() }

    // M21：锚点在原点（顶栏⋯在上沿、底栏钮在下沿），缩放两个轴一起走才像"从按钮长出来"，
    // 只缩 Y 会读成面板被压扁。轻量档/Reduce Motion 把缩放归零 → 纯淡入淡出。
    // 用户实报"多功能选项的出现动画看不见"——5% 缩放 + 150ms 淡入读起来是"啪地弹出"；
    // 入场放大到 10% + 180ms（Panel 档）给出可读的"从按钮长出来"，退场保持 150ms 更快离开。
    val scaleAllowed = npDisplacementAllowed()
    val scaleFrom = if (scaleAllowed) 0.90f else 1f
    val exitMs = npDuration(Motion.Field)
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    // 退场由容器撑着：先把 visible 落下、动画跑完才回调真正的 onDismiss。
    var visible by remember { mutableStateOf(false) }
    var exitJob by remember { mutableStateOf<Job?>(null) }
    // A27（2026-10-19 订正）：菜单自己接管键盘焦点与按键。
    // 菜单是独立 Popup，焦点进不到页面根节点——此前页面级 onPreviewKeyEvent 的 Esc
    // 在菜单打开时收不到，只能点外部关闭；方向键也够不到菜单项。
    // 这里在打开时把焦点送进菜单第一项（由内容里的可聚焦行自然承担方向遍历与确认），
    // 容器自己只兜底 Esc，并处理"菜单里没有任何可聚焦项"的退化情形。
    // 注意：真正请求焦点的 LaunchedEffect 必须写在 Popup 内容的子组合里（FsMenuEntryFocus）：
    // Popup 内容有自己的 ComposeView / FocusOwner，LocalFocusManager 也随之不同，
    // 在页面组合里取到的 manager 操作的是页面窗口，moveFocus 会静默落空。
    val entryFocus = remember { FocusRequester() }
    // 换了锚点 = 重新开了一次菜单（包括"退场没跑完又点开别处"）：取消待卸载并重新入场。
    // 页内 drill-in 不换锚点，所以推进/返回不会重播入场。
    LaunchedEffect(anchor, page) {
        exitJob?.cancel()
        visible = true
    }
    val requestDismiss: () -> Unit = {
        if (visible) {
            visible = false
            exitJob = scope.launch {
                delay(exitMs.toLong())
                latestDismiss()
            }
        }
    }
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else scaleFrom,
        animationSpec = if (visible) {
            npMotion<Float>(Motion.Panel, easing = NpEasing.Snappy)
        } else {
            npMotion<Float>(Motion.Field, easing = NpEasing.Snappy)
        },
        label = "fsMenuScale",
    )
    val layerAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            npMotion<Float>(Motion.Panel, easing = NpEasing.Snappy)
        } else {
            npMotion<Float>(Motion.Field, easing = NpEasing.Snappy)
        },
        label = "fsMenuAlpha",
    )

    Popup(
        popupPositionProvider = remember(anchor, geometry.preferAbove, gapPx, marginPx) {
            FsMenuPositionProvider(
                anchor = anchor,
                preferAbove = geometry.preferAbove,
                gapPx = gapPx,
                marginPx = marginPx,
            )
        },
        onDismissRequest = requestDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // Each animated page owns its content and scroll position through the transition.
        // 且**不复用容器的入场缩放**——容器那一段是"面板整体从按钮长出来"，
        // 子页切换是"内容往里推进一层"，两者语义不同，叠在一起会读成面板又开了一次。
        val shiftAllowed = npDisplacementAllowed()
        val pageShiftPx = with(density) { 12.dp.roundToPx() }
        val pageMs = npDuration(Motion.Field)
        Column(
            modifier = Modifier
                // A27：菜单容器自己收键盘（详见上方 LaunchedEffect 的注释）。
                // canFocus=false + focusTarget：容器本身不是焦点停靠点，只作为焦点事件的
                // 观察者——焦点在行上时按 Esc，事件仍会冒泡到这里的 onPreviewKeyEvent。
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        // Esc 由菜单自己吃：Popup 抢走焦点后页面根节点收不到 Esc。
                        // 走 requestDismiss 而不是 latestDismiss，保留退场动画。
                        requestDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusProperties { canFocus = false }
                .focusTarget()
                .width(menuWidth)
                .heightIn(max = maxHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, if (geometry.preferAbove) 1f else 0f)
                    scaleX = entrance
                    scaleY = entrance
                    this.alpha = layerAlpha
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.88f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(vertical = 6.dp),
        ) {
            // A27：在 Popup 自己的组合里把焦点送进菜单（详见 FsMenuEntryFocus 的注释）。
            FsMenuEntryFocus(entryFocus, anchor, page)
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val shift = if (shiftAllowed) {
                        if (targetState == FsMenuPage.MAIN) -pageShiftPx else pageShiftPx
                    } else {
                        0
                    }
                    (fadeIn(animationSpec = tween(pageMs, easing = NpEasing.Silk)) +
                        slideInHorizontally(
                            animationSpec = tween(pageMs, easing = NpEasing.Silk),
                        ) { shift }) togetherWith
                        (fadeOut(animationSpec = tween(pageMs, easing = NpEasing.SilkExit)) +
                            slideOutHorizontally(
                                animationSpec = tween(pageMs, easing = NpEasing.SilkExit),
                            ) { -shift })
                },
                label = "fsMenuPage",
            ) { targetPage ->
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    content = {
                        // A27：零尺寸焦点锚点。打开菜单时先让它持焦，再 moveFocus(Down)
                        // 进入第一个可聚焦行；没有可聚焦行时它兜底接住 Esc/返回。
                        Box(
                            Modifier
                                .size(1.dp)
                                .focusRequester(entryFocus)
                                .focusable(),
                        )
                        content(targetPage, requestDismiss)
                    },
                )
            }
        }
    }
}

/**
 * A27：菜单入场焦点。
 *
 * 必须作为 Popup 内容的子组合调用：每个 Popup 内容有自己的 ComposeView 与 FocusOwner
 * （`AbstractComposeView.setContent` 会给 Popup 内容单独 `ProvideAndroidCompositionLocals`），
 * 因此只有在 Popup 内容里取到的 [LocalFocusManager] 才操作菜单自己的焦点系统；
 * 在页面组合里取到的 manager 指向页面窗口，`moveFocus` 会静默落空——菜单看起来打开了，
 * 但方向键没反应（实测）。
 *
 * 入场策略是"零尺寸锚点先持焦 → 方向键下移进第一行"：Compose 1.7.6 的
 * `requestFocus()` 下降搜索把 Enter 折算成 Right 方向做几何比较，纵向排列、
 * 与容器同宽的行在右方向上不构成候选，直接对容器 requestFocus 会落空。
 * 纯说明型内容没有可聚焦行时焦点停在锚点上，Esc/返回依旧可用。
 */
@Composable
private fun FsMenuEntryFocus(entryFocus: FocusRequester, anchor: FsMenuAnchor, page: FsMenuPage) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(anchor, page) {
        withFrameNanos { }
        runCatching { entryFocus.requestFocus() }
        withFrameNanos { }
        runCatching { focusManager.moveFocus(FocusDirection.Down) }
    }
}

private class FsMenuPositionProvider(
    private val anchor: FsMenuAnchor,
    private val preferAbove: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        return fsMenuPosition(
            window = windowSize,
            content = popupContentSize,
            anchor = anchor.rect.toMenuIntRect(),
            aboveLimit = anchor.aboveLimitRect?.top?.roundToInt(),
            alignEnd = anchor.alignEnd,
            preferAbove = preferAbove,
            gap = gapPx,
            margin = marginPx,
        )
    }
}

private fun Rect.toMenuIntRect() =
    IntRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

// ---- 行/标题件（暗色语言）----

/** 菜单头：标题 + 关闭（一级页用） */
@Composable
fun FsMenuHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = NpTypography.titleSmall,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        NpIconButton(
            icon = NpIcon.Close,
            contentDescription = "关闭菜单",
            onClick = onClose,
            size = 30.dp,
            iconSize = 15.dp,
            tint = Color.White,
        )
    }
}

/** 子页头：返回 + 页名（drill-in 的出口） */
@Composable
fun FsMenuBackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 命中 44dp 红线（§14 #13）：页头也是可点项（点它=返回），只靠 9dp 上下内边距是 ~42dp。
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            // A27：显式 focusable——菜单是独立 Popup，方向键 / D-pad 要能逐行进焦。
            // 触摸模式下 clickable 自带的可聚焦性（focusableInNonTouchMode）不生效，
            // 显式声明后触摸模式也能被焦点系统命中（键盘模式行为不变）。
            .onKeyEvent { event ->
                if (event.isRowConfirmUp()) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(onClick = onBack)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = NpIcon.ArrowLeft,
            contentDescription = "返回",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = NpTypography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 危险项在**画面之上**的颜色。
 *
 * 不能用 `ink.danger`：整层下拉菜单永远压在视频上（深色 scrim + 白字），和应用主题无关，
 * 亮色主题下 `ink.danger`（.55 明度）在白字旁边几乎读不出来。
 */
private val OnMediaDanger = NpMediaPalette.onMediaDanger

/**
 * A27：菜单行的「确认」键判定（Enter / 小键盘 Enter / D-pad 中心键 / 空格的抬起）。
 *
 * 菜单是独立 Popup，行用显式 `focusable()` 保证触摸模式下也能被方向键 / D-pad 命中；
 * 这种模式下 `clickable` 自带的键盘激活节点不在焦点链上，所以由行自己处理确认键。
 * 键盘模式下 `clickable` 会先消费同一个事件，不会双触发。
 */
private fun KeyEvent.isRowConfirmUp(): Boolean =
    type == KeyEventType.KeyUp &&
        (
            key == Key.Enter || key == Key.NumPadEnter ||
                key == Key.DirectionCenter || key == Key.Spacebar
            )

/**
 * 菜单行：图标（可选）+ 文字 + 右侧值/勾选/箭头。
 *
 * [check] 非 null 时展示勾选位（未选中也占位，避免选中瞬间文字被挤动）；
 * [chevron] 表示"点进去还有一层"（drill-in 子页）。
 */
@Composable
fun FsMenuRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    value: String? = null,
    active: Boolean = false,
    check: Boolean? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> OnMediaDanger
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 命中 44dp 红线（§14 #13）：只靠 9dp 上下内边距撑出来是 ~38dp。
            // 面板本身限高 72% 且内部可滚（见本文件菜单容器），行高加 6dp 不会顶出屏幕。
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            // A27：显式 focusable（原因同 FsMenuBackRow）——菜单行可被方向键 / D-pad 命中。
            .onKeyEvent { event ->
                if (event.isRowConfirmUp()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = NpTypography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = NpMonoSmall,
                color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        if (check != null) {
            Icon(
                imageVector = NpIcon.Check,
                contentDescription = null,
                tint = if (check) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.size(16.dp),
            )
        }
        if (chevron) {
            Icon(
                imageVector = NpIcon.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 开关行（暗色菜单语言）：左侧文字 + 右侧 NpSwitch；整行可点（等同拨动开关）。
 * 与 [FsMenuRow] 同 44dp 命中下界，视觉上并入同一列菜单行。
 */
@Composable
fun FsMenuSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            // A27：显式 focusable（原因同 FsMenuBackRow）。
            .onKeyEvent { event ->
                if (event.isRowConfirmUp()) {
                    onCheckedChange(!checked)
                    true
                } else {
                    false
                }
            }
            .focusable()
            // A27/REV-14：整行就是一个开关（On/Off + Role.Switch），读屏只报一个节点。
            // 之前是 clickable(Role.Button) + 里层 NpSwitch(toggleable)，会读成两个控件。
            .npToggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = NpTypography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 语义已挂在整行上：里层开关只负责画图形，清空语义避免读屏重复报两个开关。
        NpSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** 分区小标题（mono 大写、55% 白） */
@Composable
fun FsMenuTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = NpMonoSmall.copy(fontWeight = FontWeight.Medium),
        color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 说明文字（已挂载字幕名 / 空态） */
@Composable
fun FsMenuCaption(text: String) {
    Text(
        text = text,
        style = NpMonoSmall,
        color = Color.White.copy(alpha = 0.5f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * 菜单滑杆的轨道：3dp 圆头线 + 未填底段，与进度条 `NpSeekBar` 同一套语言。
 *
 * 不用 M3 的默认轨道——material3 1.3.1 的 `SliderDefaults.Track` 是 **16dp 粗**，
 * 比这里 12dp 的拇指还厚，同色叠上去拇指整个被轨道吞掉（真机实报「音量条重叠」：
 * 音量 0 那一行只看得见轨道左端凸出的一点，读不出拇指）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun NpMenuSliderTrack(
    state: SliderState,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val span = state.valueRange.endInclusive - state.valueRange.start
    val fraction = if (span <= 0f) {
        0f
    } else {
        ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
    }
    Canvas(modifier = modifier.fillMaxWidth().height(NpDimens.trackLine)) {
        val h = size.height
        val radius = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(color = inactiveColor, size = Size(size.width, h), cornerRadius = radius)
        drawRoundRect(
            color = activeColor,
            size = Size(size.width * fraction, h),
            cornerRadius = radius,
        )
    }
}

/**
 * 菜单滑杆的拇指：12dp 圆点，与进度条静止拇指同尺寸（[NpDimens.seekThumbIdle]）。
 *
 * 外面那层不是多余的：M3 把拇指槽位钉在 16dp（`requiredSizeIn(minHeight = TrackHeight)`），
 * 而槽位给内容的约束是 `minHeight = 0` + 高度可无限——圆点直接挂上去是**顶对齐**的，
 * 真机上比轨道中心高 5px（2dp），看着就像没对齐。撑满槽位再居中，圆点才压在轨道线上。
 */
@Composable
internal fun NpMenuSliderThumb(color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier.heightIn(min = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(NpDimens.seekThumbIdle)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * 滑杆行（亮度/音量、字幕字号共用）：图标 + 标签 + 数值 + 滑杆。
 *
 * [dark] 取面板语境：`true` = 永远压在画面上的 fs-panel/下拉菜单（黑底白字，
 * 与应用主题无关）；`false` = 浅色 sheet，字色与未填轨道走 `ink`，否则浅底上的白标签
 * 整片消失（此前这里就是两套实现：sheet 那套漏配色、轨道又比拇指厚）。
 *
 * 数值按 `100` 定宽并右对齐：0→9→10→100 的位数档变化不再回头挤压标签。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FsMenuSlider(
    label: String,
    icon: ImageVector,
    /** A6：传 [State]——竖滑/拖滑杆时只重组这一行，菜单里的 tile 列表不跟着重建。 */
    value: State<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    val current = value.value
    val ink = LocalNpInk.current
    val labelColor = if (dark) Color.White else ink.fg
    val valueColor = if (dark) Color.White.copy(alpha = 0.6f) else ink.muted
    val inactiveColor = if (dark) Color.White.copy(alpha = 0.24f) else ink.border
    val activeColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = valueColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = NpTypography.bodyMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NpStableWidth(
                reserve = "100",
                style = NpMonoSmall,
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "$current",
                    style = NpMonoSmall,
                    color = valueColor,
                    maxLines = 1,
                )
            }
        }
        Slider(
            value = current.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            thumb = { NpMenuSliderThumb(activeColor) },
            track = { state -> NpMenuSliderTrack(state, activeColor, inactiveColor) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 循环模式四档（图标横排，与设计稿 .fs-panel 的顺序一致） */
@Composable
fun FsLoopChips(loopMode: NpLoopMode, onPick: (NpLoopMode) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            NpLoopMode.OFF to NpIcon.ListView,
            NpLoopMode.ONE to NpIcon.Rotate,
            NpLoopMode.SHUFFLE to NpIcon.Sort,
            NpLoopMode.ALL to NpIcon.Sound,
        ).forEach { (mode, icon) ->
            val on = loopMode == mode
            // 命中层 [npTouchTarget] 把槽位撑到 44dp（§14 #13），视觉层仍是设计稿 `.fs-opt`
            // 的 38dp 方块——底色/描边只画视觉层，否则一排四枚会连成一条 44dp 的粗带。
            Box(
                modifier = Modifier
                    .npTouchTarget(38.dp)
                    .clickable { onPick(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f),
                        )
                        .border(
                            1.dp,
                            if (on) Color.Transparent else Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = loopLabel(mode),
                        tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---- 各页内容 ----

/**
 * ⋯ 一级页（原 fs-panel 的全部条目，重排为紧凑列表）。
 *
 * 需要二级选择的条目（倍速/字幕/音轨/画面/章节/选集）以 drill-in 打开子页，
 * 其余为直接动作行。
 */
@Composable
fun FsMainMenuContent(
    loopMode: NpLoopMode,
    brightnessPercent: State<Int>,
    volumePercent: State<Int>,
    favorited: Boolean,
    hasChapters: Boolean,
    sleepLabel: String,
    sleepActive: Boolean,
    onClose: () -> Unit,
    onPage: (FsMenuPage) -> Unit,
    onLoopMode: (NpLoopMode) -> Unit,
    onBrightness: (Int) -> Unit,
    onVolume: (Int) -> Unit,
    onPip: () -> Unit,
    onFavorite: () -> Unit,
    onScreenshot: () -> Unit,
    onSleep: () -> Unit,
    /**
     * D3 半锁行（「只护画面」轻一档）：只在有画面区手势的形态渲染——播放页两个形态
     * 传 true；右栏内嵌播放器的画面区没有手势（只有点按），半锁在那里无从生效，
     * 缺省不渲染，不摆一个点了没反应的假入口。
     */
    showHalfLock: Boolean = false,
    halfLocked: Boolean = false,
    onHalfLock: () -> Unit = {},
    /** 插件贡献的选项行（[PlaybackMenuContributor]，如弹幕开关）；渲染在列表尾部 */
    contributors: List<PlaybackMenuContributor> = emptyList(),
    contributorMedia: MediaItem? = null,
    contributorPlayer: VideoPlayer? = null,
) {
    FsMenuHeader(title = "播放选项", onClose = onClose)
    FsMenuTitle("循环模式")
    FsLoopChips(loopMode = loopMode, onPick = onLoopMode)
    FsMenuTitle("画面与声音")
    FsMenuSlider(
        label = "亮度",
        icon = NpIcon.Sun,
        value = brightnessPercent,
        onValueChange = onBrightness,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
    FsMenuSlider(
        label = "音量",
        icon = NpIcon.Sound,
        value = volumePercent,
        onValueChange = onVolume,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
    FsMenuTitle("更多")
    FsMenuRow(label = "音轨", icon = NpIcon.Sound, chevron = true, onClick = { onPage(FsMenuPage.AUDIO) })
    FsMenuRow(label = "画面", icon = NpIcon.AspectRatio, chevron = true, onClick = { onPage(FsMenuPage.PICTURE) })
    if (hasChapters) {
        FsMenuRow(label = "章节", icon = NpIcon.ListView, chevron = true, onClick = { onPage(FsMenuPage.CHAPTERS) })
    }
    FsMenuRow(label = "画中画", icon = NpIcon.Pip, onClick = onPip)
    if (showHalfLock) {
        FsMenuRow(
            label = if (halfLocked) "解除半锁" else "半锁（只护画面）",
            icon = NpIcon.Lock,
            active = halfLocked,
            onClick = onHalfLock,
        )
    }
    FsMenuRow(label = "截图", icon = NpIcon.Screenshot, onClick = onScreenshot)
    FsMenuRow(
        label = sleepLabel,
        icon = NpIcon.SleepTimer,
        active = sleepActive,
        onClick = onSleep,
    )
    FsMenuRow(
        label = if (favorited) "已收藏" else "收藏",
        icon = NpIcon.Heart,
        active = favorited,
        onClick = onFavorite,
    )
    contributors.sortedBy { it.order }.forEach { contributor ->
        contributor.RenderRows(
            dark = true,
            media = contributorMedia,
            player = contributorPlayer,
        )
    }
}

/** 微调步进钮（L14）：与档位行分开——它改的是当前值的 ±0.05，不是跳档。
 * 2026-10-12 用户裁定"两个白色块变小/变窄"：44dp 方块收成窄条；
 * A27（2026-10-15 订正）收窄只动宽度，**命中高回到 44dp 红线**（窄高 34dp 实测按不准）。 */
@Composable
private fun FsMenuStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.button))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .npClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = NpMono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 倍速页（2026-10-12 用户裁定重排）。
 *
 *  - 档位从"十档纵向列表"收成**六档两行 chips**（0.25 / 0.5 / 1 / 1.5 / 2 / 3）——
 *    原列表把整个菜单占满，用户实报"倍速列表占比过大"；
 *  - 微调行两侧步进钮收窄，**当前速度居中**（原布局里值紧贴左钮，读起来偏左）。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FsSpeedMenuContent(
    speed: Float,
    onPick: (Float) -> Unit,
    onNudge: (Float) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "播放倍速", onBack = onBack)
    // 微调行（L14）：档位表覆盖不到「就要 1.1x」这类手感；两步 = ±0.05，长按不加速
    // （长按加速与 §4.3 的长按 3× boost 语义冲突，这里不做第二套）。
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = NpSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(NpSpace.sm, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FsMenuStepButton(label = "-0.05", onClick = { onNudge(-SPEED_FINE_STEP) })
        Text(
            text = speedLabel(speed),
            style = NpMono,
            textAlign = TextAlign.Center,
            color = if (kotlin.math.abs(speed - 1f) < 0.001f) {
                Color.White
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        FsMenuStepButton(label = "+0.05", onClick = { onNudge(SPEED_FINE_STEP) })
    }
    // 六档 chips：两行三列，占高约为原十档列表的三分之一。
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val columns = if (LocalDensity.current.fontScale > 1.3f) 2 else 3
        SPEED_PRESETS.chunked(columns).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPresets.forEach { preset ->
                    val on = kotlin.math.abs(preset - speed) < 0.001f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f),
                            )
                            .border(
                                1.dp,
                                if (on) Color.Transparent else Color.White.copy(alpha = 0.18f),
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { onPick(preset) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = speedLabel(preset),
                            style = NpMono,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                        )
                    }
                }
            }
        }
    }
    FsMenuCaption("变速保持音调")
}

/** 字幕页：开关/内嵌轨/外挂挂载/偏移 */
@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun FsSubtitleMenuContent(
    subtitleOn: Boolean,
    subtitleLoaded: Boolean,
    savedSubtitleName: String?,
    textTracks: List<NpTrack>,
    offsetMs: Long,
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
    onSetEnabled: (Boolean) -> Unit,
    onPickTrack: (NpTrack) -> Unit,
    onMountExternal: () -> Unit,
    onOffset: (Long) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "字幕", onBack = onBack)
    // 开关行（2026-10-12 用户裁定："字幕选项里做一个滑块开关"；旧「关闭字幕」行点击
    // 只走引擎卸载，实报"点击关闭无效"）。开关同时驱动**渲染总开关**
    // （settings.subtitleEnabled，外挂/内嵌/PGS 一律受它门控）与引擎侧卸载/复原——
    // 与竖屏「字幕设置」面板的开关同一语义、同一写路径。
    FsMenuSwitchRow(
        label = "开启字幕",
        checked = subtitleOn,
        onCheckedChange = onSetEnabled,
    )
    FsMenuTitle("字幕颜色")
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(argb.toInt()))
                        .then(
                            if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier,
                        ),
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "文字字幕大小",
            style = NpTypography.bodyMedium,
            color = Color.White,
        )
        Slider(
            value = scale,
            onValueChange = onScale,
            valueRange = SUBTITLE_SCALE_MIN..SUBTITLE_SCALE_MAX,
            thumb = { NpMenuSliderThumb() },
            track = { state ->
                NpMenuSliderTrack(
                    state = state,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = Color.White.copy(alpha = 0.24f),
                )
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = "%.1f".format(scale),
            style = NpMonoSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
    FsMenuSwitchRow(
        label = "文字字幕用斜体",
        checked = italic,
        onCheckedChange = onItalic,
    )
    // A12：竖直位置 / 背景条 / 描边 / 最大行数
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "字幕垂直位置",
            style = NpTypography.bodyMedium,
            color = Color.White,
        )
        Slider(
            value = verticalPosition,
            onValueChange = onVerticalPosition,
            valueRange = SUBTITLE_VERTICAL_MIN..SUBTITLE_VERTICAL_MAX,
            thumb = { NpMenuSliderThumb() },
            track = { state ->
                NpMenuSliderTrack(
                    state = state,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = Color.White.copy(alpha = 0.24f),
                )
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = when {
                verticalPosition <= 0.01f -> "顶部"
                verticalPosition >= 0.99f -> "底部"
                else -> "${(verticalPosition * 100).roundToInt()}%"
            },
            style = NpMonoSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
    FsMenuSwitchRow(
        label = "字幕背景条",
        checked = background,
        onCheckedChange = onBackground,
    )
    FsMenuSwitchRow(
        label = "字幕描边",
        checked = outline,
        onCheckedChange = onOutline,
    )
    FsMenuTitle("字幕最大行数")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (SUBTITLE_MAX_LINES_MIN..SUBTITLE_MAX_LINES_MAX).forEach { lines ->
            val selected = lines == maxLines
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.10f),
                    )
                    .clickable { onMaxLines(lines) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$lines",
                    style = NpMonoSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    maxLines = 1,
                )
            }
        }
    }
    FsMenuRow(
        label = "挂载外挂字幕…",
        check = subtitleLoaded,
        onClick = onMountExternal,
    )
    if (savedSubtitleName != null) {
        FsMenuCaption("已挂载：$savedSubtitleName")
    }
    // 这条列表里既可能有内嵌轨、也可能有刚挂上的外挂轨（media3 把它们平铺进同一条
    // 文本轨组），标题只说"字幕轨"，外挂那行由引擎直接标成「外挂字幕」（B4 真机实报）。
    FsMenuTitle("字幕轨")
    if (textTracks.isEmpty()) {
        FsMenuCaption("此视频没有内嵌字幕轨")
    } else {
        textTracks.forEach { track ->
            FsMenuRow(
                label = track.label,
                active = track.selected,
                check = track.selected,
                onClick = { onPickTrack(track) },
            )
        }
    }
    FsMenuTitle("字幕延时")
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "当前偏移",
            style = NpTypography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (offsetMs == 0L) "0ms" else (if (offsetMs > 0) "+" else "") + "${offsetMs}ms",
            style = NpMonoSmall,
            color = if (offsetMs == 0L) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
        )
    }
    // E131：与竖屏「字幕设置」面板统一成**绝对档位**（−1.0s / −0.1s / 0 / +0.1s / +1.0s），
    // 两处语义一致；旧的 ±50/±250ms 累加式会让人算不清当前值。选中档高亮。
    FlowRow(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SUBTITLE_DELAY_STEPS_MS.forEach { step ->
            val selected = step == offsetMs
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White.copy(alpha = 0.08f)
                        },
                    )
                    .clickable { onOffset(step) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = subtitleDelayLabel(step),
                    style = NpMonoSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.White
                    },
                )
            }
        }
    }
}

/** 音轨页：内嵌音频轨列表 */
@Composable
fun FsAudioMenuContent(
    tracks: List<NpTrack>,
    onPick: (NpTrack) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "音轨", onBack = onBack)
    if (tracks.isEmpty()) {
        FsMenuCaption("此视频没有内嵌音轨")
    } else {
        tracks.forEach { track ->
            FsMenuRow(
                label = track.label,
                active = track.selected,
                check = track.selected,
                onClick = { onPick(track) },
            )
        }
    }
}

/** 画面页：比例五档 + 变换两项（勾选式，与菜单语言一致） */
@Composable
fun FsPictureMenuContent(
    currentMode: VideoScaleMode,
    mirrored: Boolean,
    rotation: Int,
    onMode: (VideoScaleMode) -> Unit,
    onMirror: (Boolean) -> Unit,
    onRotate: (Int) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "画面", onBack = onBack)
    FsMenuTitle("画面比例")
    VideoScaleMode.entries.forEach { mode ->
        val on = mode == currentMode
        FsMenuRow(
            label = mode.label,
            active = on,
            check = on,
            onClick = { onMode(mode) },
        )
    }
    FsMenuTitle("画面变换")
    FsMenuRow(
        label = "左右镜像",
        icon = NpIcon.Mirror,
        check = mirrored,
        active = mirrored,
        onClick = { onMirror(!mirrored) },
    )
    FsMenuRow(
        label = "旋转 180°",
        check = rotation == 180,
        active = rotation == 180,
        onClick = { onRotate(if (rotation == 180) 0 else 180) },
    )
}

/**
 * 章节页：列表 + 当前章标记。
 *
 * §9.2：位置以 [State] 传入并折成派生的"当前章下标"——进度上报约 250ms 一次，
 * 直接逐行比对会让整张下拉菜单跟着重画（它是浮在播放画面上的重组热点）。
 */
@Composable
fun FsChaptersMenuContent(
    chapters: List<NpChapter>,
    positionMs: State<Long>,
    onSelect: (NpChapter) -> Unit,
    onBack: () -> Unit,
) {
    val activeIndex = remember(chapters) {
        derivedStateOf {
            val position = positionMs.value
            chapters.indexOfFirst { chapter ->
                position in chapter.startMs until
                    (chapter.endMs.takeIf { it > chapter.startMs } ?: Long.MAX_VALUE)
            }
        }
    }
    FsMenuBackRow(title = "章节（${chapters.size}）", onBack = onBack)
    chapters.forEachIndexed { index, chapter ->
        val current = index == activeIndex.value
        FsMenuRow(
            label = "${index + 1}. ${chapter.title}",
            value = formatTimestamp(chapter.startMs),
            active = current,
            onClick = { onSelect(chapter) },
        )
    }
}

/** 选集页：同文件夹队列 + 当前项标记 */
@Composable
fun FsEpisodesMenuContent(
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    // A25：全屏选集改 LazyColumn。几百集时旧 Column 一次组合全部行；返回行固定，
    // 列表给一个有界高度在内部滚动，未进视口的行不组合。
    Column(modifier = Modifier.fillMaxWidth()) {
        FsMenuBackRow(title = "选集（${queue.size}）", onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
        ) {
            itemsIndexed(
                items = queue,
                key = { index, item -> "ep:${item.sourceId}:${item.id}:$index" },
            ) { index, item ->
                val current = index == currentIndex
                FsMenuRow(
                    label = "${index + 1}. " + item.title.ifBlank { item.uri.substringAfterLast('/') },
                    value = if (current) "播放中" else null,
                    active = current,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}
