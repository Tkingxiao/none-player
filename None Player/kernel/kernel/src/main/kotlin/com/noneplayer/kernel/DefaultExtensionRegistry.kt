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
        // 注册**按实例幂等**：同一实例在同一键下重复注册是 no-op，而不是记两遍。
        // 2026-10-12 release 实报闪退的根因就踩在这条上——R8 类合并把两个扩展接口
        // 合成同一个运行时 Class，于是 `register(A::class.java, x)` 与
        // `register(B::class.java, x)` 落进同一个桶，`query<A>()` 返回 x 两份。
        // keep 规则（app/proguard-rules.pro）已杜绝合并；这里再钉一遍幂等语义：
        // 查询结果里同一实例永远只出现一次，注册方的重复调用也不再是隐患。
        synchronized(lock) {
            flowFor(clazz).update { list -> if (list.any { it === extension }) list else list + extension }
        }
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
        list.filter { clazz.isInstance(it) }.map { clazz.cast(it) as T }
}
