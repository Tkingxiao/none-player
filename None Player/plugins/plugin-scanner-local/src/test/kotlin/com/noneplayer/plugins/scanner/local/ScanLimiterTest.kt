package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanLimiter] 的回归测试。
 *
 * 重点是第 2 例：真机上自适应调参「往上加许可」时，`kotlinx.coroutines.sync.Semaphore`
 * 因为只允许归还自己 acquire 过的数量，直接抛
 * `IllegalStateException: The number of released permits cannot be greater than 4`，
 * 整轮扫描被降级成失败摘要。换成令牌桶后，多出来的 release 一律返回 false 而不是抛异常。
 */
class ScanLimiterTest {

    @Test
    fun `初始令牌数等于initial且被cap钳位`() = runTest {
        // 正常：初始 = initial
        assertEquals(3, ScanLimiter(capacity = 8, initial = 3).permits)
        // initial 超过 cap → 钳到 cap（channel 容量也只有 cap，多投会失败）
        assertEquals(4, ScanLimiter(capacity = 4, initial = 9).permits)
        // initial 非正 → 至少 1 个（零许可会让整段工作永远排队）
        assertEquals(1, ScanLimiter(capacity = 4, initial = 0).permits)
        assertEquals(1, ScanLimiter(capacity = 4, initial = -5).permits)
        // capacity 非正 → 至少 1
        assertEquals(1, ScanLimiter(capacity = 0, initial = 5).permits)
    }

    @Test
    fun `release到cap后返回false且不再增长_不会抛ISE`() = runTest {
        // 复刻真机序列：cap=6、起跑 4，先借满再全部归还，然后 tuner 判定加档往上加两个
        val limiter = ScanLimiter(capacity = 6, initial = 4)
        repeat(4) { limiter.acquire() }
        assertEquals(0, limiter.permits)
        repeat(4) { assertTrue("归还自己借出的许可必须成功", limiter.release()) }
        assertEquals(4, limiter.permits)
        // 加档：凭空新增许可——旧实现（kotlinx Semaphore）就在这一步抛 ISE
        assertTrue(limiter.release())
        assertTrue(limiter.release())
        assertEquals(6, limiter.permits)
        // 已到 cap：再涨就是 false，绝不抛异常
        assertFalse("到 cap 之后必须返回 false 而不是抛异常", limiter.release())
        assertEquals(6, limiter.permits)
        // 到 cap 之后 6 个许可真的能同时借出（第 7 个会挂起等待，不抛）
        repeat(6) { limiter.acquire() }
        assertEquals(0, limiter.permits)
    }

    @Test
    fun `缩许可的acquire要等到有许可回来才返回`() = runTest {
        val limiter = ScanLimiter(capacity = 4, initial = 2)
        // 两个许可先借满
        val first = launch { limiter.acquire() }
        val second = launch { limiter.acquire() }
        runCurrent()
        assertEquals(0, limiter.permits)
        // 调参降档：收许可的协程会挂起在这里
        val shrink = launch { limiter.acquire() }
        advanceUntilIdle()
        assertFalse("没有空闲许可时，收许可的 acquire 必须挂起", shrink.isCompleted)
        // 一个工作项干完归还 → 收许可那侧立刻拿到并返回
        limiter.release()
        advanceUntilIdle()
        assertTrue("有许可回来之后，收许可的 acquire 必须完成", shrink.isCompleted)
        assertEquals(0, limiter.permits)
        // 语义确认：真实可用许可被永久收回（2 个 → 1 个）
        limiter.release()
        assertEquals(1, limiter.permits)
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
    }

    @Test
    fun `withPermit块内抛异常也归还令牌`() = runTest {
        val limiter = ScanLimiter(capacity = 2, initial = 2)
        val boom = runCatching {
            limiter.withPermit { throw IllegalStateException("注入故障：块内失败") }
        }.exceptionOrNull()
        assertNotNull("块内异常必须原样抛给调用方", boom)
        assertEquals("异常路径也必须归还许可，否则后续任务永久排队", 2, limiter.permits)
        // 归还后仍能正常借满，说明令牌没丢
        limiter.withPermit { }
        limiter.withPermit { }
        assertEquals(2, limiter.permits)
    }

    @Test
    fun `取消等待中的acquire不会泄漏令牌`() = runTest {
        val limiter = ScanLimiter(capacity = 2, initial = 1)
        limiter.acquire()
        assertEquals(0, limiter.permits)
        // 排队的 acquire 被取消（真机对应：扫描取消时还在等许可的工作协程）
        val waiter = launch { limiter.acquire() }
        advanceUntilIdle()
        assertFalse(waiter.isCompleted)
        waiter.cancel()
        advanceUntilIdle()
        // 借出计数不变：取消既不吞掉正在等的令牌，也不凭空多出一个
        assertEquals(0, limiter.permits)
        // 归还后仍只有一个许可可用，且能被立刻借走（若被取消的 acquire 吞了令牌，这里会挂住）
        limiter.release()
        assertEquals(1, limiter.permits)
        val again = launch { limiter.acquire() }
        advanceUntilIdle()
        assertTrue("取消过的 acquire 不能吃掉令牌", again.isCompleted)
        assertEquals(0, limiter.permits)
    }
}
