package com.noneplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StaggerWindowCacheTest {

    @Test
    fun `同 key 复用同一窗口`() {
        val cache = StaggerWindowCache(maxEntries = 4, nowMs = { 100L })
        assertEquals(cache.windowFor("grid"), cache.windowFor("grid"))
    }

    @Test
    fun `超过上限淘汰最久未用的 key`() {
        var now = 0L
        val cache = StaggerWindowCache(maxEntries = 2, nowMs = { now })

        val a = cache.windowFor("a")
        now = 1L
        val b = cache.windowFor("b")
        cache.windowFor("a") // 访问 a，b 变成 LRU

        now = 2L
        cache.windowFor("c")
        assertEquals(2, cache.sizeForTest())

        // 先确认 a 仍保留（访问 a 使其成为 MRU），再验证 b 已淘汰。
        assertEquals("仍被访问的 key 保持原窗口", a, cache.windowFor("a"))
        now = 3L
        val bAgain = cache.windowFor("b")
        assertNotEquals("被淘汰的 key 应重新起窗", b, bAgain)
    }

    @Test
    fun `不同 key 分别登记`() {
        var now = 10L
        val cache = StaggerWindowCache(maxEntries = 4, nowMs = { now })
        val first = cache.windowFor("one")
        now = 20L
        val second = cache.windowFor("two")
        assertNotEquals(first, second)
        assertEquals(2, cache.sizeForTest())
    }
}
