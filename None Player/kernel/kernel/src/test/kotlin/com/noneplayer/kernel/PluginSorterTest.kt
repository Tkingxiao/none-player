package com.noneplayer.kernel

import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.Plugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakePlugin(
    override val id: String,
    override val dependencies: List<String> = emptyList(),
) : Plugin {
    override val name: String = id
    override val version: String = "1.0.0"
    var installCalls = 0
    var startCalls = 0
    var stopCalls = 0

    override suspend fun onInstall(registry: ExtensionRegistry) { installCalls++ }
    override suspend fun onStart() { startCalls++ }
    override suspend fun onStop() { stopCalls++ }
}

class PluginSorterTest {

    @Test
    fun `无依赖时保持原有顺序`() {
        val a = FakePlugin("a")
        val b = FakePlugin("b")
        assertEquals(listOf(a, b), PluginSorter.sort(listOf(a, b)))
    }

    @Test
    fun `依赖者排在被依赖者之后`() {
        val core = FakePlugin("core")
        val ui = FakePlugin("ui.x", dependencies = listOf("core"))
        val sorted = PluginSorter.sort(listOf(ui, core))
        assertEquals(listOf(core, ui), sorted)
    }

    @Test
    fun `多层依赖按拓扑序展开`() {
        val a = FakePlugin("a")
        val b = FakePlugin("b", dependencies = listOf("a"))
        val c = FakePlugin("c", dependencies = listOf("b"))
        val sorted = PluginSorter.sort(listOf(c, b, a))
        assertTrue(sorted.indexOf(a) < sorted.indexOf(b))
        assertTrue(sorted.indexOf(b) < sorted.indexOf(c))
    }

    @Test
    fun `依赖缺失时崩溃报错`() {
        val orphan = FakePlugin("orphan", dependencies = listOf("ghost"))
        try {
            PluginSorter.sort(listOf(orphan))
            fail("应当因依赖缺失而失败")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ghost"))
        }
    }

    @Test
    fun `循环依赖时崩溃报错`() {
        val a = FakePlugin("a", dependencies = listOf("b"))
        val b = FakePlugin("b", dependencies = listOf("a"))
        try {
            PluginSorter.sort(listOf(a, b))
            fail("应当因循环依赖而失败")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("循环依赖"))
        }
    }

    @Test
    fun `插件 id 重复时崩溃报错`() {
        val a1 = FakePlugin("dup")
        val a2 = FakePlugin("dup")
        try {
            PluginSorter.sort(listOf(a1, a2))
            fail("应当因 id 冲突而失败")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("dup"))
        }
    }
}
