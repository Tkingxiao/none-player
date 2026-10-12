package com.noneplayer.plugins.danmaku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.PlaybackMenuContributor
import com.noneplayer.kernel.ui.api.npPlaybackMenuRow
import com.noneplayer.kernel.ui.api.npPlaybackMenuSwitchRow
import com.noneplayer.kernel.ui.api.npPlaybackMenuTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 弹幕选项表 ↔ 覆盖层之间的进程级通道（2026-10-12 用户裁定：开关进「播放选项」表）。
 *
 * 两处组合没有共同的 `remember` 作用域：选项表行活在播放页的弹层里，覆盖层活在
 * 视频舞台子树里，而插件之间禁止互相依赖——与 `NpTabChannel`/`NpInlinePlayRequest`
 * 同一姿势，共享点落在插件自己的进程级对象里（内核不感知"弹幕"这个概念）。
 *
 * [Command.PickFile] 走命令通道而不是在菜单行里直接开 SAF：
 * `rememberLauncherForActivityResult` 的回调随组合一起销毁，而菜单是弹层——
 * 弹层先关就没人在结果处接住了。launcher 因此常驻在覆盖层组合里，菜单只按一下。
 */
internal object DanmakuMenuBridge {

    /** 内嵌弹幕轨选择：`(媒体 uri, 轨 id)`——uri 不匹配当前媒体即视为未选 */
    private val _picked = MutableStateFlow<Pair<String, String?>>("" to null)
    val picked: StateFlow<Pair<String, String?>> = _picked.asStateFlow()

    fun setPicked(mediaUri: String, trackId: String?) {
        _picked.value = mediaUri to trackId
    }

    enum class Command { PickFile }

    private val _command = MutableStateFlow<Pair<Command, String>?>(null)
    val command: StateFlow<Pair<Command, String>?> = _command.asStateFlow()

    fun send(command: Command, mediaUri: String) {
        _command.value = command to mediaUri
    }

    /** 覆盖层执行完即清，避免重进播放页时把同一条命令再放一次 */
    fun clearCommand() {
        _command.value = null
    }
}

/**
 * 弹幕在播放器「播放选项」表里的贡献行（[PlaybackMenuContributor]）。
 *
 * 内容与原右上角「弹」pill 一一对应：总开关、手动选择弹幕文件、清除手动、
 * 内嵌弹幕轨、退出内嵌轨——换的是宿主与形态：开/关是**一行正经开关**，
 * 点它只翻状态、不弹任何二级面板（用户实报"打开的时候不要弹。弹，是弹。开才对"）。
 */
class DanmakuMenuContributor : PlaybackMenuContributor {

    override val contributorId: String = "danmaku.menu"

    /** 播放器自己的行（收藏等）之后——贡献的是附加能力，不抢主操作的位置 */
    override val order: Int = 200

    @Composable
    override fun RenderRows(dark: Boolean, media: MediaItem?, player: VideoPlayer?) {
        if (media == null) return
        val kernel = LocalKernel.current
        val settings = remember { kernel.services.get<AppSettings>() }
        val scope = rememberCoroutineScope()

        val enabled by settings.danmakuEnabled.collectAsStateWithLifecycle(initialValue = true)
        val manualUri by settings.danmakuManualUri(media.uri)
            .collectAsStateWithLifecycle(initialValue = null)
        val tracks by remember(player) { player?.tracks ?: emptyTracks }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val picked by DanmakuMenuBridge.picked.collectAsStateWithLifecycle(initialValue = "" to null)

        val textTracks = tracks.filter { it.type == NpTrackType.TEXT }
        val embeddedPicked = picked.second.takeIf { picked.first == media.uri }

        npPlaybackMenuTitle("弹幕", dark)
        npPlaybackMenuSwitchRow(
            label = "弹幕",
            checked = enabled,
            onCheckedChange = { on -> scope.launch { settings.setDanmakuEnabled(on) } },
            dark = dark,
            icon = NpIcon.Danmaku,
            value = sourceLabel(enabled, manualUri, embeddedPicked, textTracks.isNotEmpty()),
        )
        npPlaybackMenuRow(
            label = "选择弹幕文件…",
            onClick = {
                // 先切回文件来源，否则内嵌轨选中时文件加载 effect 会被跳过（选了不生效）
                DanmakuMenuBridge.setPicked(media.uri, null)
                DanmakuMenuBridge.send(DanmakuMenuBridge.Command.PickFile, media.uri)
            },
            dark = dark,
            icon = NpIcon.Folder,
        )
        if (manualUri != null) {
            npPlaybackMenuRow(
                label = "清除手动选择（回到自动匹配）",
                onClick = {
                    DanmakuMenuBridge.setPicked(media.uri, null)
                    scope.launch { settings.setDanmakuManualUri(media.uri, null) }
                },
                dark = dark,
                icon = NpIcon.Close,
            )
        }
        if (embeddedPicked != null) {
            npPlaybackMenuRow(
                label = "退出内嵌轨（回到自动匹配）",
                onClick = { DanmakuMenuBridge.setPicked(media.uri, null) },
                dark = dark,
                icon = NpIcon.ArrowLeft,
            )
        }
        // 内嵌文本轨与「字幕」按钮是同一批轨道。多功能菜单里再列一遍就是重复项，
        // 用户要求从多功能选项里去掉。选轨只留字幕面板。
    }

    private companion object {
        val emptyTracks: StateFlow<List<NpTrack>> = MutableStateFlow(emptyList<NpTrack>())

        /** 开关行的副标题 = "现在用的是哪个来源"，与字幕行同一读法 */
        fun sourceLabel(
            enabled: Boolean,
            manualUri: String?,
            embeddedPicked: String?,
            hasEmbeddedTracks: Boolean,
        ): String = when {
            !enabled -> "关闭"
            embeddedPicked != null -> "内嵌轨"
            manualUri != null ->
                android.net.Uri.decode(manualUri.substringAfterLast('/')).substringBeforeLast('.')
            hasEmbeddedTracks -> "自动匹配 / 内嵌轨"
            else -> "自动匹配"
        }
    }
}
