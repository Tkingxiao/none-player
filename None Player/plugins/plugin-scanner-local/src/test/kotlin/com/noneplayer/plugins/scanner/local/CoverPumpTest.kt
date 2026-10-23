package com.noneplayer.plugins.scanner.local

import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * E203-B 封面后台泵的行为测试。
 *
 * 断言口径全部是「**换个实现就会挂**」的可观察行为，而不是「调一下不抛」：
 * - 许可：扫描中必须是 `max(2, workers/2)`，扫描收尾后必须放开到 `workers`；
 * - 结果回流：publish 之后 [CoverPump.applyCovers] 必须真的收到 id→封面，且是**批量**（不是逐项）；
 * - 取消：队列必须清空、在飞的那一项**不写否定结论**（被取消 ≠ 抽不出来）；
 * - 落盘：排空后落一次快照；扫描中一律不落（让位给提交段）。
 *
 * 用真实线程（[Dispatchers.IO]/[Dispatchers.Default]）而不是 runTest 的虚拟时间：泵的价值就在于
 * 「不阻塞等待」，虚拟时间会把竞态掩盖掉。时限统一走 [awaitTrue]（5s 上限），不用裸 delay 赌。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverPumpTest {

    @Test
    fun `扫描中许可只有一半_收尾后放开到满速`() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fx = Fixture(
            workers = 8,
            scanning = true,
            produce = {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
                "/cache/${it.id}.jpg"
            },
        )
        fx.items = listOf(videoItem("ms-1"))
        fx.pump.publish(fx.items)

        assertTrue("泵必须在 publish 后立刻开工", entered.await(5, TimeUnit.SECONDS))
        // (workers=8) → 扫描期上限 = max(2, 8/2) = 4；E205⑧ 起点给上限一半 = 2（给 tuner
        // 留真实爬坡空间，旧写法起点==上限、调参空转）。currentPermits() 报的是**可用**
        // 令牌，此刻 1 项正持有许可（阻塞在 produce 里），所以可用 1 而不是 2。
        // tuner 首个 tick 在 ~7×40ms 后，本断言远早于它，不存在爬坡竞态。
        assertEquals("扫描中起点必须是扫描期上限的一半", 1, fx.pump.currentPermits())

        // 扫描收尾：监视协程在下一个窗口把它抬到满速
        fx.scanning.set(false)
        assertTrue(
            "scanFinished 后许可必须放开到 workers=8（实际 ${fx.pump.currentPermits()}）",
            awaitTrue(3_000L) { fx.pump.currentPermits() == 8 },
        )

        gate.countDown()
        fx.pump.awaitIdle(5_000L)
        assertEquals(1, fx.pump.doneCount())
        assertTrue("封面必须回流到 applyCovers", fx.coversOf("ms-1") != null)
    }

    @Test
    fun `request把条目插到队首_重复id只排一次`() = runBlocking {
        val gate = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        // workers=1 → 串行，处理顺序可断言
        val fx = Fixture(
            workers = 1,
            scanning = false,
            produce = { item ->
                if (item.id == "ms-a") {
                    firstEntered.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                }
                "/cache/${item.id}.jpg"
            },
        )
        val a = videoItem("ms-a")
        val b = videoItem("ms-b")
        val z = videoItem("ms-z")
        fx.items = listOf(a, b, z)
        fx.pump.publish(listOf(a, b))
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        // a 还在抽：此时 UI 要 z（且重复要了两次）→ z 必须插到 b 前面，且只排一次
        fx.pump.request(listOf("ms-z"))
        fx.pump.request(listOf("ms-z"))
        // 队列里此刻是 [z, b]：z 只排了一次（b 还没被摘出队列——泵先拿许可再出队）
        assertEquals("重复 request 不该产生多余队列项", 2, fx.pump.pendingCount())

        gate.countDown()
        fx.pump.awaitIdle(5_000L)
        assertEquals(
            "request 必须插到队首（a 之后、b 之前），且幂等",
            listOf("ms-a", "ms-z", "ms-b"),
            fx.produced,
        )
        assertEquals("三个 id 各回流一次", 3, fx.pump.appliedCount())
    }

    @Test
    fun `request重排已在队列里的条目到队首（E205④）`() = runBlocking {
        val gate = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val fx = Fixture(
            workers = 1,
            scanning = false,
            produce = { item ->
                if (item.id == "ms-a") {
                    firstEntered.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                }
                "/cache/${item.id}.jpg"
            },
        )
        val a = videoItem("ms-a")
        val b = videoItem("ms-b")
        val c = videoItem("ms-c")
        fx.items = listOf(a, b, c)
        fx.pump.publish(listOf(a, b, c))
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        // c 已被 publish 按列表序登记（排在 b 后面）：request(c) 必须把它摘出重排队首——
        // 旧实现只对「本轮从未登记」的 id 生效，扫描发布过的条目插队全部空操作
        fx.pump.request(listOf("ms-c"))
        assertEquals("重排不改变队列长度", 2, fx.pump.pendingCount())

        gate.countDown()
        fx.pump.awaitIdle(5_000L)
        assertEquals(
            "已入队条目也要重排到队首：a 之后先 c 再 b",
            listOf("ms-a", "ms-c", "ms-b"),
            fx.produced,
        )
    }

    @Test
    fun `cancel清空队列_且在飞的那项不写否定结论`() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fx = Fixture(
            workers = 1,
            scanning = true,
            // 一律抽不出来：如果取消后仍然落结论，failedCount 与否定缓存都会变脏
            produce = {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
                null
            },
        )
        fx.items = listOf(videoItem("ms-1"), videoItem("ms-2"), videoItem("ms-3"))
        fx.pump.publish(fx.items)
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        fx.pump.cancel()
        assertEquals("取消必须清空队列", 0, fx.pump.pendingCount())
        assertFalse("取消必须停泵", fx.pump.isRunning())

        gate.countDown()
        fx.pump.awaitIdle(5_000L)
        // 取消前已经跑了的那一项也不能回写：这不是「抽不出封面」，是「没抽完」
        assertEquals("取消后不得统计完成数", 0, fx.pump.doneCount())
        assertEquals("取消后不得统计失败数", 0, fx.pump.failedCount())
        assertTrue("取消不得写否定缓存", fx.negative.failed.isEmpty())
        assertEquals("取消不得回写封面", 0, fx.pump.appliedCount())
    }

    @Test
    fun `多条目按节流窗口批量回写_不是逐项emit`() = runBlocking {
        val fx = Fixture(
            workers = 2,
            scanning = false,
            // 窗口 300ms：10 项几毫秒就跑完，正常情况下会被攒成 1~2 批
            emitIntervalMs = 300L,
            idleMs = 150L,
        )
        val items = (1..10).map { videoItem("ms-$it") }
        fx.items = items
        fx.pump.publish(items)
        fx.pump.awaitIdle(8_000L)

        assertEquals("10 项都要抽到", 10, fx.pump.doneCount())
        assertTrue("回写的批次数必须少于条目数（证明确实合批）", fx.batches.size < items.size)
        assertEquals("回写总量等于抽到的数量", 10, fx.batches.sumOf { it.size })
        assertEquals(10, fx.pump.appliedCount())
        assertEquals(
            "回写内容必须是 id → 封面路径",
            (1..10).map { "/cache/ms-$it.jpg" }.toSet(),
            fx.batches.flatMap { it.values }.toSet(),
        )
    }

    @Test
    fun `排空后落一次快照_内容带回调回来的封面`() = runBlocking {
        val fx = Fixture(workers = 2, scanning = false, emitIntervalMs = 40L, idleMs = 150L)
        fx.items = listOf(videoItem("ms-1"), videoItem("ms-2"))
        fx.pump.publish(fx.items)
        fx.pump.awaitIdle(8_000L)

        assertEquals("排空后必须落一次快照", 1, fx.snapshots.size)
        val saved = fx.snapshots.first()
        assertEquals(2, saved.size)
        assertTrue(
            "快照里的条目必须带上泵回写的 coverUri（否则冷启动还是要重抽）",
            saved.all { it.coverUri == "/cache/${it.id}.jpg" },
        )
    }

    @Test
    fun `扫描中不落快照_让位给提交段`() = runBlocking {
        val fx = Fixture(workers = 2, scanning = true, emitIntervalMs = 40L, idleMs = 150L)
        fx.items = listOf(videoItem("ms-1"))
        fx.pump.publish(fx.items)
        fx.pump.awaitIdle(8_000L)

        assertEquals(1, fx.pump.doneCount())
        assertTrue("扫描中落快照会和提交段抢同一份文件，必须跳过", fx.snapshots.isEmpty())
    }

    @Test
    fun `有失败结论的条目直接跳过_抽不出来的写进否定缓存且key是唯一实现`() = runBlocking {
        val failedBefore = videoItem("ms-bad")
        val fx = Fixture(
            workers = 1,
            scanning = false,
            isCoverFailed = { it.id == failedBefore.id },
            produce = { null },
        )
        val ok = videoItem("ms-ok")
        fx.items = listOf(failedBefore, ok)
        fx.pump.publish(fx.items)
        fx.pump.awaitIdle(8_000L)

        assertFalse(
            "已有失败结论的条目不该再白烧一次抽帧",
            fx.produced.contains(failedBefore.id),
        )
        assertEquals("抽不出来的那项记一次失败", 1, fx.pump.failedCount())
        assertEquals(
            "失败结论的 key 必须走 ScanCoverNegativeCache.keyOf（唯一实现，别在泵里手写第二份）",
            setOf(ScanCoverNegativeCache.keyOf(ok.id, ok.sizeBytes, ok.lastModifiedSec)),
            fx.negative.failed.keys,
        )
        assertTrue("收尾必须 flush 否定缓存", fx.negative.flushCount.get() >= 1)
    }

    @Test
    fun `持久否定结论命中的条目根本不进队列`() = runBlocking {
        val bad = videoItem("ms-gone")
        val fx = Fixture(workers = 2, scanning = false)
        // 模拟「进程重启后读到上一轮落盘的否定结论」：缓存里已经有这个 id 的失败记录
        val key = ScanCoverNegativeCache.keyOf(bad.id, bad.sizeBytes, bad.lastModifiedSec)
        fx.negative.markFailed(key, 0L)

        fx.items = listOf(bad)
        fx.pump.publish(fx.items)

        // 只写不读的老实现会让 runRump 起来白抽一遍；必须一条都不排队、泵也不启动
        assertEquals("否定结论命中的条目不该排进队列", 0, fx.pump.pendingCount())
        assertFalse("没有可抽的条目就不该启动泵", fx.pump.isRunning())
        fx.pump.awaitIdle(1_000L)
        assertTrue("否定结论命中的条目不该被抽帧", fx.produced.isEmpty())
        assertEquals(0, fx.pump.failedCount())
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 真实调度器：泵的整套价值就是并发与节流，虚拟时间会把竞态掩盖掉。 */
    private object RealDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
    }

    private class FakeNegative : CoverNegativeSink {
        val failed = ConcurrentHashMap<String, Long>()
        val flushCount = AtomicInteger(0)

        override fun isFailed(key: String, nowMs: Long): Boolean = failed.containsKey(key)

        override fun markFailed(key: String, nowMs: Long): Boolean {
            failed[key] = nowMs
            return true
        }

        override fun flush() {
            flushCount.incrementAndGet()
        }
    }

    private class Fixture(
        workers: Int = 8,
        scanning: Boolean = false,
        private val produce: (MediaItem) -> String? = { "/cache/${it.id}.jpg" },
        private val emitIntervalMs: Long = 40L,
        private val idleMs: Long = 150L,
        private val isCoverFailed: (MediaItem) -> Boolean = { false },
    ) {
        val scanning = AtomicBoolean(scanning)
        val negative = FakeNegative()
        val produced = CopyOnWriteArrayList<String>()
        val batches = CopyOnWriteArrayList<Map<String, String>>()
        val snapshots = CopyOnWriteArrayList<List<MediaItem>>()
        var items: List<MediaItem> = emptyList()

        val pump = CoverPump(
            dispatchers = RealDispatchers,
            workers = workers,
            cores = 4,
            produceCover = { item ->
                produced += item.id
                produce(item)
            },
            applyCovers = { batch ->
                batches += batch
                // 模拟 LocalMediaSource 的 _media 合并：只覆盖 coverUri，其余字段保持
                items = items.map { item -> batch[item.id]?.let { item.copy(coverUri = it) } ?: item }
            },
            currentItems = { items },
            saveSnapshot = { snapshots += it },
            // 注意用 this.scanning：构造参数同名会遮蔽属性
            isScanning = { this.scanning.get() },
            isCoverFailed = isCoverFailed,
            negative = negative,
            // request 只给 id：泵按 id 反查条目（生产实现同样回查 _media）
            resolveItem = { id -> items.firstOrNull { it.id == id } },
            emitIntervalMs = emitIntervalMs,
            idleMs = idleMs,
        )

        /** 取某个条目最近一次回写的封面（没回写返回 null）。 */
        fun coversOf(id: String): String? =
            batches.asReversed().firstNotNullOfOrNull { it[id] }
    }

    private companion object {
        fun videoItem(id: String): MediaItem = MediaItem(
            id = id,
            sourceId = "local",
            title = id,
            uri = "content://media/external/video/media/$id",
            mimeType = "video/mp4",
            sizeBytes = 1_024L,
            lastModifiedSec = 1_700_000_000L,
        )

        /** 轮询等待（5s 上限），不用裸 delay 赌时序。 */
        fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (cond()) return true
                Thread.sleep(5L)
            }
            return cond()
        }
    }
}
