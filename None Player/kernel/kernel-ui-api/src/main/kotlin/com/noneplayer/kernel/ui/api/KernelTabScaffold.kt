package com.noneplayer.kernel.ui.api

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query

/**
 * 主界面骨架——B 站式信息架构的容器，**随窗口形态自适应导航**（E46）。
 *
 * - 紧凑手机（Compact）：底部 `NavigationBar`，迷你条等 Chrome 贴在它上沿；
 * - 中等/扩展（Medium / Expanded，平板横屏 / 折叠展开 / 桌面窗口）：侧边 `NavigationRail`，
 *   把本就稀缺的竖向高度还给内容，Chrome 贴在内容列底部。
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
    if (windowInfo.isCompact) {
        // 紧凑：底部 NavigationBar + Chrome（迷你条）贴上沿。保留 Scaffold 的 innerPadding
        // 契约——各 Tab 页面正靠它做底栏避让（G1 定稿形态，勿改成手搓 Column）。
        Scaffold(
            bottomBar = {
                Column {
                    sortedChromes.forEach { chrome -> chrome.Render(navigator) }
                    NavigationBar {
                        sortedTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { selectTab(tab.route) },
                                icon = {
                                    (tab.icon as? ImageVector)?.let { vector ->
                                        Icon(vector, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            navHost(Modifier.fillMaxSize().padding(innerPadding))
        }
    } else {
        // 中等/扩展：侧边 Rail。竖向高度全部还给内容；Chrome 贴内容列底部。
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                sortedTabs.forEach { tab ->
                    NavigationRailItem(
                        selected = currentRoute == tab.route,
                        onClick = { selectTab(tab.route) },
                        icon = {
                            (tab.icon as? ImageVector)?.let { vector ->
                                Icon(vector, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                navHost(Modifier.weight(1f).fillMaxSize())
                sortedChromes.forEach { chrome -> chrome.Render(navigator) }
            }
        }
    }
}
