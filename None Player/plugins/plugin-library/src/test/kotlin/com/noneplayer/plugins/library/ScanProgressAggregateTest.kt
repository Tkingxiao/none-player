package com.noneplayer.plugins.library

import com.noneplayer.kernel.api.ScanPhase
import com.noneplayer.kernel.api.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `单来源的 phase 原样透传`() {
        val result = aggregateScanProgress(
            listOf(ScanProgress(active = true, processed = 12, phase = ScanPhase.HIDDEN_WALK)),
        )
        assertEquals(ScanPhase.HIDDEN_WALK, result.phase)
    }

    @Test
    fun `多来源取第一个非 null 的 phase 且忽略 idle 来源`() {
        val result = aggregateScanProgress(
            listOf(
                // idle 来源即使带 phase 也不参与聚合
                ScanProgress(active = false, phase = ScanPhase.COMMIT),
                // total=0（HIDDEN_WALK 的"已发现"口径）：不混进「已处理 x / y」的分子，
                // 否则会出现"已处理 13 / 48 之外的 5"——E205⑬ 修的就是这个量纲混用
                ScanProgress(active = true, processed = 5, total = 0),
                ScanProgress(active = true, processed = 7, total = 40, phase = ScanPhase.HIDDEN_WALK),
                ScanProgress(active = true, processed = 1, total = 8, phase = ScanPhase.COVERS),
            ),
        )
        assertEquals(true, result.active)
        assertEquals("分子只汇总 total>0 的来源（7+1），发现数不冒充处理数", 8, result.processed)
        assertEquals(48, result.total)
        assertEquals(ScanPhase.HIDDEN_WALK, result.phase)
    }

    @Test
    fun `全部来源都无可度量总数时退回全量和（已发现口径）`() {
        val result = aggregateScanProgress(
            listOf(
                ScanProgress(active = true, processed = 100, total = 0, phase = ScanPhase.HIDDEN_WALK),
                ScanProgress(active = true, processed = 23, total = 0, phase = ScanPhase.HIDDEN_WALK),
            ),
        )
        assertEquals(true, result.active)
        assertEquals(123, result.processed)
        assertEquals(0, result.total)
    }

    @Test
    fun `active 来源都没报 phase 时 phase 为 null`() {
        val result = aggregateScanProgress(
            listOf(
                ScanProgress(active = true, total = 10),
                ScanProgress(active = true, total = 5),
            ),
        )
        assertNull(result.phase)
        assertEquals(15, result.total)
    }

    @Test
    fun `阶段文案覆盖全部枚举`() {
        assertEquals("正在准备", scanPhaseLabel(ScanPhase.PREPARING))
        assertEquals("查询媒体库", scanPhaseLabel(ScanPhase.MEDIA_STORE))
        assertEquals("遍历隐藏目录", scanPhaseLabel(ScanPhase.HIDDEN_WALK))
        assertEquals("生成封面", scanPhaseLabel(ScanPhase.COVERS))
        assertEquals("补读时长", scanPhaseLabel(ScanPhase.DURATION))
        assertEquals("写入结果", scanPhaseLabel(ScanPhase.COMMIT))
        assertNull(scanPhaseLabel(null))
    }

    @Test
    fun `无 phase 时主文案保持原样`() {
        assertEquals("正在准备扫描…", scanProgressHeadline(ScanProgress(active = true)))
        assertEquals("遍历隐藏目录", scanProgressHeadline(ScanProgress(active = true, phase = ScanPhase.HIDDEN_WALK)))
    }

    @Test
    fun `明细文案`() {
        // 已知总数：确定进度
        assertEquals(
            "已处理 3 / 10 项",
            scanProgressDetail(ScanProgress(active = true, processed = 3, total = 10, phase = ScanPhase.COVERS)),
        )
        // 遍历隐藏目录且总数未知：已发现条目数
        assertEquals(
            "已发现 128 项",
            scanProgressDetail(ScanProgress(active = true, processed = 128, phase = ScanPhase.HIDDEN_WALK)),
        )
        // 未知总数且非遍历段：无明细
        assertNull(scanProgressDetail(ScanProgress(active = true, phase = ScanPhase.PREPARING)))
        assertNull(scanProgressDetail(ScanProgress(active = true)))
    }

    @Test
    fun `进度比例在 0 到 1 之间并处理越界`() {
        assertNull(scanProgressFraction(ScanProgress(active = true)))
        assertEquals(0.5f, scanProgressFraction(ScanProgress(active = true, processed = 5, total = 10)))
        assertEquals(1f, scanProgressFraction(ScanProgress(active = true, processed = 12, total = 10)))
        assertEquals(0f, scanProgressFraction(ScanProgress(active = true, processed = -3, total = 10)))
    }
}
