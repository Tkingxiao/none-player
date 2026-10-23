package com.noneplayer.plugins.scanner.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * [ScanLaneTuner] + [ScanLanePresets]：扫描中闭环调车道数、调好的值落盘。
 *
 * 时钟、CPU 采样、日志全部注入，所以每个窗口的吞吐/利用率都是精确值，断言不用留容差：
 * - 窗口 300ms、完成 done 件 → 吞吐 = done × 1000 / 300 件/秒；
 * - 利用率 = Δtick / 100 / (0.3 × cores)（Δtick=204、8 核 → 0.85）。
 *
 * 覆盖的策略分支：加档 → 撞 knee 回落锁死 → 锁死后不回弹 → 连续 2 窗口劣化才降档；
 * 以及落盘的容错（缺文件/坏 JSON/版本不符/越界值/未知 kind 一律丢弃，绝不把扫描调死）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanLaneTunerTest {

    private var now = 0L
    private var ticks = 0L
    private val deltas = ArrayList<Int>()
    private val logs = ArrayList<String>()
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "lane-tuner-test-" + UUID.randomUUID())
        assertTrue("临时目录创建失败", root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun tuner(
        initial: Int = 4,
        cap: Int = 12,
        cores: Int = 8,
        kind: LaneKind = LaneKind.WALK,
    ) = ScanLaneTuner(
        kind = kind,
        initialPermits = initial,
        cap = cap,
        cores = cores,
        nowMs = { now },
        cpuTicks = { ticks },
        onPermitsChanged = { deltas.add(it) },
        log = { logs.add(it) },
    )

    /** 推进一个观测窗口：时钟 +[advanceMs]、CPU tick +[tickDelta]、完成 [done] 件后 tick 一次。 */
    private fun ScanLaneTuner.window(done: Int, advanceMs: Long = 300L, tickDelta: Long = 0L) {
        now += advanceMs
        ticks += tickDelta
        repeat(done) { onItemDone() }
        tick()
    }

    // ---------------- 加档 / 锁死 ----------------

    @Test
    fun `构造_许可等于初始值且不触发回调`() {
        val t = tuner(initial = 4, cap = 12)
        assertEquals(4, t.permits)
        assertEquals(emptyList<Int>(), deltas)
        assertEquals(LaneKind.WALK, t.kind)
        assertEquals(12, t.cap)
    }

    @Test
    fun `初始值越界_钳进1到cap`() {
        assertEquals(1, tuner(initial = 0, cap = 12).permits)
        assertEquals(1, tuner(initial = -5, cap = 12).permits)
        assertEquals(12, tuner(initial = 999, cap = 12).permits)
        assertEquals(1, tuner(initial = 0, cap = 0).permits) // cap=0 也要留 1 条车道
    }

    @Test
    fun `首窗口增益充足_加一档并记下最佳`() {
        val t = tuner()
        t.window(done = 300) // 300 件 / 300ms = 1000 件/s
        assertEquals(5, t.permits)
        assertEquals(listOf(1), deltas)
        val s = t.finish()
        assertEquals(4, s.bestPermits)
        assertEquals(1000.0, s.bestThr, 1e-6)
        assertFalse(s.reachedTarget)
        assertEquals(ScanLaneTuner.CEILING_GROWING, s.ceilingReason)
    }

    @Test
    fun `连续两个窗口都在涨_连续加档`() {
        val t = tuner()
        t.window(done = 300) // thr=1000 → 4→5
        t.window(done = 350) // thr=1166.67 ≥ 1000×1.05=1050 → 5→6
        assertEquals(6, t.permits)
        assertEquals(listOf(1, 1), deltas)
    }

    @Test
    fun `增益不足_回落到最佳并锁死`() {
        val t = tuner()
        t.window(done = 300) // thr=1000 → permits 5
        t.window(done = 350) // thr=1166.67 → permits 6
        t.window(done = 360) // thr=1200 < 1166.67×1.05=1225 → 回落 5 并锁死
        assertEquals(5, t.permits)
        assertEquals(listOf(1, 1, -1), deltas)
        assertEquals(ScanLaneTuner.CEILING_KNEE, t.finish().ceilingReason)
        assertTrue("锁死必须留日志，真机 logcat 靠它取证：" + logs, logs.any { it.contains("lock") })
    }

    @Test
    fun `锁死后吞吐再高也不回弹`() {
        val t = tuner()
        t.window(done = 300)
        t.window(done = 350)
        t.window(done = 360) // 锁死，permits=5
        val before = deltas.size
        t.window(done = 1200) // thr=4000，远高于 bestThr
        assertEquals(5, t.permits)
        assertEquals("锁死后不许再动许可", before, deltas.size)
    }

    @Test
    fun `零完成窗口不调整也不拉低利用率均值`() {
        val t = tuner()
        t.window(done = 300, tickDelta = 204) // util=0.85 计入均值
        t.window(done = 0, tickDelta = 0) // 空闲窗口：不调许可、不计均值
        assertEquals(0.85, t.finish().utilAvg, 1e-9)
    }

    @Test
    fun `已经到上限_只记峰值不回调_下一窗口判cap`() {
        val t = tuner(initial = 4, cap = 4)
        t.window(done = 300) // 已达 cap：只更新 bestThr/bestPermits
        assertEquals(4, t.permits)
        assertEquals(emptyList<Int>(), deltas)
        t.window(done = 300) // thr=1000 < 1050 → 锁死
        assertEquals(4, t.permits)
        val s = t.finish()
        assertEquals(ScanLaneTuner.CEILING_CAP, s.ceilingReason)
        assertTrue("锁死后吞吐仍贴 knee，应判达标", s.reachedTarget)
    }

    // ---------------- 锁死后的劣化降档 ----------------

    @Test
    fun `锁死后单窗口劣化不降_连续两个窗口才降一档`() {
        val t = tuner()
        t.window(done = 300)
        t.window(done = 350)
        t.window(done = 360) // 锁死 permits=5，bestThr=1166.67
        t.window(done = 100) // thr=333 < 933：第 1 个劣化窗口，只记 streak
        assertEquals(5, t.permits)
        t.window(done = 100) // 第 2 个劣化窗口：降一档
        assertEquals(4, t.permits)
        assertTrue("降档必须留日志：" + logs, logs.any { it.contains("degrade") })
    }

    // ---------------- 利用率口径 ----------------

    @Test
    fun `利用率达到80百分比_判达标`() {
        val t = tuner(initial = 12, cap = 12, cores = 8)
        // 300ms 窗口、8 核，累计 204 tick = 2.04 CPU 秒 → 2.04 / 2.4 = 0.85
        t.window(done = 300, tickDelta = 204)
        val s = t.finish()
        assertEquals(0.85, s.utilAvg, 1e-9)
        assertTrue("util 0.85 ≥ TARGET_UTIL 0.8 应判达标", s.reachedTarget)
    }

    @Test
    fun `采样不到CPU时利用率记0_不崩不误判`() {
        ticks = -1L // 让 cpuTicks 恒返回「观测不到」
        val t = tuner()
        t.window(done = 300) // 采样不可用：本窗口不计入 util 样本
        val s = t.finish()
        assertEquals(0.0, s.utilAvg, 1e-9)
        assertFalse(s.reachedTarget)
        assertEquals(5, t.permits) // 吞吐策略照常生效（不受 util 影响）
    }

    // ---------------- 窗口节流 / 收尾快照 ----------------

    @Test
    fun `不足一个窗口的tick被忽略_计数留到下一窗口`() {
        val t = tuner()
        now += 100
        repeat(50) { t.onItemDone() }
        t.tick() // 只过了 100ms：忽略
        assertEquals(4, t.permits)
        assertEquals(emptyList<Int>(), deltas)
        now += 200 // 凑满 300ms：累计 50 件 → thr=166.67
        t.tick()
        assertEquals(5, t.permits)
        assertEquals(listOf(1), deltas)
    }

    @Test
    fun `从未跑过窗口_收尾快照为idle`() {
        val s = tuner().finish()
        assertEquals(ScanLaneTuner.CEILING_IDLE, s.ceilingReason)
        assertEquals(0.0, s.bestThr, 1e-9)
        assertEquals(0.0, s.utilAvg, 1e-9)
        assertFalse(s.reachedTarget)
    }

    @Test
    fun `summaryLine格式与真机logcat口径一致`() {
        val t = tuner()
        t.window(done = 300) // permits=5、best=4、thr=1000
        assertEquals(
            "lane tune kind=WALK permits=5 best=4 thr=1000.0/s util=0.00 target=growing reached=false",
            t.summaryLine(),
        )
    }

    // ---------------- ScanLanePresets ----------------

    @Test
    fun `预设落盘再读回_原样相等且无tmp残留`() {
        assertTrue(ScanLanePresets.save(root, mapOf(LaneKind.WALK to 8, LaneKind.COVER to 3)))
        assertEquals(
            mapOf(LaneKind.WALK to 8, LaneKind.COVER to 3),
            ScanLanePresets.load(root),
        )
        assertTrue("落盘文件应存在", File(root, ScanLanePresets.FILE_NAME).isFile)
        assertFalse("原子替换后不许留 .tmp", File(root, ScanLanePresets.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `预设文件缺失_返回空表`() {
        assertEquals(emptyMap<LaneKind, Int>(), ScanLanePresets.load(root))
    }

    @Test
    fun `预设文件损坏_返回空表不抛异常`() {
        File(root, ScanLanePresets.FILE_NAME).writeText("not json")
        assertEquals(emptyMap<LaneKind, Int>(), ScanLanePresets.load(root))
        File(root, ScanLanePresets.FILE_NAME).writeText("")
        assertEquals(emptyMap<LaneKind, Int>(), ScanLanePresets.load(root))
    }

    @Test
    fun `预设版本不符_整份丢弃`() {
        File(root, ScanLanePresets.FILE_NAME)
            .writeText("{\"version\":2,\"lanes\":{\"WALK\":8}}")
        assertEquals(emptyMap<LaneKind, Int>(), ScanLanePresets.load(root))
    }

    @Test
    fun `预设许可越界_只丢越界项`() {
        File(root, ScanLanePresets.FILE_NAME)
            .writeText("{\"version\":1,\"lanes\":{\"WALK\":0,\"COVER\":3,\"DURATION\":999}}")
        assertEquals(mapOf(LaneKind.COVER to 3), ScanLanePresets.load(root))
    }

    @Test
    fun `预设未知车道名_忽略而不整份丢弃`() {
        File(root, ScanLanePresets.FILE_NAME)
            .writeText("{\"version\":1,\"lanes\":{\"FLY\":5,\"WALK\":2}}")
        assertEquals(mapOf(LaneKind.WALK to 2), ScanLanePresets.load(root))
    }

    @Test
    fun `预设全部无效_不落盘`() {
        assertFalse(ScanLanePresets.save(root, emptyMap()))
        assertFalse(ScanLanePresets.save(root, mapOf(LaneKind.WALK to 0, LaneKind.COVER to 999)))
        assertFalse("没有合法项就不该写文件", File(root, ScanLanePresets.FILE_NAME).exists())
    }
}
