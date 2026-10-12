package com.noneplayer.kernel.ui.api

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「在**右栏/内嵌语境**里起播这条媒体」的一次性请求通道。
 *
 * 场景（2026-10-12 用户实报）：平板右栏被会话占用（`LocalNpPaneOccupied`）时，
 * 详情页的「播放/继续播放」没有内嵌画面可挂（一台播放器只有一个输出面），
 * 此前直接回退成"跳转独占全屏播放页"——用户看到的是一次突兀的整屏跳变。
 * 期望语义是**就地播在右栏**：右栏本来就是这台平板的播放面。
 *
 * 为什么是值型通道而不是接口方法：发起方在 `plugin-library`（详情页），
 * 消费方在 `plugin-player-ui`（右栏），插件之间禁止互相依赖——与 `NpTabChannel`
 * （首页 ↔ 我的 的聚合跳转）同一姿势，共享点落在双方都依赖的内核 UI 契约里。
 * **消费即清空**，同一请求不会被消费两次；时间戳做 key，连续两次点同一媒体也能触发。
 */
object NpInlinePlayRequest {

    data class Request(
        val item: MediaItem,
        /** 仅听音频：右栏放不了画面，消费方应把它交给「仅听音频」入口而不是内嵌画面 */
        val audioOnly: Boolean = false,
    )

    private val pending = MutableStateFlow<Pair<Request, Long>?>(null)
    val flow: StateFlow<Pair<Request, Long>?> = pending.asStateFlow()

    /** 详情页发起：请右栏/内嵌语境起播这条媒体 */
    fun request(item: MediaItem, audioOnly: Boolean = false) {
        pending.value = Request(item, audioOnly) to System.currentTimeMillis()
    }

    /** 消费方取用一次；取到即清空（值不匹配说明已有更新的一次请求，不动它） */
    fun consume(tick: Pair<Request, Long>) {
        if (pending.value === tick) pending.value = null
    }
}
