package com.noneplayer.plugins.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文字宽度缓存（E215）：键 = 文本 + 字号。
 * 钉住四件事：命中不重测、跨字号不串值也不互相清除、字号档交替仍命中
 * （旧"字号分代"实现的退化场景）、桶 LRU 与溢出清桶只动自己的桶。
 */
class TextWidthCacheTest {

    /** 计数 measure：宽度 = 字号 + 文本长度，读数稳定且可分辨串值 */
    private class CountingMeasure {
        var calls = 0
        operator fun invoke(textSizePx: Float, text: String): Float {
            calls++
            return textSizePx + text.length
        }
    }

    @Test
    fun `same text and size measures once then hits cache`() {
        val m = CountingMeasure()
        val cache = TextWidthCache(m::invoke)
        assertEquals(15f, cache.width("abc", 12f), 0f)
        assertEquals(15f, cache.width("abc", 12f), 0f)
        assertEquals(1, m.calls)
    }

    @Test
    fun `same text under different sizes keeps separate values`() {
        val m = CountingMeasure()
        val cache = TextWidthCache(m::invoke)
        assertEquals(13f, cache.width("abc", 10f), 0f)
        assertEquals(23f, cache.width("abc", 20f), 0f)
        // 回读两个字号都不重测——旧分代实现这里会整体 clear 后重测
        assertEquals(13f, cache.width("abc", 10f), 0f)
        assertEquals(23f, cache.width("abc", 20f), 0f)
        assertEquals(2, m.calls)
    }

    @Test
    fun `alternating sizes keep hitting cache`() {
        val m = CountingMeasure()
        val cache = TextWidthCache(m::invoke)
        repeat(10) {
            cache.width("弹", 10f)
            cache.width("弹", 20f)
        }
        assertEquals(2, m.calls)
    }

    @Test
    fun `lru evicts oldest bucket beyond maxBuckets`() {
        val m = CountingMeasure()
        val cache = TextWidthCache(m::invoke, maxBuckets = 2)
        cache.width("x", 10f)
        cache.width("x", 20f)
        cache.width("x", 30f) // 10f 桶最久未用 → 被逐出
        cache.width("x", 20f)
        cache.width("x", 30f) // 20f/30f 都还在，两次命中
        assertEquals(3, m.calls)
        cache.width("x", 10f) // 桶已不在 → 重新建桶重测
        assertEquals(4, m.calls)
    }

    @Test
    fun `bucket overflow clears only that bucket`() {
        val m = CountingMeasure()
        val cache = TextWidthCache(m::invoke, maxEntries = 2)
        cache.width("a", 10f)
        cache.width("b", 10f)
        cache.width("c", 10f) // 10f 桶满 → 清桶（a/b 失效，c 重测）
        cache.width("a", 20f) // 20f 桶不受影响
        assertEquals(4, m.calls)
        // 10f 桶已清：a 需要重测；20f 桶的 a 仍命中
        cache.width("a", 10f)
        cache.width("a", 20f)
        assertEquals(5, m.calls)
    }
}
