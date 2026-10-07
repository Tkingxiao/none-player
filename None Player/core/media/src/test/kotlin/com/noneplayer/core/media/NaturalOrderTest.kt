package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自然序（E42 的验收就是第一组用例）。 */
class NaturalOrderTest {

    private fun sortedOf(vararg names: String): List<String> = names.sortedWith(NaturalOrder)

    @Test
    fun `数字段按数值排 E2 在 E10 之前`() {
        assertEquals(
            listOf("E2", "E10"),
            sortedOf("E10", "E2"),
        )
        assertEquals(
            listOf("1", "2", "10", "20"),
            sortedOf("20", "10", "2", "1"),
        )
    }

    @Test
    fun `完整文件名里 E2 也排在 E10 前`() {
        val sorted = sortedOf("剧集 E10.mp4", "剧集 E2.mp4")
        assertEquals("剧集 E2.mp4", sorted.first())
    }

    @Test
    fun `前导零数值相等时位数少的在前`() {
        assertEquals(listOf("1.mp4", "01.mp4"), sortedOf("01.mp4", "1.mp4"))
    }

    @Test
    fun `S01E02 形态两段各自按数值`() {
        val sorted = sortedOf("Show.S01E10.mkv", "Show.S01E2.mkv", "Show.S02E01.mkv")
        assertEquals(listOf("Show.S01E2.mkv", "Show.S01E10.mkv", "Show.S02E01.mkv"), sorted)
    }

    @Test
    fun `中文数字按量级排`() {
        val sorted = sortedOf("第二集.mp4", "第十集.mp4", "第一集.mp4")
        assertEquals(listOf("第一集.mp4", "第二集.mp4", "第十集.mp4"), sorted)
    }

    @Test
    fun `阿拉伯数字与中文数字混排也按数值`() {
        val sorted = sortedOf("第10集.mp4", "第二集.mp4", "第1集.mp4", "第三集.mp4")
        assertEquals(
            listOf("第1集.mp4", "第二集.mp4", "第三集.mp4", "第10集.mp4"),
            sorted,
        )
    }

    @Test
    fun `中文数字不吃前导零规则`() {
        // "十" 与 "10" 数值相等；位数比较只对阿拉伯数字生效，所以两者继续比后续字符，最终相等
        assertEquals(0, NaturalOrder.compare("十集", "10集"))
    }

    @Test
    fun `中文数字解析覆盖常见写法`() {
        assertEquals(12L, NaturalOrder.parseChineseNumber("十二"))
        assertEquals(20L, NaturalOrder.parseChineseNumber("二十"))
        assertEquals(25L, NaturalOrder.parseChineseNumber("二十五"))
        assertEquals(105L, NaturalOrder.parseChineseNumber("一百零五"))
        assertEquals(1010L, NaturalOrder.parseChineseNumber("一千零一十"))
        assertEquals(null, NaturalOrder.parseChineseNumber("甲乙"))
    }

    @Test
    fun `无数字时是大小写不敏感的字符序且短前缀在前`() {
        assertTrue(NaturalOrder.compare("apple", "Banana") < 0)
        assertTrue(NaturalOrder.compare("App", "Apple") < 0)
        assertEquals(0, NaturalOrder.compare("Same", "same"))
    }
}
