package com.noneplayer.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底部 Sheet 体系（设计稿 `.sheet / .sheet-scrim / .grab / .opt / .opt-tile`）。
 *
 * 不用 Material `ModalBottomSheet`：它的圆角/把手/遮罩/动画曲线都不可控，而设计稿
 * 对这套浮层有精确规格——r20 顶角 + 38×4 把手 + 82vh 上限 + `.32s cubic-bezier(.34,1.1,.5,1)`
 * 弹入。自绘的代价（进出动画状态机）换的是与设计稿逐像素对齐。
 */

/**
 * 底部弹层：遮罩 + 容器（surface、顶 r20、上边框、把手、可选标题）+ 内容槽。
 *
 * 内容超过 82% 屏高时内部滚动；点遮罩/系统返回键关闭（都带退场动画）。
 * [content] 的竖向滚动已由容器接管，调用方不要再套 `verticalScroll`。
 */
@Composable
fun NpSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = LocalNpInk.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 进出场状态机：进场后 visible=true；任何关闭路径先 visible=false，动画走完再回调
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // §5.5 下滑关闭：面板高与拖拽位移分开存——`progress` 只服务进出场，
    // 手势用独立的 Animatable，才能把抬手速度直接交给回弹弹簧（§7.3 速度接棒）。
    var panelHeightPx by remember { mutableFloatStateOf(0f) }
    val dragOffset = remember { Animatable(0f) }
    // 抬手收尾的 spec 读在组合期：`settle()` 是本函数里的局部函数，协程内调不到
    // @Composable 的 [npMotion]，所以在这里造好再被它捕获。E119 之前这里写的是
    // `silkSpring` + 裸令牌——进了弹簧、没进管道，三档倍率与 Reduce Motion
    // 对这条收尾完全无效（而它是 §14 #14 验收过的那只手）。
    val drawerSettleSpec: FiniteAnimationSpec<Float> = npMotion(Motion.Drawer)
    // §7.4 越界橡皮筋：原始拖拽量按 1:1 累加（负值 = 向上拉过头），呈现给动画的值
    // 只在负半轴过橡皮筋。分开的理由不是洁癖——硬夹紧时手指继续上划的那段位移会被
    // 整段丢掉，往回拖必须先补回丢掉的距离，读起来是"反向时先卡一下"。
    var rawDrag by remember { mutableFloatStateOf(0f) }
    val dismissVelocityPx = with(density) { 700.dp.toPx() }
    var dismissDragged by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }

    fun close() {
        // 拖拽关闭已在自己的动画里收尾：这时再落 visible 会让 progress 从 1 重新
        // 起跳、两条退场同时跑，onDismiss 也会被回调两次。
        if (visible && !dismissDragged) visible = false
    }

    // M11 detent 触觉（§7.5 `Segment`）：本 Sheet 只有一个可越的档位——30% 关闭判据线。
    // 越过它的那一帧响一次，退回线内重新武装；将来出现多停靠点版式，仍按"每越过
    // 一条线一次"接，而不是按位移比例连续响。
    val haptics = rememberNpHaptics()
    var detentArmed by remember { mutableStateOf(true) }
    fun trackDetent() {
        val past = dragOffset.value > panelHeightPx * 0.30f
        when {
            past && detentArmed -> {
                detentArmed = false
                haptics.performHardwareTick()
            }
            !past && !detentArmed -> detentArmed = true
        }
    }

    /**
     * 累加一次拖拽增量：界内（含向下关闭方向）1:1 跟手，向上过头走橡皮筋
     * （越走越沉、渐近但不越过面板高），抬手由 [settle] 弹回 0——**不允许停在界外**。
     */
    fun applyDrag(delta: Float) {
        rawDrag += delta
        val banded = if (rawDrag >= 0f) rawDrag else rubberband(rawDrag, panelHeightPx.coerceAtLeast(1f))
        scope.launch {
            dragOffset.snapTo(banded)
            trackDetent()
        }
    }

    /**
     * 抬手收尾：位移 > 面板高 30% 或向下速度 > 700dp/s 判关闭，否则回弹。
     * [velocity] 取**向下为正**的 px/s（§5.5 的两条判据都按这个方向写）。
     */
    fun settle(velocity: Float) {
        if (settling) return
        settling = true
        rawDrag = 0f
        val far = dragOffset.value > panelHeightPx * 0.30f
        if (far || velocity > dismissVelocityPx) {
            // 关闭走拖拽自己的动画：`close()` 会从 progress 重新起跳，
            // 半路的位移被清零，读起来就是"弹一下才走"。
            dismissDragged = true
            scope.launch {
                dragOffset.animateTo(panelHeightPx, drawerSettleSpec, initialVelocity = velocity)
                onDismiss()
            }
        } else {
            scope.launch {
                dragOffset.animateTo(0f, drawerSettleSpec, initialVelocity = velocity)
                settling = false
            }
        }
    }
    // `if (reduceMotion) 1` 那个裸 1 是"跳过动画"的哨兵，读起来像漏了单位：
    // 明确走 ReduceFade(120)，同时把 Sheet 的 320 收成档位换算后的值。
    val sheetDuration = if (reduceMotion) Motion.ReduceFade else npDuration(Motion.Sheet)
    LaunchedEffect(visible) {
        if (!visible) {
            delay(sheetDuration.toLong() + 32L)
            onDismiss()
        }
    }
    // §5.5 下滑关闭的可达性：面板体的竖向手势会被内容区的 `verticalScroll` 先吃掉，
    // 只有 38×4 的把手拖得动——长列表 Sheet 等于关不掉。这里把内容**吃不下**的向下
    // 滚动量（已到顶）接过来变成面板位移，抬手再走同一套 `settle` 判据。
    val contentDragConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 实测 `available.y` 与手指同向（向下为正），且已扣掉子级吃掉的部分
                val down = available.y
                if (down > 0f && !dismissDragged) {
                    applyDrag(down)
                    return Offset(0f, down)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val down = available.y
                if (dragOffset.value > 0f && down > 0f) {
                    settle(down)
                    return Velocity(0f, down)
                }
                return Velocity.Zero
            }
        }
    }
    // 慢速抬手（速度≈0）时子级根本不起 fling，`onPostFling` 不会回调——面板会停在半路。
    // 所以在抬手那一刻补一次兜底收尾（`settling` 保证一次手势只收一次尾）。
    val releaseFallback = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var event: PointerEvent
            do {
                event = awaitPointerEvent(PointerEventPass.Main)
            } while (event.changes.any { it.id == down.id && it.pressed })
            if (dragOffset.value != 0f) settle(0f)
        }
    }
    BackHandler(enabled = true) { close() }

    val progress = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = sheetDuration, easing = NpEasing.Sheet),
        label = "npSheetProgress",
    )

    // E100：Sheet 必须长在**窗口层**，不能长在调用方的布局流里。它的一个宿主是
    // LazyVerticalGrid 的 item{}（媒体库「控制行」的视图面板），惰性项拿到的是**无限高度**约束，
    // fillMaxSize() 顶不住 → 内层 verticalScroll 直接抛
    // "Vertically scrollable component was measured with an infinity maximum height constraints"
    // 整应用闪退。Popup 的约束来自窗口，任何宿主都成立。**必须 focusable**：
    // 非聚焦弹窗不进无障碍树（uiautomator dump 里整块内容消失，TalkBack 同样读不到）。
    // 代价是聚焦的 PopupWindow 会**自己吞掉系统返回键**，页面里的 BackHandler 收不到——
    // 所以退出必须走 onDismissRequest（它同样承接"点到弹窗外"，本弹窗铺满窗口故不会触发）。
    Popup(
        onDismissRequest = { close() },
        properties = PopupProperties(focusable = true),
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // 设计稿 .sheet{max-height:82vh}——在 BoxWithConstraints 作用域先固化，
            // 内层 lambda 里拿不到这个隐式接收器
            val sheetMaxHeight = maxHeight * 0.82f
            // 遮罩（设计稿 --scrim 62%，fade 跟随 sheet 进度，并随下滑同步变淡）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 进度读在绘制层 lambda 里：sheet 的 320ms 进出场只让遮罩层与
                    // 面板层重绘，不重组下面的整棵 sheet 内容（此前 `alpha(progress)`
                    // 在组合期取值，每帧都要把 content() 重组一遍——开合时的主要掉帧源）。
                    .graphicsLayer {
                        val dragged = if (panelHeightPx > 0f) dragOffset.value / panelHeightPx else 0f
                        alpha = (progress.value - dragged).coerceIn(0f, 1f)
                    }
                    .background(ink.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { close() },
                    ),
            )
            val shape = RoundedCornerShape(topStart = NpRadius.sheet, topEnd = NpRadius.sheet)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = (1f - progress.value) * size.height + dragOffset.value },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .onSizeChanged { panelHeightPx = it.height.toFloat() }
                        .clip(shape)
                        .background(ink.surface)
                        .border(1.dp, ink.border, shape)
                        .npShadow(ink, shape, elevation = 18.dp)
                        .npTopHairline(ink)
                        .nestedScroll(contentDragConnection)
                        .then(releaseFallback)
                        .draggable(
                            state = rememberDraggableState { delta ->
                                // 只跟手向下：向上拖不产生"更开"，但过头的那段按橡皮筋跟随，
                                // 抬手弹回——而不是夹紧成死区。
                                applyDrag(delta)
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> settle(velocity) },
                            enabled = !dismissDragged,
                        ),
                ) {
                    // 把手（设计稿 `.grab`：38×4 r999 居中）
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 6.dp)
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(NpRadius.chip))
                            .background(ink.border)
                            .align(Alignment.CenterHorizontally),
                    )
                    // 内容滚出遮罩（§4.5 第四处）：16dp、取 **surface**（Sheet 自己的底），
                    // 只在"那个方向确实还有内容"时挂——常驻会让短内容 Sheet 上下各糊一道灰。
                    val contentScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(contentScroll)
                                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
                        ) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = NpTypography.titleMedium,
                                    color = ink.fg,
                                    modifier = Modifier.padding(bottom = 14.dp),
                                )
                            }
                            content()
                        }
                        if (contentScroll.canScrollBackward) {
                            NpMaskFade(
                                color = ink.surface,
                                modifier = Modifier.align(Alignment.TopCenter),
                                size = 16.dp,
                                fromTop = true,
                            )
                        }
                        if (contentScroll.canScrollForward) {
                            NpMaskFade(
                                color = ink.surface,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                size = 16.dp,
                                fromTop = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * `.sheet .opt`：弹层选项行 —— 13/12 padding、r12、选中 accent-soft 底 + accent 字 600。
 */
@Composable
fun NpOptRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 44dp 命中红线（§14 #13）：这类"文字 + 尾部值"的行原来只靠 13dp 上下
            // 内边距撑高，实测 42.5dp 差半格。写死下限而不是加内边距，字体缩放时也不会塌。
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.button))
            .background(if (selected) ink.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .npClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Text(
            text = text,
            style = NpTypography.bodyMedium,
            color = if (selected) ink.accent else ink.fg,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/**
 * `.opt-tile`：圆形图标 tile（4 列网格用）——50dp 圆 + 11.5sp 标签，
 * 选中 accent 实底反白，[danger] 走 danger 色（"移除"）。
 */
@Composable
fun NpQuickTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val ink = LocalNpInk.current
    val tint = when {
        danger -> ink.danger
        selected -> ink.bg
        else -> ink.fg
    }
    val circleColor = when {
        selected -> ink.accent
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Column(
        modifier = modifier.npPressScale(onClick = onClick, pressedScale = 0.95f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(NpDimens.themeTile)
                .clip(CircleShape)
                .background(circleColor)
                .border(1.dp, if (danger) ink.danger else ink.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = NpTypography.labelSmall,
            color = if (danger) ink.danger else ink.fg,
            textAlign = TextAlign.Center,
        )
    }
}
