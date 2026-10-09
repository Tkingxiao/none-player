package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * per-media 记忆键（A2-8）——`sourceId:mediaId`，与 Room 的 `media_key` 同一条约定。
 *
 * 钉住两件事：
 * ① 有 sourceId 时原样限定；
 * ② **没有** sourceId 时（深链契约只带 id）补的缺省前缀必须与一次性搬迁对旧键的
 *    推断一致——两处不一致的后果是"搬完家读不到"，而且是静默的（用户只看到记忆丢了）。
 */
class MediaMemoryKeyTest {

    @Test
    fun `有来源时按来源限定`() {
        assertEquals("nas-1:ms-68", mediaMemoryKey("nas-1", "ms-68"))
    }

    @Test
    fun `缺来源时补当前唯一来源前缀`() {
        assertEquals("local:ms-68", mediaMemoryKey("", "ms-68"))
    }

    @Test
    fun `同一媒体在两种入口下算出同一个键`() {
        // 迷你条/库页（条目带 sourceId）与深链冷进（只有路由 id）必须落同一桶，
        // 否则"在库里开的有字幕、从通知点回来的没有"
        val fromLibrary = mediaMemoryKey("local", "ms-68")
        val fromDeepLink = mediaMemoryKey("", "ms-68")
        assertEquals(fromLibrary, fromDeepLink)
    }
}
