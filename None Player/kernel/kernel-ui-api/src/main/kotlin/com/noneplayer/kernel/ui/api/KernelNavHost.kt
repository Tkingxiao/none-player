package com.noneplayer.kernel.ui.api

import android.content.Intent

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.rememberReduceMotion
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import kotlinx.coroutines.flow.Flow
import com.noneplayer.kernel.api.query

/**
 * 主界面 Shell 路由（E33）：承载底部标签栏（Tab 由各插件以 TabProvider 贡献）。
 *
 * 专用名 "shell" 而非 "home"——外层 Shell 与内层首页 Tab（LibraryTabProvider）
 * 分属两个导航图，此前同为 "home" 导致同名异义（navigate("home") 语义歧义）。
 */
const val SHELL_ROUTE = "shell"

/**
 * 内核导航宿主——动态组装所有 [ScreenProvider] 贡献的页面。
 *
 * 校验（设计文档 §7 踩坑 1）：
 * - [ScreenProvider.route] 重复 → 立即抛错崩溃，而非静默覆盖；
 * - 起始路由不存在 → 抛错并列出可用路由，方便定位缺失的首页插件。
 */
@Composable
fun KernelNavHost(
    modifier: Modifier = Modifier,
    /**
     * 热启动深链（E2）：singleTask Activity 经 `onNewIntent` 投递的新 Intent。
     * 冷启动深链无需此参数——NavController 在 setGraph 时会自动
     * `handleDeepLink(activity.intent)`（与 startDestination 二选一）。
     */
    deepLinkIntents: Flow<Intent>? = null,
) {
    val kernel = LocalKernel.current
    // B8 修复：响应式收集——运行期注册/注销插件页面时导航图随之重建。
    // 注意：initialValue 必须用同步 query 快照——boot 完成后才进这里，
    // 快照即完整集合；若用 emptyList 占位，首帧校验会在 Flow 发射前误判崩溃。
    // Tab 注册在 TabProvider 类型键下，与普通页面分开收集。
    val screens by remember { kernel.registry.observe<ScreenProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<ScreenProvider>())
    val tabs by remember { kernel.registry.observe<TabProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<TabProvider>())

    // 路由冲突校验只随 screens/tabs 变化重算：此前写在组合体内，任何一次重组都要
    // 重新 map/groupBy/filter 分配三个中间集合，而这些结果在列表不变时是常量。
    val duplicateRoutes = remember(screens, tabs) {
        (screens.map { it.route } + tabs.map { it.route })
            .groupBy { it }.filterValues { it.size > 1 }.keys
    }
    require(duplicateRoutes.isEmpty()) {
        "路由冲突: $duplicateRoutes。route 必须全局唯一，请以插件 id 为前缀。"
    }
    require(tabs.isNotEmpty()) {
        "未注册任何 TabProvider，主界面无内容。请在插件中实现 TabProvider。"
    }

    val navController = rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }
    // 转屏回调发生在组合之前，播放页必须在那一刻就能被推进栈（见 NpFormHandoff）。
    // 带令牌解绑：本层若被重建，旧节点的 onDispose 晚于新节点的 bind，
    // 一把置空会把刚绑上的出口清掉——真机上表现为横转竖不打开播放页、直接停在首页。
    val navigatorToken = remember(navigator) { Any() }
    DisposableEffect(navigator) {
        NpFormHandoff.bindNavigator(navigatorToken) { route -> navigator.navigate(route) }
        onDispose { NpFormHandoff.releaseNavigator(navigatorToken) }
    }

    /**
     * Tab 导航控制器**必须在本层创建**（E53）——早前它在 [KernelTabScaffold] 内部
     * `remember`，跳播放页时 Shell 目的地离开组合、控制器随之销毁；返回时
     * `startDestination` 重新解析成首个 Tab，表现为
     * **「在文件夹里看了视频再返回，回到了首页」**（Tab 选择与页内层级全丢）。
     * 提升到外层后实例跨导航存活，内层返回栈与 `rememberSaveable` 归属都不变。
     */
    val tabNavController = rememberNavController()

    /**
     * Tab 内层 `NavHost` 的 ViewModelStore 宿主（B25）。
     *
     * `NavHost` 每次重组都会把当下 `LocalViewModelStoreOwner` 的 store 塞给自己的控制器，
     * 而内层那台控制器默认继承的是**外层 SHELL 条目**的 store。E53 把 `tabNavController`
     * 提升到跨导航存活，store 却还跟着条目走：外层条目一重建（深链落到播放页后返回、
     * 小窗回前台重投），`NavController.setViewModelStore` 就撞上
     * `check(backQueue.isEmpty())` → `IllegalStateException: ViewModelStore should be set
     * before setGraph call`（真机两条路径同一个栈）。
     * 这里给内层一份**与控制器同寿命**的宿主，store 不再随外层条目换；清理交给本层的
     * `DisposableEffect`——比条目活得久，但绝不比组合活得久。
     */
    val tabViewModelStoreOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(tabViewModelStoreOwner) {
        onDispose { tabViewModelStoreOwner.viewModelStore.clear() }
    }

    // 导航图的构建块**必须是稳定引用**：`NavHost` 内部以
    // `remember(route, startDestination, builder)` 缓存导航图，`builder` 的 lambda
    // 身份一旦每次重组都变，整张图就会被重建（所有目的地重新注册）。
    // 这里按 screens/navigator 记忆——列表不变时切页只是换目的地，不再重建图。
    val graphBuilder: NavGraphBuilder.() -> Unit = remember(screens, navigator, tabNavController) {
        {
            // 主界面 = 底部标签栏 Shell（B 站式信息架构）；Tab 由各插件贡献
            composable(SHELL_ROUTE) {
                KernelTabScaffold(
                    navigator = navigator,
                    tabNavController = tabNavController,
                    tabViewModelStoreOwner = tabViewModelStoreOwner,
                )
            }
            screens.forEach { screen ->
                screen.register(builder = this, navigator = navigator)
            }
        }
    }

    // 热启动深链：Activity 复用实例时系统不会重建导航图，必须显式转发给 NavController
    LaunchedEffect(navController, deepLinkIntents) {
        deepLinkIntents?.collect { navController.handleDeepLink(it) }
    }

    // 时长读在组合期：`npDuration` 要取 LocalNpMotionQuality，而下面 NavHost 的
    // enter/exit lambda 跑在过渡作用域里，拿不到组合上下文。
    // Reduce Motion 下整页只剩淡入淡出，且收成 `ReduceFade`(120)——路由位移是最容易被
    // 晕眩感抓住的一类动画，系统「移除动画」必须能一次把它压掉。
    val reduceMotion = rememberReduceMotion()
    val navFadeMs = if (reduceMotion) npDuration(Motion.ReduceFade) else npDuration(Motion.NavFade)
    val navEnterMs = npDuration(Motion.NavEnter)
    val navExitMs = if (reduceMotion) npDuration(Motion.ReduceFade) else npDuration(Motion.NavExit)
    val navSlideAllowed = npDisplacementAllowed()

    // 整页不淡不滑的两种情形：共享元素正在飞（画面由那枚飞行帧连着），以及**转屏交接**
    // ——形态换了画面归属者而自动导航一次，页面位移叠在系统转屏动画上就是用户实报的
    // "切换动画会闪屏、很难看"（见 [NpFormHandoff]）。交接的目标形态本身就是新落点，
    // 没有"从哪一页来"的叙事要表达。
    fun noPageMotion(): Boolean = NpFormHandoff.isSilent

    // 共享元素「飞行帧」挂**在导航宿主之上**，不能挂在 Shell 里（E120）。转屏交接的
    // 两条路径都会整页换目的地——右栏 → 播放页是 push、播放页 → 右栏是 pop——Shell
    // 一旦离场，挂在它内部的飞行层就随之被取消（`LaunchedEffect` 的 `finally` 会清掉
    // 这一轮飞行）。此前只有"pop 回 Shell"那一向还留着层可画，另外两向是**零动画**，
    // 用户实报的"几乎看不到切换动画"正是这一条。挪到本层后任何一个目的地都在组合中。
    // 坐标仍是窗口原点：本层 `fillMaxSize`，与各侧上报的 `boundsInWindow()` 同一套。
    // 整屏 morph 的宿主（E133）：本层就是**窗口原点**上的那个 Box——各部件登记的
    // `boundsInWindow()` 与它同一套坐标，映射与插值才成立。窗口尺寸每次布局登记，
    // 上一形态的矩形要靠它搬进新窗口。
    // 形态标签：过渡那几帧里新旧两棵树可能同时在组合中，登记要认得出"这块是哪个形态的"。
    val desktopForm = LocalNpWindowInfo.current.isDesktopLike
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { NpMorph.recordWindow(it.size, desktopForm) },
    ) {
        NavHost(
            navController = navController,
            startDestination = SHELL_ROUTE,
            // 主题底铺在**整个导航宿主**上，而不是只铺在 Shell 里：
            // Tab 页由 KernelTabScaffold 自己画底，但**外层路由**（搜索页、播放页、
            // 关于/插件页）不经过 Shell——不在这里铺底，它们会露出窗口默认背景
            // （浅色系统主题下的白色），暗色主题下就是"顶栏黑、内容白"的断头效果。
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            // iOS push 式转场：新页自右轻滑入场，返回时反向滑出；进出伴随淡入淡出。
            // 时长走 §7.2 的 Nav* 令牌（Q20：裸 tween 例外清零），档位换算在组合期完成——
            // 下面的 lambda 跑在 EnterTransitionScope 里，读不到组合上下文。
            enterTransition = {
                if (noPageMotion()) EnterTransition.None
                else fadeIn(tween(navFadeMs, easing = NpEasing.Silk)) +
                    slideInHorizontally(tween(navEnterMs, easing = NpEasing.Silk)) { if (navSlideAllowed) it / 4 else 0 }
            },
            exitTransition = {
                if (noPageMotion()) ExitTransition.None else fadeOut(tween(navExitMs, easing = NpEasing.SilkExit))
            },
            popEnterTransition = {
                if (noPageMotion()) EnterTransition.None
                else fadeIn(tween(navFadeMs, easing = NpEasing.Silk))
            },
            popExitTransition = {
                // 返回时旧页往**左**退出（`-it/4`）：与 push 的入场方向成对，
                // 写成 `it / 4` 会让"返回"读成"又推进了一页"。
                // E119：滑出时长原本错用了 navEnterMs(280)——alpha 在 navExitMs(180) 就归零了，
                // 多出来的 100ms 是一帧都看不见的动画，还让退场比进场更慢（违反 §3.9 的
                // "退场加速离开"）。两条 spec 同长才是"一次离开"，不是"淡出后再溜一段"。
                if (noPageMotion()) ExitTransition.None
                else fadeOut(tween(navExitMs, easing = NpEasing.SilkExit)) +
                    slideOutHorizontally(tween(navExitMs, easing = NpEasing.SilkExit)) { if (navSlideAllowed) -it / 4 else 0 }
            },
            builder = graphBuilder,
        )
        // 整屏 morph 的节拍器（E133）。取代旧的"抓一帧当幽灵层飞"：不再有盖住整窗的黑幕，
        // 各部件由自己的 graphicsLayer 从上一形态的位置飞到位（见 NpMorph）。
        NpMorphDriver()
    }
}

/**
 * 基于 NavController 的 [Navigator] 实现。
 *
 * `args` 附加参数当前未使用：路由参数按 Navigation 惯例编码在 route 字符串中
 * （如 `player/{id}`），由各插件在自己的 register 内解析。
 */
class NavControllerNavigator(private val navController: NavHostController) : Navigator {
    override fun navigate(route: String, args: Map<String, Any>) {
        navController.navigate(route) { launchSingleTop = true }
    }

    override fun popBackStack() {
        if (!navController.popBackStack()) {
            // A cold external deep link may have no shell underneath its destination.
            navController.navigate(SHELL_ROUTE) { launchSingleTop = true }
        }
    }
}
