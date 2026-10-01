package com.noneplayer.kernel.ui.api

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query

/**
 * 底部标签栏骨架——B 站式主界面的容器。
 *
 * - 从注册表收集全部 [TabProvider]，按 order 排序渲染 NavigationBar；
 * - 内层 NavHost 承载各 Tab 页面，切换采用
 *   `popUpTo(saveState) + restoreState` 标准语义（各 Tab 状态互不丢失）；
 * - 内容窗口 Insets 置零：各 Tab 页面自行处理状态栏/导航栏避让，
 *   底部导航栏自身处理系统 Insets。
 *
 * 装配校验：tabId 重复、route 重复立即崩溃（与页面路由同一约束）。
 */
@Composable
fun KernelTabScaffold(navigator: Navigator) {
    val kernel = LocalKernel.current
    // E22：响应式收集——插件启用/停用后底栏随注册表更新，无需重启。
    // initialValue 用同步 query 快照（B8 同款写法）：首帧校验不会在 Flow 发射前误判
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

    val tabNavController = rememberNavController()
    val backStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                sortedTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                // B 站式 Tab 切换：保留各 Tab 状态、回到栈底、不重复入栈
                                popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            (tab.icon as? ImageVector)?.let { vector ->
                                Icon(imageVector = vector, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = sortedTabs.first().route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            sortedTabs.forEach { tab ->
                tab.register(builder = this, navigator = navigator)
            }
        }
    }
}
