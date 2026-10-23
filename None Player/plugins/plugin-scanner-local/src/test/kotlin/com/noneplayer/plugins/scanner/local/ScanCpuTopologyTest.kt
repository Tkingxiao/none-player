package com.noneplayer.plugins.scanner.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * [ScanCpuTopology]：扫描前的设备能力探测。
 *
 * 回归点：
 * 1. 分簇阈值 0.85：1+3+4 三簇机型必须判出 4 个大核（3 颗「中核」不算大核，但 85% 能把
 *    频率接近大核的中核算进来 —— 宁可多算，别把 8 核当 4 核用）；
 * 2. 读不到频率表 / 全 0 / 单核 / 全相同都要有确定结果，绝不抛异常；
 * 3. [ScanCpuTopology.Topology.bigCores] 恒在 1..cores（探测失败也要能扫描）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCpuTopologyTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "cpu-topology-test-" + UUID.randomUUID())
        assertTrue("临时目录创建失败", root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 在假 sysfs 里造一颗核；maxFreq 传 null 表示该核没有 cpufreq 文件（SELinux 常见）。 */
    private fun cpu(index: Int, maxFreq: Long?): File {
        val dir = File(root, "cpu" + index)
        assertTrue(dir.mkdirs())
        if (maxFreq != null) {
            val cpufreq = File(dir, "cpufreq")
            assertTrue(cpufreq.mkdirs())
            File(cpufreq, "cpuinfo_max_freq").writeText(maxFreq.toString())
        }
        return dir
    }

    // ---- 分簇纯函数 ----

    @Test
    fun `空频率表_0个大核`() {
        assertEquals(0, ScanCpuTopology.splitBigCores(emptyList()))
    }

    @Test
    fun `全为0或负数_视为读不到_0个大核`() {
        assertEquals(0, ScanCpuTopology.splitBigCores(listOf(0L, 0L, -1L)))
    }

    @Test
    fun `单核_1个大核`() {
        assertEquals(1, ScanCpuTopology.splitBigCores(listOf(1_800_000L)))
    }

    @Test
    fun `全相同频率_全部算大核`() {
        assertEquals(8, ScanCpuTopology.splitBigCores(List(8) { 2_000_000L }))
    }

    @Test
    fun `一加三加四三簇_判出4个大核`() {
        // 1×1.8GHz + 3×2.4GHz + 4×3.2GHz：0.85×3.2G = 2.72G，2.4G 中核不算大核。
        val freqs = listOf(1_800_000L) + List(3) { 2_400_000L } + List(4) { 3_200_000L }
        assertEquals(4, ScanCpuTopology.splitBigCores(freqs))
    }

    @Test
    fun `阈值边界_恰好85百分比算大核_差1不算`() {
        val peak = 4_000_000L
        val boundary = (peak * ScanCpuTopology.BIG_CORE_RATIO).toLong() // 3_400_000
        assertEquals(2, ScanCpuTopology.splitBigCores(listOf(peak, boundary)))
        assertEquals(1, ScanCpuTopology.splitBigCores(listOf(peak, boundary - 1)))
    }

    @Test
    fun `读不全频率表_只按读到的算`() {
        // 小核 cpufreq 读不到（被 SELinux 挡），只剩两个大核读数：判定成 2，偏保守。
        assertEquals(2, ScanCpuTopology.splitBigCores(listOf(3_200_000L, 3_000_000L)))
    }

    // ---- sysfs 读取 ----

    @Test
    fun `读假sysfs_只挑cpuN目录_读不到的核跳过`() {
        cpu(0, 1_800_000L)
        cpu(1, 2_400_000L)
        cpu(2, null) // 没有 cpufreq：读不到就跳过，不能当 0 参与分簇
        cpu(3, 3_200_000L)
        File(root, "cpufreq").mkdirs() // 名字以 cpu 开头但不是 cpuN，不能被当成核
        File(root, "cpuidle").mkdirs()

        val freqs = ScanCpuTopology.readMaxFreqs(root)
        assertEquals(listOf(1_800_000L, 2_400_000L, 3_200_000L), freqs)
    }

    @Test
    fun `sysfs不存在_返回空表不抛异常`() {
        assertEquals(emptyList<Long>(), ScanCpuTopology.readMaxFreqs(File(root, "nope")))
    }

    @Test
    fun `频率文件内容非法_该核跳过`() {
        val dir = File(root, "cpu0")
        File(dir, "cpufreq").mkdirs()
        File(File(dir, "cpufreq"), "cpuinfo_max_freq").writeText("not-a-number")
        assertEquals(emptyList<Long>(), ScanCpuTopology.readMaxFreqs(root))
    }

    // ---- detect ----

    @Test
    fun `detect_核数至少1_大核数在1到核数之间_型号非空`() {
        val t = ScanCpuTopology.detect()
        assertTrue("cores 必须 >= 1，实际 " + t.cores, t.cores >= 1)
        assertTrue("bigCores 必须在 1..cores，实际 " + t.bigCores + "/" + t.cores, t.bigCores in 1..t.cores)
        assertTrue("socModel 不能为空", t.socModel.isNotBlank())
    }

    @Test
    fun `detect_在真机上读不到sysfs也不影响核心字段`() {
        // Robolectric 宿主没有 /sys/devices/system/cpu：检不到频率就必须退化成 1 个大核。
        val t = ScanCpuTopology.detect()
        assertEquals(ScanCpuTopology.UNKNOWN_SOC, t.socModel)
        assertEquals(1, t.bigCores)
    }
}
