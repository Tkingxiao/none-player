package com.noneplayer.plugins.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npGlassSurface
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
 *
 * **顶部栏（2026-10-07 用户实报新增）**：返回主页/切页不关闭右栏是路径 A 的定案，
 * 但面板必须自带一个显式的退出点——顶部栏左键就是「关闭播放器」（停会话、
 * 清队列，`nowPlaying` 变 null 后外壳收回该栏，迷你条一并消失）。
 * 栏体走 `npGlassSurface` 完整配方（§4.1 横条一律不透明底 + 发丝线）。
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
        val session = sessionOf()
        val snapshot = session.nowPlaying.collectAsStateWithLifecycle().value ?: return
        val ink = LocalNpInk.current
        Column(
            modifier = modifier
                .background(ink.bg)
                // 右栏通到屏幕顶（外壳三栏是 edge-to-edge 布局），顶栏必须自己
                // 让开状态栏，否则标题/返回键与状态栏图标同高打架（实拍确认）
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NpDimens.paneBarHeight)
                    .npGlassSurface(ink, topHighlight = false, bottomHairline = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NpIconButton(
                    icon = NpIcon.ArrowLeft,
                    contentDescription = "关闭播放器",
                    onClick = {
                        // 关闭 = 停止会话（断点先落盘、队列清空）：
                        // nowPlaying → null 后 isOccupied 翻 false，外壳收回右栏，
                        // 迷你条同步消失——与迷你条 ✕ 是同一个出口。
                        session.stop()
                    },
                    modifier = Modifier.padding(start = NpSpace.sm),
                )
                Text(
                    text = snapshot.title,
                    style = NpTypography.titleSmall,
                    color = ink.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(NpSpace.lg))
            }
            Box(modifier = Modifier.weight(1f)) {
                surface.InlinePlayer(
                    media = snapshot.item,
                    navigator = navigator,
                    onFullscreen = {
                        // `fs=1` = 明确的全屏意图：全屏只有沉浸一种（假全屏已删除）
                        navigator.navigate(
                            "player?uri=${encode(snapshot.item.uri)}" +
                                "&title=${encode(snapshot.title)}" +
                                "&mime=${encode(snapshot.item.mimeType)}" +
                                "&id=${encode(snapshot.item.id)}" +
                                "&fs=1",
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
