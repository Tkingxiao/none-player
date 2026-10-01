package com.noneplayer.kernel.ui.api

import androidx.compose.foundation.layout.Column
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query

/**
 * 底部标签栏骨架——B 站式主界面的容器。
 *
 * - 从注册表收集全部 [TabProvider]，按 order 排序渲染 NavigationBar；
 * - 内层 NavHost 承载各 Tab 页面，切换用 `popUpTo(栈顶, inclusive, saveState)` +
 *   `restoreState`：内层栈始终只有一个 Tab（各 Tab 状态靠存/取档保留而非靠栈保留），
 *   因此在任意 Tab 按系统返回 = 退出应用，而不是"弹回首页"（E53）；
 * - 内容窗口 Insets 置零：各 Tab 页面自行处理状态栏/导航栏避让，
 *   底部导航栏自身处理系统 Insets。
 *
 * [tabNavController] 由外层 [KernelNavHost] 注入——**不能在此 `remember`**：
 * 跳二级页（播放页）时本 Composable 会离开组合，内部创建的控制器会随之销毁，
 * 返回时 Tab 选择与页内层级全部丢失（E53）。
 *
 * 装配校验：tabId 重复、route 重复立即崩溃（与页面路由同一约束）。
 */
@Composable
fun KernelTabScaffold(
    navigator: Navigator,
    tabNavController: NavHostController,
) {
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

    val backStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // §3.5 / G1：底部常驻 Chrome 槽位——插件（当前是迷你播放条）贡献的内容
    // 渲染在内容区与 NavigationBar 之间。响应式收集，与 Tab 同款写法。
    val chromes by remember { kernel.registry.observe<BottomChromeProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<BottomChromeProvider>())
    val sortedChromes = chromes.sortedBy { it.order }

    Scaffold(
        bottomBar = {
            // G1 定稿形态：**保留 Scaffold**，只在 bottomBar 里加一层 Column。
            // 迷你条因此是"占位"而非"浮层"——内容区自动让位，innerPadding 契约不变
            // （各 Tab 页面正靠它做底栏避让）。不要改成 Column{Box(weight){NavHost}…}。
            Column {
                sortedChromes.forEach { chrome -> chrome.Render(navigator) }
                NavigationBar {
                    sortedTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // 点当前已选 Tab 不做任何事（避免整页重建）
                                if (currentRoute != tab.route) {
                                    // E53：切 Tab 时把**栈顶那一个**连自己一起弹出（它必然在栈里，
                                    // popUpTo 一定命中），于是内层栈永远只有一个 Tab。
                                    // 旧写法把首页留在栈底：NavController 的返回回调在
                                    // `栈内目的地数 > 1` 时才启用，两个条目就意味着"文件夹 Tab
                                    // 按返回"被消费成"弹回首页"。清空到只剩一个之后，返回回调
                                    // 自动失效，返回键交回系统 → 退出应用（顶层页的标准语义）。
                                    // 状态不丢：popUpTo(saveState) 与 navigate(restoreState) 成对，
                                    // 每个 Tab 离场存档、回场取档（滚动位置、文件夹层级都在）。
                                    val topDestinationId = tabNavController.currentDestination?.id
                                    tabNavController.navigate(tab.route) {
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
                            },
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
