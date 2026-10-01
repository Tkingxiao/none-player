package com.noneplayer.kernel.ui.api

import android.content.Intent

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    val duplicateRoutes = (screens.map { it.route } + tabs.map { it.route })
        .groupBy { it }.filterValues { it.size > 1 }.keys
    require(duplicateRoutes.isEmpty()) {
        "路由冲突: $duplicateRoutes。route 必须全局唯一，请以插件 id 为前缀。"
    }
    require(tabs.isNotEmpty()) {
        "未注册任何 TabProvider，主界面无内容。请在插件中实现 TabProvider。"
    }

    val navController = rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }

    // 热启动深链：Activity 复用实例时系统不会重建导航图，必须显式转发给 NavController
    LaunchedEffect(navController, deepLinkIntents) {
        deepLinkIntents?.collect { navController.handleDeepLink(it) }
    }

    NavHost(
        navController = navController,
        startDestination = SHELL_ROUTE,
        modifier = modifier,
        // iOS push 式转场：新页自右轻滑入场，返回时反向滑出；进出伴随淡入淡出
        enterTransition = {
            fadeIn(tween(240)) + slideInHorizontally(tween(280)) { it / 4 }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = {
            fadeOut(tween(180)) + slideOutHorizontally(tween(280)) { it / 4 }
        },
    ) {
        // 主界面 = 底部标签栏 Shell（B 站式信息架构）；Tab 由各插件贡献
        composable(SHELL_ROUTE) {
            KernelTabScaffold(navigator = navigator)
        }
        screens.forEach { screen ->
            screen.register(builder = this, navigator = navigator)
        }
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
        navController.popBackStack()
    }
}
