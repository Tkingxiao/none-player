package com.noneplayer.kernel

import com.noneplayer.kernel.api.Plugin

/**
 * 插件依赖拓扑排序（Kahn 算法，设计文档 §11）。
 *
 * 两类装配错误都必须启动即崩溃、绝不静默：
 * - 依赖的插件不存在；
 * - 存在循环依赖（含自依赖）。
 */
object PluginSorter {

    fun sort(plugins: List<Plugin>): List<Plugin> {
        val byId = plugins.associateBy { it.id }
        check(byId.size == plugins.size) {
            val duplicated = plugins.groupBy { it.id }.filterValues { it.size > 1 }.keys
            "插件 id 冲突: $duplicated"
        }

        val inDegree = HashMap<String, Int>()
        val graph = HashMap<String, MutableList<String>>()
        plugins.forEach { inDegree[it.id] = 0 }

        plugins.forEach { plugin ->
            plugin.dependencies.forEach { dep ->
                check(byId.containsKey(dep)) { "插件 ${plugin.id} 依赖的插件 \"$dep\" 不存在" }
                graph.getOrPut(dep) { mutableListOf() }.add(plugin.id)
                inDegree[plugin.id] = inDegree.getValue(plugin.id) + 1
            }
        }

        val queue = ArrayDeque(plugins.filter { inDegree.getValue(it.id) == 0 })
        val sorted = mutableListOf<Plugin>()
        while (queue.isNotEmpty()) {
            val plugin = queue.removeFirst()
            sorted += plugin
            graph[plugin.id]?.forEach { next ->
                val remaining = inDegree.getValue(next) - 1
                inDegree[next] = remaining
                if (remaining == 0) queue += byId.getValue(next)
            }
        }

        check(sorted.size == plugins.size) {
            val cyclic = plugins.map { it.id }.toSet() - sorted.map { it.id }.toSet()
            "检测到插件循环依赖，涉及: $cyclic"
        }
        return sorted
    }
}
