package com.noneplayer.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条检测到特效的字幕来源（A12 第一期提示用）。
 *
 * [sourceUri] 是**用户挂载时的原地址**（不是转码后的 cache 副本），同一份字幕重复
 * 挂载/重新转码时提示可以按来源去重；[tags] 见 [SubtitleEffectTag]。
 */
data class SubtitleEffectInfo(
    val sourceUri: String,
    val tags: List<SubtitleEffectTag>,
) {
    /** 中文标签，供日志与提示明细使用（提示主文案是固定句子）。 */
    val tagLabels: List<String> get() = tags.map { it.label }
}

/**
 * 外挂字幕特效检测结果的进程级发布点（A12 第一期）。
 *
 * 谁写：`plugin-player-ui` 的 `prepareSubtitleUri`——所有外挂字幕挂载路径（播放页、
 * 会话恢复、迷你条、起播引导）都经过它，此时原始文件字节已经读到内存，检测不额外 IO。
 * 谁读：`PlayerSubtitleLayer`——只负责在画面上沿显示一条可关闭的诚实提示。
 * 谁清：引擎在字幕卸载/换片/清队列/释放时清（`VideoPlayer.setSubtitle(null)` 等），
 * 避免上一条片子的提示残留到下一个视频。
 *
 * 放 `core:media`（而不是 kernel-api / 某个插件）是因为：它是纯域数据，UI 与引擎
 * 都能依赖，且插件之间不允许互相依赖。
 */
object SubtitleEffectNotice {

    private val _info = MutableStateFlow<SubtitleEffectInfo?>(null)

    /** 当前挂载字幕的特效信息；null = 没有检测到特效（或没有外挂字幕）。 */
    val info: StateFlow<SubtitleEffectInfo?> = _info.asStateFlow()

    /** 新一次挂载的检测结果；传 null 表示「这份字幕没有特效」或「已卸载」。 */
    fun publish(info: SubtitleEffectInfo?) {
        _info.value = info
    }

    fun clear() {
        _info.value = null
    }
}
