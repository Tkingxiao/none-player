package com.noneplayer.kernel.ui.api

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「请把媒体库左栏落到这条媒体的剧集页」的一次性请求。
 *
 * **方向与 [NpInlinePlayRequest] 相反**：那条是媒体库请右栏起播，这条是播放页请媒体库
 * 落位。发起方在 `plugin-player-ui`（独占播放页），消费方在 `plugin-library`（左栏），
 * 插件之间禁止互相依赖（§5 铁律），所以共享点只能落在双方都依赖的 `kernel-ui-api`。
 *
 * 为什么需要它：类 PC 档的"横屏视频界面"是**左栏剧集页 + 右栏画面**。竖屏点卡片是直接
 * 进播放页（没有剧集页可进），此时把窗口转横、播放页交还右栏，左栏就停在首页/文件夹上
 * ——用户实报"应该是横屏的剧集页"（2026-10-14 真机）。播放页手里正好有会话的当前项，
 * 由它把这一项交给媒体库落位，是唯一不跨插件依赖的做法。
 *
 * **带时间戳且只认新鲜值**：媒体库可能根本不在当前 Tab（用户在「我的」里看的视频），
 * 那时没人消费，请求会一直躺着；不设时限的话，用户下一次切回媒体库会被当成"刚转完屏"
 * 而凭空多出一次剧集页跳转。时限就是给这种迟到值准备的出口。
 */
object NpDetailRequest {

    /** 超过这个年龄的请求一律丢弃（转屏交还到本页重新组合只隔一两帧，1.5s 已是宽裕上限） */
    const val MAX_AGE_MS = 1_500L

    private val pending = MutableStateFlow<Pair<MediaItem, Long>?>(null)
    val flow: StateFlow<Pair<MediaItem, Long>?> = pending.asStateFlow()

    /** 播放页发起：请媒体库把左栏落到 [item] 的剧集页 */
    fun request(item: MediaItem) {
        pending.value = item to System.currentTimeMillis()
    }

    /** 消费方取用一次；取到即清空（值不匹配说明已有更新的一次请求，不动它） */
    fun consume(tick: Pair<MediaItem, Long>) {
        if (pending.value === tick) pending.value = null
    }
}
