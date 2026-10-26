package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 8：容器嗅探单测——§12.4 规则表逐项（ftyp/EBML/RIFF/OggS/ID3/同步字）。
 */
class ContainerSnifferTest {

    private fun head(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    private fun text(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `ftyp 识别 MP4 与 M4A 分支`() {
        val mp4 = text("....ftypisom....") // 偏移 4 的 'ftyp' + brand isom
        assertEquals("video/mp4", ContainerSniffer.sniff(mp4))
        val m4a = text("....ftypM4A ....")
        assertEquals("audio/mp4", ContainerSniffer.sniff(m4a))
    }

    @Test
    fun `EBML 识别 Matroska 与 WebM`() {
        val ebml = byteArrayOf(
            0x1A.toByte(), 0x45, 0xDF.toByte(), 0xA3.toByte(),
        ) + text("matrixSeriez")
        assertEquals("video/x-matroska", ContainerSniffer.sniff(ebml))
        val webm = byteArrayOf(0x1A.toByte(), 0x45, 0xDF.toByte(), 0xA3.toByte()) + text("...webm...")
        assertEquals("video/webm", ContainerSniffer.sniff(webm))
    }

    @Test
    fun `RIFF 家族分派 WAVE 与 AVI`() {
        val wav = text("RIFF") + head(0, 0, 0, 0) + text("WAVE")
        assertEquals("audio/wav", ContainerSniffer.sniff(wav))
        val avi = text("RIFF") + head(0, 0, 0, 0) + text("AVI ")
        assertEquals("video/avi", ContainerSniffer.sniff(avi))
        val riff = text("RIFF") + head(0, 0, 0, 0) + text("JUNK")
        assertNull(ContainerSniffer.sniff(riff))
    }

    @Test
    fun `裸音频按魔数识别`() {
        assertEquals("audio/ogg", ContainerSniffer.sniff(text("OggS....")))
        assertEquals("audio/flac", ContainerSniffer.sniff(text("fLaC....")))
        assertEquals("audio/midi", ContainerSniffer.sniff(text("MThd....")))
        assertEquals("audio/amr", ContainerSniffer.sniff(text("#!AMR\n")))
        assertEquals("audio/mpeg", ContainerSniffer.sniff(text("ID3\u0003\u0000")))
        assertEquals("audio/mpeg", ContainerSniffer.sniff(head(0xFF, 0xFB, 0x90, 0x00)))
    }

    @Test
    fun `E244 avformat 容器按魔数识别`() {
        assertEquals("video/x-nut", ContainerSniffer.sniff(text("NUTM....")))
        assertEquals("video/x-pn-realvideo", ContainerSniffer.sniff(text(".RMF....")))
        assertEquals("video/mpeg", ContainerSniffer.sniff(head(0, 0, 1, 0xBA)))
    }

    @Test
    fun `认不出的头返回 null`() {
        assertNull(ContainerSniffer.sniff(ByteArray(0)))
        assertNull(ContainerSniffer.sniff(head(1, 2, 3)))
        assertNull(ContainerSniffer.sniff(text("PK\u0003\u0004zip zip zip")))
    }

    @Test
    fun `needsSniff 只对缺失或非媒体 mime 为真`() {
        assertTrue(ContainerSniffer.needsSniff(null))
        assertTrue(ContainerSniffer.needsSniff(""))
        assertTrue(ContainerSniffer.needsSniff("application/octet-stream"))
        assertFalse(ContainerSniffer.needsSniff("video/mp4"))
        assertFalse(ContainerSniffer.needsSniff("audio/flac"))
    }
}
