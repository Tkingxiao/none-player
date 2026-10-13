package com.noneplayer.core.media

import java.io.InputStream

/**
 * 从 AVI 文件头读时长。
 *
 * MediaStore 的 `DURATION` 对很多 AVI 是 0（系统抽不到时长），选集和卡片就会空白。
 * 只读开头 64KB 里的 `avih`（微秒/帧 × 总帧数），不够再看第一条 `strh`。
 * 不解码、不扫整个文件。
 */
object AviDuration {
    private const val PREFIX_BYTES = 64 * 1024

    fun read(input: InputStream): Long {
        val buf = ByteArray(PREFIX_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return fromPrefix(buf, n)
    }

    fun fromPrefix(bytes: ByteArray, length: Int = bytes.size): Long {
        val limit = length.coerceIn(0, bytes.size)
        val fromAvih = chunk(bytes, limit, "avih")?.let { data ->
            val usPerFrame = u32(bytes, data)
            val frames = u32(bytes, data + 16)
            if (usPerFrame in 1..5_000_000 && frames in 1..50_000_000) {
                (usPerFrame * frames + 500L) / 1000L
            } else {
                0L
            }
        } ?: 0L
        if (fromAvih > 0L) return fromAvih
        val fromStrh = chunk(bytes, limit, "strh")?.let { data ->
            val scale = u32(bytes, data + 20)
            val rate = u32(bytes, data + 24)
            val len = u32(bytes, data + 32)
            if (scale > 0L && rate > 0L && len > 0L) (len * scale * 1000L) / rate else 0L
        } ?: 0L
        return fromStrh
    }

    private fun chunk(bytes: ByteArray, limit: Int, fourcc: String): Int? {
        val a = fourcc[0].code.toByte()
        val b = fourcc[1].code.toByte()
        val c = fourcc[2].code.toByte()
        val d = fourcc[3].code.toByte()
        var i = 0
        while (i + 8 <= limit) {
            if (bytes[i] == a && bytes[i + 1] == b && bytes[i + 2] == c && bytes[i + 3] == d) {
                val size = u32(bytes, i + 4).toInt()
                val data = i + 8
                if (size >= 20 && data + 20 <= limit) return data
            }
            i++
        }
        return null
    }

    private fun u32(bytes: ByteArray, index: Int): Long {
        if (index < 0 || index + 4 > bytes.size) return -1L
        return (bytes[index].toLong() and 0xff) or
            ((bytes[index + 1].toLong() and 0xff) shl 8) or
            ((bytes[index + 2].toLong() and 0xff) shl 16) or
            ((bytes[index + 3].toLong() and 0xff) shl 24)
    }
}
