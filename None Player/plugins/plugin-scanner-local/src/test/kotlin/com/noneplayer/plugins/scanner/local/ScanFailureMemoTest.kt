package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFailureMemoTest {

    @Test
    fun `失败在 TTL 内命中，过期后允许重试`() {
        var now = 1_000L
        val memo = ScanFailureMemo(ttlMs = 5_000L, nowMs = { now })
        memo.markFailed("file:///a.avi")

        now = 1_001L
        assertTrue(memo.hasFailed("file:///a.avi"))
        now = 6_000L
        assertTrue(memo.hasFailed("file:///a.avi"))
        now = 6_001L
        assertFalse(memo.hasFailed("file:///a.avi"))
    }

    @Test
    fun `成功清除失败记录`() {
        val memo = ScanFailureMemo(ttlMs = 5_000L, nowMs = { 1_000L })
        memo.markFailed("file:///a.avi")
        memo.markOk("file:///a.avi")
        assertFalse(memo.hasFailed("file:///a.avi"))
    }

    @Test
    fun `失败表有 LRU 上限`() {
        val memo = ScanFailureMemo(ttlMs = 60_000L, maxEntries = 4, nowMs = { 1_000L })
        repeat(10) { memo.markFailed("file:///v$it.avi") }
        assertEquals(4, memo.sizeForTest())
    }
}
