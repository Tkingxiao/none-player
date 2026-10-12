package com.noneplayer.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * None Player 图标集——**路径逐字取自设计稿 `none-player.html` 的 `I` 表**。
 *
 * 为什么自绘而不是用 `material-icons-extended`：项目只依赖 `material-icons-core`
 * （只含 5 个基础图标），而 extended 会为几十个字形给 APK 增重 5MB+，且形状与设计稿
 * 并不一致（Material 用 filled 变体，设计稿是 24 视口 / 2px 描边的 monoline）。
 *
 * 保真手段：[addPathNodes] 直接解析 SVG 的 `d` 串，所以这里的每一段路径都能与设计稿
 * 的 `<path d="…">` 逐字符对照。**不要改成手写的 `moveTo/lineTo` 调用**——那正是
 * 形状漂移的来源。
 *
 * 两条纪律（brand-spec「Filled, not outlined」）：
 * - 选中态用填充变体（[Play] / [Pause] / [SkipNext]…），未选中用 2px 描边；
 * - 视觉尺寸走 `Modifier.size()`，颜色走 `Icon(tint=)`——图标本身不含颜色。
 */
object NpIcon {

    // ---- 导航（.rail-btn / .tabbar）----

    /** 首页：屋顶 + 屋身（设计稿 I.home 的两段） */
    val Home: ImageVector by lazy { stroke("NpHome", "M3 10.5 12 3l9 7.5M5 9.5V21h14V9.5") }

    val Folder: ImageVector by lazy {
        stroke("NpFolder", "M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    }

    /** 我的：头像圆 + 肩部弧 */
    val Person: ImageVector by lazy {
        stroke("NpPerson", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5")
    }

    val Gear: ImageVector by lazy {
        stroke(
            "NpGear",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0M19.4 15a1.6 1.6 0 0 0 .3 1.7l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.7-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 9 19.4a1.6 1.6 0 0 0-1.7.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.7 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.3-1.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.7.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.7-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.7V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z",
        )
    }

    // ---- 导航填充变体（设计稿 `.rail-btn.on .np-ico{fill:currentColor;stroke:none}`）----
    // 选中从描边变实心是整套导航的核心信号（brand-spec「Filled, not outlined」）。

    val HomeFill: ImageVector by lazy {
        // 描边版（屋顶两段 + 屋身）的实心化：单回路 + 拱形门洞
        filled("NpHomeFill", "M12 3l9 7.5V21h-6v-4.5a3 3 0 0 0-6 0V21H3v-10.5z")
    }

    val FolderFill: ImageVector by lazy {
        filled("NpFolderFill", "M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    }

    val PersonFill: ImageVector by lazy {
        filled("NpPersonFill", "M12 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8zM12 14.5c4.4 0 8 2.5 8 6.5v.9H4V21c0-4 3.6-6.5 8-6.5z")
    }

    /** 填充齿轮：Material 实心齿轮路径（非零环绕自带中孔），与描边版外形一致。 */
    val GearFill: ImageVector by lazy {
        filled(
            "NpGearFill",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z",
        )
    }

    val Moon: ImageVector by lazy {
        stroke("NpMoon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    }

    val Sun: ImageVector by lazy {
        stroke(
            "NpSun",
            "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 2v2M12 20v2M2 12h2M20 12h2" +
                "M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4",
        )
    }

    // ---- 顶栏 / 通用动作 ----

    val Search: ImageVector by lazy {
        stroke("NpSearch", "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0M20 20l-3.5-3.5")
    }

    val Close: ImageVector by lazy { stroke("NpClose", "M6 6l12 12M18 6 6 18") }

    val Check: ImageVector by lazy { stroke("NpCheck", "M4 12l5 5L20 6") }

    /** 右向箭头：返回键旋转 180° 复用（设计稿 `.listhead .back .xs`） */
    val ChevronRight: ImageVector by lazy { stroke("NpChevronRight", "M9 6l6 6-6 6") }

    val ArrowLeft: ImageVector by lazy { stroke("NpArrowLeft", "M15 5l-7 7 7 7") }

    /** 三点（⋯）——播放选项 */
    val Dots: ImageVector by lazy {
        stroke(
            "NpDots",
            "M3.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0M17.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0",
        )
    }

    // ---- 内容角标与行内图标 ----

    /** 视频角标：胶片框 + 齿孔（设计稿 I.film） */
    val Film: ImageVector by lazy {
        stroke(
            "NpFilm",
            "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4",
        )
    }

    /** 音频角标：喇叭 + 声波（设计稿 I.sound，也用作音量） */
    val Sound: ImageVector by lazy {
        stroke("NpSound", "M4 9v6h4l5 4V5L8 9zM16 9a4 4 0 0 1 0 6")
    }

    val Clock: ImageVector by lazy {
        stroke("NpClock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 7v5l3 2")
    }

    val Info: ImageVector by lazy {
        stroke("NpInfo", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 11v5M12 8h.01")
    }

    val Heart: ImageVector by lazy {
        stroke("NpHeart", "M12 20s-7-4.5-7-9.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 7 3.5c0 5-7 9.5-7 9.5z")
    }

    val Trash: ImageVector by lazy {
        stroke("NpTrash", "M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13")
    }

    /** 分享（§3.6 长按菜单）：三节点连线，24 视口 2px 描边 monoline */
    val Share: ImageVector by lazy {
        stroke(
            "NpShare",
            "M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 10.5l6.8-3.9M8.6 13.5l6.8 3.9",
        )
    }

    val Pip: ImageVector by lazy {
        stroke("NpPip", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM12 12h7v5h-7z")
    }

    /**
     * 耳机（设计稿 `#miniAudio` 与音频模式的"仅听音频"）。
     *
     * 设计稿的原始 path 是 `M4 14v-2a8 8 0 0 1 16 0v2` 加左右两个耳罩；
     * 这里按同一几何重写为闭合的耳罩外形——原 path 的耳罩用了 `a` 弧 + `z` 的组合，
     * 直接搬运会在 round 线帽下把耳罩画成两个开放的钩子，读不出"耳机"。
     */
    val Headphones: ImageVector by lazy {
        stroke(
            "NpHeadphones",
            "M4 14v-2a8 8 0 0 1 16 0v2" +
                "M6 14h1a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H6a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2z" +
                "M18 14h-1a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h1a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2z",
        )
    }

    /** 队列 / 选集：三行 + 圆点 */
    val List: ImageVector by lazy {
        stroke("NpList", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    }

    // ---- 批次 5 补的 5 枚（§3.7）----
    // 它们不是新语义，而是**收编借用工位**：画面比例此前借 GridView、截图与睡眠
    // 定时此前都借 Clock/Moon、播放列表此前借 List。借用会让"看图认功能"失效——
    // 同一枚字形在两个入口出现，用户只能靠文字。

    /** 画面比例：画框 + 对角线 + 左下角定位块 */
    val AspectRatio: ImageVector by lazy {
        stroke(
            "NpAspectRatio",
            "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z" +
                "M8 16 16 8" +
                "M8 12.5V16h3.5",
        )
    }

    /** 左右镜像：中轴 + 两只相向三角 */
    val Mirror: ImageVector by lazy {
        stroke("NpMirror", "M12 4v16M6 7v10l4-5zM18 7v10l-4-5z")
    }

    /** 截图：取景四角 + 中心镜头 */
    val Screenshot: ImageVector by lazy {
        stroke(
            "NpScreenshot",
            "M4 9V6a2 2 0 0 1 2-2h3" +
                "M15 4h3a2 2 0 0 1 2 2v3" +
                "M20 15v3a2 2 0 0 1-2 2h-3" +
                "M9 20H6a2 2 0 0 1-2-2v-3" +
                "M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        )
    }

    /** 睡眠定时：钟面 + 时分针 + 一枚 Z */
    val SleepTimer: ImageVector by lazy {
        stroke(
            "NpSleepTimer",
            "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0" +
                "M12 8.5V12l2.5 1.5" +
                "M15.5 2.5h5l-5 4h5",
        )
    }

    /** 播放列表：三行 + 右下角播放三角 */
    val QueueMusic: ImageVector by lazy {
        stroke("NpQueueMusic", "M4 7h12M4 12h12M4 17h7M16.5 14.5v5l4.5-2.5z")
    }

    /** 倍速：断口圆环 + 指针 */
    val Speed: ImageVector by lazy { stroke("NpSpeed", "M12 12l4-3M12 20a8 8 0 1 0-8-8") }

    /** 字幕：框 + 两条弧线 */
    val Subtitle: ImageVector by lazy {
        stroke("NpSubtitle", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM10 10a2.5 2.5 0 0 0 0 4M16 10a2.5 2.5 0 0 0 0 4")
    }

    /** 弹幕：气泡 + 两行飞过的文字（与 [Subtitle] 的"框内字幕"区分开） */
    val Danmaku: ImageVector by lazy {
        stroke(
            "NpDanmaku",
            "M6 5h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-6l-5 4v-4H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM8 9h8M8 12h5",
        )
    }

    val Expand: ImageVector by lazy {
        stroke("NpExpand", "M4 9V5a1 1 0 0 1 1-1h4M20 9V5a1 1 0 0 0-1-1h-4M4 15v4a1 1 0 0 0 1 1h4M20 15v4a1 1 0 0 1-1 1h-4")
    }

    /**
     * 退出全屏（§8.4）：[Expand] 的**反向**——折角由外向内。
     *
     * 存在的理由是"同一颗钮不能只有一个方向的形状"（E129 / 审查 #10）：全屏态若仍画
     * 向外展开的折角，图标承诺的动作（展开）与实际结果（退出）相反。描边/圆角与
     * [Expand] 逐段对齐，两枚在同一排里是同一套语言。
     */
    val Collapse: ImageVector by lazy {
        stroke("NpCollapse", "M9 4v4a1 1 0 0 1-1 1H4M15 4v4a1 1 0 0 0 1 1h4M9 20v-4a1 1 0 0 0-1-1H4M15 20v-4a1 1 0 0 1 1-1h4")
    }

    /** 循环：两条弧线 + 箭头 */
    val Rotate: ImageVector by lazy {
        stroke("NpRotate", "M4 12a8 8 0 0 1 13.7-5.6L20 8M20 12a8 8 0 0 1-13.7 5.6L4 16M20 4v4h-4M4 20v-4h4")
    }

    /** 排序：双向箭头 */
    val Sort: ImageVector by lazy {
        stroke("NpSort", "M7 4v16M7 20l-3-3M7 20l3-3M17 20V4M17 4l-3 3M17 4l3 3")
    }

    val GridView: ImageVector by lazy {
        stroke(
            "NpGridView",
            // 四个圆角方块各是一段独立 path；joinToString 避免字面量跨行拼接
            // （路径数据不能插空格，TextConcatSpace 会误报）。
            listOf(
                "M4.5 3h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 8.5 10h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3z",
                "M15.5 3h4A1.5 1.5 0 0 1 21 4.5v4A1.5 1.5 0 0 1 19.5 10h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3z",
                "M4.5 14h4A1.5 1.5 0 0 1 10 15.5v4A1.5 1.5 0 0 1 8.5 21h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14z",
                "M15.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z",
            ).joinToString(""),
        )
    }

    val ListView: ImageVector by lazy { stroke("NpListView", "M4 6h16M4 12h16M4 18h16") }

    // ---- 填充图标（播放 / 暂停 / 切集）----

    val Play: ImageVector by lazy { filled("NpPlay", "M8 5v14l11-7z") }

    val Pause: ImageVector by lazy {
        filled("NpPause", "M8 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM14 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
    }

    /** 上一集：左三角 + 前端竖条（设计稿 `M19 5v14l-9-7zM6 5v14` 的填充版） */
    val SkipPrevious: ImageVector by lazy {
        filled("NpSkipPrevious", "M7 5h1a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM19 5v14l-9-7z")
    }

    val SkipNext: ImageVector by lazy {
        filled("NpSkipNext", "M16 5h1a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM5 5v14l9-7z")
    }

    // ---- 播放页控制层（锁定键 / 顶栏自绘时钟电量，批次 2）----

    /** 锁定（闭环锁体 + 弧形锁梁）——播放页「锁屏」键 */
    val Lock: ImageVector by lazy {
        stroke("NpLock", "M7 11h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1zM8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    }

    /** 解锁（锁梁一端翘开）——锁定态的解锁键 */
    val LockOpen: ImageVector by lazy {
        stroke("NpLockOpen", "M7 11h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1zM8.5 11V7.5a3.5 3.5 0 0 1 6.9-.9")
    }

    /** 电池（横向壳体 + 正极端子 + 内部电量条）——顶栏自绘电量 */
    val Battery: ImageVector by lazy {
        stroke("NpBattery", "M3 8h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM20 10.5v3M5 10.5h6v3H5z")
    }

    // ---- 构造器 ----

    /**
     * 描边图标：24 视口 / 2px 描边 / round 端点与拐角——与设计稿 `.np-ico` 完全一致。
     *
     * [path] 直接喂给 [addPathNodes]，因此可以逐字粘贴设计稿的 `d` 串。
     */
    private fun stroke(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    /** 填充图标：形状自带闭合区域，着色由 `Icon(tint=)` 决定。 */
    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black),
        ).build()
}
