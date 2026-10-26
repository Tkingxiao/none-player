package com.noneplayer.kernel.api

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * 事件总线——跨插件通知通道（Flow-based）。
 *
 * 约束（设计文档 §4.3）：只用于**跨插件通知**，不用于请求-响应；
 * 请求-响应请走扩展点。事件是即发即弃的，不保证重放。
 */
interface EventBus {
    /** 发布事件；buffer 满或无订阅者时事件被丢弃（通知语义） */
    fun publish(event: Any)

    /** 订阅指定类型的事件流 */
    fun <T : Any> subscribe(clazz: KClass<T>): Flow<T>
}

/** 订阅指定类型的事件流（reified 便捷重载） */
inline fun <reified T : Any> EventBus.subscribe(): Flow<T> = subscribe(T::class)

/**
 * 内核级事件。插件私有事件自行定义，无需继承此处任何类型。
 */
sealed interface KernelEvent {
    /** 某个媒体来源完成了一次扫描 */
    data class MediaScanned(val sourceId: String, val count: Int) : KernelEvent

    /** 播放开始 */
    data class PlaybackStarted(val mediaId: String) : KernelEvent

    /**
     * E232：媒体文件已从设备删除（库页删除链路成功后广播）。
     *
     * 播放会话据此停掉"正在播的那一条"——否则引擎仍握着已删除文件的上一个句柄，
     * 迷你条/通知会停在一条已经不存在的媒体上。事件只做通知，不携带完整条目。
     */
    data class MediaDeleted(val mediaId: String, val uri: String) : KernelEvent

    /** 插件完成安装 */
    data class PluginInstalled(val pluginId: String) : KernelEvent

    /**
     * E59 修复配套：某个插件的 onStart() 抛出异常（已被内核捕获、不影响其余插件与
     * BootState.Running）。设置页"插件管理"等可订阅此事件向用户呈现局部故障。
     */
    data class PluginStartFailed(val pluginId: String, val cause: Throwable) : KernelEvent
}
