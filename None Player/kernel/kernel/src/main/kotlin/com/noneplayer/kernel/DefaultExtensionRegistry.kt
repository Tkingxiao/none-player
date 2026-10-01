package com.noneplayer.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.ExtensionRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * 扩展注册表默认实现。
 *
 * - 每个扩展点类型对应一个不可变列表的 [MutableStateFlow]，注册/注销
 *   以"整体替换"的方式发布，读侧天然线程安全；
 * - [observe] 基于同一 StateFlow，晚到的订阅者立即拿到当前快照并
 *   持续接收增量（设计文档 §14：扩展点查询要有缓存 + Flow 推送）。
 */
class DefaultExtensionRegistry : ExtensionRegistry {

    private val lock = Any()
    private val extensions = ConcurrentHashMap<Class<*>, MutableStateFlow<List<Any>>>()

    override fun <T : ExtensionPoint> register(clazz: Class<T>, extension: T) {
        synchronized(lock) { flowFor(clazz).update { it + extension } }
    }

    override fun <T : ExtensionPoint> unregister(clazz: Class<T>, extension: T) {
        synchronized(lock) { flowFor(clazz).update { it - extension } }
    }

    override fun <T : ExtensionPoint> query(clazz: Class<T>): List<T> =
        synchronized(lock) { snapshot(clazz) }

    override fun <T : ExtensionPoint> observe(clazz: Class<T>): Flow<List<T>> =
        synchronized(lock) { flowFor(clazz).map { list -> castList(clazz, list) } }

    private fun flowFor(clazz: Class<*>): MutableStateFlow<List<Any>> =
        extensions.getOrPut(clazz) { MutableStateFlow(emptyList()) }

    private fun <T : ExtensionPoint> snapshot(clazz: Class<T>): List<T> =
        castList(clazz, flowFor(clazz).value)

    @Suppress("UNCHECKED_CAST")
    private fun <T : ExtensionPoint> castList(clazz: Class<T>, list: List<Any>): List<T> =
        list.filter { clazz.isInstance(it) }.map { clazz.cast(it) }
}
