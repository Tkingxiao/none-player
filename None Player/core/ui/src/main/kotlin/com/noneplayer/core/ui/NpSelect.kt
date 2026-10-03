package com.noneplayer.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = silkSpring(Motion.Panel),
        label = "fselChevron",
    )

    Box(modifier = modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
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
                popupPositionProvider = remember(anchor, alignEnd) {
                    DropdownPositionProvider(anchor, alignEnd)
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                NpFilterMenu(
                    options = options,
                    selected = selected,
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
 */
private class DropdownPositionProvider(
    private val anchor: Rect,
    private val alignEnd: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (alignEnd) {
            anchor.right.roundToInt() - popupContentSize.width
        } else {
            anchor.left.roundToInt()
        }
        // 视口右缘兜底：360dp 手机 + 184dp 弹层时右对齐仍可能溢出 1–2px
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), anchor.bottom.roundToInt() + 8)
    }
}

/**
 * `.fsel-menu`：最小 184dp 宽的弹层。
 *
 * 独立出来是因为设置页的"主题 / 默认倍速 / 字幕语言"也会复用同一个形状——
 * 圆角 14dp、6dp 内边距、每项 10/12dp 内缩。这三处若各自实现，
 * 弹层的圆角与行高一定会分叉。
 */
@Composable
fun NpFilterMenu(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val shape = RoundedCornerShape(14.dp)
    // 设计稿 `.fsel.open .fsel-menu`：scale(.96)→1 + opacity，.16s ease-out
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val entrance by animateFloatAsState(
        targetValue = if (shown) 1f else 0.96f,
        animationSpec = tween(Motion.Field, easing = NpEasing.Silk),
        label = "fselEntrance",
    )
    Column(
        modifier = modifier
            // 宽度贴合内容（最宽一项 + 内边距），下限 184dp（设计稿 `.fsel-menu{min-width:184px}`）。
            // 不加 IntrinsicSize.Max 的话，行内的 fillMaxWidth() 在 Popup 的窗口级约束下
            // 会把菜单拉成整屏宽——一个筛选菜单占满屏幕会让人以为进了新页面。
            .width(IntrinsicSize.Max)
            .widthIn(min = 184.dp, max = 300.dp)
            .graphicsLayer {
                scaleX = entrance
                scaleY = entrance
                alpha = 0.4f + 0.6f * ((entrance - 0.96f) / 0.04f).coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(ink.surface)
            .npShadow(ink, shape, elevation = 12.dp)
            .border(1.dp, ink.border, shape)
            .padding(6.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .npClickable { onSelect(option) }
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
    Row(
        modifier = modifier
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
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) ink.surface else Color.Transparent)
                    .border(
                        width = if (on) 1.dp else 0.dp,
                        color = if (on) ink.border else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                    )
                    // 设计稿 `.seg button.on`：surface 底 + `0 1px 3px 黑14%` 微投影
                    .then(if (on) Modifier.npShadow(ink, RoundedCornerShape(9.dp), elevation = 3.dp) else Modifier)
                    .npClickable { onSelect(option) }
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
