package com.noneplayer.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PerMediaKeysTest {

    @Test
    fun `only keys whose media is gone are pruned`() {
        val keys = setOf(
            "subtitle_src:1",
            "display_src:1",
            "audio_only_src:2",
            "subtitle_",
            "danmaku_manual_uri-a",
            "danmaku_manual_uri-b",
            "theme_mode",
        )
        val orphans = AppSettings.perMediaOrphanKeys(
            keyNames = keys,
            validMediaKeys = setOf("src:1"),
            validUris = setOf("uri-a"),
            includeDanmaku = true,
        ).toSet()
        assertEquals(
            setOf("audio_only_src:2", "subtitle_", "danmaku_manual_uri-b"),
            orphans,
        )
    }

    @Test
    fun `danmaku keys stay when permission gaps make the scan incomplete`() {
        val keys = setOf("danmaku_manual_uri-gone", "display_src:gone")
        val orphans = AppSettings.perMediaOrphanKeys(
            keyNames = keys,
            validMediaKeys = setOf("src:1"),
            validUris = emptySet(),
            includeDanmaku = false,
        )
        // 三族仍可清理；弹幕手选键在 includeDanmaku=false 时整族跳过
        assertEquals(setOf("display_src:gone"), orphans.toSet())
    }
}
