package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanProcCpu]：/proc/self/stat 解析 + 进程 CPU 利用率。
 *
 * 回归点：
 * 1. comm 里带空格/括号（如 `(Web Content)`）不能让字段错位 —— 错位会把别的字段当 utime，
 *    利用率立刻变成荒诞值，闭环会把车道数带偏；
 * 2. 任何畸形输入都返回 [ScanProcCpu.UNKNOWN_TICKS] 且不抛异常（调参器据此跳过本窗口）；
 * 3. 利用率口径 1.0 = **全部核**跑满（8 核机 util=0.8 表示 6.4 核在忙）。
 */
class ScanProcCpuTest {

    /** 造一行 /proc/self/stat：comm 之后依次是 state(3) ... utime(14) stime(15)。 */
    private fun stat(comm: String, utime: Long, stime: Long, extra: Int = 2): String {
        val middle = (4..13).joinToString(" ") { it.toString() } // 字段 4..13 的占位值
        val tail = (16..(15 + extra)).joinToString(" ") { "0" }
        return "1234 (" + comm + ") S " + middle + " " + utime + " " + stime + " " + tail
    }

    @Test
    fun `解析普通stat_utime加stime`() {
        assertEquals(600L, ScanProcCpu.parseTicks(stat("com.noneplayer", utime = 250L, stime = 350L)))
    }

    @Test
    fun `comm含空格与括号_字段不错位`() {
        // 真实 Android 进程名可能形如 (Web Content)、(a b (c))，只能按最后一个 ')' 切分。
        assertEquals(42L, ScanProcCpu.parseTicks(stat("Web Content", utime = 30L, stime = 12L)))
        assertEquals(42L, ScanProcCpu.parseTicks(stat("(a b (c))", utime = 30L, stime = 12L)))
    }

    @Test
    fun `缺右括号_返回未知`() {
        assertEquals(ScanProcCpu.UNKNOWN_TICKS, ScanProcCpu.parseTicks("1234 (no close S 1 2 3"))
    }

    @Test
    fun `字段不足_返回未知`() {
        assertEquals(ScanProcCpu.UNKNOWN_TICKS, ScanProcCpu.parseTicks("1234 (x) S 1 2 3"))
    }

    @Test
    fun `字段非数字或负数_返回未知`() {
        val nonNumeric = "1234 (x) S " + (4..13).joinToString(" ") { "0" } + " abc 12 0 0"
        assertEquals(ScanProcCpu.UNKNOWN_TICKS, ScanProcCpu.parseTicks(nonNumeric))
        assertEquals(
            ScanProcCpu.UNKNOWN_TICKS,
            ScanProcCpu.parseTicks(stat("x", utime = -1L, stime = 12L)),
        )
    }

    @Test
    fun `空串_返回未知`() {
        assertEquals(ScanProcCpu.UNKNOWN_TICKS, ScanProcCpu.parseTicks(""))
        assertEquals(ScanProcCpu.UNKNOWN_TICKS, ScanProcCpu.parseTicks("1234 (x)"))
    }

    @Test
    fun `真实采样_不抛异常_要么未知要么非负`() {
        // 单测宿主可能是 Windows/JVM（没有 /proc），也可能是 Linux：两种都不能炸。
        val t = ScanProcCpu.ticks()
        assertTrue("ticks 只允许 >= 0 或 UNKNOWN_TICKS，实际 " + t, t >= 0L || t == ScanProcCpu.UNKNOWN_TICKS)
    }

    @Test
    fun `利用率_8核跑3个核`() {
        // 1 秒内 3.0 秒 CPU 时间（300 tick）÷ 8 核 = 0.375
        val util = ScanProcCpu.utilization(fromTicks = 1_000L, toTicks = 1_300L, wallMs = 1_000L, cores = 8)
        assertEquals(0.375, util, 1e-9)
    }

    @Test
    fun `利用率_全核跑满等于1`() {
        // 1 秒内 8 个核各跑满 → 800 tick
        val util = ScanProcCpu.utilization(fromTicks = 0L, toTicks = 800L, wallMs = 1_000L, cores = 8)
        assertEquals(1.0, util, 1e-9)
    }

    @Test
    fun `利用率_半个窗口也算对`() {
        // 300ms 窗口、8 核、跑满 2 个核 → 2 * 0.3s = 0.6 CPU 秒 = 60 tick → 0.6 / (0.3 * 8) = 0.25
        val util = ScanProcCpu.utilization(fromTicks = 0L, toTicks = 60L, wallMs = 300L, cores = 8)
        assertEquals(0.25, util, 1e-9)
    }

    @Test
    fun `采样不可用时返回负一_让上层跳过本窗口`() {
        assertEquals(-1.0, ScanProcCpu.utilization(-1L, 500L, 300L, 8), 1e-9)
        assertEquals(-1.0, ScanProcCpu.utilization(500L, -1L, 300L, 8), 1e-9)
    }

    @Test
    fun `窗口时长或核数非法时返回0`() {
        assertEquals(0.0, ScanProcCpu.utilization(0L, 100L, 0L, 8), 1e-9)
        assertEquals(0.0, ScanProcCpu.utilization(0L, 100L, 300L, 0), 1e-9)
    }

    @Test
    fun `计数器不增_利用率为0而不是负数`() {
        assertEquals(0.0, ScanProcCpu.utilization(500L, 500L, 300L, 8), 1e-9)
        // 计数器回绕（理论上不该发生）：钳成 0，别让负数污染均值
        assertEquals(0.0, ScanProcCpu.utilization(500L, 400L, 300L, 8), 1e-9)
    }
}
