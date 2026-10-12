package com.noneplayer.plugins.danmaku

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.PlaybackMenuContributor
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import com.noneplayer.kernel.ui.api.SettingsProvider

/**
 * 本地弹幕插件（批次 6）——零网络的本地弹幕链路：
 * **来源**只有本地文件（同名自动匹配 / 手动选择 / 后续 MKV 内嵌 ASS 轨），
 * 不做弹幕发送、不接在线弹幕库（§2.6 产品边界）。
 *
 * 本批六个任务全部落地：
 * ① 三格式解析（[DanmakuParsers]）＋ ② 同名匹配（[DanmakuLocator]）＋
 * ③ 设置区块（[DanmakuSettingsSection]）＋ ④ 渲染层（[DanmakuOverlay]，
 * `PlaybackOverlay` 挂载，DanmakuPlanner 纯函数摆位 + 单 Canvas 绘制）＋
 * ⑤ 开关与来源管理（[DanmakuMenuContributor]，经 `PlaybackMenuContributor`
 * 挂进播放器「播放选项」表）＋ ⑥ 内嵌弹幕轨 cue 通路
 * （已知限制见 [DanmakuOverlay] 类注）。
 */
@PluginMarker
class DanmakuPlugin : Plugin {

    override val id: String = "danmaku"
    override val name: String = "弹幕"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(SettingsProvider::class.java, DanmakuSettingsSection())
        registry.register(PlaybackOverlay::class.java, DanmakuOverlay())
        // 弹幕开关进播放器「播放选项」表（2026-10-12 用户裁定）。
        // 新增扩展点接口必须同步 app/proguard-rules.pro 的 keep，否则 release 下
        // R8 接口合并会让注册落进同一个桶（历史上真实翻过一次）。
        registry.register(PlaybackMenuContributor::class.java, DanmakuMenuContributor())
    }

    override suspend fun onStart() = Unit
    override suspend fun onStop() = Unit
}
