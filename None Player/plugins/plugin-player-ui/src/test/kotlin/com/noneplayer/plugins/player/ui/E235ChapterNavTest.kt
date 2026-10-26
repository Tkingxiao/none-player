package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E235：章节入口的纯函数口径——进度条点按吸附与面板上/下一章步进。
 *
 * 吸附是"点得准"的辅助：12dp（轨宽换算成 snapFraction）以内归到章起点，
 * 超过就是普通点按定位；拖动路径不受影响。步进在端点返回 null（行降透明度）。
 */
class E235ChapterNavTest {

    private val starts = listOf(0L, 30_000L, 60_000L)

    @Test
    fun `点按落在刻度邻域吸附到章节起点`() {
        // 30.5s（离 30s 刻度 0.5s）在 1%（=1s）吸附半径内
        assertEquals(30_000L, chapterSnapPositionMs(0.305f, 100_000L, starts, 0.01f))
        // 60s 刻度旁同理
        assertEquals(60_000L, chapterSnapPositionMs(0.5985f, 100_000L, starts, 0.01f))
    }

    @Test
    fun `点按远离刻度时按点按位置精确 seek`() {
        assertEquals(50_000L, chapterSnapPositionMs(0.5f, 100_000L, starts, 0.01f))
    }

    @Test
    fun `时长未知或没有章节时不做吸附`() {
        assertEquals(0L, chapterSnapPositionMs(0.5f, 0L, starts, 0.01f))
        assertEquals(42_000L, chapterSnapPositionMs(0.42f, 100_000L, emptyList(), 0.01f))
        // snapFraction 为 0 = 关闭吸附
        assertEquals(30_500L, chapterSnapPositionMs(0.305f, 100_000L, starts, 0f))
    }

    @Test
    fun `越界刻度被过滤且点按比例夹取`() {
        // 200s 的刻度超出时长，不参与吸附
        val beyond = listOf(200_000L)
        assertEquals(100_000L, chapterSnapPositionMs(1.5f, 100_000L, beyond, 0.01f))
        // 负值刻度同样过滤
        assertEquals(5_000L, chapterSnapPositionMs(0.05f, 100_000L, listOf(-10L), 0.01f))
    }

    @Test
    fun `下一章在末章返回 null`() {
        assertEquals(1, chapterStepIndex(currentIndex = 0, chapterCount = 3, forward = true))
        assertEquals(2, chapterStepIndex(currentIndex = 1, chapterCount = 3, forward = true))
        assertNull(chapterStepIndex(currentIndex = 2, chapterCount = 3, forward = true))
        assertNull(chapterStepIndex(currentIndex = 0, chapterCount = 0, forward = true))
    }

    @Test
    fun `上一章在首章返回 null 且越界下标被夹取`() {
        assertEquals(0, chapterStepIndex(currentIndex = 1, chapterCount = 3, forward = false))
        assertNull(chapterStepIndex(currentIndex = 0, chapterCount = 3, forward = false))
        // 位置在全部章之前（-1）按第 0 章处理：上一章 null、下一章 1
        assertNull(chapterStepIndex(currentIndex = -1, chapterCount = 3, forward = false))
        assertEquals(1, chapterStepIndex(currentIndex = -1, chapterCount = 3, forward = true))
        // 越界大下标夹到末章：下一章 null
        assertNull(chapterStepIndex(currentIndex = 99, chapterCount = 3, forward = true))
    }
}