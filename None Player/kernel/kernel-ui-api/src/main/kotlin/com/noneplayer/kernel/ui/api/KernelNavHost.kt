package com.noneplayer.kernel.ui.api

import android.content.Intent

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    /**
     * Tab 导航控制器**必须在本层创建**（E53）——早前它在 [KernelTabScaffold] 内部
     * `remember`，跳播放页时 Shell 目的地离开组合、控制器随之销毁；返回时
     * `startDestination` 重新解析成首个 Tab，表现为
     * **「在文件夹里看了视频再返回，回到了首页」**（Tab 选择与页内层级全丢）。
     * 提升到外层后实例跨导航存活，内层返回栈与 `rememberSaveable` 归属都不变。
     */
    val tabNavController = rememberNavController()

    // 导航图的构建块**必须是稳定引用**：`NavHost` 内部以
    // `remember(route, startDestination, builder)` 缓存导航图，`builder` 的 lambda
    // 身份一旦每次重组都变，整张图就会被重建（所有目的地重新注册）。
    // 这里按 screens/navigator 记忆——列表不变时切页只是换目的地，不再重建图。
    val graphBuilder: NavGraphBuilder.() -> Unit = remember(screens, navigator, tabNavController) {
        {
            // 主界面 = 底部标签栏 Shell（B 站式信息架构）；Tab 由各插件贡献
            composable(SHELL_ROUTE) {
                KernelTabScaffold(navigator = navigator, tabNavController = tabNavController)
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

    NavHost(
        navController = navController,
        startDestination = SHELL_ROUTE,
        // 主题底铺在**整个导航宿主**上，而不是只铺在 Shell 里：
        // Tab 页由 KernelTabScaffold 自己画底，但**外层路由**（搜索页、播放页、
        // 关于/插件页）不经过 Shell——不在这里铺底，它们会露出窗口默认背景
        // （浅色系统主题下的白色），暗色主题下就是"顶栏黑、内容白"的断头效果。
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        // iOS push 式转场：新页自右轻滑入场，返回时反向滑出；进出伴随淡入淡出
        enterTransition = {
            fadeIn(tween(240)) + slideInHorizontally(tween(280)) { it / 4 }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = {
            fadeOut(tween(180)) + slideOutHorizontally(tween(280)) { it / 4 }
        },
        builder = graphBuilder,
    )
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
        navController.popBackStack()
    }
}
