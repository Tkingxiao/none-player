package com.noneplayer.plugins.scanner.local

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 车道种类：一条扫描流水线里的四段并发，各自独立调参，互不牵连。 */
internal enum class LaneKind {
    /** 目录遍历（binder IPC 密集）：加车道的收益最直接，也最容易撞上 Provider 的串行化上限。 */
    WALK,
    /** 嗅探（IsMediaFile 判定、容器头部）：CPU 轻、调用密。 */
    SNIFF,
    /** 封面解码：CPU 重、分配大，过量车道会触发 GC 抖动。 */
    COVER,
    /** 时长探测：MediaMetadataRetriever 重、有系统级锁，通常最早就到 knee。 */
    DURATION,
}

/**
 * 闭环车道调解器：扫描中按窗口观测「吞吐 + 进程 CPU 利用率」，动态增减并发许可。
 *
 * ## 为什么需要闭环
 *
 * 固定车道数不可能对所有设备都对：binder 侧 Provider 的串行化程度、CPU 大小核配比、
 * 存储随机读带宽三者组合起来，只有跑一遍才知道 knee 在哪。所以这里不猜，只做两件事：
 * 1. **加**：只要上个窗口的吞吐比历史最佳再涨 5% 以上，就说明还没到 knee，继续加一档；
 * 2. **停并回落**：一旦加档换来的增益 < 5%，说明已经压到 knee 或撞上 Provider 串行化，
 *    回落到历史最佳车道数并**锁死**（不再上调），避免在 knee 附近来回震荡把扫描拖慢。
 *
 * 锁死之后只保留一个动作：连续 2 个窗口吞吐掉到最佳值的 80% 以下（例如系统开始后台杀进程、
 * 存储降速），才降一档 —— 单窗口抖动不降，防止误判。
 *
 * ## 「跑满设备约 80% 能力」的口径（[Summary.reachedTarget]）
 *
 * 两种达标任取其一：
 * - **CPU 利用率口径**：进程利用率（[ScanProcCpu.utilization]，1.0 = 全部核跑满）
 *   窗口均值 >= [TARGET_UTIL] = 0.8；
 * - **knee 口径**：已经锁死（再上调无增益），且锁定后吞吐仍保持最佳吞吐的 95% 以上。
 *   这一条专门给 IPC 型负载兜底 —— WALK 车道大部分时间在等 binder 返回，CPU 利用率天然
 *   上不去（真实机型常见 0.1~0.3），此时「把设备榨到 80%」的正确含义是「把并发榨到 knee 的
 *   80% 以上」，而不是把 8 个核的 CPU 跑到 80%。
 *
 * ## 确定性
 *
 * 时间源 [nowMs]、CPU 采样 [cpuTicks]、日志 [log] 全部可注入，[tick] 内部无 sleep、无随机数，
 * 因此单测注入假时钟即可复现任意序列（见 ScanLaneTunerTest）。
 *
 * ## 与调用方的约定
 *
 * - 每条车道干完一件活调用一次 [onItemDone]（无锁原子自增，可并发高频调用）；
 * - 监视协程每 [windowMs] 调一次 [tick]（不足一个窗口的 tick 会被忽略，不会污染吞吐统计）；
 * - 许可增减通过 [onPermitsChanged] 下发**增量**：正数表示 `ScanLimiter.release()` 调 n 次，
 *   负数表示调用方循环 `acquire()` 收回许可；构造时**不会**触发回调（初始许可由调用方自己建）。
 *   注意必须是 `ScanLimiter`（令牌桶，可凭空加许可），`kotlinx.coroutines.sync.Semaphore`
 *   会因 `release()` 超过已 acquire 数量抛 `IllegalStateException`，不能用于动态加许可。
 *
 * @param kind 车道种类（只用于日志/trace，不参与策略）
 * @param initialPermits 初始许可数，会被钳进 1..cap
 * @param cap 该车道的许可上限（调用方按设备能力给，例如 2 × 大核数）
 * @param cores 逻辑核数，仅用于利用率归一（1.0 = 全部核跑满）
 * @param windowMs 观测窗口长度
 * @param nowMs 单调时钟毫秒（默认 [SystemClock.elapsedRealtime]，不受系统时间调整影响）
 * @param cpuTicks 进程 CPU tick 采样（默认 [ScanProcCpu.ticks]，返回负值表示观测不到）
 * @param onPermitsChanged 许可增量回调（正 = 放行更多，负 = 收回）
 * @param log 观测日志回调（默认走 Log.i，TAG=ScanLaneTuner）
 */
internal class ScanLaneTuner(
    val kind: LaneKind,
    initialPermits: Int,
    val cap: Int,
    private val cores: Int,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val cpuTicks: () -> Long = ScanProcCpu::ticks,
    private val onPermitsChanged: (Int) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val safeCap: Int = cap.coerceAtLeast(1)

    /** 当前许可数（调用方 [onPermitsChanged] 已同步过的值）。 */
    var permits: Int = initialPermits.coerceIn(1, safeCap)
        private set

    private var lastAtMs: Long = nowMs()
    private var lastTicks: Long = cpuTicks()

    /** 本窗口完成数：无锁原子自增，车道线程高频写、[tick] 单线程读并清零。 */
    private val doneInWindow = AtomicLong(0L)

    private var bestPermits: Int = permits
    private var bestThr: Double = 0.0
    private var lastThr: Double = 0.0
    private var locked: Boolean = false
    private var degradeStreak: Int = 0
    private var utilSum: Double = 0.0
    private var utilSamples: Int = 0
    private var evaluatedWindows: Int = 0

    /** 车道干完一件活就调一次；无锁、无分配、不抛异常。 */
    fun onItemDone() {
        doneInWindow.incrementAndGet()
    }

    /**
     * 每个 [windowMs] 调一次：算吞吐与利用率，按策略调整许可。
     *
     * 不足一个窗口的调用直接忽略（把计数留给下一个完整窗口，避免窗口被切碎导致吞吐虚高）；
     * 窗口内完成数为 0 也不调整（排队中、刚开始、或已收尾，样本无意义）。
     */
    fun tick() {
        val now = nowMs()
        val elapsedMs = now - lastAtMs
        if (elapsedMs < windowMs || elapsedMs <= 0L) return

        val done = doneInWindow.getAndSet(0L)
        val ticksNow = cpuTicks()
        val util = ScanProcCpu.utilization(lastTicks, ticksNow, elapsedMs, cores)
        lastAtMs = now
        lastTicks = ticksNow

        if (done <= 0L) {
            // 零完成：不调许可，也不计入利用率均值（空闲窗口会把均值拖低，误判成没跑满）。
            return
        }

        val thr = done * 1000.0 / elapsedMs
        lastThr = thr
        evaluatedWindows++
        if (util >= 0.0) {
            utilSum += util
            utilSamples++
        }
        if (locked) degradeOrHold(thr) else growOrLock(thr)
    }

    /** 未锁死：增益够就继续加，增益不足就回落到最佳值并锁死。 */
    private fun growOrLock(thr: Double) {
        val improved = bestThr <= 0.0 || thr >= bestThr * GAIN_RATIO
        if (!improved) {
            // 加了车道却没换来 5% 的吞吐：已经压到 knee（或撞上 Provider 串行化），回落并锁死。
            val fallback = bestPermits.coerceIn(1, safeCap)
            if (fallback != permits) applyPermits(fallback)
            locked = true
            log(
                "lane tune kind=" + kind + " lock permits=" + permits +
                    " bestThr=" + fmtThr(bestThr) + " thr=" + fmtThr(thr),
            )
            return
        }
        bestThr = thr
        bestPermits = permits
        if (permits >= safeCap) return // 已在上限：只更新峰值，等下一个窗口判 knee
        // 步进 +25%（至少 +1）：小基数十 +1 已经够激进，大基数下按比例放大才不会收敛太慢。
        val step = maxOf(1, permits / 4)
        applyPermits((permits + step).coerceAtMost(safeCap))
        log(
            "lane tune kind=" + kind + " grow permits=" + permits +
                " thr=" + fmtThr(thr),
        )
    }

    /** 已锁死：只允许「连续 2 个窗口显著劣化」再降一档。 */
    private fun degradeOrHold(thr: Double) {
        if (bestThr > 0.0 && thr < bestThr * DEGRADE_RATIO) {
            degradeStreak++
        } else {
            degradeStreak = 0
        }
        if (degradeStreak < DEGRADE_WINDOWS) return
        degradeStreak = 0
        val next = (permits - maxOf(1, permits / 4)).coerceAtLeast(1)
        if (next >= permits) return
        applyPermits(next)
        log("lane tune kind=" + kind + " degrade permits=" + permits + " thr=" + fmtThr(thr))
    }

    private fun applyPermits(next: Int) {
        val target = next.coerceIn(1, safeCap)
        if (target == permits) return
        val delta = target - permits
        permits = target
        onPermitsChanged(delta)
    }

    /**
     * 收尾快照：交给 trace/日志与 [ScanLanePresets] 落盘用。
     *
     * @param permits 结束时的许可数
     * @param bestPermits 历史最佳许可数（推荐下次起跑值）
     * @param bestThr 历史最佳吞吐（件/秒）
     * @param utilAvg 有观测窗口的进程 CPU 利用率均值（1.0 = 全核跑满；无观测为 0.0）
     * @param reachedTarget 是否达到「约 80% 能力」（口径见类 KDoc）
     * @param ceilingReason 峰值是被什么卡住的：[CEILING_CAP] / [CEILING_KNEE] /
     *   [CEILING_GROWING] / [CEILING_IDLE]
     */
    data class Summary(
        val permits: Int,
        val bestPermits: Int,
        val bestThr: Double,
        val utilAvg: Double,
        val reachedTarget: Boolean,
        val ceilingReason: String,
    )

    /** 生成收尾快照（可在扫描结束时调用一次，不改变内部状态）。 */
    fun finish(): Summary {
        val utilAvg = if (utilSamples > 0) utilSum / utilSamples else 0.0
        val kneeHolds = locked && bestThr > 0.0 && lastThr >= bestThr * KNEE_KEEP_RATIO
        val reason = when {
            evaluatedWindows == 0 -> CEILING_IDLE
            bestPermits >= safeCap -> CEILING_CAP
            locked -> CEILING_KNEE
            else -> CEILING_GROWING
        }
        return Summary(
            permits = permits,
            bestPermits = bestPermits,
            bestThr = bestThr,
            utilAvg = utilAvg,
            reachedTarget = utilAvg >= TARGET_UTIL || kneeHolds,
            ceilingReason = reason,
        )
    }

    /**
     * 一行 trace，形如
     * `lane tune kind=WALK permits=12 best=12 thr=263.0/s util=0.31 target=knee reached=true`。
     */
    fun summaryLine(): String {
        val s = finish()
        return "lane tune kind=" + kind +
            " permits=" + s.permits +
            " best=" + s.bestPermits +
            " thr=" + fmtThr(s.bestThr) + "/s" +
            " util=" + String.format(Locale.US, "%.2f", s.utilAvg) +
            " target=" + s.ceilingReason +
            " reached=" + s.reachedTarget
    }

    private fun fmtThr(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val TAG = "ScanLaneTuner"

        /** 观测窗口长度：300ms 足够容纳几十件活，又不至于让调参反应太慢。 */
        const val DEFAULT_WINDOW_MS = 300L

        /** 增益阈值：吞吐至少涨 5% 才算「加档有效」。 */
        const val GAIN_RATIO = 1.05

        /** 劣化阈值：吞吐掉到最佳值的 80% 以下算「显著劣化」。 */
        const val DEGRADE_RATIO = 0.8

        /** 连续几个劣化窗口才降一档（单窗口抖动不降）。 */
        const val DEGRADE_WINDOWS = 2

        /** 「约 80% 能力」的 CPU 利用率口径（1.0 = 全核跑满）。 */
        const val TARGET_UTIL = 0.8

        /** knee 口径：锁死后吞吐仍保持最佳值的 95% 以上，视为站在 knee 上。 */
        const val KNEE_KEEP_RATIO = 0.95

        /** 峰值卡在许可上限。 */
        const val CEILING_CAP = "cap"

        /** 峰值卡在 knee（加档不再有收益）。 */
        const val CEILING_KNEE = "knee"

        /** 还在加档过程中，扫描就结束了。 */
        const val CEILING_GROWING = "growing"

        /** 全程没有有效窗口（没跑起来 / 立刻结束）。 */
        const val CEILING_IDLE = "idle"
    }
}

/**
 * 调好的车道数落盘，下次扫描直接按它起跑。
 *
 * 为什么值得单独落盘：用户抱怨的「已存在文件夹二次扫描慢」，除了目录级增量索引（[ScanWalkIndex]）
 * 之外，还有一半是**起跑太慢** —— 每台设备、每种负载的第一轮调参都要花掉几个窗口（≈1~2s）在
 * knee 附近试探。把上一轮 [ScanLaneTuner.Summary.bestPermits] 存下来当初始许可，第二轮起跑就
 * 直接站在 knee 上，连试探都省了。
 *
 * 容错原则：**宁可回到默认值，也不信任可疑文件**。版本不符、JSON 损坏、kind 不认识、许可越界
 * 一律丢弃 —— 车道数只是性能参数，错一个不会丢数据，但错成 0 或 10000 会卡死扫描。
 */
internal object ScanLanePresets {

    /** 落盘格式版本；结构变更时 +1，旧版本一律当损坏丢弃。 */
    const val VERSION = 1

    /** 落盘文件名（放在 filesDir 下，与 scan_walk_index.json 同级）。 */
    const val FILE_NAME = "scan_lanes.json"

    /** 许可下界：0 条车道等于不扫描，绝不允许。 */
    const val MIN_PERMITS = 1

    /** 许可上界：64 条远超任何家用设备的合理并发，超过即视为文件损坏。 */
    const val MAX_PERMITS = 64

    private const val TAG = "ScanLaneTuner"
    private const val TMP_SUFFIX = ".tmp"
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    /**
     * 读上次落盘的车道数。
     *
     * @param dir filesDir（测试可注入临时目录）
     * @return 只包含**合法**条目的映射；文件缺失/损坏/版本不符时返回空表（调用方回默认值）。
     */
    fun load(dir: File): Map<LaneKind, Int> {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return emptyMap()
        val text = try {
            readPayload(file)
        } catch (t: Throwable) {
            Log.w(TAG, "车道预设读取失败，回默认值", t)
            return emptyMap()
        }
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", -1) != VERSION) return emptyMap()
            val lanes = root.optJSONObject("lanes") ?: return emptyMap()
            val out = LinkedHashMap<LaneKind, Int>()
            for (kind in LaneKind.values()) {
                if (!lanes.has(kind.name)) continue
                val raw = lanes.optInt(kind.name, -1)
                if (raw !in MIN_PERMITS..MAX_PERMITS) continue
                out[kind] = raw
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "车道预设解析失败，回默认值", t)
            emptyMap()
        }
    }

    /**
     * 原子落盘：先写 .tmp 再 rename，失败不破坏旧文件。
     *
     * @return true = 已落盘；false = 目录不可写/序列化失败之类（调用方忽略即可，不影响扫描）。
     */
    fun save(dir: File, map: Map<LaneKind, Int>): Boolean {
        val lanes = JSONObject()
        for ((kind, permits) in map) {
            if (permits !in MIN_PERMITS..MAX_PERMITS) continue
            lanes.put(kind.name, permits)
        }
        if (lanes.length() == 0) return false
        val json = JSONObject()
            .put("version", VERSION)
            .put("lanes", lanes)
            .toString()
        if (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return false
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, FILE_NAME + TMP_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeText(json, Charsets.UTF_8)
            replaceAtomically(tmp, target)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "车道预设落盘失败", t)
            tmp.delete()
            false
        }
    }

    /** 按字节上限读取，读不到的按损坏处理（与 ScanSnapshotStore 同一姿势）。 */
    private fun readPayload(file: File): String {
        // E205⑩：先按 length 预检再读入（TOCTOU 由第二次检查兜底）——
        // 被外部写坏成超大文件时不再先做一次全量分配
        val len = file.length()
        require(len in 1..MAX_PAYLOAD_BYTES) { "车道预设文件过大：$len" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "车道预设文件过大：" + bytes.size }
        return String(bytes, Charsets.UTF_8)
    }

    private fun replaceAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Log.w(TAG, "文件系统不支持原子替换，降级为普通替换", e)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Log.w(TAG, "原子替换失败，降级为普通替换", e)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
