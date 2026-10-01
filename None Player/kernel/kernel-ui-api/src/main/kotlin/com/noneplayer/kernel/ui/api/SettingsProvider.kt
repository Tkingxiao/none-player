package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import com.noneplayer.kernel.api.ExtensionPoint

/**
 * 设置区块扩展点——插件向设置页注入自己的设置项。
 *
 * 内核聚合所有实现并按 [order] 排序渲染（设计文档 §8）：
 * 区块标题由内核统一绘制，[Render] 只输出区块内容行，
 * 保证所有插件设置项的视觉一致性。
 */
interface SettingsProvider : ExtensionPoint {
    val sectionId: String
    val title: String

    /** 排序权重，越小越靠前 */
    val order: Int get() = 100

    /** 渲染区块内容（若干设置行） */
    @Composable
    fun Render()
}
