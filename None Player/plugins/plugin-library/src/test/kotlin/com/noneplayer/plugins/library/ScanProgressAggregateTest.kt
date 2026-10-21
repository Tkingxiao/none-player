package com.noneplayer.plugins.library

import com.noneplayer.kernel.api.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanProgressAggregateTest {

    @Test
    fun `全部 idle 时回到 idle`() {
        assertEquals(ScanProgress(), aggregateScanProgress(emptyList()))
        assertEquals(ScanProgress(), aggregateScanProgress(listOf(ScanProgress(), ScanProgress(total = 10))))
    }

    @Test
    fun `只聚合 active 的来源`() {
        val result = aggregateScanProgress(
            listOf(
                ScanProgress(active = true, processed = 3, total = 10),
                ScanProgress(active = false, processed = 9, total = 9),
                ScanProgress(active = true, processed = 1, total = 5),
            ),
        )
        assertEquals(ScanProgress(active = true, processed = 4, total = 15), result)
    }

    @Test
    fun `active 但 total 未知时保留 indeterminate`() {
        val result = aggregateScanProgress(listOf(ScanProgress(active = true)))
        assertEquals(true, result.active)
        assertEquals(0, result.total)
    }
}
