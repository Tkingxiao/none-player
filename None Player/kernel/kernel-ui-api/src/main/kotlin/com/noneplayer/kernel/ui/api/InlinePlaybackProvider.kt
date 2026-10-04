package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator

/**
 * 内嵌播放扩展点：宿主页面（媒体库「视频详情」）把播放器**嵌进自己的版式**播放，
 * 而不是跳到独占整屏的播放页。
 *
 * 这是平板/桌面上市面播放器的标准交互（B 站、nPlayer、VLC）：详情页里直接开播 →
 * 点"全屏"才进独占全屏 → 退出全屏回到详情页、画面仍在原处续播。
 * 用扩展点而不是让库插件依赖播放插件，是依赖铁律的直接推论（`plugin-* ✗→ plugin-*`）。
 *
 * **未注册实现时宿主必须回退到整页播放**——缺一个插件不该让"播放"这个动作消失。
 */
interface InlinePlaybackProvider : ExtensionPoint {

    /**
     * 渲染内嵌播放器。实现方负责"确保会话播的就是 [media]"：同文件夹队列、断点、
     * 字幕记忆、倍速走与播放页**同一条装载链**（两处各写一份必然漂移），并渲染
     * 画面（含字幕层与覆盖层）与精简控制层（播放/暂停、进度、时间、全屏）。
     *
     * @param navigator 宿主页面的导航器，供覆盖层（弹幕等）跳转
     * @param onFullscreen 用户点全屏：宿主负责导航到独占播放页。会话已在播同一媒体，
     *   落页只续播不重启（B1 语义）
     * @param modifier 宿主给定的版式约束（16:9 框 + 圆角剪裁等）
     *
     * 注意：**参数不允许带默认值**——`@Composable` 接口方法的默认实参桥
     * （`$ComposeDefaultImpls`）在运行期会以 AbstractMethodError 爆掉
     * （本扩展点落地时实测），与 [PlaybackOverlay.Render] 同一条约束。
     */
    @Composable
    fun InlinePlayer(
        media: MediaItem,
        navigator: Navigator,
        onFullscreen: () -> Unit,
        modifier: Modifier,
    )
}
