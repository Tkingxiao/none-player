package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCharsetTest {

    private val sample = """
        1
        00:00:05,000 --> 00:00:15,000
        字幕链路测试：第一行
        第二行对齐检查

        2
        00:00:20,000 --> 00:00:30,000
        中文标点、English mixed、数字 12345
    """.trimIndent()

    @Test
    fun `utf8 直通不重写`() {
        val result = SubtitleCharset.decode(sample.toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", result.charsetName)
        assertFalse(result.needsRewrite)
        assertTrue(result.text.contains("字幕链路测试"))
    }

    @Test
    fun `utf8 带 BOM 去 BOM 并重写`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        val result = SubtitleCharset.decode(bytes)
        assertTrue(result.needsRewrite)
        assertTrue(result.text.startsWith("1"))
        assertTrue(result.text.contains("字幕链路测试"))
    }

    @Test
    fun `gbk 正确识别并转出中文`() {
        val result = SubtitleCharset.decode(sample.toByteArray(charset("GBK")))
        assertEquals("GB18030", result.charsetName)
        assertTrue(result.needsRewrite)
        assertTrue(result.text.contains("字幕链路测试"))
        assertTrue(result.text.contains("数字 12345"))
    }

    @Test
    fun `big5 正确识别并转出繁体`() {
        val traditional = "1\n00:00:01,000 --> 00:00:02,000\n繁體字幕測試：萬歲\n"
        val result = SubtitleCharset.decode(traditional.toByteArray(charset("Big5")))
        assertTrue(result.needsRewrite)
        assertTrue("实际: ${result.text}", result.text.contains("繁體字幕測試"))
    }

    @Test
    fun `utf16 小端转出中文`() {
        val result = SubtitleCharset.decode(sample.toByteArray(Charsets.UTF_16LE))
        // UTF-16LE 无 BOM 时字节流含 \u0000，严格 UTF-8 会失败 → 走中文编码试探或兜底
        assertTrue(result.text.contains("字幕") || result.charsetName.contains("UTF-16"))
    }
}
