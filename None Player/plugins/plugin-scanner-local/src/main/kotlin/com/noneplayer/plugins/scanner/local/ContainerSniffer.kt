package com.noneplayer.plugins.scanner.local

/**
 * 文件头容器嗅探（§12.4「扩展名缺失」，批次 8）——无扩展名/错扩展名的文件按
 * 前若干字节判容器，选对 mimeType（进而选对 Extractor）。
 *
 * 触发面（[needsSniff]）：MediaStore 不索引非媒体文件，所以"无扩展名"的来源是
 * `MediaStore.Files` 集合里 `media_type = 0` 的行（需"所有文件访问"才可见，见
 * [LocalMediaSource] 的 sniffUnknownFiles）；"错扩展名"则来自已索引文件——
 * MediaStore 的 mimeType 也是按扩展名推的，扩展名错了它就跟着错。
 * 两类都嗅探（每文件只读 64 字节，远轻于缩略图抽帧）。
 *
 * 规则表与 §12.4 行一致；认不出的头返回 null（**保持 MediaStore 原值**，
 * 绝不因为"嗅探不到"就把一个已知 mime 打回 unknown）。
 */
internal object ContainerSniffer {

    /** 每个文件最多读多少头字节 */
    const val HEAD_BYTES = 64

    /**
     * 由文件头判 mimeType；无把握返回 null。
     * [head] 可短于 64 字节（小文件），空数组返回 null。
     */
    fun sniff(head: ByteArray): String? {
        if (head.size < 4) return null
        // ISO-BMFF：偏移 4 起是 "ftyp"（box 头 4 字节大小 + 'ftyp'）
        if (head.size >= 8 && head.slice(4..7).toByteArray().decodeToString() == "ftyp") {
            val brand = head.drop(8).take(4).map { it.toInt().toChar() }.joinToString("")
            return if (brand.startsWith("M4A")) "audio/mp4" else "video/mp4"
        }
        // EBML：Matroska / WebM（WebM 的 DocType 在头部几十字节内可寻得）
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) {
            val headText = head.decodeToString(0, head.size)
            return if (headText.contains("webm")) "video/webm" else "video/x-matroska"
        }
        // RIFF 家族：偏移 8 起 WAVE / AVI
        if (head.size >= 12 && head.decodeToString(0, 4) == "RIFF") {
            return when (head.decodeToString(8, 12)) {
                "WAVE" -> "audio/wav"
                "AVI " -> "video/avi"
                else -> null
            }
        }
        // MPEG-TS：0x47 同步字节（首包 + 第二包跨 188 字节双校验，降低误判）
        if (head[0] == 0x47.toByte() && (head.size < 189 || head[188] == 0x47.toByte())) {
            // 裸 0x47 开头也可能是别的二进制；要求至少见到同步字节对，短文件退而求其次
            if (head.size >= 189) return "video/mp2t"
        }
        val magic4 = head.decodeToString(0, 4) // 入口 26 行已保证 head.size >= 4
        return when {
            magic4 == "OggS" -> "audio/ogg"
            magic4 == "fLaC" -> "audio/flac"
            magic4 == "FLV\u0001" -> "video/x-flv"
            magic4 == "MThd" -> "audio/midi"
            head.size >= 5 && head.decodeToString(0, 5) == "#!AMR" -> "audio/amr"
            // MP3：ID3v2 头，或 MPEG 帧同步字（0xFFEx / 0xFFFx 的高 11 位）
            magic4.startsWith("ID3") -> "audio/mpeg"
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    /**
     * 该条目是否需要嗅探：MediaStore 给不出可信 mime（缺失 / 通用二进制 /
     * 扩展名不在媒体扩展名表内）时为 true；已知媒体 mime 交给「错扩展名校正」——
     * 调用方仍嗅探，但只有**结果与原值不同**才覆盖。
     */
    fun needsSniff(mimeType: String?): Boolean =
        mimeType.isNullOrBlank() ||
            mimeType.equals("application/octet-stream", true) ||
            !mimeType.startsWith("video/") && !mimeType.startsWith("audio/")
}
