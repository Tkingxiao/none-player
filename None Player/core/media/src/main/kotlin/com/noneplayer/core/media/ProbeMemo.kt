package com.noneplayer.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 最近一次起播探测的摘要（E39 余项 + §12.3 解码诊断，批次 8）。
 *
 * 探测发生在播放侧（`:core:media-android` / `plugin-player-exo`），而查看入口在
 * 设置页「解码诊断」——两者模块不同源，共享点放在纯 Kotlin 的 [ProbeMemo]
 * （kernel-api 已 `api(core:media)`，全链可见）。进程级单例、StateFlow 只写最新一条：
 * 这是**排障工具**不是日志，历史没有消费方，不制造"看起来该有历史"的假期望。
 */
object ProbeMemo {

    /** 一条探测摘要：只放人能读的展示值，原始 Result 不外流 */
    data class Entry(
        val displayName: String,
        val container: String?,
        val videoCodec: String?,
        val audioCodec: String?,
        /** HDR 概况（"HDR10" / "HLG"）；SDR / 未知为 null */
        val hdr: String?,
        /** 音频声道布局描述（"2.0" / "5.1"）；未知为 null */
        val audioChannels: String?,
        val playable: Boolean,
        val reason: String?,
        val atMs: Long,
    )

    private val _last = MutableStateFlow<Entry?>(null)

    /** 设置页订阅；播放侧每次 FormatProbe 调用后写入 */
    val last: StateFlow<Entry?> = _last.asStateFlow()

    fun record(entry: Entry) {
        _last.value = entry
    }
}
