package com.noneplayer.plugins.player.ui

import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.kernel.api.PlaybackState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R2-3 / REV-06 独立验收：**存储降级下会话不崩、断点安全、可恢复**。
 *
 * 验收面（对应任务卡 ①–④）：
 * 1. `persistPosition()` 遇写故障 → 异常不逃逸、会话/引擎完好；故障恢复后下一次落盘成功；
 * 2. `open()` 的 `markPlayed` 遇写故障 → open 本身不抛、装载照常完成；
 * 3. `positionOfIfIntact` 读故障（契约：返回 null）→ `SessionBootstrap` 把断点当 0、从头起播；
 * 4. 连续 N 次失败后恢复 → 只有最后一次成功写入落盘，失败不排队、不重复写错键。
 *
 * 说明：本类的 [session] 在 `@Before` 里被替换成注入 [FailingHistoryStore] 的会话
 * （父类夹具默认注入真 `HistoryRepository`）。真引擎/Room 不参与这些用例——故障必须
 * 可控可断言；真实 Room 路径由 `HistoryRepositoryResilienceTest`（task-8）覆盖。
 */
internal class R2StorageFailureTest : SessionRobolectricTest() {

    private lateinit var store: FailingHistoryStore

    @Before
    fun setUpFailingStoreSession() {
        // 父类 @Before 先建了注入真 HistoryRepository 的会话；这里换成注入失败替身的会话。
        runCatching { session.stop() }
        store = FailingHistoryStore()
        session = PlaybackSession(context, factory, registry, settings, events, store)
    }

    // ---- ① 断点落盘失败：异常隔离 + 恢复后继续落盘 ---------------------------

    @Test
    fun persistFailureIsIsolatedAndRecoversOnNextAttempt() {
        val item = testItem("ms-persist")
        session.open(item = item, queueOfFolder = listOf(item), startPlaying = true)
        val engine = fakePlayer
        engine.simulateProgress(12_000L)

        store.failWrites = true
        session.persistPosition() // 不得把 IOException 抛给调用方（异常在会话的异步任务里被隔离）
        awaitCondition { store.recordAttempts == 1 }
        settleIo()

        // 会话本身没有被击穿：引擎、队列、播放状态都还在
        assertSame(engine, session.player)
        assertEquals(listOf("ms-persist"), session.queue.value.map { it.id })
        assertEquals(PlaybackState.Playing, engine.state.value)
        // 失败的写入不得出现在内存历史里
        assertNull(store.breakpointOf(HistoryEntry.keyOf(item)))
        assertEquals(1, store.recordAttempts)

        // 故障恢复后：同一次断点写入必须能落地
        store.failWrites = false
        session.persistPosition()
        awaitCondition { store.breakpointOf(HistoryEntry.keyOf(item)) == 12_000L }
        assertEquals(2, store.recordAttempts)
        assertEquals(
            mapOf(HistoryEntry.keyOf(item) to 12_000L),
            store.breakpointsSnapshot(),
        )
    }

    // ---- ② open() 的 markPlayed 失败：装载不抛、不中断 -----------------------

    @Test
    fun markPlayedFailureDoesNotBreakOpen() {
        store.failWrites = true
        val item = testItem("ms-mark")

        session.open(item = item, queueOfFolder = listOf(item), startPlaying = true)

        awaitCondition { store.markPlayedAttempts == 1 }
        // open() 同步返回、引擎已装载：markPlayed 是异步副作用，失败不能影响装载结果
        assertEquals(1, fakePlayer.setQueueCalls().size)
        assertEquals(listOf("ms-mark"), session.queue.value.map { it.id })
        assertEquals(PlaybackState.Playing, fakePlayer.state.value)
        settleIo()
        assertNull(store.breakpointOf(HistoryEntry.keyOf(item)))

        // 故障恢复后，同一会话的断点写入仍然成功（会话 scope 没被异常取消）
        store.failWrites = false
        fakePlayer.simulateProgress(9_000L)
        session.persistPosition()
        awaitCondition { store.breakpointOf(HistoryEntry.keyOf(item)) == 9_000L }
    }

    // ---- ③ 断点读取失败：按 0 处理、从头起播 --------------------------------

    @Test
    fun bootstrapTreatsReadFailureAsNoBreakpointAndStartsFromZero() {
        val item = testItem("ms-boot")
        // 库里其实有 42s 断点；读取故障时必须降级为"从头"，而不是抛异常/卡住装载
        store.seedBreakpoint(item, 42_000L)
        store.failReads = true
        val probe = FakeVideoPlayer(engineId = "bootstrap-probe")

        val start = runBlocking {
            ensureSessionPlaying(
                context = context,
                registry = registry,
                settings = settings,
                history = store,
                player = probe,
                session = session,
                item = item,
            )
        }

        assertTrue("content:// 应被 scheme 白名单接受", start.accepted)
        assertEquals("断点读取失败必须按 0 起播", 0L, start.resumedFromMs)
        assertEquals("装载链只应尝试读一次断点", 1, store.readCalls)
        val engine = session.player as FakeVideoPlayer
        assertEquals(
            "起播位置必须落在 0，不能把失败读到的旧断点（或异常）带进引擎",
            0L,
            engine.setQueueCalls().single().startPositionMs,
        )
        assertEquals("读故障只影响断点恢复，不影响装载本身", PlaybackState.Playing, engine.state.value)
    }

    // ---- ④ 连续失败后恢复：失败不排队、只写最后一次成功 ----------------------

    @Test
    fun repeatedFailuresDoNotQueueAndOnlySuccessfulWriteLands() {
        val item = testItem("ms-retry")
        session.open(item = item, queueOfFolder = listOf(item), startPlaying = true)
        val engine = fakePlayer
        engine.simulateProgress(12_000L)

        store.failWrites = true
        repeat(3) { index ->
            val before = store.recordAttempts
            session.persistPosition()
            awaitCondition { store.recordAttempts == before + 1 }
            assertEquals("第 ${index + 1} 次失败写入不得落盘", null, store.breakpointOf(HistoryEntry.keyOf(item)))
        }
        settleIo()
        assertEquals(3, store.recordAttempts)
        assertTrue(store.breakpointsSnapshot().isEmpty())
        // 每次尝试都针对当前目标键，没有写错键
        assertTrue(store.recordCallList.all { it.mediaKey == HistoryEntry.keyOf(item) })

        store.failWrites = false
        session.persistPosition()
        awaitCondition { store.breakpointOf(HistoryEntry.keyOf(item)) == 12_000L }
        // 恢复后只有这一次补偿写入（共 4 次），失败没有被排队重放
        settleIo()
        assertEquals(4, store.recordAttempts)
        assertEquals(
            mapOf(HistoryEntry.keyOf(item) to 12_000L),
            store.breakpointsSnapshot(),
        )
    }
}
