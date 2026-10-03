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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import android.os.Build
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect


/**
 * None Player 材质系统（第 2 层配方）——由令牌组合出的"玻璃 / 高光 / 发丝线"。
 *
 * 设计稿的核心材质主张是 `.topbar / .mini / .tabbar` 上的
 * `background: var(--glass); backdrop-filter: blur(22px) saturate(150%)` 加一条
 * `::after { inset:0 0 auto; height:1px; background:var(--edge) }` 顶边高光。
 *
 * 这里把它统一成 [Modifier.npGlassSurface]。
 */

/**
 * 玻璃条材质：半透明底 + 顶边 1px 高光 + 可选底边发丝线。
 *
 * 用于 `.topbar` / `.playerbar` / `.mini` / `.tabbar`——它们**贴在滚动内容之上**，
 * 所以不给投影（投影会把"浮层"读成"卡片"），层次只靠高光与描边表达。
 *
 * [blurRadius] 为真实高斯模糊半径，0 表示不模糊。模糊是 `RenderEffect`，
 * 单条常驻 chrome 的开销可控；列表项**不要**用它（逐帧离屏重绘）。
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
 * 玻璃容器：贴内容之上的浮层（迷你条、控制层、面板）。
 *
 * 与 [npGlassSurface] 的区别是会带一圈投影——浮层与背景不是同一张纸。
 *
 * [strength] 已在设计稿对齐后取消：真实模糊（`RenderEffect`）在 API 31+ 可用，
 * 但在这层抽一个"模糊强度"参数会诱使调用点各挑一个值，而设计稿只有一种玻璃
 * （`blur(22px) saturate(150%)`）。需要更实的底时改 [elevation] 与
 * [npGlassSurface] 的 alpha，而不是调模糊。
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
 * 玻璃条材质的 backdrop-blur 版（设计稿 `backdrop-filter: blur(22px)` 的落地）。
 *
 * [state] 是与内容层共享的 [HazeState]：内容层挂 `hazeSource(state)`，玻璃条挂
 * 本修饰符后才能取到"玻璃后面的内容"做真实模糊。[state] 为 null（外壳之外、
 * 或 API 31 以下无 RenderEffect）时退化为半透明底色——这正是设计稿
 * `prefers-reduced-transparency` 的降级形态。
 *
 * **注意**：挂了本修饰符的容器不要再画 `ink.glass` 底色——着色由 effect 节点负责，
 * 叠两层会变闷。
 */
fun Modifier.npGlassChrome(
    state: HazeState?,
    ink: InkTokens,
    blurRadius: Dp = 22.dp,
): Modifier = composed {
    if (state != null && !isGpuBlurUnreliable()) {
        this.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = ink.bg,
                tints = listOf(HazeTint(ink.glass)),
                blurRadius = blurRadius,
                fallbackTint = HazeTint(ink.glass),
            ),
        )
    } else {
        // 无 haze（外壳之外）或 GPU 模糊不可靠：退化为半透明底——即设计稿
        // `prefers-reduced-transparency` 的降级形态。
        this.background(ink.glass)
    }
}

/**
 * GPU 模糊可信度探测：模拟器（ranchu/generic/sdk_gphone）上 `RenderEffect.createBlurEffect`
 * 会静默输出空层（gfxstream 缺陷，Pixel 10 Pro AVD 实测：blurRadius=0 一切正常、>0 整层消失），
 * 一律走 scrim 降级；真机不受影响。
 */
fun isGpuBlurUnreliable(): Boolean =
    Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.contains("emulator") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.PRODUCT.contains("sdk_gphone")

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
