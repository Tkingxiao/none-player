package com.noneplayer.plugins.scanner.local

import android.os.Build
import java.io.File

/**
 * 扫描前的能力探测：核数、大小核拓扑、芯片型号。
 *
 * 为什么需要它：固定车道数在两种机器上都会错 —— 千元四核机上开 12 条扫描车道只会互相抢核、
 * binder 争用加剧，反而比 4 条慢；旗舰 8 核（1+3+4）机上只开 4 条又吃不满。所以先问一句
 * 「这台机器几核、几个大核」，把初始车道数建在真实拓扑上，再交给 [ScanLaneTuner] 在扫描中
 * 闭环微调。
 *
 * 探测原则：**所有读取都可能失败**（部分机型 SELinux 不给读 cpufreq），失败就降级 —
 * 读不到频率表按 [Topology.bigCores] = 1 处理（保守，宁可少开），绝不因为探测失败不扫描。
 */
internal object ScanCpuTopology {

    /**
     * @param cores 逻辑核数（[Runtime.availableProcessors]，至少 1）
     * @param bigCores 大核数：最高频 >= [BIG_CORE_RATIO] × 全局最高频的核数，钳在 1..cores
     * @param socModel 芯片型号（[Build.SOC_MODEL]，API < 31 或取不到时为 [UNKNOWN_SOC]）
     */
    data class Topology(val cores: Int, val bigCores: Int, val socModel: String)

    /** 取不到芯片型号时的占位值（不猜 [Build.HARDWARE]，避免把一个板级代号当 SoC 名）。 */
    const val UNKNOWN_SOC = "unknown"

    /** 大核判定阈值：最高频达到全局最高频的 85% 即视为大核（覆盖 1+3+4 的 3 颗「中核」）。 */
    const val BIG_CORE_RATIO = 0.85

    private const val CPU_ROOT = "/sys/devices/system/cpu"

    /** 探测本机能力。永不抛异常。 */
    fun detect(): Topology {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val bigCores = splitBigCores(readMaxFreqs()).coerceIn(1, cores)
        return Topology(cores = cores, bigCores = bigCores, socModel = socModel())
    }

    /**
     * 按最高频率表判断大核数量（纯函数，便于注入频率表做单测）。
     *
     * 规则：忽略 <= 0 的非法读数；以有效读数的最大值为基准，统计 >= 0.85 × 最大值的核数。
     *
     * 边界：空表 / 全为 0 → 0（表示「读不到」，由 [detect] 兜底成 1）；全相同 → 全部算大核；
     * 1+3+4 三簇（如 1.8/2.4/3.2GHz）→ 4；只读到部分核（例如只能读到大核）→ 读到的都算。
     */
    fun splitBigCores(maxFreqs: List<Long>): Int {
        val valid = maxFreqs.filter { it > 0L }
        if (valid.isEmpty()) return 0
        val peak = valid.max()
        val threshold = peak * BIG_CORE_RATIO
        return valid.count { it >= threshold }
    }

    /**
     * 读各核的 cpuinfo_max_freq（kHz）。读不到该核就跳过，绝不抛异常。
     *
     * @param cpuRoot 用于单测注入的假 sysfs 根；默认 [CPU_ROOT]
     */
    fun readMaxFreqs(cpuRoot: File = File(CPU_ROOT)): List<Long> {
        val cpuDirs = try {
            cpuRoot.listFiles() ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        return cpuDirs
            .filter { it.isDirectory && it.name.toCpuIndex() != null }
            .sortedBy { it.name.toCpuIndex() }
            .mapNotNull { dir -> readFreq(File(dir, "cpufreq/cpuinfo_max_freq")) }
    }

    private fun readFreq(file: File): Long? = try {
        file.readText().trim().toLongOrNull()?.takeIf { it > 0L }
    } catch (t: Throwable) {
        null
    }

    /** "cpu0"/"cpu7" → 0/7；"cpufreq"、"cpuidle" 之类返回 null。 */
    private fun String.toCpuIndex(): Int? = removePrefix("cpu").toIntOrNull()

    /** [Build.SOC_MODEL] 是 API 31 才有的字段，用反射取，取不到返回 [UNKNOWN_SOC]。 */
    private fun socModel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return UNKNOWN_SOC
        val raw = try {
            Build::class.java.getField("SOC_MODEL").get(null) as? String
        } catch (t: Throwable) {
            null
        }
        return raw?.takeIf { it.isNotBlank() } ?: UNKNOWN_SOC
    }
}
