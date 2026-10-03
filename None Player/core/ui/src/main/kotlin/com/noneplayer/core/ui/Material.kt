package com.noneplayer.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/**
 * None Player 材质系统（第 2 层配方）——由令牌组合出的"条底 / 高光 / 发丝线"。
 *
 * 设计稿的核心材质主张是 `.topbar / .mini / .tabbar` 上的
 * `background: var(--glass); backdrop-filter: blur(22px) saturate(150%)` 加一条
 * `::after { inset:0 0 auto; height:1px; background:var(--edge) }` 顶边高光。
 *
 * **本实现只取"底 + 高光 + 发丝线"，不取 backdrop-blur**：条底改为不透明
 * （[InkTokens.glass] = `--bg`），背后模糊已无可见效果，保留它只会让滚动时
 * 每一帧多做一次全屏离屏采样 —— 这是列表滚动掉帧的最大单一来源。
 * 这里把它统一成 [Modifier.npGlassSurface]。
 */

/**
 * 条材质：**不透明底** + 顶边 1px 高光 + 可选底边发丝线。
 *
 * 用于 `.topbar` / `.playerbar` / `.mini` / `.tabbar`——它们**贴在滚动内容之上**，
 * 所以不给投影（投影会把"浮层"读成"卡片"），层次只靠高光与描边表达。
 *
 * 不透明是刻意的：半透明条会让滚动内容透出来干扰阅读，且迫使每帧做一次
 * 背后取样。层次改由 1px 发丝线承担，视觉更稳、绘制更省。
 */
fun Modifier.npGlassSurface(
    ink: InkTokens,
    shape: Shape = RoundedCornerShape(0.dp),
    topHighlight: Boolean = true,
    bottomHairline: Boolean = false,
    topHairline: Boolean = false,
): Modifier = this
    .clip(shape)
    .background(ink.glass)
    .then(
        if (topHairline) {
            Modifier.drawWithContent {
                drawContent()
                val y = 0.6f.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, ink.edge, Color.Transparent),
                    ),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        } else {
            Modifier
        },
    )
    .then(
        if (bottomHairline) {
            Modifier.drawWithContent {
                drawContent()
                val y = size.height - 0.5f.dp.toPx()
                drawLine(
                    color = ink.border,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        } else {
            Modifier
        },
    )
    .then(
        if (topHighlight) {
            Modifier.drawWithContent {
                drawContent()
                // 顶边高光只画中间一段并两端淡出（设计稿 `.npTopHairline` 等效物）
                // ——四边等亮就没有玻璃味，中间亮两端无才是光从上方来的读法。
                val inset = 16.dp.toPx()
                if (size.width > inset * 2) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.5f to ink.edge,
                                1f to Color.Transparent,
                            ),
                            startX = inset,
                            endX = size.width - inset,
                        ),
                        start = Offset(inset, 0.6f.dp.toPx()),
                        end = Offset(size.width - inset, 0.6f.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        } else {
            Modifier
        },
    )

/**
 * 卡片表面：实底 + 发丝描边。用于封面卡、分组卡这类"内容承载面"。
 *
 * 亮色下 `--surface` 与 `--bg` 同为纯白，这时唯一的层次来源就是这条描边，
 * 所以**描边不是装饰而是结构**，删掉卡片会糊成一片。
 */
fun Modifier.npCardSurface(
    ink: InkTokens,
    shape: Shape,
): Modifier = this
    .clip(shape)
    .background(ink.surface)
    .border(BorderStroke(1.dp, ink.border), shape)

/**
 * 顶边 1px 高光——任何表面上边缘内侧铺满的发丝亮线。
 * 用于 Sheet / 分组卡等"有实底"的面，与 [npGlassSurface] 的玻璃高光同源。
 */
fun Modifier.npTopHairline(ink: InkTokens): Modifier = drawWithContent {
    drawContent()
    val inset = 12.dp.toPx()
    val y = 0.6f.dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to ink.edge,
                1f to Color.Transparent,
            ),
            startX = inset,
            endX = size.width - inset,
        ),
        start = Offset(inset, y),
        end = Offset(size.width - inset, y),
        strokeWidth = 1.2f.dp.toPx(),
    )
}

/**
 * 设计稿 `--shadow`：`0 18px 48px -20px` ——**大模糊、负扩散**的"悬浮"投影。
 *
 * Compose 的 `shadow` 没有 spread 参数，用一个居中构图的大 elevation + 透明 ambient
 * 近似：负扩散的效果是投影比元素本身窄，读起来是"浮起来"而不是"描了一圈黑边"。
 */
@Composable
fun Modifier.npShadow(
    ink: InkTokens,
    shape: Shape,
    elevation: Dp = 18.dp,
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = ink.shadowColor.copy(alpha = if (ink.isDark) 0.40f else 0.14f),
    spotColor = ink.shadowColor.copy(alpha = if (ink.isDark) 0.55f else 0.20f),
)

/**
 * 浮层容器：贴内容之上的面板 / 圆形按钮（面板、中央播放键）。
 *
 * 与 [npGlassSurface] 的区别是会带一圈投影——浮层与背景不是同一张纸。
 *
 * 底是**不透明**的（同 [npGlassSurface]）：浮层要么被看见、要么不出现，
 * 让它半透明地"糊"在视频上既难读又要逐帧取样。
 */
@Composable
fun NpGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NpRadius.sheet),
    elevation: Dp = 12.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = modifier
            .npShadow(ink, shape, elevation)
            .npGlassSurface(ink, shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 外壳条（顶栏 / 标签栏 / 迷你条）的底色：**不透明**。
 *
 * 此前这里是设计稿 `backdrop-filter: blur(22px)` 的落地（Haze 离屏模糊 +
 * 内容层 `hazeSource` 取样）。用户明确要求标签栏与顶栏不做半透明后，模糊
 * 已经**不可见**——不透明底之后没有任何"背后的内容"可模糊——而它仍是每次
 * 滚动/切换都要付出的一次全屏离屏渲染，是掉帧的主要来源。故一并移除。
 *
 * 顶边 1px 高光与底边发丝线由调用点自行叠加（见 [npGlassSurface] / 各条实现），
 * 这里只负责"底色是实心的"这一条事实。
 */
fun Modifier.npGlassChrome(ink: InkTokens): Modifier = this.background(ink.glass)

/**
 * 设计稿 `.mini .lead-line` / `.prog i` 里的"进度线是骨架"：
 * 一段 2dp 的 accent 竖线，标记"这里正在播放"。
 *
 * 抽成独立组件是因为它在迷你条、播放页侧栏、选集面板三处出现，
 * 尺寸与颜色必须一致——否则同一条"骨架"在不同页面上粗细不一。
 */
@Composable
fun NpLeadLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NpDimens.railLeadLine)
            .background(LocalNpInk.current.accent),
    )
}
