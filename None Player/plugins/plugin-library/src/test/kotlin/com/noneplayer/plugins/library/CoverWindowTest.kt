package com.noneplayer.plugins.library

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §8.5：库页按需封面的窗口计算。
 *
 * 这些用例只碰纯函数——不启 Compose、不碰来源，所以是普通 JUnit（与 CoverFramePolicyTest 同档）。
 */
class CoverWindowTest {

    private fun media(index: Int, sourceId: String = LOCAL): MediaItem = MediaItem(
        id = "id$index",
        sourceId = sourceId,
        title = "视频$index",
        uri = "file:///v$index.mp4",
    )

    private fun medias(count: Int, sourceId: String = LOCAL): List<MediaItem> =
        (0 until count).map { media(it, sourceId) }

    private fun ids(items: List<MediaItem>): List<String> = items.map { it.id }

    @Test
    fun `静止时前后各一屏`() {
        // 可见 50..59（10 条）：前面再要 10 条、后面再要 10 条
        val window = coverWindow(itemCount = 100, firstVisibleItem = 50, visibleItemCount = 10)
        assertEquals(40, window.firstIndex)
        assertEquals(69, window.lastIndex)
        assertEquals(30, window.size)
    }

    @Test
    fun `急速向下滚时未来一侧加到两屏`() {
        val window = coverWindow(
            itemCount = 100,
            firstVisibleItem = 50,
            visibleItemCount = 10,
            direction = CoverScrollDirection.FORWARD,
        )
        assertEquals(40, window.firstIndex) // 回看一侧仍是 1 屏
        assertEquals(79, window.lastIndex) // 未来一侧 2 屏
    }

    @Test
    fun `急速向上滚时回看一侧加到两屏`() {
        val window = coverWindow(
            itemCount = 100,
            firstVisibleItem = 50,
            visibleItemCount = 10,
            direction = CoverScrollDirection.BACKWARD,
        )
        assertEquals(30, window.firstIndex)
        assertEquals(69, window.lastIndex)
    }

    @Test
    fun `窗口夹紧在列表两端`() {
        val head = coverWindow(itemCount = 100, firstVisibleItem = 0, visibleItemCount = 10)
        assertEquals(0, head.firstIndex)
        assertEquals(19, head.lastIndex)

        val tail = coverWindow(itemCount = 100, firstVisibleItem = 99, visibleItemCount = 1)
        assertEquals(98, tail.firstIndex)
        assertEquals(99, tail.lastIndex)
    }

    @Test
    fun `空列表给空窗口`() {
        assertTrue(coverWindow(0, 0, 10).isEmpty)
        assertTrue(coverDemandItems(emptyList(), 0, 0, 5).isEmpty())
    }

    @Test
    fun `可见条数至少按一条算`() {
        // 布局还没算完时 visibleItemCount 可能是 0——不能因此算出空窗口
        val window = coverWindow(itemCount = 100, firstVisibleItem = 5, visibleItemCount = 0)
        assertEquals(4, window.firstIndex)
        assertEquals(6, window.lastIndex)
    }

    @Test
    fun `槽位换算跳过前导非媒体项`() {
        // 前导 3 槽（标题 / 控件行 / 副标题），可见槽 0..5 → 条目 0..2
        val partial = mediaIndexRange(itemCount = 100, leaderSlots = 3, firstVisibleSlot = 0, lastVisibleSlot = 5)
        assertEquals(0..2, partial)

        // 头部已经滚出：可见槽 10..19 → 条目 7..16
        val scrolled = mediaIndexRange(itemCount = 100, leaderSlots = 3, firstVisibleSlot = 10, lastVisibleSlot = 19)
        assertEquals(7..16, scrolled)

        // 可见区整块停在头部（还没露出第一条内容）：这一帧没有可预热条目
        assertNull(mediaIndexRange(itemCount = 100, leaderSlots = 3, firstVisibleSlot = 0, lastVisibleSlot = 2))
        // 还没布局
        assertNull(mediaIndexRange(itemCount = 100, leaderSlots = 3, firstVisibleSlot = -1, lastVisibleSlot = -1))
    }

    @Test
    fun `条目序号夹在列表范围内`() {
        // 可见槽位比条目还多（末尾留白）：不能越界取到不存在的条目
        val range = mediaIndexRange(itemCount = 10, leaderSlots = 2, firstVisibleSlot = 8, lastVisibleSlot = 40)
        assertEquals(6..9, range)
    }

    @Test
    fun `从可见槽位直接切出预热条目`() {
        val items = medias(20)
        val demand = coverDemandItems(items, leaderSlots = 3, firstVisibleSlot = 5, lastVisibleSlot = 7)
        // 槽 5..7 → 条目 2..4（可见 3 条）→ 窗口 0..7
        assertEquals(listOf("id0", "id1", "id2", "id3", "id4", "id5", "id6", "id7"), ids(demand))
    }

    @Test
    fun `向下滚时未来一侧多一屏条目`() {
        val items = medias(50)
        val idle = coverDemandItems(items, leaderSlots = 0, firstVisibleSlot = 10, lastVisibleSlot = 14)
        val forward = coverDemandItems(
            items,
            leaderSlots = 0,
            firstVisibleSlot = 10,
            lastVisibleSlot = 14,
            direction = CoverScrollDirection.FORWARD,
        )
        // 静止：5..19（前 5 后 5）；向下滚：5..24（未来一侧 2 屏）
        assertEquals("id5", idle.first().id)
        assertEquals("id19", idle.last().id)
        assertEquals("id5", forward.first().id)
        assertEquals("id24", forward.last().id)
    }

    @Test
    fun `方向判定`() {
        assertEquals(
            CoverScrollDirection.IDLE,
            coverScrollDirection(isScrollInProgress = false, firstVisibleSlot = 5, previousFirstVisibleSlot = 3),
        )
        assertEquals(
            CoverScrollDirection.FORWARD,
            coverScrollDirection(isScrollInProgress = true, firstVisibleSlot = 5, previousFirstVisibleSlot = 3),
        )
        assertEquals(
            CoverScrollDirection.BACKWARD,
            coverScrollDirection(isScrollInProgress = true, firstVisibleSlot = 3, previousFirstVisibleSlot = 5),
        )
        // 首帧没有基准 / 槽位没动
        assertEquals(
            CoverScrollDirection.IDLE,
            coverScrollDirection(isScrollInProgress = true, firstVisibleSlot = 5, previousFirstVisibleSlot = -1),
        )
        assertEquals(
            CoverScrollDirection.IDLE,
            coverScrollDirection(isScrollInProgress = true, firstVisibleSlot = 5, previousFirstVisibleSlot = 5),
        )
    }

    @Test
    fun `按来源分组保序去重`() {
        val window = listOf(
            media(0, LOCAL),
            media(1, REMOTE),
            media(0, LOCAL), // 同一窗口内重复：只发一次
            media(2, LOCAL),
            media(9, "uninstalled"), // 来源已经不在：分组里保留，由分发方跳过
        )
        val grouped = groupCoverIdsBySource(window)
        assertEquals(listOf("id0", "id2"), grouped[LOCAL])
        assertEquals(listOf("id1"), grouped[REMOTE])
        assertEquals(listOf("id9"), grouped["uninstalled"])
        assertTrue(groupCoverIdsBySource(emptyList()).isEmpty())
    }

    private companion object {
        const val LOCAL = "local-media"
        const val REMOTE = "remote"
    }
}
