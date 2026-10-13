package com.noneplayer.kernel.ui.api


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

    /**
     * 选中态图标（ImageVector，设计稿 `.rail-btn.on .np-ico{fill}`）。
     *
     * 「Filled, not outlined」：选中从描边变实心是整套导航的核心信号。
     * 默认 null = 未提供，外壳回退用 [icon]（只有变色，无填充切换）。
     */
    val selectedIcon: Any? get() = null

    /** 排序权重，越小越靠左（横向）/ 越靠上（纵向） */
    val order: Int

    /**
     * 是否固定到导航栏末端。
     *
     * 设计稿把"设置"与另外三个目的地**在空间上分开**——紧凑档它排在最右、
     * 平板 Rail 上它被推到最底部。这不是"第四个并列项"，而是"关于这个应用"的入口，
     * 位置本身承载这个语义。
     *
     * 默认 false：绝大多数 Tab 是并列的内容区。
     */
    val pinnedToEnd: Boolean get() = false
}
