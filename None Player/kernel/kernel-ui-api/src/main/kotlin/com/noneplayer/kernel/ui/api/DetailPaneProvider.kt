package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator

/**
 * 右栏（详情/播放栏）扩展点——《设计文档.md》§8.2「路径 A」的落点。
 *
 * 解决的问题：宽窗口（类 PC 档）里"列表 + 播放"本来只能二选一——要么画面独占
 * 整屏、列表看不见，要么边播边跳页。本槽位让外壳在 `isDesktopLike` 档位把窗口
 * 切成三栏（导航 80 / 列表 320–400 / 本栏 ≥480），右栏内联渲染播放器，
 * 浏览列表时播放不中断；这一栏有内容时**迷你条不再渲染**（它已被右栏取代）。
 *
 * 与 [BottomChromeProvider] 一样，内核不知道这一栏渲染的是什么，
 * 插件也不得依赖其他 `plugin-*`（依赖铁律）。
 *
 * **未注册实现时外壳保持单栏**——少一个插件只是少一种版式，不影响任何功能。
 */
interface DetailPaneProvider : ExtensionPoint {

    /** 排序权重，越小越优先；同时只启用一个贡献者（多栏分屏不在本批范围） */
    val order: Int

    /**
     * 这一栏当前是否有内容要展示。返回 false 时外壳**不分配栏宽**，
     * 列表回到全宽、底部 Chrome（迷你条）照旧渲染。
     */
    @Composable
    fun isOccupied(): Boolean

    @Composable
    fun showsBottomChrome(): Boolean = false

    /**
     * 渲染右栏内容。
     *
     * 注意：**参数不允许带默认值**——`@Composable` 接口方法的默认实参桥
     * （`$ComposeDefaultImpls`）在运行期会以 AbstractMethodError 爆掉
     * （见 [InlinePlaybackProvider] 的同条约束）。
     */
    @Composable
    fun Render(navigator: Navigator, modifier: Modifier)
}

/**
 * 外壳此刻是否已把右栏让给了播放器（§8.2 路径 A 的"单一画面"约束）。
 *
 * 一个播放器实例只能挂一个输出 Surface：右栏在渲染画面时，页面里**不得**再挂一份
 * 内联画面（详情页封面区要退回静态封面），否则后挂载的那个把前一个的 Surface 顶掉、
 * 两处一起变黑屏。"这一档有没有分栏"只有外壳算得出来（栏宽、档位、占用判定都在它那里），
 * 所以由 [KernelTabScaffold] 在分栏子树里 provide `true`；默认 `false` = 单栏。
 */
val LocalNpPaneOccupied: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }
