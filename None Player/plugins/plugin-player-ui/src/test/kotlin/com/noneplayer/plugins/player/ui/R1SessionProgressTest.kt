package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 验收：REV-04（会话一致性——进度不得写错媒体）、A23（Ended 重播从头）、
 * legacy 引擎兼容。
 *
 * 这些用例全部通过真 [PlaybackSession] + 真 [com.noneplayer.core.database.HistoryRepository]
 * 走端到端：假引擎只负责制造「慢探测窗口 / applied 身份 / Ended」这些真实引擎里会
 * 出现的时序。
 */
internal class R1SessionProgressTest : SessionRobolectricTest() {

    // ------------------------------------------------------------------
    // REV-04：A 播到 10s → open(B)（慢探测窗口内引擎 applied 仍是 A）→ stop()
    // ------------------------------------------------------------------

    @Test
    fun `REV-04 慢探测窗口内 stop 不得把 A 的进度写到 B`() {
        val a = testItem("a")
        val b = testItem("b")

        session.open(a, listOf(a))
        fakePlayer.simulateProgress(10_000, 60_000)
        assertEquals("前置条件：A 已生效", fakeMediaKey(a), fakePlayer.appliedMediaKey)

        // B 的起播探测慢：setQueue 后 applied 仍指向 A。
        fakePlayer.slowLoad = true
        session.open(b, listOf(b))
        assertEquals("前置条件：慢探测窗口内引擎仍认为在播 A", fakeMediaKey(a), fakePlayer.appliedMediaKey)
        assertEquals("前置条件：会话队列已切到 B", listOf("b"), session.queue.value.map { it.id })

        session.stop()

        assertEquals(
            "REV-04：A 的断点必须落盘（10s）",
            10_000L,
            awaitPosition(a, 10_000L),
        )
        assertNoBreakpoint(b, "REV-04：B 不得继承 A 的进度")
    }

    // ------------------------------------------------------------------
    // REV-04：A → B → C 连续换集，每一步只落当前已生效媒体的进度
    // ------------------------------------------------------------------

    @Test
    fun `REV-04 A 到 B 到 C 连续换集时各自断点正确`() {
        val a = testItem("a")
        val b = testItem("b")
        val c = testItem("c")

        session.open(a, listOf(a))
        fakePlayer.simulateProgress(10_000, 60_000)

        // 切 B：探测慢，B 尚未生效。
        fakePlayer.slowLoad = true
        session.open(b, listOf(b))
        fakePlayer.finishLoad()
        fakePlayer.simulateProgress(20_000, 60_000)
        assertEquals("前置条件：B 已生效", fakeMediaKey(b), fakePlayer.appliedMediaKey)

        // 切 C：探测仍慢，C 尚未生效。
        fakePlayer.slowLoad = true
        session.open(c, listOf(c))
        assertEquals("前置条件：慢探测窗口内引擎仍认为在播 B", fakeMediaKey(b), fakePlayer.appliedMediaKey)

        session.stop()

        assertEquals("REV-04：A 断点应为 10s", 10_000L, awaitPosition(a, 10_000L))
        assertEquals("REV-04：B 断点应为 20s", 20_000L, awaitPosition(b, 20_000L))
        assertNoBreakpoint(c, "REV-04：C 不得继承 B 的进度")
    }

    // ------------------------------------------------------------------
    // REV-04：同一 URI 的不同 clip（CUE 分轨）是两条独立媒体，进度互不污染
    // ------------------------------------------------------------------

    @Test
    fun `REV-04 同 URI 不同 clip 的断点互不污染`() {
        // CUE 分轨：uri 相同、id 与 clip 区间不同。
        val track1 = testItem("cue-track-1", uri = "content://media/album.cue", clipStartUs = 0L, clipEndUs = 10_000_000L)
        val track2 = testItem("cue-track-2", uri = "content://media/album.cue", clipStartUs = 10_000_000L, clipEndUs = 20_000_000L)

        session.open(track1, listOf(track1, track2))
        fakePlayer.simulateProgress(15_000, 60_000)

        // 切到同一文件里的第 2 轨，但第 2 轨的装载还没生效：此时引擎 applied 仍是第 1 轨。
        fakePlayer.slowLoad = true
        session.open(track2, listOf(track1, track2))
        assertEquals("前置条件：慢探测窗口内引擎仍认为在播 track1", fakeMediaKey(track1), fakePlayer.appliedMediaKey)
        assertNoBreakpoint(track2, "REV-04：track2 尚未生效，不得继承 track1 的进度")

        // 第 2 轨真正生效后，它自己的进度只能落到它自己的历史键。
        fakePlayer.finishLoad()
        fakePlayer.simulateProgress(12_000, 60_000)
        session.stop()

        assertEquals("REV-04：track1 断点应为 15s", 15_000L, awaitPosition(track1, 15_000L))
        assertEquals("REV-04：track2 自己的断点应为 12s", 12_000L, awaitPosition(track2, 12_000L))
    }

    // ------------------------------------------------------------------
    // REV-04 反向：open(B) 必须先把旧媒体 A 的断点落下（open 前置 persist）
    // ------------------------------------------------------------------

    @Test
    fun `REV-04 open 新条目前必须落旧媒体断点`() {
        val a = testItem("a")
        val b = testItem("b")

        session.open(a, listOf(a))
        fakePlayer.simulateProgress(10_000, 60_000)

        fakePlayer.slowLoad = true
        session.open(b, listOf(b))

        // 不调用 stop()：仅靠 open() 本身就必须完成 A 的落盘。
        assertEquals(
            "REV-04：open(B) 必须先落 A 的断点（open 前置 persist），而不是等 stop/5s 周期",
            10_000L,
            awaitPosition(a, 10_000L),
        )
        assertNoBreakpoint(b, "REV-04：B 不得继承 A 的进度")
    }

    // ------------------------------------------------------------------
    // REV-04 反向竞态：引擎已切到 B、会话下标还没跟上（currentIndex 回调未到）
    // ------------------------------------------------------------------

    @Test
    fun `REV-04 引擎已切到 B 但会话下标未跟上时不得把 B 的位置写进 A`() {
        val a = testItem("a")
        val b = testItem("b")

        session.open(a, listOf(a, b))
        fakePlayer.simulateProgress(10_000, 60_000)
        // 先把 A 的合法断点落下，作为基线。
        session.persistPosition()
        assertEquals("前置条件：A 基线断点已落盘", 10_000L, awaitPosition(a, 10_000L))

        // 引擎自动连播到 B（已生效、位置 20s），但 currentIndex 回调还没到会话侧：
        // 会话下标仍指向 A，此刻若按"当前项 + 引擎位置"落盘就会把 B 的 20s 写进 A。
        fakePlayer.simulateAppliedMedia(b, 20_000)
        assertEquals("前置条件：会话下标仍是 A", 0, session.index.value)

        session.stop()

        assertEquals(
            "REV-04：A 的断点必须保持 10s（不得被引擎里 B 的 20s 覆盖）",
            10_000L,
            awaitPosition(a, 10_000L),
        )
    }

    // ------------------------------------------------------------------
    // A23：Ended 后重播必须 seekTo(0) 再 play
    // ------------------------------------------------------------------

    @Test
    fun `A23 Ended 后 toggle 必须先 seekTo 0 再 play`() {
        val a = testItem("a")
        session.open(a, listOf(a))
        fakePlayer.simulateEnded()
        assertEquals("前置条件：引擎已到结尾", PlaybackState.Ended, fakePlayer.state.value)

        fakePlayer.calls.clear()
        session.toggle()

        val seekIndex = fakePlayer.seekToIndex(0L)
        val playIndex = fakePlayer.lastPlayIndex()
        assertTrue(
            "A23：Ended 后 toggle() 必须先 seekTo(0)，实际调用序列=${fakePlayer.labels}",
            seekIndex >= 0,
        )
        assertTrue(
            "A23：Ended 后 toggle() 的 seekTo(0) 必须发生在 play() 之前，实际调用序列=${fakePlayer.labels}",
            seekIndex < playIndex,
        )
        assertEquals("A23：重播后位置应回到 0", 0L, fakePlayer.positionMs.value)
        assertEquals("A23：重播后应进入 Playing", PlaybackState.Playing, fakePlayer.state.value)
    }

    @Test
    fun `A23 Ended 后 play 必须先 seekTo 0 再 play`() {
        val a = testItem("a")
        session.open(a, listOf(a))
        fakePlayer.simulateEnded()
        assertEquals("前置条件：引擎已到结尾", PlaybackState.Ended, fakePlayer.state.value)

        fakePlayer.calls.clear()
        session.play()

        val seekIndex = fakePlayer.seekToIndex(0L)
        val playIndex = fakePlayer.lastPlayIndex()
        assertTrue(
            "A23：Ended 后 play() 必须先 seekTo(0)，实际调用序列=${fakePlayer.labels}",
            seekIndex >= 0,
        )
        assertTrue(
            "A23：Ended 后 play() 的 seekTo(0) 必须发生在 play() 之前，实际调用序列=${fakePlayer.labels}",
            seekIndex < playIndex,
        )
        assertEquals("A23：重播后位置应回到 0", 0L, fakePlayer.positionMs.value)
    }

    // ------------------------------------------------------------------
    // legacy 兼容：引擎没有 applied 身份时仍按 positionMs 落盘
    // ------------------------------------------------------------------

    @Test
    fun `legacy 引擎 applied 身份为 null 时仍按 positionMs 落盘`() {
        val a = testItem("a")
        session.open(a, listOf(a))
        fakePlayer.simulateLegacyIdentity()
        assertEquals("前置条件：legacy 引擎 applied 身份为 null", null, fakePlayer.appliedMediaKey)
        fakePlayer.simulateProgress(12_000, 60_000)

        session.stop()

        assertEquals(
            "legacy 兼容：appliedMediaKey 为 null 时必须退回按 positionMs 落盘",
            12_000L,
            awaitPosition(a, 12_000L),
        )
    }
}
