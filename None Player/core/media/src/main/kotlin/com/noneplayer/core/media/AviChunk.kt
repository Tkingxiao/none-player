package com.noneplayer.core.media

/**
 * AVI chunk 头的纯计算（A1）。
 *
 * 放在 `core:media` 而不是留在解封装器里，是为了能按字节在 JVM 上测：正片级的 `movi`
 * （LIST，size = 4 + 整段媒体数据）与 OpenDML 写死的 `0xFFFFFFFF` 都需要**声明长度极大**
 * 的头，而真样本受 B17 的素材欠账限制拿不到——构造一个声明 4GB 的头只要 12 个字节。
 */
object AviChunk {

    /** DataSource 报不出长度（`C.LENGTH_UNSET` 之类）时的哨兵。 */
    const val UNKNOWN_LENGTH = -1L

    /** 一个 chunk 里的角色。扫描路径上每个 chunk 都要判一次，不再造 String。 */
    enum class Kind { VIDEO, AUDIO, OTHER }

    /** fourcc → 32 位打包值（小端）。两小时 25fps 是十几万次，每次一个 String 就是十几万个短命对象。 */
    fun fourcc(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun isFourcc(value: Int, first: Char, second: Char, third: Char, fourth: Char): Boolean =
        value == (first.code or (second.code shl 8) or (third.code shl 16) or (fourth.code shl 24))

    /**
     * `'00dc'` / `'01wb'` 的前两个字节是流号（按十六进制位读，与解封装器既有口径一致）；
     * 不是数字返回 null。
     */
    fun streamIndex(fcc: Int): Int? {
        val hi = hexDigit(fcc and 0xFF) ?: return null
        val lo = hexDigit((fcc ushr 8) and 0xFF) ?: return null
        return hi * 16 + lo
    }

    /** `'00dc'` 的后两个字节是类型：`dc` 压缩视频、`db` 未压缩视频、`wb` 压缩音频。 */
    fun chunkKind(fcc: Int): Kind =
        when ((fcc ushr 16) and 0xFFFF) {
            pair('d', 'c'), pair('d', 'b') -> Kind.VIDEO
            pair('w', 'b') -> Kind.AUDIO
            else -> Kind.OTHER
        }

    /**
     * LIST chunk 的结束位置：`chunkStart + 8 + size + pad(size)`，**夹到文件长度**。
     *
     * LIST 是容器不是叶子：`movi` 的 size 等于 4 加上整段媒体数据，正片几乎都超过
     * 任何"叶子 chunk 上限"，OpenDML 更直接写 `0xFFFFFFFF`。按声明值拒绝会让整部正片
     * 在解封装入口就出局——探测侧 0 条 format 判 `Unplayable`，播放侧报"封装格式暂无
     * 解封装支持"。长度未知时保持声明值（宁可多扫，不裁掉真数据）。
     */
    fun listEnd(chunkStart: Long, size: Long, length: Long): Long {
        val declared = chunkStart + 8 + size + pad(size)
        if (length == UNKNOWN_LENGTH || length <= 0L) return declared
        return declared.coerceAtMost(length)
    }

    /** 偶数长度不补齐，奇数长度后面有一个填充字节。 */
    fun pad(size: Long): Long = if (size % 2L == 0L) 0L else 1L

    /**
     * 未知 LIST 的下一个 chunk 位置：读过 4 字节 list type 之后，跳过**剩余** payload。
     *
     * E140 的回归点：真机上一版把这里算成 `position + (size + (size - 4))`——多算了一个
     * `size`，seek 直接越过 `movi`，WMV2 整片零格式（探测判"没有受支持的视频编码"）。
     * 提成纯函数就是为了让这段算术有单测钉着：`size` 是**含**那 4 字节 type 的。
     */
    fun nextAfterUnknownList(positionAfterType: Long, size: Long): Long =
        positionAfterType + (size - 4) + pad(size)

    private fun pair(first: Char, second: Char): Int = first.code or (second.code shl 8)

    private fun hexDigit(code: Int): Int? = when (code) {
        in '0'.code..'9'.code -> code - '0'.code
        in 'a'.code..'f'.code -> code - 'a'.code + 10
        in 'A'.code..'F'.code -> code - 'A'.code + 10
        else -> null
    }
}
