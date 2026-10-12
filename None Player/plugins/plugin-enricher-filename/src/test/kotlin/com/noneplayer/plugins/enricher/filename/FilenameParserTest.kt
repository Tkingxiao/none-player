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
    fun `多个独立数字段时取末尾那个当集号`() {
        // "2" 与 "04" 都是合法的 1–3 位独立数字段，只有末尾那个是集号
        assertEquals(4, FilenameParser.parse("Rock 2 04.mkv")!!.episode)
        assertEquals(12, FilenameParser.parse("Live 2024 12.mkv")!!.episode)
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

    @Test
    fun `物语标题的括号副标题不会丢失`() {
        val parsed = FilenameParser.parse("[VCB-Studio] Nekomonogatari (Shiro) [02][Ma10p_1080p][x265_flac].mkv")!!
        assertEquals("Nekomonogatari Shiro", parsed.title)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun `双语方括号标题都保留`() {
        val parsed = FilenameParser.parse("[终物语][Owarimonogatari][12][1080p][HEVC].mkv")!!
        assertEquals("终物语 Owarimonogatari", parsed.title)
        assertEquals(12, parsed.episode)
    }

    @Test
    fun `编码小数与位深不会被当成集号`() {
        val parsed = FilenameParser.parse("Owarimonogatari - 03 [h.264][10bit].mkv")!!
        assertEquals("Owarimonogatari", parsed.title)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `季与集标记一起从标题清理`() {
        val parsed = FilenameParser.parse("剧集 第二季 第三集.mp4")!!
        assertEquals("剧集", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `没有扩展名的点分标题不会被截断`() {
        assertEquals("Show", FilenameParser.parse("Show.S01E02")!!.title)
        assertEquals(2, FilenameParser.parse("Show.S01E02")!!.episode)
    }

    @Test fun technicalChannelDimensionsAndChecksumAreNotEpisodes() {
        val parsed = FilenameParser.parse("Owarimonogatari [07][1920x1080][AAC 2.0][ABCDEF12].mkv")!!
        assertEquals("Owarimonogatari", parsed.title)
        assertEquals(7, parsed.episode)
    }

    @Test fun underscoreCodecTagsStillPublishCodec() {
        val parsed = FilenameParser.parse("[VCB-Studio] Nekomonogatari [02][x265_flac].mkv")!!
        assertEquals(2, parsed.episode)
        assertEquals("HEVC", parsed.codec)
    }

    @Test fun openingLabelDoesNotExposeSequelNumberAsEpisode() {
        assertNull(FilenameParser.parse("Show 2 NCOP [1080p].mkv"))
    }

    @Test fun saioMonogatariKeepsSubtitleAndEpisode() {
        val cat = FilenameParser.parse(
            "[SAIO-Raws] Nekomonogatari (Shiro) 01 [BD 1920x1080 HEVC-10bit OPUSx2 ASSx4].mkv",
        )!!
        assertEquals("Nekomonogatari Shiro", cat.title)
        assertEquals(1, cat.episode)
        assertEquals("HEVC", cat.codec)

        val ended = FilenameParser.parse(
            "[SAIO-Raws] Owarimonogatari 13 END [BD 1920x1080 HEVC-10bit OPUSx2 ASSx4].mkv",
        )!!
        assertEquals("Owarimonogatari", ended.title)
        assertEquals(13, ended.episode)

        val sequel = FilenameParser.parse(
            "[SAIO-Raws] Zoku Owarimonogatari 02 [BD 1920x1080 HEVC-10bit OPUSx3 ASSx4].mkv",
        )!!
        assertEquals("Zoku Owarimonogatari", sequel.title)
        assertEquals(2, sequel.episode)
    }

    @Test fun channelLayoutAndRecapAreNotEpisodes() {
        val parsed = FilenameParser.parse("终物语 03 AAC5.1.mkv")
        assertEquals("终物语", parsed?.title)
        assertEquals(3, parsed?.episode)
        assertNull(FilenameParser.parse("[SAIO-Raws] Nekomonogatari (Shiro) Recap 01 [BD 1920x1080 HEVC-10bit OPUS ASSx2].mkv")?.episode?.takeIf { it > 0 })
        assertNull(FilenameParser.parse("[SAIO-Raws] Zoku Owarimonogatari NCED 01 [BD 1920x1080 HEVC-10bit OPUSx2].mkv")?.episode?.takeIf { it > 0 })
        assertNull(FilenameParser.parse("[SAIO-Raws] Hanamonogatari NCOP [BD 1920x1080 HEVC-10bit OPUS].mkv"))
    }

    @Test fun hi10pAfterEpisodeDoesNotSwallowTheNumber() {
        val parsed = FilenameParser.parse("[VCB-Studio] Nekomonogatari Kuro [01][Hi10p_1080p][x264_flac].mkv")!!
        assertEquals("Nekomonogatari Kuro", parsed.title)
        assertEquals(1, parsed.episode)
        assertEquals("AVC", parsed.codec)
    }
}
