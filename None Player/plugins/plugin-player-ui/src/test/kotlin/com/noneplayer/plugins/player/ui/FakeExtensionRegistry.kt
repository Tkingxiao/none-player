package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.ExtensionRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 最小 [ExtensionRegistry] 替身：够 [com.noneplayer.kernel.api.VideoPlayerFactory]
 * 按 `query<PlayerEngineProvider>()` 选引擎即可。
 */
internal class FakeExtensionRegistry : ExtensionRegistry {

    private val byType = LinkedHashMap<Class<*>, MutableList<Any>>()

    override fun <T : ExtensionPoint> register(clazz: Class<T>, extension: T) {
        byType.getOrPut(clazz) { mutableListOf() } += extension
    }

    override fun <T : ExtensionPoint> unregister(clazz: Class<T>, extension: T) {
        byType[clazz]?.remove(extension)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ExtensionPoint> query(clazz: Class<T>): List<T> =
        byType[clazz].orEmpty().toList() as List<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : ExtensionPoint> observe(clazz: Class<T>): Flow<List<T>> =
        flowOf(query(clazz))
}
