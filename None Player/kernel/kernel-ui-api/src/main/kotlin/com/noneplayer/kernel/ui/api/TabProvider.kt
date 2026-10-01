package com.noneplayer.kernel.ui.api

import com.noneplayer.kernel.api.ExtensionPoint

/**
 * 底部标签栏扩展点——插件向主界面贡献一个顶层 Tab（B 站式信息架构）。
 *
 * Tab 是一种特殊的页面：注册到主界面底部的 NavigationBar，
 * 由内核的 [KernelTabScaffold] 统一承载（saveState/restoreState 切换语义）。
 *
 * 约束：
 * - [route] 全局唯一且不得与其他 ScreenProvider 路由冲突（内核启动校验）；
 * - [icon] 为 androidx.compose.ui.graphics.vector.ImageVector，由 UI 层解释；
 * - 页面自身负责安全区（状态栏/导航栏）内边距。
 */
interface TabProvider : ScreenProvider {
    /** Tab 唯一标识 */
    val tabId: String

    /** 底部标签文字（建议 ≤ 2 字） */
    val label: String

    /** 标签图标（ImageVector） */
    val icon: Any

    /** 排序权重，越小越靠左 */
    val order: Int
}
