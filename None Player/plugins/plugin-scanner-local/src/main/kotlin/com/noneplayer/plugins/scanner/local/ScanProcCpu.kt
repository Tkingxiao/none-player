package com.noneplayer.plugins.scanner.local

import java.io.File

/**
 * 进程级 CPU 时间采样：给 [ScanLaneTuner] 的「跑满设备约 80% 能力」闭环提供观测量。
 *
 * 为什么读 /proc/self/stat 而不是 /proc/stat：我们要的是**本进程**用掉了多少 CPU。系统总体
 * 利用率会被后台别的进程（微信、系统扫描）抢核污染，拿它做闭环会把我们的车道数和别人的
 * 负载绑在一起，越调越偏。utime/stime 只统计本进程的用户态 + 内核态时间，正是「我们吃了
 * 多少核」的口径。
 *
 * 解析失败（Android 上偶发 SELinux 限制、宿主是 Windows/JVM 单测、字段不足）一律返回
 * [UNKNOWN_TICKS] 且**从不抛异常**：调参只是加速手段，观测不到就退化成「只按吞吐调」，
 * 绝不能因为读不到一次 /proc 就把扫描拖垮。
 */
internal object ScanProcCpu {

    /** 观测不到进程 CPU 时间时的哨兵值（负数，任何真实 ticks 都 >= 0）。 */
    const val UNKNOWN_TICKS = -1L

    /** USER_HZ：Android/Linux 固定 100，即 1 tick = 10ms CPU 时间。 */
    const val USER_HZ = 100.0

    private const val STAT_PATH = "/proc/self/stat"

    /** /proc/self/stat 里 comm 字段可能含空格/括号，所以按**最后一个** ')' 切分。 */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 读当前进程累计 CPU 时间（utime + stime，单位 tick）。
     *
     * @return tick 数；任何异常或字段不可解析都返回 [UNKNOWN_TICKS]。
     */
    fun ticks(): Long = try {
        parseTicks(File(STAT_PATH).readText())
    } catch (t: Throwable) {
        // 文件不存在 / 无权限 / 不是 Linux 宿主：静默降级，不打扰扫描主流程。
        UNKNOWN_TICKS
    }

    /**
     * 解析 /proc/self/stat 的内容，抽出 utime + stime。纯函数，便于单测注入合成内容。
     *
     * 字段布局（1 起）：pid (comm) state ppid ... utime(14) stime(15) ...。comm 允许包含空格和
     * 括号（例如 "(Web Content)"），所以只能以**最后一个** ')' 为界，右侧从 state 开始计。
     *
     * @return tick 数；'(' / ')' 缺失、字段不足 13 个或数值非法时返回 [UNKNOWN_TICKS]。
     */
    fun parseTicks(stat: String): Long {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close >= stat.length - 1) return UNKNOWN_TICKS
        val rest = stat.substring(close + 1).trim()
        if (rest.isEmpty()) return UNKNOWN_TICKS
        val fields = rest.split(WHITESPACE)
        // rest[0] 是第 3 个字段 state，于是第 N 个字段在 rest 里的下标是 N - 3。
        if (fields.size < 13) return UNKNOWN_TICKS
        val userTicks = fields[11].toLongOrNull() ?: return UNKNOWN_TICKS
        val sysTicks = fields[12].toLongOrNull() ?: return UNKNOWN_TICKS
        if (userTicks < 0L || sysTicks < 0L) return UNKNOWN_TICKS
        return userTicks + sysTicks
    }

    /**
     * 两次采样之间的进程 CPU 利用率。
     *
     * 口径：**1.0 = 全部 [cores] 个核都跑满**（不是「单核跑满」）。所以 8 核机上 util=0.8 意味着
     * 平均 6.4 个核在忙 —— 正是用户要的「跑满设备约 80% 能力」。
     *
     * @param fromTicks 窗口起点的 [ticks] 值
     * @param toTicks 窗口终点的 [ticks] 值
     * @param wallMs 窗口实际墙钟时长（毫秒）
     * @param cores 逻辑核数（分母，决定 100% 的定义）
     * @return 利用率；采样不可用（任一端为 [UNKNOWN_TICKS]）时返回 -1.0 表示「本窗口无观测」，
     *         调用方应跳过该窗口的利用率统计。理论上限 1.0，但受采样抖动影响偶尔会略超 1。
     */
    fun utilization(fromTicks: Long, toTicks: Long, wallMs: Long, cores: Int): Double {
        if (fromTicks < 0L || toTicks < 0L) return -1.0
        if (wallMs <= 0L || cores <= 0) return 0.0
        val deltaTicks = (toTicks - fromTicks).coerceAtLeast(0L)
        val cpuSeconds = deltaTicks / USER_HZ
        val wallSeconds = wallMs / 1000.0
        return cpuSeconds / (wallSeconds * cores)
    }
}
