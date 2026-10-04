package com.noneplayer.plugins.enricher.filename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 文件名解析（E42 验收：`[字幕组] 标题 - 01 [1080p][HEVC]` → 「标题 · 第 1 集」）。 */
class FilenameParserTest {

    @Test
    fun `字幕组标签 + 破折号编号 + 编码标签`() {
        val parsed = FilenameParser.parse("[字幕组] 我的视频 - 01 [1080p][HEVC].mkv")!!
        assertEquals("我的视频", parsed.title)
        assertEquals(1, parsed.episode)
        assertEquals(0, parsed.season)
        assertEquals("HEVC", parsed.codec)
    }

    @Test
    fun `方括号里的编号也认`() {
        val parsed = FilenameParser.parse("[Airota][Title][02][1080p].mkv")!!
        assertEquals("Title", parsed.title)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun `SxxExx 形态`() {
        val parsed = FilenameParser.parse("Show.S01E10.1080p.WEB-DL.mkv")!!
        assertEquals(1, parsed.season)
        assertEquals(10, parsed.episode)
    }

    @Test
    fun `中文集号`() {
        val parsed = FilenameParser.parse("剧集 第三集.mp4")!!
        assertEquals(3, parsed.episode)
        assertEquals("剧集", parsed.title)
    }

    @Test
    fun `中文十位数集号`() {
        val parsed = FilenameParser.parse("番剧 第十二话.mp4")!!
        assertEquals(12, parsed.episode)
    }

    @Test
    fun `EP 标记与末尾独立编号`() {
        assertEquals(5, FilenameParser.parse("Title EP05.mkv")!!.episode)
        assertEquals(7, FilenameParser.parse("我的记录 07.mp4")!!.episode)
    }

    @Test
    fun `四位数字不当作集号（1080 与年份）`() {
        val parsed = FilenameParser.parse("演唱会 2024 1080p.mp4")
        // 只有编码没解析出来时不该产出结果；若产出，也绝不能把 2024 当集号
        assertNull(parsed?.episode?.takeIf { it > 0 })
    }

    @Test
    fun `普通文件名不富化`() {
        assertNull(FilenameParser.parse("holiday.mp4"))
        assertNull(FilenameParser.parse("IMG_20241004.jpg"))
    }

    @Test
    fun `只有编号的文件名不产空标题`() {
        val parsed = FilenameParser.parse("[01].mkv")!!
        assertEquals(1, parsed.episode)
        assertEquals("[01]", parsed.title)
    }

    @Test
    fun `h265 与 x264 归一`() {
        assertEquals("HEVC", FilenameParser.parse("a 01 x265.mkv")!!.codec)
        assertEquals("AVC", FilenameParser.parse("a 01 h.264.mkv")!!.codec)
    }
}
