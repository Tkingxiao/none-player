package com.noneplayer.plugins.danmaku

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.PluginMarker
import com.noneplayer.kernel.api.register
import com.noneplayer.kernel.ui.api.SettingsProvider

/**
 * 本地弹幕插件（批次 6）——零网络的本地弹幕链路：
 * **来源**只有本地文件（同名自动匹配 / 手动选择 / 后续 MKV 内嵌 ASS 轨），
 * 不做弹幕发送、不接在线弹幕库（§2.6 产品边界）。
 *
 * 本批已落三个任务：
 * ① 三格式解析（[DanmakuParsers]：B 站 XML / 转换器 JSON / ASS）；
 * ② 同名匹配（[DanmakuLocator]，file 直查 + MediaStore 两条通路）；
 * ③ 设置区块（[DanmakuSettingsSection]，经 `SettingsProvider` 聚合）。
 *
 * 待落（批次 6 收尾）：`PlaybackOverlay` 渲染实现——滚动/顶/底三模式的轨道
 * 分配与密度控制、弹幕开关/状态入口、手动选择的 SAF 选择入口与偏移调节。
 * 渲染层按 [AppSettings] 的 `danmaku_*` 键读同一份设置。
 */
@PluginMarker
class DanmakuPlugin : Plugin {

    override val id: String = "danmaku"
    override val name: String = "弹幕"
    override val version: String = "1.0.0"

    override suspend fun onInstall(registry: ExtensionRegistry) {
        registry.register(SettingsProvider::class.java, DanmakuSettingsSection())
    }

    override suspend fun onStart() = Unit
    override suspend fun onStop() = Unit
}
