package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A1：AVI chunk 头的纯计算。
 *
 * 这些用例钉的是"正片形态"——`movi` 的 LIST 声明长度超过任何叶子 chunk 上限、
 * OpenDML 直接写 `0xFFFFFFFF`。真样本受 B17 的素材欠账限制拿不到，但构造一个
 * 声明 4GB 的 `movi` 头只要 12 个字节，所以判定可以在 JVM 上做。
 */
class AviChunkTest {

    private fun fourccBytes(id: String): ByteArray = id.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `fourcc packs little endian and yields stream and kind`() {
        assertEquals(0, AviChunk.streamIndex(AviChunk.fourcc(fourccBytes("00dc"), 0)))
        assertEquals(1, AviChunk.streamIndex(AviChunk.fourcc(fourccBytes("01wb"), 0)))
        assertEquals(AviChunk.Kind.VIDEO, AviChunk.chunkKind(AviChunk.fourcc(fourccBytes("00dc"), 0)))
        assertEquals(AviChunk.Kind.VIDEO, AviChunk.chunkKind(AviChunk.fourcc(fourccBytes("00db"), 0)))
        assertEquals(AviChunk.Kind.AUDIO, AviChunk.chunkKind(AviChunk.fourcc(fourccBytes("01wb"), 0)))
        assertEquals(AviChunk.Kind.OTHER, AviChunk.chunkKind(AviChunk.fourcc(fourccBytes("LIST"), 0)))
        assertNull(AviChunk.streamIndex(AviChunk.fourcc(fourccBytes("LIST"), 0)))
    }

    @Test
    fun `isFourcc compares without building a string`() {
        val list = AviChunk.fourcc(fourccBytes("LIST"), 0)
        assertEquals(true, AviChunk.isFourcc(list, 'L', 'I', 'S', 'T'))
        assertEquals(false, AviChunk.isFourcc(list, 'm', 'o', 'v', 'i'))
    }

    @Test
    fun `feature length movi list is clamped to the file, not rejected`() {
        // 正片：movi 从 12 起、声明 1GB，文件实际只有 40MB
        val end = AviChunk.listEnd(chunkStart = 12L, size = 1L shl 30, length = 40L * 1024 * 1024)
        assertEquals(40L * 1024 * 1024, end)
    }

    @Test
    fun `opendml's 0xFFFFFFFF sentinel is clamped too`() {
        val end = AviChunk.listEnd(chunkStart = 12L, size = 0xFFFFFFFFL, length = 5_000_000L)
        assertEquals(5_000_000L, end)
    }

    @Test
    fun `unknown length keeps the declared end rather than truncating real data`() {
        val declared = AviChunk.listEnd(chunkStart = 12L, size = 1024L, length = AviChunk.UNKNOWN_LENGTH)
        assertEquals(12L + 8 + 1024, declared)
    }

    @Test
    fun `a list that already fits is left alone and odd sizes carry one pad byte`() {
        assertEquals(12L + 8 + 64L, AviChunk.listEnd(chunkStart = 12L, size = 64L, length = 10_000L))
        assertEquals(0L, AviChunk.pad(64L))
        assertEquals(1L, AviChunk.pad(63L))
    }

    @Test
    fun `unknown list skips only the remainder after the 4 byte type`() {
        // E140 的回归点：多算一个 size 就会越过后面的 movi，整片零格式。
        // size 含那 4 字节 type，所以这里只该再跳 size - 4（奇偶补齐与 size 一致）。
        assertEquals(100L + 96L, AviChunk.nextAfterUnknownList(positionAfterType = 100L, size = 100L))
        assertEquals(100L + 97L + 1L, AviChunk.nextAfterUnknownList(positionAfterType = 100L, size = 101L))
    }
}
