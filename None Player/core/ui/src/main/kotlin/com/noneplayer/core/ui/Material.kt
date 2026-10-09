package com.noneplayer.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min


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
 * 条材质：**不透明底** + 顶边 1px 高光 + 可选顶/底发丝线。
 *
 * 用于 `.topbar` / `.playerbar` / `.mini` / `.tabbar`——它们**贴在滚动内容之上**，
 * 所以不给投影（投影会把"浮层"读成"卡片"），层次只靠高光与描边表达。
 *
 * 两根线各有语义，别混用（§4.2 / §4.3）：
 * - [topHighlight] 是**高光**：`ink.edge`，只画中间一段（两端内缩 16dp 后淡出），
 *   说"这里有一块实体的边、光从上方来"。四边等亮就没有玻璃味。
 * - [topHairline] / [bottomHairline] 是**发丝线**：`ink.border` 实色通栏，
 *   说"内容到此被干净切断"，只在语义边界出现。
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
                drawLine(
                    color = ink.border,
                    start = Offset(0f, 0.5f.dp.toPx()),
                    end = Offset(size.width, 0.5f.dp.toPx()),
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
 *
 * elevation 乘上档位的 [NpMotionQuality.shadowFactor]：阴影是逐帧的高斯，轻量档减半
 * （18→9dp）换回的是滚动期一整块离屏合成。**只缩高度不改颜色与规则**——
 * "只有 L3 有阴影、L2 永不、L1 卡片无阴影"这条分层纪律在三档都一样。
 */
@Composable
fun Modifier.npShadow(
    ink: InkTokens,
    shape: Shape,
    elevation: Dp = 18.dp,
): Modifier = this.shadow(
    elevation = elevation * npQuality().shadowFactor,
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
 * 定稿依据是 R1：**禁止**任何背后有滚动内容或视频帧的面做模糊——逐帧全屏离屏采样
 * 是列表滚动掉帧的最大单一来源，而条底一旦不透明，模糊就没有可被看见的对象。
 * 层次改由"光"表达：顶边 1px 高光（[npTopHairline]）说"这里有一块实体的边"，
 * 发丝线说"内容到此被干净切断"。
 *
 * 横向的条一律走 [npGlassSurface]（它就是"这块底 + 那两根线"的完整配方）；
 * 只剩竖条（`.rail`）用得上这个裸底——它没有"顶边"可言。
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

/**
 * L4 渐隐遮罩（§4.5，R11）：一块"和它要遮住的那个底同色"的渐变，
 * 用来把滚动内容干净地收进条下 / 画面边缘，而不是让内容被硬切一刀。
 *
 * 它**不是**模糊的替代品：不采样背后像素，只画一层带 alpha 渐变的实心色，
 * 所以零离屏成本——这正是 R1 之后本应用唯一允许的"边缘柔化"手段。
 *
 * 两处纪律：
 * - [color] 必须等于被遮住的底（页面底取 `ink.bg`、Sheet 内取 `ink.surface`）。
 *   亮色主题下**不许**出现黑渐变——会读成"脏边"；只有遮在**视频画面**上才允许纯黑
 *   （视频不是主题色，四种主题下它都是那块亮的东西）。
 * - [startAlpha] 默认 0.9 而非 1.0：留一点"底下还在动"的暗示，
 *   完全不透的边带会读成一条色块压在内容上。
 * - [floorAlpha] 是**中段那个 stop 的下限**（默认 0 = 沿用 `startAlpha * 0.30`）。
 *   压字的控制层要它：白画面（过曝帧、白底标题卡）上中段那点 alpha 抬不起
 *   白图标，§14 #9 的 4.5:1 红线会在"控件正好落在渐变中段"时塌陷——
 *   只有压在视频上的控制层需要，页面底渐隐不该拿它垫黑。
 */
@Composable
fun NpMaskFade(
    color: Color,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    fromTop: Boolean = true,
    size: Dp = 88.dp,
    startAlpha: Float = 0.9f,
    stops: Float = 0.55f,
    floorAlpha: Float = 0f,
) {
    fun at(alpha: Float) = color.copy(alpha = alpha)
    val midAlpha = maxOf(startAlpha * 0.30f, floorAlpha)
    // 中段过渡位按渐变**起点**算：fromTop=false 时整条曲线镜像，
    // 否则"从下往上渐隐"会变成"下面先淡掉"，读起来方向反了。
    val gradientStops = if (fromTop) {
        arrayOf(0f to at(startAlpha), stops to at(midAlpha), 1f to at(0f))
    } else {
        val mirrored = 1f - stops
        arrayOf(0f to at(0f), mirrored to at(midAlpha), 1f to at(startAlpha))
    }
    val brush = if (vertical) {
        Brush.verticalGradient(*gradientStops)
    } else {
        Brush.horizontalGradient(*gradientStops)
    }
    Box(
        modifier = modifier
            .then(if (vertical) Modifier.fillMaxWidth().height(size) else Modifier.width(size).fillMaxHeight())
            .background(brush),
    )
}

/**
 * [NpMaskFade] 的 modifier 形态，专供**横滑列表两端**（§4.5 第三处）：
 * 左右各一条 [fadeSize] 宽的渐隐带，颜色同页面底。
 *
 * 之所以是 modifier 而不是两个叠在上面的 [NpMaskFade]：rail 的容器就是 `LazyRow`，
 * 用组合形态得为每个 rail 多包一层 Box + 两次 align，五处重复；
 * 这里在 `drawWithContent` 之后补两次 `drawRect`，成本与语义都完全一致。
 *
 * 两端**常驻**而不按滚动位置显隐是有意的：rail 有 `contentPadding`（14–20dp gutter），
 * 渐隐最强的那一段正好落在留白上，摸不到第一格的封面；
 * 换来的是不需要读 `LazyListState`、滚到底也不会看到"色带跳一下"。
 *
 * [fadeStart] / [fadeEnd]：类 PC 右栏占位时，横滑列表的**右端**正好贴着栏边界，
 * 渐隐带会被读成"列表与播放栏之间的半透明过渡"（2026-10-07 用户实报）——
 * 此时调用方传 `fadeEnd = false`，让边界回到硬分割。
 */
fun Modifier.npMaskFadeEdges(
    color: Color,
    fadeSize: Dp = 24.dp,
    startAlpha: Float = 0.9f,
    fadeStart: Boolean = true,
    fadeEnd: Boolean = true,
): Modifier = drawWithContent {
    drawContent()
    val band = min(fadeSize.toPx(), size.width / 2f)
    if (band <= 0f) return@drawWithContent
    val h = size.height
    fun at(alpha: Float) = color.copy(alpha = alpha)
    if (fadeStart) {
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to at(startAlpha),
                    0.55f to at(startAlpha * 0.30f),
                    1f to at(0f),
                ),
                startX = 0f,
                endX = band,
            ),
            size = Size(band, h),
        )
    }
    if (fadeEnd) {
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to at(0f),
                    0.45f to at(startAlpha * 0.30f),
                    1f to at(startAlpha),
                ),
                startX = size.width - band,
                endX = size.width,
            ),
            topLeft = Offset(size.width - band, 0f),
            size = Size(band, h),
        )
    }
}
