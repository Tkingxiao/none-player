package com.noneplayer.kernel.ui.api

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * 内核设置页的固定路由。
 *
 * 设置是**第四个顶层标签**（设计稿 `.tabbar` 四项 / `.rail` 底部一项），
 * 与首页/文件夹/我的同级：它有选中态、标签栏始终可见、没有返回箭头。
 * 早前它被注册成 `ScreenProvider`（压在栈上的二级页），于是打开时会盖住标签栏、
 * 顶部多一个返回箭头，看起来像"从设置里出去了"而不是"切到了设置"。
 */
const val SETTINGS_ROUTE = "kernel.settings"

/**
 * 「关于」页的固定路由（版本 / 架构 / 已加载插件清单 / 运行环境）。
 *
 * 定义在 `kernel-ui-api` 而不是 `:kernel`：**插件要能跳过来**（「我的」页有入口），
 * 而插件的依赖铁律只允许 `kernel-api / kernel-ui-api / core-*`，拿不到 `:kernel` 里的常量。
 * 这与 [SETTINGS_ROUTE] 同一处理。
 *
 * 它压在栈上（`ScreenProvider`），不是顶层标签——是从设置里下钻的详情页，
 * 所以顶栏有返回键。
 */
const val ABOUT_ROUTE = "kernel.about"

/**
 * 「插件」页的固定路由（已加载插件清单 + 扩展点统计）。
 *
 * 与 [ABOUT_ROUTE] 同样定义在 `kernel-ui-api`：插件要能跳过来。
 */
const val PLUGINS_ROUTE = "kernel.plugins"

/**
 * 切换到某个顶层 Tab 的通道（E53 配套）。
 *
 * **为什么需要它**：`TabProvider.register` 拿到的是**外层** `Navigator`（外层 NavHost 才管
 * 顶层路由），而 Tab 本身注册在**内层** Tab NavHost 里。所以插件页面里
 * `navigator.navigate("kernerl.settings")` 会去外层图找这个路由而找不到——
 * 表现为切不到设置 Tab 或直接崩。
 *
 * 页面要"跳到另一个 Tab"时一律用本通道，不拼路由：
 * ```
 * val switchTab = LocalNpTabSwitcher.current
 * switchTab(SETTINGS_ROUTE)
 * ```
 * 未在 Tab 骨架内（如压在栈上的播放页）时是安全的空实现。
 */
val LocalNpTabSwitcher: androidx.compose.runtime.ProvidableCompositionLocal<(String) -> Unit> =
    androidx.compose.runtime.staticCompositionLocalOf { { } }

/**
 * 外壳玻璃 backdrop-blur 的取样通道（Haze [HazeState]）。
 *
 * 内容层挂 `hazeSource`、玻璃条（tabbar / 迷你条 chrome 槽位）挂 `hazeEffect`，
 * 两者必须共享同一个 state 才能取到"玻璃后面的内容"。迷你条在插件里渲染，
 * 经本通道拿到外壳的 state；外壳之外（播放页等）为 null——玻璃退化为半透明底。
 */
val LocalNpShellHazeState: ProvidableCompositionLocal<HazeState?> =
    staticCompositionLocalOf { null }

/**
 * 底部玻璃条（迷你条 + tabbar）覆盖在内容之上的总高度。
 *
 * 设计稿的内容层是**延伸到玻璃条之下**的（`.mini{position:fixed}` / `.tabbar{position:fixed}`），
 * 所以各页的滚动容器不再被 Scaffold 内边距截断，而是自己把本值加进
 * `contentPadding.bottom`——滚动的最后一屏才能从玻璃条底下滑过。
 */
val LocalNpBottomInset: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }

/**
 * 主界面骨架——设计稿 `none-player.html` 的导航外壳，**随窗口形态自适应**。
 *
 * 两套形态与设计稿的 `data-tier` 一一对应：
 *
 * - **紧凑（Compact，手机竖屏）**：底部玻璃 Tab 栏（`.tabbar`）——标签由
 *   `TabProvider` 贡献，11sp 文字常驻、选中项 accent 色 + 实心图标；
 *   迷你条（`.mini`）贴在其上沿。
 * - **中/扩展（Medium / Expanded，平板横屏 / 折叠展开 / 桌面）**：左侧 80dp 玻璃 Rail
 *   （`.rail`）——顶部品牌块 "N"，导航项居中排布，**order 最大的那一项被推到底部**
 *   （设置就是这样的"关于这个应用"入口，空间上的分离承载这个语义）。
 *
 * **主题入口**：设计稿 `.rail` 底部有一个"主题"按钮（二态开关，
 * `toggleTheme` 在明/暗间翻转），由宿主经 [LocalNpThemeToggle] 注入后渲染
 * 在设置项上方；本应用的三态主题（跟随系统/浅色/深色）在设置页的"外观"区块仍是
 * 完整入口——Rail 按钮是快捷方式，设置页是完整语义。
 *
 * 材质令牌经 `MaterialTheme.colorScheme` 取用（`Theme.kt` 已把 Ink 令牌投影到 M3 槽位：
 * primary=accent、secondaryContainer=accentSoft、surface=surface、onSurface=fg、
 * onSurfaceVariant=muted、outlineVariant=border）——本模块与 `:core:ui` 之间刻意
 * **没有依赖边**（§13.2），令牌一致性靠投影保证，不靠直接引用。因此下面出现的
 * 少量 alpha 常量是投影无法表达的"玻璃配方"，集中在此一处。
 *
 * 两条分支共用**同一套** Tab 切换逻辑（`popUpTo(栈顶, inclusive, saveState)` + `restoreState`）：
 * 内层栈始终只有一个 Tab，任意 Tab 按返回=退出应用（E53），切换不丢滚动位置与文件夹层级。
 *
 * [tabNavController] 由外层 [KernelNavHost] 注入——**不能在此 `remember`**（E53）。
 */
@Composable
fun KernelTabScaffold(
    navigator: Navigator,
    tabNavController: NavHostController,
) {
    val kernel = LocalKernel.current
    // E22：响应式收集——插件启用/停用后底栏随注册表更新，无需重启。
    val tabs by remember { kernel.registry.observe<TabProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<TabProvider>())
    val sortedTabs = tabs.sortedBy { it.order }
    require(tabs.isNotEmpty()) {
        "未注册任何 TabProvider，主界面无内容。请在插件中实现 TabProvider 并注册。"
    }
    val duplicateIds = tabs.groupBy { it.tabId }.filterValues { it.size > 1 }.keys
    require(duplicateIds.isEmpty()) { "TabProvider tabId 冲突: $duplicateIds" }
    val duplicateRoutes = tabs.groupBy { it.route }.filterValues { it.size > 1 }.keys
    require(duplicateRoutes.isEmpty()) { "TabProvider 路由冲突: $duplicateRoutes" }

    val backStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val chromes by remember { kernel.registry.observe<BottomChromeProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<BottomChromeProvider>())
    val sortedChromes = chromes.sortedBy { it.order }

    // 切 Tab：栈内永远只保留一个 Tab（E53），状态靠 saveState/restoreState 成对保留。
    fun selectTab(route: String) {
        if (currentRoute == route) return
        val topDestinationId = tabNavController.currentDestination?.id
        tabNavController.navigate(route) {
            if (topDestinationId != null) {
                popUpTo(topDestinationId) {
                    inclusive = true
                    saveState = true
                }
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navHost: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = tabNavController,
            startDestination = sortedTabs.first().route,
            modifier = modifier,
        ) {
            sortedTabs.forEach { tab ->
                tab.register(builder = this, navigator = navigator)
            }
        }
    }

    val windowInfo = LocalNpWindowInfo.current
    // 玻璃 blur 的取样 state：内容层 hazeSource，玻璃条 hazeEffect（见 LocalNpShellHazeState）
    val hazeState = remember { HazeState() }
    // 底部玻璃条实测高度：内容层延伸到玻璃之下后，各页靠它做最后一屏的 contentPadding
    var bottomBarPx by remember { mutableIntStateOf(0) }
    val bottomInset = with(LocalDensity.current) { bottomBarPx.toDp() }

    // 骨架自己铺一层页面底：各页面的 `NpPageScaffold` 有顶部/两侧安全区内边距，
    // 内边距区域不会被页面的 background 覆盖；不在这里铺底的话，那圈露出来的是
    // 窗口底（`android:windowBackground`），一旦它与主题不同步就是一条灰边。
    // 1600dp 上限（设计稿 `.app{max-width:1600px}`）：超宽屏上整壳居中收窄。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1600.dp),
        ) {
        // 页面内"跳到另一个 Tab"的通道（见 [LocalNpTabSwitcher]）
        CompositionLocalProvider(
            LocalNpTabSwitcher provides ::selectTab,
            LocalNpShellHazeState provides hazeState,
            LocalNpBottomInset provides bottomInset,
        ) {
    if (windowInfo.isCompact) {
        // 紧凑：底部玻璃 Tab 栏 + Chrome（迷你条）贴上沿。内容层**不再被 innerPadding
        // 截断**——它延伸到玻璃条之下（设计稿 `.mini/.tabbar{position:fixed}`），
        // 各页用 LocalNpBottomInset 在自己的 contentPadding 里做最后一屏避让。
        Scaffold(
            bottomBar = {
                Column(
                    modifier = Modifier.onSizeChanged { bottomBarPx = it.height },
                ) {
                    sortedChromes.forEach { chrome -> chrome.Render(navigator) }
                    NpBottomTabBar(
                        tabs = sortedTabs,
                        currentRoute = currentRoute,
                        onSelect = ::selectTab,
                        hazeState = hazeState,
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            navHost(Modifier.fillMaxSize().hazeSource(hazeState))
        }
    } else {
        // 中/扩展：左侧玻璃 Rail。内容列占满、Chrome **悬浮在内容列底部**
        // （设计稿 `.mini{position:absolute;bottom:0}`），同样通过
        // LocalNpBottomInset 让各页避让。Rail 消费左侧/竖向安全区，
        // 内容列消费右侧/底部——横屏刘海、手势导航条在哪一侧都不遮挡。
        Row(modifier = Modifier.fillMaxSize()) {
            NpNavRail(
                tabs = sortedTabs,
                currentRoute = currentRoute,
                onSelect = ::selectTab,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.End + WindowInsetsSides.Bottom,
                        ),
                    ),
            ) {
                navHost(Modifier.fillMaxSize().hazeSource(hazeState))
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { bottomBarPx = it.height },
                ) {
                    sortedChromes.forEach { chrome -> chrome.Render(navigator) }
                }
            }
        }
    }
        }
        }
    }
}

// ---- 玻璃配方（投影无法表达的 alpha 常量，集中一处）----
// 数值逐条取自设计稿：--glass = bg@78%（暗）/ surface@74%（亮）、
// --accent-soft = accent @ 16%、--edge = #fff @ 8%（暗）/ 55%（亮）、Tab 栏描边 = --border。
private const val SHELL_GLASS_ALPHA_DARK = 0.78f
private const val SHELL_GLASS_ALPHA_LIGHT = 0.74f
private const val SHELL_ACCENT_SOFT_ALPHA = 0.16f
private const val SHELL_EDGE_ALPHA_DARK = 0.08f
private const val SHELL_EDGE_ALPHA_LIGHT = 0.55f

/** 选中态切换刚度（设计稿 `.16s --ease-out` 的 spring 换算值）。 */
private const val SHELL_STIFFNESS = 3200f

/**
 * GPU 模糊可信度：模拟器（ranchu/generic/sdk_gphone）上 RenderEffect.createBlurEffect
 * 静默输出空（gfxstream 缺陷，Pixel 10 Pro AVD 实测），一律退化用底色；真机不受影响。
 * 与 :core:ui 的同名探测各持一份——kernel-ui-api 与 core:ui 之间无依赖边（§13.2）。
 */
private fun isGpuBlurUnreliable(): Boolean =
    android.os.Build.FINGERPRINT.contains("generic") ||
        android.os.Build.FINGERPRINT.contains("emulator") ||
        android.os.Build.HARDWARE.contains("ranchu") ||
        android.os.Build.PRODUCT.contains("sdk_gphone")

/**
 * `.tabbar`：底部玻璃 Tab 栏。
 *
 * 设计稿的关键取舍：**文字标签常驻**，选中只改颜色 + 换成实心图标。
 * 隐藏未选中的文字会让用户失去"这一栏是什么"的线索；用 11sp 的小字表达克制，
 * 而不是用"藏起来"表达克制。
 */
@Composable
private fun NpBottomTabBar(
    tabs: List<TabProvider>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    hazeState: HazeState?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val edge = Color.White.copy(alpha = if (isDark) SHELL_EDGE_ALPHA_DARK else SHELL_EDGE_ALPHA_LIGHT)
    val glassAlpha = if (isDark) SHELL_GLASS_ALPHA_DARK else SHELL_GLASS_ALPHA_LIGHT

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hazeState != null && !isGpuBlurUnreliable()) {
                    // 玻璃条：blur(22px) + 表面着色（设计稿 `backdrop-filter:blur(22px)` +
                    // `background:var(--glass)`）。API 31+ 真实模糊，之下退化用底色。
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = MaterialTheme.colorScheme.background,
                            tints = listOf(
                                HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = glassAlpha)),
                            ),
                            blurRadius = 22.dp,
                            fallbackTint = HazeTint(MaterialTheme.colorScheme.surface),
                        ),
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = glassAlpha))
                },
            ),
    ) {
        // 顶边：1px 发丝描边 + 高光。设计稿 `.tabbar{border-top:1px solid var(--border)}`
        // 与 `.topbar::after` 是同一套"玻璃有边"的语言。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, edge, Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(vertical = 6.dp),
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                NpTabItem(
                    label = tab.label,
                    icon = tab.icon as? ImageVector,
                    selectedIcon = tab.selectedIcon as? ImageVector,
                    selected = selected,
                    onClick = { onSelect(tab.route) },
                    accent = accent,
                    muted = muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * `.rail`：左侧 80dp 玻璃导航栏。
 *
 * 与 Tab 栏的差异不只是方向（设计稿两套并非简单转置）：
 * Rail 有顶部品牌块 "N"，导航项之间留 6dp，底部单独一组放"设置"——
 * 设置不是"第四个目的地"，而是"关于这个应用"的入口，空间上的分离表达这个语义。
 */
@Composable
private fun NpNavRail(
    tabs: List<TabProvider>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // pinnedToEnd 的项（设置）被一个 weight 推到底部——空间上的分离承载
    // "它不是并列的内容区，而是关于这个应用"这层语义（设计稿 `.rail .spacer`）。
    val (pinned, main) = tabs.partition { it.pinnedToEnd }

    // 安全区**在固定宽度之外**消费：横屏刘海会给出约 22dp 的 Start inset，
    // 若把它作为 width(80.dp) 的内边距，Rail 的内容区只剩 58dp，
    // 而导航项本身宽 64dp —— "文件夹"三个字会被右缘裁掉（实测 952×427 横屏复现）。
    // 正确顺序是先让 inset 占位、再在剩余空间里放 80dp 的 Rail。
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Start + WindowInsetsSides.Vertical,
                ),
            ),
    ) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 品牌块：44dp、14dp 圆角、accent-soft 底 + accent 字
            Box(
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = SHELL_ACCENT_SOFT_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "N",
                    color = accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            main.forEach { tab ->
                NpRailItem(
                    label = tab.label,
                    icon = tab.icon as? ImageVector,
                    selectedIcon = tab.selectedIcon as? ImageVector,
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.route) },
                    accent = accent,
                    muted = muted,
                )
            }
            // 推到底部的那一项（设置）
            Box(modifier = Modifier.weight(1f))
            // 设计稿 `.rail` 底部的「主题」按钮（B6）：亮色显示月亮、暗色显示太阳，
            // 点击切换。主题状态由宿主经 [LocalNpThemeToggle] 注入（null 时不渲染）。
            val themeToggle = LocalNpThemeToggle.current
            if (themeToggle != null) {
                NpRailItem(
                    label = "主题",
                    icon = if (themeToggle.isDark) NpShellThemeIcons.Sun else NpShellThemeIcons.Moon,
                    selectedIcon = null,
                    selected = false,
                    onClick = themeToggle.onToggle,
                    accent = accent,
                    muted = muted,
                )
            }
            pinned.forEach { tab ->
                NpRailItem(
                    label = tab.label,
                    icon = tab.icon as? ImageVector,
                    selectedIcon = tab.selectedIcon as? ImageVector,
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.route) },
                    accent = accent,
                    muted = muted,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
    }
}

/** `.rail-btn`：64dp 宽 / 14dp 圆角 / 图标 + 11sp 文字；选中为 accent 字 + accent-soft 底 + 实心图标。 */
@Composable
private fun NpRailItem(
    label: String,
    icon: ImageVector?,
    selectedIcon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    muted: Color,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else muted,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "railTint",
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = SHELL_ACCENT_SOFT_ALPHA)
        } else {
            Color.Transparent
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "railBg",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.98f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "railScale",
    )
    // 按压缩放（设计稿 `.rail-btn:active{transform:scale(.94)}`）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "railPress",
    )
    // 「Filled, not outlined」：选中从描边变实心（设计稿 `.rail-btn.on .np-ico{fill:currentColor}`）
    val shownIcon = if (selected) selectedIcon ?: icon else icon
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
    ) {
        if (shownIcon != null) {
            Icon(
                imageVector = shownIcon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = scale * pressScale
                        scaleY = scale * pressScale
                    },
            )
        }
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** `.tabbar button`：等分宽度 / 图标 20dp + 11sp 文字；选中 accent + 实心图标 + 按压缩放。 */
@Composable
private fun NpTabItem(
    label: String,
    icon: ImageVector?,
    selectedIcon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    muted: Color,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else muted,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "tabTint",
    )
    // 按压缩放（设计稿 `:active` 语言的 tabbar 版本，取 chip 的 .96 档）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SHELL_STIFFNESS,
        ),
        label = "tabPress",
    )
    val shownIcon = if (selected) selectedIcon ?: icon else icon
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
        if (shownIcon != null) {
            Icon(
                imageVector = shownIcon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Rail 主题钮的图标——**路径与 `NpIcon.Moon` / `NpIcon.Sun` 逐字一致**。
 * 内核骨架层不能引用 core:ui（§13.2），所以这里自备一份；两处若画得不一样，
 * 同一个"主题"动作在 Rail 与设置页会长成两个图标。
 */
private object NpShellThemeIcons {
    val Moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpShellMoon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    val Sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpShellSun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 2v2M12 20v2M2 12h2M20 12h2" +
                    "M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4",
            ),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}
