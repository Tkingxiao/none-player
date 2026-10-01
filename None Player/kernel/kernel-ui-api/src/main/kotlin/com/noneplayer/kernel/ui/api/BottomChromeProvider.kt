package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator

/**
 * 底部常驻 Chrome 槽位扩展点（§3.5，G1 定稿）。
 *
 * 插件向主界面"内容区与 NavigationBar 之间"贡献一块常驻 UI——当前的真实
 * 消费方是 `plugin-player-ui` 的迷你播放条（MiniPlayerChrome）。
 *
 * **槽位形态（G1 定稿，勿改）**：`KernelTabScaffold` **保留现有 `Scaffold`**，
 * 把本槽位塞进 `bottomBar = { Column { chrome… ; NavigationBar } }`。
 * 这样迷你条作为**占位**参与布局，自动获得"内容让位"与正确的焦点顺序，
 * 也不必改动各 Tab 页面赖以做底栏避让的 `innerPadding` 契约。
 *
 * 反面方案（已被 G1 否决）：改成 `Column { Box(weight(1f)){NavHost} ; chrome ; NavigationBar }`
 * 会丢掉 `innerPadding` 契约；浮层方案则要额外规定 `zIndex` 与"浮层不能吃掉列表手势"。
 *
 * **实现约束**：
 * - 内核不知道具体渲染的是什么，插件也不得依赖其他 `plugin-*`；
 * - 渲染内容为空时应自行不占位（`KernelTabScaffold` 不做有无判断，
 *   由实现方用 `if (state == null) return` 之类自行早退，避免出现空白条）。
 */
interface BottomChromeProvider : ExtensionPoint {
    /** 排序权重，越小越靠上（越靠近内容区） */
    val order: Int

    /** 渲染槽位内容；无内容可展示时应不占任何空间 */
    @Composable
    fun Render(navigator: Navigator)
}
