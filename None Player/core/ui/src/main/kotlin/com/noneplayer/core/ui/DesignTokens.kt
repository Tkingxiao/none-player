package com.noneplayer.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * None Player 设计令牌（第 1 层）——数值的唯一事实源。
 *
 * 视觉契约来自设计稿 `none-player.html`（`D:\Code\Player\音乐播放器界面设计`）。
 * 设计稿用 OKLch 写颜色，这里给出等价的 sRGB 常量；两者是**同一个真相**，
 * 差异只来自色彩空间，不是重新调色。accnet 是"直播信号"而非装饰：只出现在
 * 选中态、进度填充、主操作与"新增"角标上。
 *
 * 本文件位于 :core:ui（令牌所在层），允许写裸色值与裸 dp；插件层禁止硬编码，只能引用这里。
 */

/**
 * 语义色令牌集：设计稿 [data-theme] 的暗/亮两套，经 [LocalNpInk] 下发。
 *
 * 命名对齐设计稿变量：`--bg / --surface / --surface-2 / --fg / --muted / --border / --accent`。
 * 设计稿的 `color-mix(...)` 派生色在这里预乘为带 alpha 的实色——Compose 的
 * `Color` 已含 alpha，运行时再 mix 一遍只会多一层计算。
 */
data class InkTokens(
    val bg: Color,            // --bg 页面底
    val surface: Color,       // --surface 卡片 / Sheet / 浮层面板
    val surface2: Color,      // --surface-2 纯占位底（封面未加载）
    val fg: Color,            // --fg 主文字（设计稿刻意不用纯白）
    val muted: Color,         // --muted 次要文字 / 时间码 / 未选中图标
    val border: Color,        // --border 发丝描边 / 分割线
    val accent: Color,        // --accent 琥珀橙：选中、进度、主操作
    val accentSoft: Color,    // --accent-soft 选中底
    val accentLine: Color,    // --accent-line 选中描边
    val fgSoft: Color,        // --fg-soft 6% 前景：hover / 次级填充
    val fgFaint: Color,       // --fg-faint 3% 前景：最轻的 hover
    val glass: Color,         // --glass 玻璃条底（近不透明 + 模糊）
    val edge: Color,          // --edge 玻璃顶部 1px 高光
    val scrim: Color,         // --scrim Sheet 遮罩
    val success: Color,       // 成功
    val danger: Color,        // --danger 危险 / 移除
    val dangerSoft: Color,    // --danger-soft 危险底
    val isDark: Boolean,
) {
    /** 设计稿 `--shadow` 的主色：暗色下是纯黑投影，亮色下也是纯黑、只降 alpha。 */
    val shadowColor: Color get() = Color.Black
}

/**
 * 暗色：设计稿 `:root` 默认套（近黑 + 暖中性）。
 *
 * 注意 `--bg` 是纯黑 `oklch(0 0 0)`——暗色下页面底与 OLED 熄屏同色，
 * 内容浮在"暗室"里而不是灰盒子里。
 */
val NpInkDark = InkTokens(
    bg = Color(0xFF000000),
    surface = Color(0xFF010101),      // oklch(.075 0 0)
    surface2 = Color(0xFF060606),     // oklch(.12 0 0)
    fg = Color(0xFFF6F5F2),           // oklch(.97 .004 90)
    muted = Color(0xFF9C9792),        // oklch(.68 .01 70)
    border = Color(0xFF322D29),       // oklch(.30 .01 60)
    accent = Color(0xFFF58028),       // oklch(.72 .17 52)
    accentSoft = Color(0x29F58028),   // accent @ 16%
    accentLine = Color(0x73F58028),   // accent @ 45%
    fgSoft = Color(0x0FF6F5F2),       // fg @ 6%
    fgFaint = Color(0x08F6F5F2),      // fg @ 3%
    glass = Color(0xC7000000),        // bg @ 78%
    edge = Color(0x14FFFFFF),         // #fff @ 8%
    scrim = Color(0x9E000000),        // black @ 62%
    success = Color(0xFF54C57A),      // oklch(.74 .15 152)
    danger = Color(0xFFFC5855),       // oklch(.68 .20 25)
    dangerSoft = Color(0x2EFC5855),   // danger @ 18%
    isDark = true,
)

/**
 * 亮色：设计稿 `[data-theme="light"]` 套。
 *
 * `--bg` 与 `--surface` **同为纯白**：亮色下卡片不靠"底色差"分层，靠发丝描边与
 * 投影分层（设计稿 `--shadow` 亮色只有 22% 黑）。这是刻意的——一白到底更像纸。
 */
val NpInkLight = InkTokens(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF7F7F7),     // oklch(.975 0 0)
    fg = Color(0xFF231E1A),           // oklch(.24 .01 62)
    muted = Color(0xFF6E6862),        // oklch(.52 .012 68)
    border = Color(0xFFDFDFDF),       // oklch(.905 0 0)
    accent = Color(0xFFCE5600),       // oklch(.60 .17 46)
    accentSoft = Color(0x21CE5600),   // accent @ 13%
    accentLine = Color(0x66CE5600),   // accent @ 40%
    fgSoft = Color(0x0B231E1A),       // fg @ 4.5%
    fgFaint = Color(0x06231E1A),      // fg @ 2.5%
    glass = Color(0xBCFFFFFF),        // surface @ 74%
    edge = Color(0x8CFFFFFF),         // #fff @ 55%
    scrim = Color(0x6B423930),        // oklch(.35 .02 65) @ 42%
    // 设计稿亮色未覆盖 --success，沿用暗色值（oklch(.74 .15 152)）
    success = Color(0xFF54C57A),
    danger = Color(0xFFCC2827),       // oklch(.55 .20 27)
    dangerSoft = Color(0x1FCC2827),   // danger @ 12%
    isDark = false,
)

/** 语义色令牌下发通道（随明暗切换，复用 Theme 的单帧交叉淡化）。 */
val LocalNpInk = staticCompositionLocalOf { NpInkDark }

/**
 * alpha 阶梯——"材质感"的来源。组件不许各挑一个 `.5f`，统一走这里对齐。
 */
object NpAlpha {
    const val panel = 0.78f          // 玻璃面板底（顶栏 / 迷你条 / Tab 栏 78%）
    const val panelDense = 0.92f     // 高密度浮层
    const val field = 0.78f          // 输入框
    const val row = 0.60f            // 列表行
    const val rowActive = 0.82f      // 选中行
    const val chip = 0.68f
    const val player = 0.91f         // 播放页控制层（近不透明）
}

/**
 * 圆角阶梯（设计稿 `--r-card / --r-hero / --r-sheet / --r-chip`）。
 *
 * 设计稿只有四档：卡片 16 / 主视觉 24 / 底部面板顶 20 / 丸 999。
 * 额外的 [cover] 与 [button] 是视频封面的 8–11dp 小区间（设计稿 `.mini img` 8px、
 * `.listrow .lr-thumb` 11px），归一到此处以免各处各写一个 dp。
 */
object NpRadius {
    val chip: Dp = 999.dp
    val cover: Dp = 8.dp         // 迷你条封面
    val tile: Dp = 11.dp         // 列表行缩略图 / 圆形图标底
    val button: Dp = 12.dp       // .iconbtn / .fsel 菜单按钮
    val card: Dp = 16.dp         // 卡片
    val sheet: Dp = 20.dp        // 底部 Sheet 顶
    val hero: Dp = 24.dp         // 主视觉 / 文件夹头
    val rail: Dp = 14.dp         // 侧边栏按钮 / 品牌块
}

/** 8 点网格间距（设计稿 page gutter 紧凑档 16dp、内容档 20dp）。 */
object NpSpace {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
}

/**
 * 组件关键尺寸（设计稿逐条量取）。
 *
 * 触控目标红线 44dp 在 [touchTarget]；设计稿里少数视觉更小的圆形图标按钮
 * （如 `.fs-opt button` 38dp）实现时用 44dp 命中区 + 38dp 视觉，二者分开。
 */
object NpDimens {
    val touchTarget: Dp = 44.dp
    val transportButton: Dp = 40.dp
    val transportIcon: Dp = 22.dp
    val primaryPlay: Dp = 56.dp      // .audio-controls .rnd
    val miniHeight: Dp = 54.dp       // .mini-in 行高
    val listRowMin: Dp = 56.dp       // .row-item min-height
    val railWidth: Dp = 80.dp        // .rail
    val railButton: Dp = 64.dp
    val tabbarHeight: Dp = 64.dp     // .tabbar（含安全区前）
    val topbarIcon: Dp = 44.dp       // .iconbtn
    val chipHeight: Dp = 34.dp       // .chip 视觉高
    val trackLine: Dp = 3.dp         // .track .bar
    val miniProgress: Dp = 2.dp      // .mini-prog
    val railLeadLine: Dp = 2.dp      // .mini .lead-line
    val switchWidth: Dp = 46.dp      // .switch
    val switchHeight: Dp = 27.dp
    val heroCover: Dp = 54.dp        // .folder-head .ficon
    val ficoBox: Dp = 40.dp          // .fico
    val railCardWidth: Dp = 184.dp   // .rail-scroll .cw
    val themeTile: Dp = 50.dp        // .opt-tile .oi
    /** 阅读型内容最大宽度（设计稿 .content max-width:1100px）。 */
    val contentMaxWidth: Dp = 1100.dp
}
