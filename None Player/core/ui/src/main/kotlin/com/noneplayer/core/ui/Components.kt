package com.noneplayer.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * None Player 组件库——设计稿里**反复出现的原子件**，一处实现、全项目复用。
 *
 * 纪律：这里只放"两个以上页面都在用的形状"。单页独有的布局留在各自页面里，
 * 否则组件库会退化成"把每一行都包一层"的转写层。
 */

/**
 * 按压缩放——设计稿 `--press: transform .16s var(--ease-out)` 与大量
 * `:active{transform:scale(.9x)}` 的统一实现。
 *
 * 用 [NpEasing.Silk] 的 spring 回弹且**不过冲**：列表卡片上的果冻回弹显得廉价；
 * 过冲只留给开关与滑块 thumb（表达"物理开关"）。
 *
 * **写成 `@Composable` 扩展而不是 `Modifier.composed{}`**：后者会为每个元素额外
 * 建一个组合节点，在 LazyList 的每个卡片上都挂一层是官方点名的滚动期开销。
 *
 * 动画状态**不在组合期解包**：`scale.value` 读在 `graphicsLayer` 的 lambda 里，
 * 状态变化只让该绘制层失效（draw 阶段重跑），不触发重组。此前用 `by` 在组合期读，
 * 按下的 160ms 里每帧都要重组一次卡片——列表里几十张卡片同时被按下就是掉帧。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.npPressScale(
    onClick: () -> Unit,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    /**
     * 复用的交互源：卡片要把"按压"与"悬停"（[npHoverLift]）读在同一个源上，
     * 传入后本函数不再自建。为空时自建（绝大多数按钮/图标的用法）。
     */
    interactionSource: MutableInteractionSource? = null,
    /** 长按（§3.6 上下文菜单入口）；null = 仅点击（既有调用点行为不变） */
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 位移/缩放门控：E119 前只读 `rememberReduceMotion()`，于是 §9.6 的
    // `displacementAsFade`（轻量档把位移/缩放整体退化成 fade）**挡不住按压缩放**——
    // 降到轻量档后按钮仍在缩放，而同一档位的滑入滑出已经变成淡入淡出了。
    val displacementAllowed = npDisplacementAllowed()
    val scale = silkFloat(
        target = if (pressed && enabled && displacementAllowed) pressedScale else 1f,
        durationMs = Motion.Press,
        label = "npPressScale",
    )
    // M1 触觉（§7.5 `Tap`）在**抬起成键**时下发：按下去滑走、或长按改走 `Long`，
    // 都不该响。放在这一处=卡片/图标按钮/底栏按钮一次铺满（§6 M1「补铺到 BarCtlButton」）。
    val haptics = rememberNpHaptics()
    val tap = {
        haptics.performHaptic(NpHaptic.Tap)
        onClick()
    }
    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = tap,
        )
    } else {
        // combinedClickable 才会把"长按"与"点击"分开派发；无长按时保留原 clickable
        Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = tap,
            onLongClick = {
                haptics.performHaptic(NpHaptic.Long)
                onLongClick()
            },
        )
    }
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .then(clickModifier)
        // E8：本项目此前 0 处显式 Role——读屏只报"双击即可激活"不报"按钮"。
        // 收口在这一处 = 卡片/图标按钮/chip/列表行一次铺满；个别需要别的角色
        // 的调用点再单独覆盖。
        .semantics { role = Role.Button }
}

/**
 * 指针悬停（hover）是否可用——桌面化输入（E50）的全局开关，默认**关闭**。
 *
 * 设计稿把所有 hover 效果统一关在 `@media (hover:hover) and (pointer:fine)` 里：
 * 触屏没有 hover 概念，浏览器里会留下"粘住的 hover 态"，所以必须按"是否有指针设备"开闸。
 * Compose 侧同理——由 app 层用 `NpWindowInfo.isHoverable`（实查鼠标/触控板输入设备）注入。
 *
 * 归属 core:ui 而非 kernel-ui-api：消费方是卡片组件自身，而 core:ui 与 kernel-ui-api
 * 之间没有依赖边（见 `NpWindowInfo` 文档注释），不能反向 import；app 层同时依赖两者，
 * 是天然的注入点。未注入时默认 false —— hover 效果整体关闭，绝不误触发。
 */
val LocalNpHoverEnabled: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { false }

/**
 * 指针悬停上浮（设计稿 2.7 `.medcard:hover{transform:translateY(-2px)}` /
 * `.folder:hover{transform:translateY(-2px)}`）。
 *
 * [hovered] 由调用方从共享的 [MutableInteractionSource] 读（`collectIsHoveredAsState`）——
 * 卡片还要用同一个"是否悬停"去改标题色/描边，状态留在调用方而不是藏在这里。
 * [enabled] 传 [LocalNpHoverEnabled]：无指针设备时整体关闭（触屏不会误触发）。
 * reduced-motion 下退化为不位移（颜色类反馈不受影响）。
 */
@Composable
fun Modifier.npHoverLift(
    hovered: Boolean,
    enabled: Boolean = true,
    distance: Dp = 2.dp,
): Modifier {
    // 无指针设备：hover 态永远不会成立。**直接短路**，不建动画状态——
    // 否则每张卡片都要背一个 Animatable 与随动协程，只为追一个恒为 0 的目标。
    if (!enabled) return this
    // 同 [npPressScale]：E119 起门控读 npDisplacementAllowed()，轻量档也不上浮。
    val displacementAllowed = npDisplacementAllowed()
    // 状态读在 graphicsLayer 的 lambda 里，悬停上浮只走绘制层，不重组卡片。
    val lift = silkDp(
        target = if (hovered && displacementAllowed) distance else 0.dp,
        durationMs = Motion.Panel,
        label = "npHoverLift",
    )
    return graphicsLayer { translationY = -lift.value.toPx() }
}

/**
 * 菜单锚点持有者：**非可观察**的矩形盒子（见 [rememberNpAnchorHolder]）。
 *
 * 存在的理由是"读取时机"：长按菜单只在**长按发生的那一瞬**需要卡片矩形，
 * 但矩形只有布局阶段才知道。若用 `mutableStateOf` 接住布局回调，
 * 滚动时每一帧的位置变化都会写一次状态 → 卡片重组一次——
 * 列表里 8 张可见卡片就是 60fps × 8 次全卡片重组，这是滚动掉帧最典型的一种写法。
 *
 * 这里换成普通字段：布局阶段照常写入（不触发重组），点击阶段现取。
 * 代价是"矩形不是状态"——它本来也不该是，菜单锚点不需要驱动任何 UI。
 */
class NpAnchorHolder internal constructor() {
    private var rect: Rect = Rect.Zero

    internal fun update(value: Rect) {
        rect = value
    }

    fun current(): Rect = rect
}

/** 见 [NpAnchorHolder]：布局期写入、交互期读取，全程不产生重组。 */
@Composable
fun rememberNpAnchorHolder(): NpAnchorHolder = remember { NpAnchorHolder() }

/**
 * 把当前节点的窗口坐标持续写进 [holder]（[onGloballyPositioned] + [boundsInWindow]）。
 * 与 [NpAnchorHolder] 配套：写入不经过快照系统，因此滚动期零重组开销。
 */
fun Modifier.npAnchor(holder: NpAnchorHolder): Modifier =
    onGloballyPositioned { holder.update(it.boundsInWindow()) }

/** 无涟漪点击（列表行、图标按钮）——Material 涟漪与设计稿的干净按压不是一套语言。 */
@Composable
fun Modifier.npClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    onClick = onClick,
).semantics { role = Role.Button }

/**
 * 「视觉小、命中 44」分离（§3.1 红线 / §14 #13）：**方形状**可点元素专用。
 *
 * 命中区（=本节点的槽位）至少 [NpDimens.touchTarget]；**视觉**由调用方在里层再画
 * [size]，所以 38dp 的圆角底色、24dp 的图标都还是设计稿那么大，长出来的只有透明边距。
 *
 * 为什么是"撑槽位"而不是"外溢命中区"：先写过一版 `size(视觉) → wrapContentSize(unbounded)
 * → requiredSize(44)`，思路是槽位不动、让可点层居中溢出。实测（uiautomator 逐节点量边界）
 * 溢出层在**主轴方向被邻居裁掉**——40dp 的钮仍报 40dp 宽，等于没修。撑槽位是笨办法，
 * 但它量得到、也不依赖 Compose 的无障碍边界怎么算。
 *
 * 代价说清楚：同一排里每个钮的间距会按 (44 - 视觉) 变宽（传输键 +4dp、底栏键 +12dp），
 * 行高不变（这些钮的行本来就 ≥48dp）。
 */
fun Modifier.npTouchTarget(size: Dp): Modifier = this
    .size(maxOf(size, NpDimens.touchTarget))

/**
 * `.iconbtn`：44dp 命中区 / 12dp 圆角 / 未选 muted、选中 accent + accentSoft 底。
 *
 * 设计稿里 38–44dp 的图标按钮遍布顶栏、迷你条、播放页。统一在这里，
 * 保证"同一层级的图标按钮大小一致"——这是设计稿最容易被逐个实现搞坏的地方。
 */
@Composable
fun NpIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = NpDimens.topbarIcon,
    iconSize: Dp = 24.dp,
    selected: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val ink = LocalNpInk.current
    val target = tint ?: if (selected) ink.accent else ink.muted
    val color by animateColorAsState(target, npMotion(Motion.Hover), label = "npIconTint")
    val background by animateColorAsState(
        if (selected) ink.accentSoft else Color.Transparent,
        npMotion(Motion.Hover),
        label = "npIconBg",
    )
    Box(
        modifier = modifier
            .npTouchTarget(size)
            .npPressScale(onClick = onClick, pressedScale = 0.90f, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        // 视觉层单独一层：选中底色 `.background` 必须只画到 [size]，
        // 画在外层就变成 44dp 的色块（§14 #13 要的是"命中 44 / 视觉 38"，不是把钮放大）。
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(NpRadius.button))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) color else ink.muted.copy(alpha = 0.45f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * `.chip`（§5.7）：丸形过滤/标签。
 *
 * 选中 = `accentSoft` 底 + `accent` 字 + `accentLine` 描边；未选 = `fgFaint` 底 + `muted` 字。
 * 切换走 `Snappy / Field=150`（颜色是"一次性状态变化"，不许线性），并落一次 `Tick` 触觉——
 * chips 是**一键切换**的控件，点了不响就等于没点到。
 */
@Composable
fun NpChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val haptics = rememberNpHaptics()
    val colorSpec = npMotion<Color>(Motion.Field, easing = NpEasing.Snappy)
    val border by animateColorAsState(
        if (selected) ink.accentLine else ink.border,
        colorSpec,
        label = "chipBorder",
    )
    val background by animateColorAsState(
        if (selected) ink.accentSoft else ink.fgFaint,
        colorSpec,
        label = "chipBg",
    )
    val content by animateColorAsState(
        if (selected) ink.accent else ink.muted,
        colorSpec,
        label = "chipFg",
    )
    // 命中层高到 44dp（§14 #13），视觉层仍是设计稿的 34dp 丸形：底色/描边只画里层。
    // chip 不走 [npTouchTarget]（那是方形状钮的方形槽位），这里宽度由内容决定，
    // 所以外层只钉高度——代价是过滤行真的变高 10dp，换来的是与邻居零重叠。
    Box(
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .npPressScale(
                onClick = {
                    haptics.performHaptic(NpHaptic.Tick)
                    onClick()
                },
                pressedScale = 0.96f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = NpDimens.chipHeight)
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(background)
                .border(1.dp, border, RoundedCornerShape(NpRadius.chip))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme0.chipText,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/**
 * `.eyebrow`：等宽小号 + 字距展开的 accent 小标题。
 *
 * 设计稿只用它标记"这一块是什么"（继续观看 / 选集 / 循环模式），
 * **不用于强调**——强调是 [NpSectionHead] 的 `h2` 的职责。
 */
@Composable
fun NpEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = NpEyebrow,
        color = LocalNpInk.current.accent,
        modifier = modifier,
    )
}

/**
 * `.sec-head`：区块标题行 —— 标题 + 右侧动作（"全部 ›" / 排序 / 视图切换）。
 *
 * [trailing] 走槽位而不是固定字符串：右侧可能是三个控件（排序菜单 + 视图切换 + 计数），
 * 也可能是单个"全部 ›"，用槽位才能两种情况都不变形。
 * [actionLabel] 是"全部 ›"这一最常见形态的快捷方式，与 [trailing] 可同时使用。
 *
 * **标题必须占满剩余宽度**（`weight(1f)` 且默认 `fill=true`），右侧动作才会被顶到行尾。
 * 早前这里是"标题 `weight(1f, fill=false)` + 一个 `Spacer(weight(1f))`"——
 * 两个 weight 各分走一半，于是"全部 ›"稳稳停在屏幕中线而不是右缘。
 */
@Composable
fun NpSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NpSpace.md),
    ) {
        Text(
            text = title,
            style = NpTypography.headlineSmall,
            color = ink.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
        if (actionLabel != null && onAction != null) {
            NpMoreLink(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * `.sec-head .more`：次级动作文字 + 箭头，hover 时文字转 accent、箭头右移 2dp。
 *
 * 触屏没有 hover，所以按下（:active）等效触发——否则这个"箭头右移"的细节
 * 在手机上永远看不到，等于白写。
 */
@Composable
fun NpMoreLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val color by animateColorAsState(
        if (pressed) ink.accent else ink.muted,
        npMotion(Motion.Field),
        label = "moreColor",
    )
    val shift by animateFloatAsState(
        if (pressed) 2f else 0f,
        npMotion(Motion.Field),
        label = "moreShift",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NpRadius.tile))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = text, style = NpTypography.bodySmall, color = color, maxLines = 1)
        Icon(
            imageVector = NpIcon.ChevronRight,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { translationX = shift },
        )
    }
}

/**
 * `.row-item`：设置/入口行 —— 圆形图标底 + 标题(+副标题) + 尾部插槽。
 *
 * 最小高度 56dp、行间 1px 发丝线（设计稿 `.row-item`）。
 * [trailing] 覆盖"箭头 / 开关 / 当前值"三种尾部形态。
 */
@Composable
fun NpSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NpDimens.listRowMin)
                .then(if (onClick != null) Modifier.npPressScale(onClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(NpDimens.ficoBox)
                        .clip(RoundedCornerShape(NpRadius.tile))
                        .background(ink.fgSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ink.muted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = NpTypography.titleSmall,
                    color = ink.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = NpTypography.bodySmall,
                        color = ink.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ink.border),
            )
        }
    }
}

/**
 * `.switch`：46×27 丸形开关，轨道 accent、滑块 21dp（设计稿精确值）。
 *
 * 用一个 `Box + graphicsLayer` 而不是 Material `Switch`：Material 的开关尺寸、
 * 轨道色、thumb 投影都不可控，与设计稿差距明显。
 */
@Composable
fun NpSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ink = LocalNpInk.current
    val track by animateColorAsState(
        if (checked) ink.accent else ink.border,
        npMotion(Motion.Panel),
        label = "switchTrack",
    )
    val thumb by animateColorAsState(
        if (checked) ink.bg else ink.fg,
        npMotion(Motion.Panel),
        label = "switchThumb",
    )
    val offset by animateFloatAsState(
        if (checked) 19f else 0f,
        npMotion(Motion.Switch, bouncy = true),
        label = "switchOffset",
    )
    Box(
        modifier = modifier
            .size(width = NpDimens.switchWidth, height = NpDimens.switchHeight)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(track)
            .npClickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp, top = 3.dp)
                .size(21.dp)
                .graphicsLayer { translationX = offset.dp.toPx() }
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(thumb),
        )
    }
}

/**
 * `.empty`：空状态 —— 虚线圆环（环内 muted 图标）+ 标题 + 一句引导 + 可选动作。
 *
 * 设计稿 `.empty .ring{border:2px dashed}` 且环内放一枚 `np-ico lg`：
 * 虚线读作"容器还在等内容"，实线会像"封死的空盒子"。
 *
 * **M15（§5.8）**：环按 `Bubble`(500ms, Silk) 一路画出来，文案在起手 120ms 后淡入，
 * 动作按钮 `bouncySpring(Switch)` 弹入。三件事有先后，用户读到的才是"这里在长东西"
 * 而不是"整块内容闪了一下"。
 * 动画**只在首次进入播**——同页复用（切 Tab 回来、重组）直接给终态，
 * 否则每次看见空态都要陪它演半秒；轻量档 `emptyStroke=false` 同样直接给终态（§9.6）。
 */
@Composable
fun NpEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val ink = LocalNpInk.current
    val quality = npQuality()
    val ringSpec = npMotion<Float>(Motion.Bubble, easing = NpEasing.Silk)
    val copySpec = npMotion<Float>(Motion.Field, easing = NpEasing.Silk)
    val actionSpec = npMotion<Float>(Motion.Switch, bouncy = true)
    val copyDelayMs = npDuration(Motion.ReduceFade)
    val scaleAllowed = npDisplacementAllowed()

    var strokePlayed by rememberSaveable { mutableStateOf(false) }
    val ring = remember { Animatable(0f) }
    val copy = remember { Animatable(0f) }
    val action = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (!quality.emptyStroke || strokePlayed) {
            // 不播也要落到终态，否则动画关了空态就永远是半透明的
            ring.snapTo(1f)
            copy.snapTo(1f)
            action.snapTo(1f)
            strokePlayed = true
            return@LaunchedEffect
        }
        launch { ring.animateTo(1f, ringSpec) }
        delay(copyDelayMs.toLong())
        copy.animateTo(1f, copySpec)
        action.animateTo(1f, actionSpec)
        strokePlayed = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 设计稿 `.empty .ring`：64dp 虚线圆环 + 环内一枚 muted 图标
        Box(
            modifier = Modifier
                .size(64.dp)
                .drawBehind {
                    drawArc(
                        color = ink.border,
                        startAngle = -90f,
                        sweepAngle = 360f * ring.value,
                        useCenter = false,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                            ),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NpIcon.Folder,
                contentDescription = null,
                tint = ink.muted,
                modifier = Modifier
                    .size(28.dp)
                    .alpha(copy.value),
            )
        }
        Text(
            text = title,
            style = NpTypography.titleSmall.copy(fontSize = 16.sp),
            color = ink.fg,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .alpha(copy.value),
        )
        Text(
            text = message,
            style = NpTypography.bodySmall,
            color = ink.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .alpha(copy.value),
        )
        if (actionLabel != null && onAction != null) {
            NpPrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .alpha(action.value)
                    .graphicsLayer {
                        // 弹入的位移/缩放归 Reduce Motion 管：不许动时就只剩淡入
                        val scale = if (scaleAllowed) 0.92f + 0.08f * action.value else 1f
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/**
 * `.list-foot .lf-btn`：丸形按钮。primary 是 accent 实底 + 反色字，
 * 普通态是 surface 实底 + 描边——两者只差填充，尺寸与圆角必须一致。
 */
@Composable
fun NpPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(if (primary) ink.accent else ink.surface)
            .border(1.dp, if (primary) ink.accent else ink.border, RoundedCornerShape(NpRadius.chip))
            .npPressScale(onClick = onClick, pressedScale = 0.97f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (primary) ink.bg else ink.fg,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = NpTypography.labelLarge,
            color = if (primary) ink.bg else ink.fg,
            maxLines = 1,
        )
    }
}

/**
 * `.sk`：骨架屏微光条。设计稿 `shimmer` 动画的 Compose 版本。
 *
 * 用 `Brush.linearGradient` 随无限动画平移，而不是 alpha 呼吸：
 * 呼吸看起来像"闪烁的坏屏"，平移才是"内容正在流进来"。
 */
@Composable
fun NpSkeleton(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(NpRadius.card),
) {
    val ink = LocalNpInk.current
    // M13：流光圈长走 `Shimmer` 令牌；轻量档（`quality.shimmer=false`）直接给静态骨架——
    // 骨架屏本意是"我在加载"，在慢机上跑无限动画反而和真正的内容抢帧。
    val shimmerEnabled = npQuality().shimmer
    // 关掉时**不建**无限动画：留着它即便零位移也每帧回调，而骨架屏正是和真正内容
    // 抢帧的那一方——轻量档要的是"别动"，不是"动得慢一点"。
    //
    // 取值挪到**绘制相位**（`background { }` 的 brush lambda 在 DrawScope 里求值）：
    // 旧写法把 `.value` 解在组合期，于是每条骨架以 60fps 重组自己、每帧再造一条
    // `linearGradient` 加两个 `Offset`——只该重绘的东西不该走重组。
    val shiftAnim = if (shimmerEnabled) {
        rememberInfiniteTransition(label = "sk").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(npDuration(Motion.Shimmer), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "skShift",
        )
    } else {
        null
    }
    Box(
        modifier = modifier.clip(shape).drawBehind {
            // 取值与造刷子都在绘制相位：State 只失效 draw，不再失效组合
            val shift = shiftAnim?.value
            drawRect(
                if (shift == null) {
                    SolidColor(ink.surface)
                } else {
                    Brush.linearGradient(
                        colors = listOf(ink.surface, ink.surface2, ink.surface),
                        start = Offset(shift * 400f - 400f, 0f),
                        end = Offset(shift * 400f + 400f, 0f),
                    )
                },
            )
        },
    )
}

/**
 * 组件库里对排版的引用别名。
 *
 * 组件模块与 `Type.kt` 同属 `:core:ui`，但直接写 `NpTypography.bodySmall` 会在
 * 每个调用点重复"从 Typography 对象里取字段"这条路径；这里给它一个短名字，
 * 让组件代码读起来是"用哪种字"而不是"从哪个对象取"。
 */
private object MaterialTheme0 {
    val chipText = NpTypography.bodySmall
}

/**
 * "⊘ None" 签名标记——圆 + 斜线，本应用最易被记住的元素。
 *
 * 出现在启动画面与空状态：媒体库为空时它天然成立（"空无一物"是产品名的具象），
 * 而不是贴上去的装饰。默认单色，让 accent 留给真正的动作。
 */
@Composable
fun NoneMark(
    modifier: Modifier = Modifier,
    color: Color = LocalNpInk.current.muted,
    strokeWidth: Dp = 5.dp,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = strokeWidth.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val inset = stroke.width / 2
        val arcSize = size.width - stroke.width
        drawCircle(
            color = color,
            radius = arcSize / 2,
            center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
            style = stroke,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(
                inset + arcSize * 0.22f,
                size.height - inset - arcSize * 0.22f,
            ),
            end = androidx.compose.ui.geometry.Offset(
                size.width - inset - arcSize * 0.22f,
                inset + arcSize * 0.22f,
            ),
            strokeWidth = stroke.width,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * 页面外壳：顶栏 + 内容最大宽度 + 两侧安全区，三件每个页面都要重复的事收在一处。
 *
 * 设计稿里 `.topbar` 是 **sticky 玻璃**（内容从它下面滚过时呈毛玻璃），
 * `.content{max-width:1100px}` 是全局约定。本外壳保留**覆盖式布局**：
 * 内容层占满全高、延伸到顶栏之下，顶栏悬浮其上；但顶栏底是**不透明的**
 * （见 [npGlassChrome]）——滚动内容从它下面经过而不是透出来，省掉逐帧背后取样。
 * 页面在自己的滚动容器 `contentPadding.top` 里消费 [LocalNpTopBarInset]
 * 做首屏避让，`contentPadding.bottom` 里消费外壳下发的底部条高度。
 *
 * 底部留白**不在这里**：各页用 `LazyColumn(contentPadding = …)` 自己声明，
 * 因为滚动容器比外壳更清楚"最后一行下面该留多少"（网格与列表的留白本来就不同）。
 */

/** 顶栏覆盖在内容之上时，内容首屏需要预留的顶栏实测高度（含安全区之内部分）。 */
val LocalNpTopBarInset = compositionLocalOf { Dp.Unspecified }

@Composable
fun NpPageScaffold(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = NpDimens.contentMaxWidth,
    topBar: @Composable (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val ink = LocalNpInk.current
    var topBarPx by remember { mutableIntStateOf(0) }
    val topInset = with(LocalDensity.current) { topBarPx.toDp() }
    CompositionLocalProvider(LocalNpTopBarInset provides topInset) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 内容层：占满全高、延伸到顶栏之下（首屏由页面自行避让）
            content(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (maxContentWidth != Dp.Unspecified) {
                            Modifier.widthIn(max = maxContentWidth)
                        } else {
                            Modifier
                        },
                    ),
            )
            if (topBar != null) {
                // 顶栏：全宽不透明条（设计稿 .topbar 通栏，内容行另收 1100 上限）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // L2 条配方：不透明底 + 顶高光 + 底发丝线（`.topbar{border-bottom}`）
                        .npGlassSurface(ink, topHighlight = true, bottomHairline = true)
                        .onSizeChanged { topBarPx = it.height },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (maxContentWidth != Dp.Unspecified) {
                                    Modifier.widthIn(max = maxContentWidth)
                                } else {
                                    Modifier
                                },
                            )
                            .align(Alignment.TopCenter),
                    ) { topBar() }
                }
            }
        }
    }
}

/**
 * 设计稿 `.topbar`：大标题 + 副标题 + 可选返回键 + 右侧图标组。
 *
 * 抽成共享组件而不是各页各写一份：`.topbar h1` 的 27sp/700/-0.02em 与紧凑档
 * 22sp 的降级、`.sub` 在紧凑档隐藏、图标按钮 44/40dp —— 这一串细节在多处重复时
 * 一定会分叉（此前媒体库与设置页的顶栏就是两个略不相同的高度）。
 *
 * [subtitle] 在紧凑档隐藏是有意的：手机上 22sp 的标题已占满一行，
 * 再加一行 13sp 统计只会把首屏内容往下推，而统计值在内容里也能看到。
 */
@Composable
fun NpAppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 12.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        if (onBack != null) {
            NpIconButton(
                icon = NpIcon.ArrowLeft,
                contentDescription = "返回",
                onClick = onBack,
                size = if (compact) 40.dp else 44.dp,
                tint = ink.fg,
            )
        }
        // 标题/副题随页内态或路由变化：**淡变**而不是瞬时跳字。
        // 背景（2026-10-08 走查）：库页的正文已经交叉淡入，标题却先一步跳变，
        // 读起来是"两段动作"；两者同节拍才叫衔接完整。只做 alpha——同字号、单行、
        // 左对齐，不动布局（轻量档/Reduce Motion 下本来也没有可并的位移）。
        val titleFadeMs = npDuration(Motion.NavFade)
        Column(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title to subtitle,
                transitionSpec = {
                    fadeIn(tween(titleFadeMs, easing = NpEasing.Silk)) togetherWith
                        fadeOut(tween(titleFadeMs, easing = NpEasing.SilkExit))
                },
                label = "npTopBarTitle",
            ) { (t, sub) ->
                Column {
                    Text(
                        text = t,
                        style = if (compact) {
                            NpTypography.displayLarge.copy(fontSize = NpTypography.headlineMedium.fontSize)
                        } else {
                            NpTypography.displayLarge
                        },
                        color = ink.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact && sub != null && sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = NpTypography.bodySmall,
                            color = ink.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        trailing()
    }
}
