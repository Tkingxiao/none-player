package com.noneplayer.kernel.api

import kotlinx.coroutines.flow.Flow

/**
 * 扩展注册表——内核的心脏。
 *
 * 插件在 [Plugin.onInstall] 中注册扩展实现；任何一方通过 query/observe
 * 发现扩展。实现必须线程安全，且 observe 推送后续注册的增量。
 */
interface ExtensionRegistry {
    fun <T : ExtensionPoint> register(clazz: Class<T>, extension: T)
    fun <T : ExtensionPoint> unregister(clazz: Class<T>, extension: T)
    fun <T : ExtensionPoint> query(clazz: Class<T>): List<T>
    fun <T : ExtensionPoint> observe(clazz: Class<T>): Flow<List<T>>
}

/** 注册一个扩展点实现（reified 便捷重载） */
inline fun <reified T : ExtensionPoint> ExtensionRegistry.register(extension: T) =
    register(T::class.java, extension)

/** 注销一个扩展点实现（reified 便捷重载） */
inline fun <reified T : ExtensionPoint> ExtensionRegistry.unregister(extension: T) =
    unregister(T::class.java, extension)

/** 查询扩展点当前所有实现（reified 便捷重载） */
inline fun <reified T : ExtensionPoint> ExtensionRegistry.query(): List<T> =
    query(T::class.java)

/** 观察扩展点实现的动态变化（reified 便捷重载） */
inline fun <reified T : ExtensionPoint> ExtensionRegistry.observe(): Flow<List<T>> =
    observe(T::class.java)
