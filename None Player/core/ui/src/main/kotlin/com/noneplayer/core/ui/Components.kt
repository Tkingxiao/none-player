package com.noneplayer.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


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
    // reduced-motion：砍掉位移/缩放，点击路径保持不变
    val reduceMotion = rememberReduceMotion()
    val scale = silkFloat(
        target = if (pressed && enabled && !reduceMotion) pressedScale else 1f,
        durationMs = Motion.Press,
        label = "npPressScale",
    )
    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        // combinedClickable 才会把"长按"与"点击"分开派发；无长按时保留原 clickable
        Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .then(clickModifier)
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
    val reduceMotion = rememberReduceMotion()
    // 同 [npPressScale]：状态读在 graphicsLayer 的 lambda 里，悬停上浮只走绘制层，
    // 不重组卡片。
    val lift = silkDp(
        target = if (hovered && !reduceMotion) distance else 0.dp,
        durationMs = Motion.Panel,
        label = "npHoverLift",
    )
    return graphicsLayer { translationY = -lift.value.toPx() }
}

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
)

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
    val color by animateColorAsState(target, silkSpring(Motion.Hover), label = "npIconTint")
    val background by animateColorAsState(
        if (selected) ink.accentSoft else Color.Transparent,
        silkSpring(Motion.Hover),
        label = "npIconBg",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(NpRadius.button))
            .background(background)
            .npPressScale(onClick = onClick, pressedScale = 0.90f, enabled = enabled),
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

/**
 * `.chip`：丸形过滤/标签。选中态是**实心 accent + 底字反色**（设计稿 `.chip.on`），
 * 不是描边高亮——胶囊底本身就是"已选中"的信号。
 */
@Composable
fun NpChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val border by animateColorAsState(
        when {
            selected -> ink.accent
            else -> ink.border
        },
        silkSpring(Motion.Field),
        label = "chipBorder",
    )
    val background by animateColorAsState(
        if (selected) ink.accent else Color.Transparent,
        silkSpring(Motion.Field),
        label = "chipBg",
    )
    val content by animateColorAsState(
        if (selected) ink.bg else ink.muted,
        silkSpring(Motion.Field),
        label = "chipFg",
    )
    Box(
        modifier = modifier
            .heightIn(min = NpDimens.chipHeight)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(NpRadius.chip))
            .npPressScale(onClick = onClick, pressedScale = 0.96f)
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
        silkSpring(Motion.Field),
        label = "moreColor",
    )
    val shift by animateFloatAsState(
        if (pressed) 2f else 0f,
        silkSpring(Motion.Field),
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
        silkSpring(Motion.Panel),
        label = "switchTrack",
    )
    val thumb by animateColorAsState(
        if (checked) ink.bg else ink.fg,
        silkSpring(Motion.Panel),
        label = "switchThumb",
    )
    val offset by animateFloatAsState(
        if (checked) 19f else 0f,
        bouncySpring(Motion.Switch),
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 设计稿 `.empty .ring`：64px 虚线圆环 + 环内一枚 muted 图标
        Box(
            modifier = Modifier
                .size(64.dp)
                .drawBehind {
                    drawCircle(
                        color = ink.border,
                        radius = size.minDimension / 2f - 1.dp.toPx(),
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
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            style = NpTypography.titleSmall.copy(fontSize = 16.sp),
            color = ink.fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = NpTypography.bodySmall,
            color = ink.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null && onAction != null) {
            NpPrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = 18.dp),
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
    val transition = rememberInfiniteTransition(label = "sk")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skShift",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(ink.surface, ink.surface2, ink.surface),
                    start = Offset(shift * 400f - 400f, 0f),
                    end = Offset(shift * 400f + 400f, 0f),
                ),
            ),
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
                        .npGlassChrome(ink)
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
                    // 顶栏底边发丝线（设计稿 `.topbar{border-bottom:1px solid var(--border)}`）
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(ink.border),
                    )
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) {
                    NpTypography.displayLarge.copy(fontSize = NpTypography.headlineMedium.fontSize)
                } else {
                    NpTypography.displayLarge
                },
                color = ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && subtitle != null && subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = NpTypography.bodySmall,
                    color = ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}
