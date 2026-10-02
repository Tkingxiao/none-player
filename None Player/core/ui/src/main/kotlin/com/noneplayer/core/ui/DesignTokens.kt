package com.noneplayer.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * None Player 设计令牌（第 1 层）——数值的唯一事实源。
 *
 * 美学主张「暗室与放映灯」：中性面不是冷灰，而是带琥珀色相的**暖黑**（与 accent 同色相），
 * 让"暖"成为全画面的环境光而非贴上去的橙色；accent 只落在"正在播放"与"主操作"上。
 * 参照 ECHOAndroid 的 `EchoColors` 纪律（同栈 Compose 实现）与本项目《界面设计路线.md》§3。
 *
 * 本文件位于 :core:ui（令牌所在层），允许写裸色值与裸 dp；插件层禁止硬编码，只能引用这里。
 */

/** 语义色令牌集：一套暗、一套亮，经 [LocalNpInk] 下发。 */
data class InkTokens(
    val void: Color,          // 视频之外的"暗室"衬布 / 状态栏
    val bg: Color,            // 页面底
    val surface1: Color,      // 卡片 / 分组列表
    val surface2: Color,      // 浮层面板 / Sheet
    val surface3: Color,      // 最高层：菜单 / Toast / 选中行
    val heading: Color,       // 标题（暖白，减眩光）
    val label: Color,         // 正文
    val label2: Color,        // 次要文字
    val label3: Color,        // 时间码灰 / 禁用文字
    val separator: Color,     // 分割线（accent 派生或半透明，非实色灰线）
    val border: Color,        // 面板描边
    val borderStrong: Color,  // 选中态边框
    val accent: Color,        // 主操作 / "正在播放"
    val accentStrong: Color,  // 深色面板上的强调文字 / 图标高亮
    val accentSoft: Color,    // 选中底色（弱）
    val accentBg: Color,      // 选中底色（强 / hover）
    val onAccent: Color,      // accent 实底上的文字
    val destructive: Color,   // 删除 / 失败
    val scrim: Color,         // 遮罩
    val isDark: Boolean,
)

/** 暗色：暖黑暗室 + 琥珀放映灯（§3.1 的 amberNoir）。 */
val NpInkDark = InkTokens(
    void = Color(0xFF0B0A08),
    bg = Color(0xFF11100E),
    surface1 = Color(0xFF191510),
    surface2 = Color(0xFF211A12),
    surface3 = Color(0xFF2A2118),
    heading = Color(0xFFFFF3D8),
    label = Color(0xFFF0EBE2),
    label2 = Color(0xFFA89F92),
    label3 = Color(0xFF6E675D),
    separator = Color(0x14FFFFFF),      // White @ .08
    border = Color(0x29FF9F0A),         // accent @ .16
    borderStrong = Color(0x4DFF9F0A),   // accent @ .30
    accent = Color(0xFFFF9F0A),
    accentStrong = Color(0xFFFFD08A),
    accentSoft = Color(0x21FF9F0A),     // accent @ .13
    accentBg = Color(0x38FF9F0A),       // accent @ .22
    onAccent = Color(0xFF1C150E),
    destructive = Color(0xFFFF453A),
    scrim = Color(0x9E080A0E),          // #080A0E @ .62
    isDark = true,
)

/** 亮色：暖米纸（现有亮色 token 的暖化版）。 */
val NpInkLight = InkTokens(
    void = Color(0xFFF7F4EE),
    bg = Color(0xFFFBF7EE),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF3EDE0),
    surface3 = Color(0xFFEAD9BB),
    heading = Color(0xFF1A150F),
    label = Color(0xFF1A150F),
    label2 = Color(0xFF5C5145),
    label3 = Color(0xFF8C8274),
    separator = Color(0x1A1A150F),      // #1A150F @ .10
    border = Color(0x2EC24E00),         // #C24E00 @ .18
    borderStrong = Color(0x4DC24E00),   // #C24E00 @ .30
    accent = Color(0xFFC24E00),
    accentStrong = Color(0xFF8A3800),
    accentSoft = Color(0x21C24E00),     // @ .13
    accentBg = Color(0x38C24E00),       // @ .22
    onAccent = Color(0xFFFFFFFF),
    destructive = Color(0xFFD70015),
    scrim = Color(0x731A150F),          // #1A150F @ .45
    isDark = false,
)

/** 语义色令牌下发通道（随明暗切换，复用 Theme 的单进度插值）。 */
val LocalNpInk = staticCompositionLocalOf { NpInkDark }

/**
 * alpha 阶梯——"材质感"的来源。组件不许各挑一个 `.5f`，统一走这里对齐（ECHO 桌面纪律）。
 */
object NpAlpha {
    const val panel = 0.72f        // 玻璃面板底（Sheet / 控制层底）
    const val panelActive = 0.92f  // hover / 激活
    const val field = 0.78f        // 输入框
    const val button = 0.66f
    const val buttonActive = 0.90f
    const val row = 0.60f          // 列表行
    const val rowActive = 0.82f    // 选中行
    const val chip = 0.68f
    const val player = 0.91f       // 播放页控制层（接近不透明）
}

/** 圆角：8 为基、999 为丸（§3.4）。 */
object NpRadius {
    val chip: Dp = 999.dp
    val cover: Dp = 10.dp
    val card: Dp = 16.dp
    val panel: Dp = 24.dp
    val sheet: Dp = 28.dp
    val miniCollapsed: Dp = 20.dp
}

/** 4 基间距网格（§3.5）。 */
object NpSpace {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
}

/** 组件关键尺寸（§5）。 */
object NpDimens {
    val transportButton: Dp = 40.dp
    val transportIcon: Dp = 22.dp
    val primaryPlay: Dp = 72.dp
    val miniHeight: Dp = 64.dp
    val listRowMin: Dp = 48.dp
    val touchTarget: Dp = 44.dp
    /** 自适应网格每格最小宽度（E47：GridCells.Adaptive）。
     *  紧凑档 ~120dp（手机 3 列、平板更多）；舒适档 ~176dp（手机 2 列、平板 3–4 列）。 */
    val gridMinCell: Dp = 120.dp
    val gridMinCellComfy: Dp = 176.dp
}
