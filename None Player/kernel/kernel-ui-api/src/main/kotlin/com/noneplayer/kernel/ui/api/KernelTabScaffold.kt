package com.noneplayer.kernel.ui.api

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.Navigator as NavLibNavigator
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NoneMark
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npGlassChrome
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.silkFloat
import com.noneplayer.core.ui.silkOffset
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import kotlin.math.roundToInt


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
 * 底部条（迷你条 + tabbar）覆盖在内容之上的总高度。
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
// 紧凑档的 Scaffold 刻意不消费 innerPadding（见紧凑分支里的注释：内容层要延伸到
// 迷你条/Tab 栏之下，各页自己用 LocalNpBottomInset 做最后一屏避让），所以这条 lint 是有意为之。
@Composable
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
fun KernelTabScaffold(
    navigator: Navigator,
    tabNavController: NavHostController,
) {
    val kernel = LocalKernel.current
    val windowInfo = LocalNpWindowInfo.current
    // E22：响应式收集——插件启用/停用后底栏随注册表更新，无需重启。
    val tabs by remember { kernel.registry.observe<TabProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<TabProvider>())
    require(tabs.isNotEmpty()) {
        "未注册任何 TabProvider，主界面无内容。请在插件中实现 TabProvider 并注册。"
    }
    // 排序与冲突校验都**只随 tabs 变化重算**：此前它们写在组合体内，每次重组
    // （含每次切 Tab 的导航状态变化）都要重新 groupBy/sortedBy 分配中间集合。
    val sortedTabs = remember(tabs) {
        val dupIds = tabs.groupBy { it.tabId }.filterValues { it.size > 1 }.keys
        require(dupIds.isEmpty()) { "TabProvider tabId 冲突: $dupIds" }
        val dupRoutes = tabs.groupBy { it.route }.filterValues { it.size > 1 }.keys
        require(dupRoutes.isEmpty()) { "TabProvider 路由冲突: $dupRoutes" }
        tabs.sortedBy { it.order }
    }

    val backStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val chromes by remember { kernel.registry.observe<BottomChromeProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<BottomChromeProvider>())
    val sortedChromes = remember(chromes) { chromes.sortedBy { it.order } }
    // §8.2 路径 A：类 PC 档的右栏（内联播放栏）贡献者。同时只用一个（按 order 取首个），
    // 多栏分屏不在本批范围。
    val panes by remember { kernel.registry.observe<DetailPaneProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<DetailPaneProvider>())
    val sortedPanes = remember(panes) { panes.sortedBy { it.order } }

    // 切 Tab 的方向（M4 的"内容方向淡移 8dp"用它）：Tab 序里"从哪来 → 到哪去"的符号。
    // 0 = 方向未知（冷启、内层压了二级页），那就只淡不挪。
    // 必须在 `selectTab` 之前声明——它是被那个局部函数捕获的。
    val tabShiftDirection = remember { mutableIntStateOf(0) }
    // 切 Tab：栈内永远只保留一个 Tab（E53），状态靠 saveState/restoreState 成对保留。
    fun selectTab(route: String) {
        if (currentRoute == route) return
        // M4 的 `Tick` 已撤（2026-10-14 用户实报"切换界面不要震动"）：换目的地是**导航**，
        // 不是有分量的操作。触觉只留给长按、破坏性确认与进度擦洗这类直接操纵。
        val from = sortedTabs.indexOfFirst { it.route == currentRoute }
        val to = sortedTabs.indexOfFirst { it.route == route }
        tabShiftDirection.intValue = if (from >= 0 && to >= 0) to.compareTo(from) else 0
        // E128 关键一步：把**将要离场**的 Tab 条目标记为"过渡中"（ComposeNavigator 的
        // 公开 API，本意给预测性返回用）。
        //
        // 为什么必须有它：`popUpTo(当前页, inclusive=true)` 与 navigate 在同一事务里执行，
        // NavController 会把被弹出的条目直接标记成 DESTROYED（`popEntryFromBackStack`
        // 的 `transitioning=false` 分支），它随即从 `visibleEntries` 消失；而
        // navigation-compose 的 transitionSpec 有一条明确规则——**初态不在 visibleEntries
        // 就整个跳过动画**（`EnterTransition.None togetherWith ExitTransition.None`）。
        // 读值上就是：胶囊在飞、内容硬切——这正是 2026-10-11 用户实报的
        // "不是界面的切换动画"（E127 只验了条上的三件，没验到这一层）。
        // 先 `prepareForTransition` 后，弹出分支改走"保留 CREATED 形态等过渡收尾"，
        // 条目留在 visibleEntries 里，内容过渡才真正播放；收尾由 NavHost 自己调
        // `onTransitionComplete` 完成销毁，`saveState` 的存档语义不变（E53 不破）。
        // 取当前 Tab 所在导航器：NavDestination 只公开 `navigatorName`，实例要经 provider
        // 查（`ComposeNavigator.NAME` 是 internal，但这层查出来的就是它——Tab 目的地都由
        // composable 导航器承载，`as?` 兜住理论上的其他承载者）。
        val composeNavigator = tabNavController.currentDestination?.navigatorName
            ?.let { tabNavController.navigatorProvider.getNavigator<NavLibNavigator<out NavDestination>>(it) }
            as? ComposeNavigator
        composeNavigator?.backStack?.value?.lastOrNull()?.let { outgoing ->
            composeNavigator.prepareForTransition(outgoing)
        }
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

    // 内层导航图的构建块**必须是稳定引用**：`NavHost` 以
    // `remember(route, startDestination, builder)` 缓存图，`builder` 的 lambda 身份
    // 一旦每次重组都变（本骨架会随每次 Tab 切换重组），整张 Tab 图就会被重建——
    // 表现为"切一次标签重新注册一次所有目的地"。按 sortedTabs/navigator 记忆后，
    // 切 Tab 只是换目的地，图不变。
    val tabGraph: NavGraphBuilder.() -> Unit = remember(sortedTabs, navigator) {
        {
            sortedTabs.forEach { tab ->
                tab.register(builder = this, navigator = navigator)
            }
        }
    }

    // ---- 标签内容转场（E128 定稿；取代 E127 的"8dp 淡移"）----
    //
    // 四件套：交叉淡入（fade-through）+ 双向纵深缩放 + 方向视差位移 + 退场加速。
    // 时长读在组合期：下面的 lambda 跑在 TransitionScope，取不到 LocalNpMotionQuality。
    //
    // 为什么不是"整页滑"（§7.2 的红线仍成立）：位移轴**跟随导航轴**、幅度 28–36dp——
    // 紧凑档底栏是横向排列 → 水平轴；Rail 是纵向排列 → 垂直轴。整页滑会在半路把
    // 两页各露一半，而这里的位移比页宽小一个量级，两页始终同向平移、读成"换页的那一下"。
    //
    // 时长走 Nav* 三兄弟而不是 `Tab`：内容转场在语义上就是一次路由转场（Tab 是内层
    // NavHost 的平级目的地），`Tab`(220) 留给标签栏自己的三件（胶囊滑动 / 图标缩放 / 色变）。
    // 280 的 Silk 前段极快（约 40% 时间走完 90% 进度），读起来接近 200ms 的响应；
    // 退场用 SilkExit(180) 加速离开——"进慢出快"是 §3.9 的既定方向。
    val tabEnterMs = npDuration(Motion.NavEnter)
    val tabExitMs = npDuration(Motion.NavExit)
    // 缩放是位移的一种，轻量档 / Reduce Motion 下并掉，只留淡入淡出。
    val tabScale = if (npDisplacementAllowed()) TAB_CONTENT_SCALE else 1f
    val tabAxisVertical = !windowInfo.isCompact
    val tabShiftPx = if (npDisplacementAllowed()) {
        with(LocalDensity.current) {
            (if (tabAxisVertical) TAB_SHIFT_RAIL_DP else TAB_SHIFT_BAR_DP).dp.roundToPx()
        }
    } else {
        0
    }
    // 进场：自导航来向的同侧滑入（沿底栏往右切 → 从右侧进来）、0.96→1 长大、淡入。
    val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val shift = tabShiftPx * tabShiftDirection.intValue
        fadeIn(tween(tabEnterMs, easing = NpEasing.Silk)) +
            scaleIn(initialScale = tabScale, animationSpec = tween(tabEnterMs, easing = NpEasing.Silk)) +
            if (tabAxisVertical) {
                slideInVertically(tween(tabEnterMs, easing = NpEasing.Silk)) { shift }
            } else {
                slideInHorizontally(tween(tabEnterMs, easing = NpEasing.Silk)) { shift }
            }
    }
    // 退场：向同方向滑出（两页同向 = 一次"平移换页"而非互撞）、收向 0.96、并加速离开。
    val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val shift = -tabShiftPx * tabShiftDirection.intValue
        fadeOut(tween(tabExitMs, easing = NpEasing.SilkExit)) +
            scaleOut(targetScale = tabScale, animationSpec = tween(tabExitMs, easing = NpEasing.SilkExit)) +
            if (tabAxisVertical) {
                slideOutVertically(tween(tabExitMs, easing = NpEasing.SilkExit)) { shift }
            } else {
                slideOutHorizontally(tween(tabExitMs, easing = NpEasing.SilkExit)) { shift }
            }
    }
    val navHost: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = tabNavController,
            startDestination = sortedTabs.first().route,
            modifier = modifier,
            // 必须显式声明：NavHost 默认转场是 `tween(700)`，切一次标签要 0.7s
            // 才"到位"，是整壳里最慢的一处响应。pop 两份与 push 同源——Tab 之间没有
            // 前进/后退的层级语义，对称处理才不会"从二级页回来是另一种手感"。
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
            builder = tabGraph,
        )
    }

    // 底部条实测高度：内容层延伸到条之下后，各页靠它做最后一屏的 contentPadding
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
            LocalNpBottomInset provides bottomInset,
        ) {
    // 外壳在的时候把类 PC 档记下来。转屏那一帧 Activity 会更早读它；这里补自由窗口
    // 这类不走配置回调的尺寸变化，避免右栏拆掉之后没人把播放页打开、竖屏落在首页。
    SideEffect {
        NpFormHandoff.noteDesktop(windowInfo.isDesktopLike)
    }
    if (windowInfo.isCompact) {
        // 紧凑：底部 Tab 栏 + Chrome（迷你条）贴上沿。内容层**不再被 innerPadding
        // 截断**——它延伸到条之下（设计稿 `.mini/.tabbar{position:fixed}`），
        // 各页用 LocalNpBottomInset 在自己的 contentPadding 里做最后一屏避让。
        Scaffold(
            bottomBar = {
                Column(
                    modifier = Modifier.onSizeChanged { bottomBarPx = it.height },
                ) {
                    // §8.5：迷你条收进 safeDrawing 的横向安全区（横屏刘海在左右时，
                    // 条上的按钮会压在刘海下）。竖向安全区不在这里——它由下方的
                    // Tab 栏自己收，两处都收会重复一段导航栏高度。
                    Column(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        ),
                    ) {
                        sortedChromes.forEach { chrome -> chrome.Render(navigator) }
                    }
                    NpBottomTabBar(
                        tabs = sortedTabs,
                        currentRoute = currentRoute,
                        onSelect = ::selectTab,
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            navHost(Modifier.fillMaxSize())
        }
    } else {
        // 中/扩展：左侧 Rail。内容列占满、Chrome **悬浮在内容列底部**
        // （设计稿 `.mini{position:absolute;bottom:0}`），同样通过
        // LocalNpBottomInset 让各页避让。Rail 消费左侧/竖向安全区，
        // 内容列消费右侧/底部——横屏刘海、手势导航条在哪一侧都不遮挡。
        //
        // §8.2 路径 A（本批）：类 PC 档再切一栏——列表列与播放栏并列，
        // 画面常驻右侧，浏览列表时播放不中断。这一栏有内容时迷你条不再渲染
        // （它已被右栏取代，同一条"正在播放"信息不该占两处 UI）。
        //
        // 档位取 `isDesktopLike` 而不是 §12 那行写的"Expanded"：Expanded 含**竖屏**
        // 宽窗（如 840×1200 平板竖持），切栏会把列表压到 280dp、卡片掉成单列，
        // 反而比单栏更难用；§8.1 表里"路径 A 先行"那一行本来也标在 isDesktopLike。
        val pane = sortedPanes.firstOrNull()
        val paneInUse = pane != null && windowInfo.isDesktopLike && pane.isOccupied()
        val showBottomChrome = !paneInUse || pane?.showsBottomChrome() == true
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
                // **结构恒定**：NavHost 与右栏始终长在同一个组合分支里，切栏只改宽度。
                // 把 NavHost 写进 if/else 两个分支会让它整棵子树被重建——实测表现为
                // "开始播放的那一瞬间被踢回首页 Tab，详情页/文件夹层级/滚动位置全丢"。
                //
                // `LocalNpPaneOccupied` 必须包住 **navHost**（不只是右栏）：页面里的
                // 内联画面要靠它让位，一台播放器只有一个输出 Surface。
                val paneProgress by animateFloatAsState(
                    targetValue = if (paneInUse) 1f else 0f,
                    // 右栏进出是一次缩放，不是把画面横向拉开。Emphasized 比 Silk 更跟手。
                    // 离开类 PC 时不准再播这段：列表会被拉满，剧集封面就挤进转屏那几帧。
                    // 交接窗口里两个方向都不准播：飞行帧的**落点**就是右栏画面的矩形，
                    // 右栏若还在长宽（转横屏那一轮它正在从 0 撑开），那一帧会落到半途的
                    // 尺寸上，落位后右栏再自己撑大——读起来就是"落偏了又弹一下"。
                    animationSpec = if (windowInfo.isDesktopLike && !NpFormHandoff.isSilent) {
                        npMotion(Motion.Shared, easing = NpEasing.Emphasized)
                    } else {
                        snap()
                    },
                    label = "playbackPaneWidth",
                )
                // 收起动画没走完之前，详情页仍按「右栏占着画面」排版。
                // 否则 ✕ 的同一帧就会在左侧冒出封面，画面先跳到左边再弹回右边。
                // **但窗口离开类 PC 档时不成立**：那一刻右栏是"没了"而不是"在收"
                // （`paneInUse` 已为假），转屏交接落地的播放页必须立刻拿到画面；
                // 继续按"占着"下发，播放页会先空一帧、而右栏的 Surface 又还没释放，
                // 屏幕上就是两层画面同框（真机实报"一大一小两层正在播放的视频重叠"）。
                //
                // `paneShown` 是这条纪律的**完整版**：`animateFloatAsState` 即使给 `snap()`
                // 也要下一帧才落值，所以转屏那一帧 `paneProgress` 还是 1.0——右栏的
                // TextureView 还挂在树上、列表还被挤成 320dp。既然右栏只在类 PC 档存在，
                // 离开那一档就没有"正在收起"可言，进度直接按 0 算。
                val paneShown = if (windowInfo.isDesktopLike) paneProgress else 0f
                val detailPaneOccupied = paneInUse || paneShown > 0.04f
                CompositionLocalProvider(LocalNpPaneOccupied provides detailPaneOccupied) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // §8.1 类 PC 行：列表 320–400、播放 ≥480。
                        // 取窗口宽的 30% 再夹进区间——固定 360dp 在 1280dp 窗口里
                        // 会让列表列显得过窄、播放栏过大，比例失衡。
                        // 那条 480dp 下限优先：窗口不宽时缩列表，不缩画面。
                        val targetListWidth =
                            (maxWidth * 0.30f)
                                .coerceIn(320.dp, 400.dp)
                                .coerceAtMost((maxWidth - 480.dp).coerceAtLeast(280.dp))
                        val listWidth = maxWidth + (targetListWidth - maxWidth) * paneShown
                        Row(modifier = Modifier.fillMaxSize().clipToBounds()) {
                            navHost(
                                Modifier
                                    .fillMaxHeight()
                                    .width(listWidth),
                            )
                            if (pane != null && paneShown > 0.001f) {
                                // 硬分割（2026-10-07 用户口径）：内容与右栏之间不做任何
                                // 渐变过渡——1dp 发丝线 + 两侧同宽留白（pageGutter），
                                // 右栏右缘同样让出留白、不贴屏幕边缘。
                                val paneInk = LocalNpInk.current
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(paneInk.border),
                                    )
                                    pane.Render(
                                        navigator = navigator,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(
                                                start = windowInfo.pageGutter,
                                                end = windowInfo.pageGutter,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (!showBottomChrome) {
                    // 进分栏后底部没有悬浮条：清掉上一档遗留的实测高度，
                    // 否则列表列会在底部留出正好一条迷你条高的空白。
                    LaunchedEffect(Unit) { bottomBarPx = 0 }
                } else {
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
}

// ---- 条配方（投影无法表达的 alpha 常量，集中一处）----
// 数值取自设计稿：--accent-soft = accent @ 16%。
// （原来的 --edge / --border 两个常量已并入 core:ui 的 npGlassSurface 配方，
//   见 设计文档.md §4.1–§4.2：条底与两根线只由 ink 令牌表达，不再各页自算 alpha。）
private const val SHELL_ACCENT_SOFT_ALPHA = 0.16f

/** M4（§7 场景表）：Tab 图标未选中时的静止缩放，选中后回到 1.0 */
private const val TAB_ICON_REST_SCALE = 0.9f

/** E128：内容转场的纵深缩放——进出都收向该值（轻量档 / Reduce Motion 下并掉，只留淡入淡出）。
 * 2026-10-12：0.96 → 0.98——大屏上 4% 缩放边缘位移过百像素，与"移动幅度过大"同族（用户实报）。 */
private const val TAB_CONTENT_SCALE = 0.98f

/**
 * E128：内容转场的方向视差幅度（dp）——紧凑档（横向底栏）20 / Rail（纵向）14。
 *
 * 2026-10-12 用户实报"重新进入列表时归位动画移动幅度过大"后收敛：原值 36/28 在
 * 平板（Rail 纵向）上整页上下跑近 30dp，读起来像页面被抽走又塞回；收敛到
 * 手机 20dp（页宽 ~5.5%，方向仍可读）、平板 14dp（页高 ~1.7%，止于"轻挪"）。
 */
private const val TAB_SHIFT_BAR_DP = 20
private const val TAB_SHIFT_RAIL_DP = 14

/**
 * 选中胶囊的落点账本（M4「选中胶囊 morph」）。
 *
 * 各 Tab 项在布局完成时把自己在**根坐标系**里的矩形报进来，条自己也报一份；胶囊只画
 * "当前选中项那一格"，位置 = 项矩形 − 条原点。于是切换时它是**在项与项之间滑动**的一块
 * 色片，而不是每项各自淡入淡出的底色——后者读起来是"闪烁"，前者才读得出"我把选中
 * 挪过去了"。
 *
 * 为什么不用"下标 × 槽宽"算：Rail 的「设置」被一个 `weight(1f)` 推到底部，槽位**不等距**；
 * 底部条今天等距，但加第五个 Tab 就会漂。量出来的位置不会说谎。
 *
 * 首帧顺序：`onGloballyPositioned` 先子后父，所以项先报、条后报——`setHost` 里补算一次
 * 当前选中项，免得胶囊在第一帧跑到屏幕外去。
 */
private class TabSlotLedger {
    private val rects = HashMap<String, IntRect>()
    private var hostLeft = 0
    private var hostTop = 0
    private var selectedRoute = ""

    /** 选中格在**条坐标系**里的矩形；`IntRect.Zero` = 还没量到（胶囊这帧不画） */
    var target by mutableStateOf(IntRect.Zero)
        private set

    fun setHost(rect: IntRect) {
        hostLeft = rect.left
        hostTop = rect.top
        rects[selectedRoute]?.let { target = toLocal(it) }
    }

    fun report(route: String, rect: IntRect) {
        rects[route] = rect
        if (route == selectedRoute) target = toLocal(rect)
    }

    fun select(route: String?) {
        selectedRoute = route.orEmpty()
        target = rects[selectedRoute]?.let { toLocal(it) } ?: IntRect.Zero
    }

    private fun toLocal(r: IntRect) = IntRect(
        r.left - hostLeft,
        r.top - hostTop,
        r.right - hostLeft,
        r.bottom - hostTop,
    )
}

private fun androidx.compose.ui.geometry.Rect.toIntRect() = IntRect(
    left.roundToInt(),
    top.roundToInt(),
    right.roundToInt(),
    bottom.roundToInt(),
)

/**
 * 选中胶囊本体：只画一格，位置跟着 [TabSlotLedger.target] 走。
 *
 * 位置用 `offset { }` 的 lambda 版——动画值落在测量相位，不拖父级重组（§9.2）；
 * 时长经 `silkOffset` 走 `Motion.Tab` 令牌，轻量档自动缩时长、Reduce Motion 下直接
 * 到位，都不必在这里再判一次档位。
 */
@Composable
private fun NpTabCapsule(
    ledger: TabSlotLedger,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val target = ledger.target
    if (target == IntRect.Zero) return
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val pos by silkOffset(IntOffset(target.left, target.top), Motion.Tab, label = "tabCapsule")
    Box(
        modifier = modifier
            .offset { IntOffset(pos.x, pos.y) }
            // 账本里的矩形是**像素**（onGloballyPositioned 给的就是 px），所以要经密度
            // 换算成 Dp。写成 `target.width.dp` 会把 305px 当成 305dp 再乘一次密度——
            // 实机 520dpi 下胶囊变成 991px 宽，一眼就"糊到右边去了"。
            .size(
                width = with(density) { target.width.toDp() },
                height = with(density) { target.height.toDp() },
            )
            .clip(RoundedCornerShape(corner))
            .background(accent.copy(alpha = SHELL_ACCENT_SOFT_ALPHA)),
    )
}

/**
 * `.tabbar`：底部 Tab 栏（**不透明**）。
 *
 * 设计稿的关键取舍：**文字标签常驻**，选中只改颜色 + 换成实心图标。
 * 隐藏未选中的文字会让用户失去"这一栏是什么"的线索；用 11sp 的小字表达克制，
 * 而不是用"藏起来"表达克制。
 *
 * 底改不透明后，原先的 `backdrop-filter:blur(22px)` 已无可见效果，一并移除——
 * 它曾是滚动时每帧一次全屏离屏采样（掉帧主因）。层次改由顶边 1px 发丝线承担。
 */
@Composable
private fun NpBottomTabBar(
    tabs: List<TabProvider>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = LocalNpInk.current

    // M4：选中态是一枚**在四格之间滑动**的胶囊，不是四项各自淡入淡出的底色。
    val ledger = remember { TabSlotLedger() }
    LaunchedEffect(currentRoute) { ledger.select(currentRoute) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // L2 条配方（§4）：不透明底 + 顶高光（ink.edge，中间段内缩淡出）
            // + 顶发丝线（ink.border，即设计稿 `.tabbar{border-top}`）。
            // 此前这三行是就地手写的两份 background + gradient，与 core:ui 的配方各表；
            // 收编后顶栏 / 迷你条 / Tab 栏三处共用同一个 modifier。
            .npGlassSurface(ink, topHighlight = true, topHairline = true)
            .onGloballyPositioned { ledger.setHost(it.boundsInRoot().toIntRect()) },
    ) {
        NpTabCapsule(ledger = ledger, corner = 12.dp)
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
                NpTabItem(
                    label = tab.label,
                    icon = tab.icon as? ImageVector,
                    selectedIcon = tab.selectedIcon as? ImageVector,
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.route) },
                    accent = accent,
                    muted = muted,
                    onSlot = { rect -> ledger.report(tab.route, rect) },
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
    val ink = LocalNpInk.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
    // M4：Rail 的选中态也是一枚**滑动的胶囊**（竖向走位）。品牌块与「主题」钮不是目的地、
    // 不上报槽位，所以胶囊永远不会停到它们身上。
    val railLedger = remember { TabSlotLedger() }
    LaunchedEffect(currentRoute) { railLedger.select(currentRoute) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            // L2 竖条：同一条底色的口径（§4.1）。Rail 是竖的，npGlassSurface 的两根横线
            // 用不上，所以只取它的底——仍经同一个 modifier，不各写一份。
            // 底铺在**这一层**而不是里面的 Row：胶囊要画在玻璃底之上、各项之下。
            .npGlassChrome(ink)
            .clipToBounds()
            .onGloballyPositioned { railLedger.setHost(it.boundsInRoot().toIntRect()) },
    ) {
        val compact = maxHeight < 420.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        NpTabCapsule(ledger = railLedger, corner = 14.dp)
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(vertical = if (compact) 6.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 品牌块：44dp、14dp 圆角、中性底 + 应用签名标记（⊘）。
            //
            // 标记用**纯黑 / 纯白两套**随深浅主题切换，容器底也从 accent-soft 换成中性：
            // 橙色在本应用里是"选中 / 主操作"的颜色，品牌块再占一支，Rail 上就会出现
            // 两个同等强度的橙色焦点，真正的选中项反而压不住。
            if (!compact) Box(
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NoneMark(
                    modifier = Modifier.size(24.dp),
                    color = if (isDark) Color.White else Color.Black,
                    strokeWidth = 2.5.dp,
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
                    compact = compact,
                    onSlot = { rect -> railLedger.report(tab.route, rect) },
                )
            }
            // 推到底部的那一项（设置）
            if (!compact) Box(modifier = Modifier.weight(1f))
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
                    compact = compact,
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
                    compact = compact,
                    onSlot = { rect -> railLedger.report(tab.route, rect) },
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
}

/**
 * `.rail-btn`：64dp 宽 / 14dp 圆角 / 图标 + 11sp 文字；选中为 accent 字 + 实心图标。
 *
 * 选中**底色不在这里画**——那是 [NpTabCapsule] 那枚竖向滑动的胶囊（M4）。各按钮只报一次
 * 自己的槽位矩形（`onSlot`），品牌块与「主题」钮不是目的地、传 null，胶囊因此永远停不到
 * 它们身上。图标 0.9→1.0 与底部条同一档、同一时长令牌。
 */
@Composable
private fun NpRailItem(
    label: String,
    icon: ImageVector?,
    selectedIcon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    muted: Color,
    onSlot: ((IntRect) -> Unit)? = null,
    compact: Boolean = false,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else muted,
        animationSpec = npMotion(Motion.Press),
        label = "railTint",
    )
    val restScale = if (npDisplacementAllowed()) TAB_ICON_REST_SCALE else 1f
    val scale by silkFloat(if (selected) 1f else restScale, Motion.Tab, label = "railScale")
    // 按压缩放（设计稿 `.rail-btn:active{transform:scale(.94)}`）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = npMotion(Motion.Press),
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = if (compact) 12.dp else 8.dp)
            .then(
                if (onSlot == null) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { onSlot(it.boundsInRoot().toIntRect()) }
                },
            ),
    ) {
        if (shownIcon != null) {
            Icon(
                imageVector = shownIcon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = scale * pressScale.value
                        scaleY = scale * pressScale.value
                    },
            )
        }
        if (!compact) Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/**
 * `.tabbar button`：等分宽度 / 图标 20dp + 11sp 文字；选中 accent + 实心图标 + 按压缩放。
 *
 * M4 的图标那半：未选中 0.9 → 选中 1.0（`Motion.Tab` 220ms）。选中底色不在这里——
 * 那是 [NpTabCapsule] 那枚滑动的胶囊，各按钮只负责自己的图标与文字。
 */
@Composable
private fun NpTabItem(
    label: String,
    icon: ImageVector?,
    selectedIcon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    muted: Color,
    onSlot: (IntRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else muted,
        animationSpec = npMotion(Motion.Press),
        label = "tabTint",
    )
    // 0.9→1 是位移，轻量档 / Reduce Motion 下并掉（§9.6），只留颜色与实心图标两个信号
    val restScale = if (npDisplacementAllowed()) TAB_ICON_REST_SCALE else 1f
    val iconScale by silkFloat(if (selected) 1f else restScale, Motion.Tab, label = "tabIconScale")
    // 按压缩放（设计稿 `:active` 语言的 tabbar 版本，取 chip 的 .96 档）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = npMotion(Motion.Press),
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
            .onGloballyPositioned { onSlot(it.boundsInRoot().toIntRect()) }
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            },
    ) {
        if (shownIcon != null) {
            Icon(
                imageVector = shownIcon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
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
