package com.noneplayer.plugins.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E233：删除路径的 SAF 树下钻段（纯字符串函数）。
 *
 * 与播放页字幕查找同一套路径规则，但插件间禁止依赖，库侧自持一份；
 * 这里把「等于树根 / 树内子孙 / 无关路径 / 根授权 / 斜杠归一」五种口径钉住——
 * 误判会让删除在授权目录里静默找不到文件（表现为"有权限也删不掉"）。
 */
class E233DeleteTreeSegmentsTest {

    @Test
    fun `子目录视频取剩余段`() {
        assertEquals(listOf("E232"), treeRelativeSegments("Movies", "Movies/E232"))
    }

    @Test
    fun `多层子目录逐段下钻`() {
        assertEquals(
            listOf("番剧", "合集", "E232"),
            treeRelativeSegments("Movies", "Movies/番剧/合集/E232"),
        )
    }

    @Test
    fun `视频就在授权树根下时零段`() {
        assertEquals(emptyList<String>(), treeRelativeSegments("Movies", "Movies"))
    }

    @Test
    fun `根目录授权时整条路径都是段`() {
        assertEquals(listOf("Movies", "E232"), treeRelativeSegments("", "Movies/E232"))
    }

    @Test
    fun `不在授权树内返回 null`() {
        assertNull(treeRelativeSegments("Movies", "Download/x"))
        assertNull(treeRelativeSegments("Movies", "MoviesX/x"))
    }

    @Test
    fun `首尾斜杠归一`() {
        assertEquals(listOf("E232"), treeRelativeSegments("/Movies/", "/Movies/E232/"))
    }
}