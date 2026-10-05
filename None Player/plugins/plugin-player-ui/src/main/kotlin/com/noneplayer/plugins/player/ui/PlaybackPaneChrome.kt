package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.DetailPaneProvider
import com.noneplayer.kernel.ui.api.LocalKernel

/**
 * 类 PC 右栏的**内联播放栏**（§8.2 路径 A）。
 *
 * 解决的问题：宽窗口里"列表"和"画面"原本只能二选一——要么进独占播放页看不见列表，
 * 要么退回列表就没地方看画面。外壳在 `isDesktopLike` 档把窗口切成三栏后，
 * 这一栏负责右侧那一栏：画面常驻，浏览列表时播放不中断。
 *
 * 渲染直接复用 [InlinePlayerSurface]（详情页内嵌播放的同一份实现）：
 * 装载走同一条 `ensureSessionPlaying` 链（同文件夹队列 / 断点 / 字幕记忆 / 倍速），
 * 控制层同样是"播放暂停 + 进度 + 时间 + 全屏"四件——**不另起一份播放器**，
 * 两处各写一份必然漂移。
 *
 * 点「全屏」导航到独占播放页：会话已在播同一媒体，落页只续播不重启（B1 语义），
 * 与迷你条点回播放页走的是同一条深链参数形态（uri/title/mime/id 齐全）。
 */
class PlaybackPaneChrome : DetailPaneProvider {

    override val order: Int = 0

    /** 与详情页内嵌播放同一份引擎实现；本身无状态，可作为实例字段持有 */
    private val surface = InlinePlayerSurface()

    @Composable
    override fun isOccupied(): Boolean {
        val session = sessionOf()
        val nowPlaying by session.nowPlaying.collectAsStateWithLifecycle()
        val audioOnly by session.audioOnly.collectAsStateWithLifecycle()
        // 仅听音频模式由整屏音频页承载，右栏没有画面可放——这一档仍交给底部迷你条
        return nowPlaying != null && !audioOnly
    }

    @Composable
    override fun Render(navigator: Navigator, modifier: Modifier) {
        val snapshot = sessionOf().nowPlaying.collectAsStateWithLifecycle().value ?: return
        surface.InlinePlayer(
            media = snapshot.item,
            navigator = navigator,
            onFullscreen = {
                // `fs=1` = 明确的全屏意图：类 PC 档落地即沉浸隐藏系统栏（§14 #16 的出口）
                navigator.navigate(
                    "player?uri=${encode(snapshot.item.uri)}" +
                        "&title=${encode(snapshot.title)}" +
                        "&mime=${encode(snapshot.item.mimeType)}" +
                        "&id=${encode(snapshot.item.id)}" +
                        "&fs=1",
                )
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun sessionOf() = SessionHolder.of(sessionFactoryOf(LocalKernel.current.services))

    /**
     * 转义路由参数：标题含 `&`/`=`/中文/空格时裸露拼进 route 会截断参数。
     *
     * 用 `Uri.encode(value, "")` 与媒体库那条 `openPlayer` 同一条编码约定（E55）：
     * `URLEncoder` 把空格写成 `+`，而 `+` 只有在"表单解码"那一路才被还原成空格，
     * 查询参数按 URI 规范解码时它就是个加号。
     */
    private fun encode(value: String): String = android.net.Uri.encode(value, "")
}
