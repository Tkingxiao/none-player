package com.noneplayer.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * `.fsel`：紧凑的**下拉选择器**（不是 Tab 条）。
 *
 * 设计稿对"筛选"这个动作做了克制取舍：类型筛选有 6 个选项，铺成 chip 条会占掉
 * 一整行且与上方区块标题抢注意力，所以收成一个最小 184dp 的弹层菜单。
 * 只有排序与视图切换这两个"高频但有默认值"的控件常驻在标题行右侧。
 *
 * 弹层用 [Popup] + 自定义 [PopupPositionProvider]：Material 的 `DropdownMenu`
 * 自带 Surface 阴影与 8dp 内边距，与设计稿 `.fsel-menu` 的 14dp 圆角 + 6dp 内边距
 * 差得远，套着改比直接定位更难维护。
 */
@Composable
fun NpFilterSelect(
    label: String,
    icon: ImageVector,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    enabled: Boolean = true,
) {
    val ink = LocalNpInk.current
    var open by remember { mutableStateOf(false) }
    // E128：锚点是非可观察持有者（同 `NpAnchorHolder` 的既有约定）。旧写法是
    // `mutableStateOf(Rect)`——本组件长在列表页的控件行里，滚动时它每帧移动，
    // 布局回调就每帧写状态、每帧新建 Rect，触发这颗 chip 逐帧重组；
    // 而锚点真正被读的时机只有"弹层打开"那一瞬。
    val anchor = rememberNpAnchorHolder()
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = npMotion(Motion.Panel),
        label = "fselChevron",
    )

    Box(modifier = modifier.npAnchor(anchor)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NpRadius.chip))
                .border(1.dp, ink.border, RoundedCornerShape(NpRadius.chip))
                .npPressScale(onClick = { open = true }, pressedScale = 0.96f, enabled = enabled)
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) ink.accent else ink.muted.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                // 设计稿 `.fsel-btn`：13px/550
                style = NpTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) ink.fg else ink.muted.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 向下的箭头：ChevronRight 转 90° 得到，展开时再转 90° → 归到 180°
            Icon(
                imageVector = NpIcon.ChevronRight,
                contentDescription = null,
                tint = ink.muted,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = 90f + chevron },
            )
        }
        if (open) {
            Popup(
                popupPositionProvider = remember(alignEnd) {
                    DropdownPositionProvider(anchor, alignEnd)
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                NpFilterMenu(
                    options = options,
                    selected = selected,
                    alignEnd = alignEnd,
                    onSelect = {
                        onSelect(it)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 把弹层锚定在触发控件下方 8dp（设计稿 `top:calc(100% + 8px)`）。
 *
 * [alignEnd] 为 true 时右对齐（设计稿 `.sort-fsel .fsel-menu{left:auto;right:0}`）——
 * 排序控件在标题行最右侧，左对齐展开会被视口裁掉。
 *
 * 锚点矩形在 [calculatePosition] 被调用的那一瞬从持有者现取（E128）——
 * 持有者不是状态，滚动期不产生逐帧写入；弹层真正要定位时读到的一定是最新布局值。
 */
private class DropdownPositionProvider(
    private val anchor: NpAnchorHolder,
    private val alignEnd: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rect = anchor.current()
        val x = if (alignEnd) {
            rect.right.roundToInt() - popupContentSize.width
        } else {
            rect.left.roundToInt()
        }
        // 视口右缘兜底：360dp 手机 + 184dp 弹层时右对齐仍可能溢出 1–2px
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), rect.bottom.roundToInt() + 8)
    }
}

/**
 * L3 浮层面板（E129）——弹出菜单/锚定小窗的**共用材质与入场**，一处配方。
 *
 * 材质五件套里 L3 的本相（§4 层次模型）：`surface` 实底 + 1dp 发丝描边 +
 * `npShadow` 投影 + 顶高光。**阴影画在最外一层、白底画在阴影层之内**——
 * 与 [NpGlass] 同款顺序（`npShadow → clip → background`）。
 * 反过来写（先 `background` 后 `npShadow`）会让阴影的贴图落进浮层自己的白底之上：
 * 内缘出现一圈"灰带"、白底看起来像一块**错位的白框**（用户 2026-10-11 实报
 * 「性能模式中的白色方框位置有问题」，设置页值行菜单与列表筛选菜单同病）。
 *
 * 入场（M21）：origin-aware `scale 0.95→1 + fade`，`Field`(150) `Snappy`——
 * 缩放原点在**靠锚点的那一角**（`alignEnd` 时右上、否则左上），从触发钮"长出来"。
 */
@Composable
fun NpMenuSurface(
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = LocalNpInk.current
    val scaleAllowed = npDisplacementAllowed()
    val scaleFrom = if (scaleAllowed) 0.95f else 1f
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val entrance by animateFloatAsState(
        targetValue = if (shown) 1f else scaleFrom,
        animationSpec = npMotion<Float>(Motion.Field, easing = NpEasing.Snappy),
        label = "menuEntrance",
    )
    val layerAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = npMotion<Float>(Motion.Field, easing = NpEasing.Snappy),
        label = "menuAlpha",
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
                scaleX = entrance
                scaleY = entrance
                this.alpha = layerAlpha
            }
            // 阴影在**白底之外**：白底在其后重绘，内缘不会再被阴影贴图染成灰带
            .npShadow(ink, shape, elevation = 12.dp)
            .clip(shape)
            .background(ink.surface)
            .border(1.dp, ink.border, shape),
        content = content,
    )
}

/**
 * `.fsel-menu`：最小 184dp 宽的弹层。
 *
 * 独立出来是因为设置页的"主题 / 默认倍速 / 字幕语言"也会复用同一个形状——
 * 圆角 14dp、6dp 内边距、每项 10/12dp 内缩。这三处若各自实现，
 * 弹层的圆角与行高一定会分叉。
 *
 * [alignEnd] 要和调用方的定位一致：M21 要求菜单从**靠锚点的那角**长出来，
 * 弹层一律在触发器下方，所以纵向原点固定在顶边，横向随对齐端走。
 */
@Composable
fun NpFilterMenu(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val ink = LocalNpInk.current
    NpMenuSurface(
        alignEnd = alignEnd,
        modifier = modifier
            // 宽度贴合内容（最宽一项 + 内边距），下限 184dp（设计稿 `.fsel-menu{min-width:184px}`）。
            // 不加 IntrinsicSize.Max 的话，行内的 fillMaxWidth() 在 Popup 的窗口级约束下
            // 会把菜单拉成整屏宽——一个筛选菜单占满屏幕会让人以为进了新页面。
            .width(IntrinsicSize.Max)
            .widthIn(min = 184.dp, max = 300.dp),
    ) {
        Column(modifier = Modifier.selectableGroup().padding(6.dp)) {
            options.forEach { option ->
                val on = option == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // R4-17/REV-14：单选语义（selected + Role.RadioButton），
                        // 由外层 selectableGroup 宣布"N 选 1"；视觉仍只靠字重/色/勾。
                        .npSelectable(selected = on, role = Role.RadioButton) { onSelect(option) }
                        .clip(RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = option,
                        style = NpTypography.bodyMedium.copy(
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (on) ink.accent else ink.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 勾位常驻并用透明占位，而不是 if(on)：否则选中瞬间文字会被挤动一下
                    Icon(
                        imageVector = NpIcon.Check,
                        contentDescription = null,
                        tint = if (on) ink.accent else Color.Transparent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * `.seg`：分段控件（设计稿用于视图切换与主题三选一）。
 *
 * 与 [NpFilterSelect] 的分工：选项 ≤ 3 且**值本身就值得展示**时用分段控件；
 * 选项多或需要收起时用下拉。
 *
 * @param itemWidth 固定每项最小宽度；null 时等分父容器宽度。
 * @param icons 仅图标模式（视图切换用的就是这个）；null 时显示 [options] 文字。
 */
@Composable
fun NpSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp? = null,
    icons: List<ImageVector?>? = null,
) {
    val ink = LocalNpInk.current
    // 分段控件是"页内 Tab"：切档给一次刻度触觉（M4），按下不缩放（它靠胶囊表达选中）。
    val haptics = rememberNpHaptics()
    Row(
        modifier = modifier
            // R4-17/REV-14：分段控件是"N 选 1"，组语义让读屏读成一组而不是一排按钮。
            .selectableGroup()
            .clip(RoundedCornerShape(12.dp))
            .background(ink.fgSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .then(
                        if (itemWidth != null) {
                            Modifier.widthIn(min = itemWidth)
                        } else {
                            Modifier.weight(1f)
                        },
                    )
                    // 设计稿 `.seg button.on`：surface 底 + `0 1px 3px 黑14%` 微投影。
                    // E129：阴影画在**白底之外**（同 NpGlass 的顺序），否则投影会落在
                    // 选中块自己的白底上、边缘发灰。
                    .then(if (on) Modifier.npShadow(ink, RoundedCornerShape(9.dp), elevation = 3.dp) else Modifier)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) ink.surface else Color.Transparent)
                    .border(
                        width = if (on) 1.dp else 0.dp,
                        color = if (on) ink.border else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                    )
                    // M4（§7.5 `Tick`）：点已选中项没有状态变化，不该响。
                    // R4-17/REV-14：单选语义（selected + Role.RadioButton）——
                    // 视觉仍只靠白底/描边/字重表达，语义树里读得出当前档位。
                    .npSelectable(selected = on, role = Role.RadioButton) {
                        if (!on) {
                            haptics.performHaptic(NpHaptic.Tick)
                            onSelect(option)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                val icon = icons?.getOrNull(index)
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = option,
                        tint = if (on) ink.accent else ink.muted,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = option,
                        style = NpTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = if (on) ink.fg else ink.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
