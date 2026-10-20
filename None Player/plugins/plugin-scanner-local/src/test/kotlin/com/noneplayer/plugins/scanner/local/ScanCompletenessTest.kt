package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanCoverage] / [ScanCoverageRecorder] 纯 JVM 单测（R0 / REV-01 的判据本体）。
 *
 * 验收语义：一次扫描"完整"的唯一定义是**零警告**；任何子查询失败/降级都必须
 * 让 `isComplete == false`，因为下游只有看到"完整"才允许删除历史/收藏/缩略图/旧快照。
 */
class ScanCompletenessTest {

    @Test
    fun `empty coverage is complete`() {
        val coverage = ScanCoverage()
        assertTrue(coverage.isComplete)
        assertEquals(emptyList<String>(), coverage.warnings)
    }

    @Test
    fun `withWarning accumulates and turns coverage incomplete`() {
        val one = ScanCoverage().withWarning("视频查询失败，本次扫描不完整")
        assertFalse(one.isComplete)
        assertEquals(listOf("视频查询失败，本次扫描不完整"), one.warnings)

        val two = one.withWarning("音频查询失败，本次扫描不完整")
        assertFalse(two.isComplete)
        assertEquals(
            listOf("视频查询失败，本次扫描不完整", "音频查询失败，本次扫描不完整"),
            two.warnings,
        )
        // 不可变：原对象不受影响
        assertFalse(one.isComplete)
        assertEquals(1, one.warnings.size)
    }

    @Test
    fun `blank warning is ignored`() {
        val coverage = ScanCoverage().withWarning("")
        assertTrue(coverage.isComplete)
        assertTrue(ScanCoverage().withWarning("   ").isComplete)
    }

    @Test
    fun `recorder warns and merges recorded coverage`() {
        val recorder = ScanCoverageRecorder()
        recorder.warn("隐藏目录遍历失败，本次扫描不完整")
        recorder.record(
            ScanCoverage(
                listOf(
                    "视频查询失败，本次扫描不完整",
                    "隐藏目录遍历失败，本次扫描不完整",
                ),
            ),
        )
        val coverage = recorder.build()
        assertFalse(coverage.isComplete)
        // 去重后按首次出现顺序保留两条
        assertEquals(
            listOf(
                "隐藏目录遍历失败，本次扫描不完整",
                "视频查询失败，本次扫描不完整",
            ),
            coverage.warnings,
        )
    }

    @Test
    fun `recorder with no warnings builds complete coverage`() {
        assertTrue(ScanCoverageRecorder().build().isComplete)
    }

    @Test
    fun `recorded empty coverage keeps recorder complete`() {
        val recorder = ScanCoverageRecorder()
        recorder.record(ScanCoverage())
        assertTrue(recorder.build().isComplete)
    }

    @Test
    fun `recorder is snapshot on build`() {
        val recorder = ScanCoverageRecorder()
        recorder.warn("第一个故障")
        val first = recorder.build()
        recorder.warn("第二个故障")
        val second = recorder.build()
        assertEquals(listOf("第一个故障"), first.warnings)
        assertEquals(listOf("第一个故障", "第二个故障"), second.warnings)
        // build() 返回的是不可变数据类实例，后续 warn 不会回填到已构建的对象
        assertNotSame(first, second)
        assertEquals(1, first.warnings.size)
    }
}
