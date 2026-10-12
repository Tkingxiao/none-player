package com.noneplayer.kernel

import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private interface FakePoint : ExtensionPoint
private class FakeExtensionA : FakePoint
private class FakeExtensionB : FakePoint
private class OtherPoint : ExtensionPoint

class DefaultExtensionRegistryTest {

    @Test
    fun `注册后可查询`() {
        val registry = DefaultExtensionRegistry()
        val ext = FakeExtensionA()
        registry.register(FakePoint::class.java, ext)

        val result: List<FakePoint> = registry.query()
        assertEquals(listOf(ext), result)
    }

    @Test
    fun `query 只返回该扩展点类型的实现`() {
        val registry = DefaultExtensionRegistry()
        val a = FakeExtensionA()
        val b = FakeExtensionB()
        val other = OtherPoint()
        registry.register(FakePoint::class.java, a)
        registry.register(FakePoint::class.java, b)
        registry.register(OtherPoint::class.java, other)

        assertEquals(setOf(a, b), registry.query<FakePoint>().toSet())
        assertEquals(listOf(other), registry.query<OtherPoint>())
    }

    @Test
    fun `注销后不再可查`() {
        val registry = DefaultExtensionRegistry()
        val ext = FakeExtensionA()
        registry.register(FakePoint::class.java, ext)
        registry.unregister(FakePoint::class.java, ext)

        assertTrue(registry.query<FakePoint>().isEmpty())
    }

    /**
     * 同一实例重复注册是幂等的（2026-10-12 release 闪退的契约防线）。
     *
     * 当时 R8 类合并把两个扩展接口合到同一个运行时 Class：同一 source 以两个
     * "接口键"注册进同一个桶，`query<T>()` 返回两份 → 媒体列表整表翻倍 →
     * Lazy 重复 key 闪退。keep 规则已禁止合并；这份用例把"幂等注册"钉死在契约上，
     * 无论合并与否，查询结果都不允许同一实例出现两次。
     */
    @Test
    fun `同一实例重复注册只保留一份`() {
        val registry = DefaultExtensionRegistry()
        val ext = FakeExtensionA()
        registry.register(FakePoint::class.java, ext)
        registry.register(FakePoint::class.java, ext)

        assertEquals(listOf(ext), registry.query<FakePoint>())
    }

    @Test
    fun `observe 立即给出快照并推送增量`() = runTest {
        val registry = DefaultExtensionRegistry()
        val a = FakeExtensionA()
        registry.register(FakePoint::class.java, a)

        val first = registry.observe(FakePoint::class.java).first()
        assertEquals(listOf(a), first)

        val b = FakeExtensionB()
        registry.register(FakePoint::class.java, b)
        val second = registry.observe(FakePoint::class.java).first()
        assertEquals(setOf(a, b), second.toSet())
    }
}
