package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * R0/REV-03：扫描提交代际门。
 *
 * 核心断言：`invalidate` 与 `commitIfCurrent` 的「复核 + 执行」在同一个串行段内——
 * 失效之后到达的提交一律不执行；已在执行中的提交不会被中途作废；
 * 两个提交段绝不交错；并发 invalidate 不丢更新。
 */
class ScanCommitGateTest {

    // ---- ① 代际递增 ----

    @Test
    fun `invalidate 递增代际并返回新值`() = runTest {
        val gate = ScanCommitGate()
        assertEquals(0L, gate.currentGeneration())

        assertEquals(1L, gate.invalidate())
        assertEquals(1L, gate.currentGeneration())

        assertEquals(2L, gate.invalidate())
        assertEquals(2L, gate.currentGeneration())
    }

    @Test
    fun `初始代际可由构造参数指定`() = runTest {
        val gate = ScanCommitGate(initialGeneration = 7L)
        assertEquals(7L, gate.currentGeneration())
        assertEquals(8L, gate.invalidate())
    }

    // ---- ② 同代际执行并返回结果 ----

    @Test
    fun `同代际提交执行block并返回结果`() = runTest {
        val gate = ScanCommitGate(initialGeneration = 7L)
        val executed = AtomicInteger(0)

        val result = gate.commitIfCurrent(7L) {
            executed.incrementAndGet()
            "committed"
        }

        assertEquals("committed", result)
        assertEquals(1, executed.get())
    }

    // ---- ③ 失效后不执行 ----

    @Test
    fun `失效后提交返回null且block未执行`() = runTest {
        val gate = ScanCommitGate()
        val captured = gate.currentGeneration()
        gate.invalidate()
        val executed = AtomicInteger(0)

        val result = gate.commitIfCurrent(captured) {
            executed.incrementAndGet()
            "stale"
        }

        assertNull(result)
        assertEquals(0, executed.get())
    }

    @Test
    fun `旧代际提交在多次失效后仍被拒绝`() = runTest {
        val gate = ScanCommitGate()
        val captured = gate.currentGeneration()
        repeat(3) { gate.invalidate() }
        val executed = AtomicInteger(0)

        assertNull(gate.commitIfCurrent(captured) { executed.incrementAndGet() })
        assertEquals(0, executed.get())
        // 新代际可正常提交
        assertEquals("ok", gate.commitIfCurrent(gate.currentGeneration()) { "ok" })
    }

    // ---- ④ 并发交错：串行段内复核有效 + 失效计数不丢 ----

    @Test
    fun `并发失效与提交交错_串行段内代际不被篡改`() = runTest {
        val gate = ScanCommitGate()
        val violations = AtomicInteger(0)
        val committed = AtomicInteger(0)

        val commits = (0 until 40).map { index ->
            async(Dispatchers.Default) {
                val captured = gate.currentGeneration()
                gate.commitIfCurrent(captured) {
                    // 复核与执行在同一把锁内：执行期间 invalidate 只能排队，代际不得变化
                    if (gate.currentGeneration() != captured) violations.incrementAndGet()
                    delay(1) // 放大交错窗口：若复核后释放锁，这里必被 invalidate 插队
                    if (gate.currentGeneration() != captured) violations.incrementAndGet()
                    committed.incrementAndGet()
                    index
                }
            }
        }
        val invalidations = (0 until 20).map {
            async(Dispatchers.Default) { gate.invalidate() }
        }

        val commitResults = commits.awaitAll()
        val generations = invalidations.awaitAll()

        assertEquals("提交段执行期间代际被并发失效篡改", 0, violations.get())
        // 20 次 invalidate 一次都不能丢（丢失更新会让结果小于 20）
        assertEquals(20L, gate.currentGeneration())
        assertEquals((1L..20L).toList(), generations.sorted())
        assertEquals(commitResults.count { it != null }, committed.get())
    }

    @Test
    fun `提交段执行期间失效会排队_提交完整生效`() = runTest {
        val gate = ScanCommitGate()
        val captured = gate.currentGeneration()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val commit = async(Dispatchers.Default) {
            gate.commitIfCurrent(captured) {
                started.complete(Unit)
                release.await()
                "done"
            }
        }
        started.await()

        val invalidate = async(Dispatchers.Default) { gate.invalidate() }
        // 给 invalidate 足够窗口尝试抢锁：提交段未结束时它只能排队
        repeat(10) { delay(5) }
        assertFalse("提交段持锁期间 invalidate 不应完成", invalidate.isCompleted)

        release.complete(Unit)
        assertEquals("done", commit.await())
        assertEquals(1L, invalidate.await())
        assertEquals(1L, gate.currentGeneration())
    }

    // ---- ⑤ 两个并发提交段不交错 ----

    @Test
    fun `两个并发提交段的block不交错`() = runTest {
        val gate = ScanCommitGate()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val started = AtomicInteger(0)

        val jobs = (1..2).map {
            async(Dispatchers.Default) {
                gate.commitIfCurrent(gate.currentGeneration()) {
                    started.incrementAndGet()
                    val now = active.incrementAndGet()
                    peak.updateAndGet { max -> maxOf(max, now) }
                    // 持锁挂起：第二个提交段必须等待，绝不能同时进入 block
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(2, started.get())
        assertEquals("两个提交段发生了交错执行", 1, peak.get())
        assertEquals(0, active.get())
    }
}
