package com.noneplayer.core.media.probe

/**
 * MPEG-4 Part 2 (Visual) 码流的 **profile / level 判读器**（§12.9.4）。
 *
 * 为什么需要它：Android 的 `MediaCodecList.findDecoderForFormat` **只看 mimeType + 尺寸，
 * 不看 profile/level**（§12.9.2.1 实证）。于是对 Advanced Simple Profile（ASP，含 qpel /
 * global-motion-compensation 等 Simple Profile 没有的特性）的 `video/mp4v-es` 码流，系统
 * 会错误地报"有解码器"，实际第一帧就 `OMX_ErrorUnsupportedProfile (0x80001009)` 崩掉
 * （§12.1.1 真机 + §12.9.2.1 模拟器双重实证）。这是"承诺了却做不到"的**错误承诺**。
 *
 * 本解析器从码流自身的头部判定它**到底需要哪一档 profile**，供起播前探测与设备解码器声明
 * 的 `profileLevels` 求交，把"能播吗"从"试出来崩"变成"查出来拒"。
 *
 * 判读依据（ISO/IEC 14496-2）：
 *  1. **VOS header**（start code `00 00 01 B0`）后 1 字节 `profile_and_level_indication`——
 *     这是码流**自我声明**的 profile/level，最权威。映射见 Annex G Table G-1：
 *       0x01..0x08 → Simple Profile（L1..L0）
 *       0xF0..0xF5 → Advanced Simple Profile（L0..L5）
 *  2. **VOL header**（start code `00 00 01 2x`）里的 `quarter_sample` 标志——ASP 才有的
 *     1/4 像素运动补偿开关，是真正让 Simple-only 解码器拒绝的特性。作为 VOS 的佐证；
 *     VOS 缺失时作为兜底信号。解析深度大、字段多可选，采用 best-effort（失败不致命）。
 *
 * 原型来自 `D:\Code\Player\.audit\clips\volhdr.py`，此处是其 Kotlin 落地 + VOS 扩展。
 */
object Mpeg4ProfileParser {

    /** 码流需要的 MPEG-4 Visual profile 档位（只区分与设备能力相关的三类）。 */
    enum class RequiredProfile {
        /** Simple Profile：绝大多数设备都有解码器 */
        SIMPLE,

        /** Advanced Simple Profile：含 qpel/GMC；Simple-only 设备会在第一帧崩 */
        ADVANCED_SIMPLE,

        /** 其它档（Core/Main/N-bit…）或非 mp4v：不在本判读器的拦截范围 */
        OTHER,

        /** 头部不完整 / 解析失败：拿不准，调用方应 fail-open 放行 */
        UNKNOWN,
    }

    data class Verdict(
        val required: RequiredProfile,
        /** VOS 原始 profile_and_level_indication 字节（无 VOS 时为 null） */
        val profileLevelIndication: Int?,
        /** VOL quarter_sample 标志（未解析到为 null） */
        val quarterSample: Boolean?,
        /** 可上报/落日志的可读说明 */
        val detail: String,
    )

    /**
     * 从 **codec 头部字节**（csd-0 / initializationData 或首个视频样本的前导字节）判读。
     * 要求 [data] 含 VOS/VOL 起始码；XVID/DivX 的 AVI 常把这些头内联在首帧而非 csd-0，
     * 故调用方若 initializationData 为空，应传首个视频样本的前导字节。
     */
    fun parse(data: ByteArray): Verdict {
        if (data.size < 5) {
            return Verdict(RequiredProfile.UNKNOWN, null, null, "data too short (${data.size}B)")
        }

        // 1) VOS: 00 00 01 B0 后 1 字节 = profile_and_level_indication
        val vosAt = indexOfStartCode(data, 0xB0)
        val pli: Int? = if (vosAt in 0..(data.size - 5)) data[vosAt + 4].toInt() and 0xFF else null
        val vosProfile = pli?.let { profileFromIndication(it) }

        // 2) VOL: 00 00 01 2x (0x20..0x2F) → best-effort 解析 quarter_sample
        val quarterSample = runCatching { parseVolQuarterSample(data) }.getOrNull()

        val required = when {
            vosProfile == RequiredProfile.ADVANCED_SIMPLE -> RequiredProfile.ADVANCED_SIMPLE
            quarterSample == true -> RequiredProfile.ADVANCED_SIMPLE
            vosProfile == RequiredProfile.SIMPLE -> RequiredProfile.SIMPLE
            vosProfile == RequiredProfile.OTHER -> RequiredProfile.OTHER
            // 无 VOS、quarter_sample 也没解出来 → 拿不准
            else -> RequiredProfile.UNKNOWN
        }

        val detail = buildString {
            append("vos=")
            append(if (pli != null) "0x%02x(%s)".format(pli, vosProfile) else "absent")
            append(" qpel=")
            append(quarterSample?.toString() ?: "n/a")
        }
        return Verdict(required, pli, quarterSample, detail)
    }

    /** profile_and_level_indication → 粗档。Annex G Table G-1。 */
    private fun profileFromIndication(pli: Int): RequiredProfile = when (pli) {
        in 0x01..0x08 -> RequiredProfile.SIMPLE
        in 0xF0..0xF5 -> RequiredProfile.ADVANCED_SIMPLE
        else -> RequiredProfile.OTHER
    }

    /** 找 `00 00 01 <code>` 起始码，返回起始码首字节(00)的下标；找不到 -1。 */
    private fun indexOfStartCode(data: ByteArray, code: Int): Int {
        var i = 0
        val limit = data.size - 4
        while (i <= limit) {
            if ((data[i].toInt() and 0xFF) == 0 &&
                (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1 &&
                (data[i + 3].toInt() and 0xFF) == code
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /** 找 VOL 起始码（0x20..0x2F 任一），返回起始码首字节下标；找不到 -1。 */
    private fun indexOfVolStartCode(data: ByteArray): Int {
        var i = 0
        val limit = data.size - 4
        while (i <= limit) {
            if ((data[i].toInt() and 0xFF) == 0 &&
                (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1 &&
                (data[i + 3].toInt() and 0xFF) in 0x20..0x2F
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 解析 VOL header 直到 `quarter_sample`（ISO/IEC 14496-2 6.2.3）。
     * 字段众多且多可选，任一 marker 不符或读越界即抛异常由上层 best-effort 兜住。
     * 返回 null 表示该版本（verid==1）没有 quarter_sample 字段。
     */
    private fun parseVolQuarterSample(data: ByteArray): Boolean? {
        val volAt = indexOfVolStartCode(data)
        if (volAt < 0) return null
        val b = BitReader(data, (volAt + 4) * 8)

        b.u(1)                               // random_accessible_vol
        b.u(8)                               // video_object_type_indication
        val isLayerId = b.u(1)
        var verid = 1
        if (isLayerId == 1) {
            verid = b.u(4)                   // visual_object_layer_verid
            b.u(3)                           // priority
        }
        val aspect = b.u(4)
        if (aspect == 15) { b.u(8); b.u(8) } // par_width / par_height
        if (b.u(1) == 1) {                   // vol_control_parameters
            b.u(2)                           // chroma_format
            b.u(1)                           // low_delay
            if (b.u(1) == 1) {               // vbv_parameters
                b.u(15); b.marker()
                b.u(15); b.marker()
                b.u(15); b.marker()
                b.u(3)
                b.u(11); b.marker()
                b.u(15); b.marker()
            }
        }
        val shape = b.u(2)                   // video_object_layer_shape
        if (shape == 3) b.u(4)               // shape_extension (grayscale)
        b.marker()
        val tir = b.u(16)                    // vop_time_increment_resolution
        b.marker()
        if (b.u(1) == 1) {                   // fixed_vop_rate
            val bits = ceilLog2(tir)
            if (bits > 0) b.u(bits)          // fixed_vop_time_increment
        }

        if (shape == 0) {                    // rectangular
            b.marker(); b.u(13)              // width
            b.marker(); b.u(13)              // height
            b.marker()
        }
        b.u(1)                               // interlaced
        b.u(1)                               // obmc_disable
        val spriteEnable = if (verid == 1) b.u(1) else b.u(2)
        // sprite_enable: 1=static(STATIC), 2=GMC. static 需读 sprite 维度参数；GMC 不需要。
        if (spriteEnable == 1) {             // static sprite only (verid==1 的取值)
            b.u(13); b.marker()              // sprite_width
            b.u(13); b.marker()              // sprite_height
            b.u(13); b.marker()              // sprite_left
            b.u(13); b.marker()              // sprite_top
            b.u(6)                           // no_of_sprite_warping_points
            b.u(2)                           // sprite_warping_accuracy
            b.u(1)                           // sprite_brightness_change
            b.u(1)                           // low_latency_sprite_enable
        }
        if (verid != 1 && shape != 0) b.u(1) // sadct_disable
        val not8bit = b.u(1)
        if (not8bit == 1) { b.u(4); b.u(4) } // quant_precision / bits_per_pixel
        // grayscale shape 的额外字段（shape==3）本判读器不覆盖——这类几乎不存在于常规视频
        if (shape == 3) return null
        if (b.u(1) == 1) {                   // quant_type
            if (b.u(1) == 1) skipQuantMatrix(b)   // load_intra_quant_mat
            if (b.u(1) == 1) skipQuantMatrix(b)   // load_nonintra_quant_mat
        }
        return if (verid != 1) {
            b.u(1) == 1                      // quarter_sample
        } else {
            null                             // verid==1 没有该字段 → 必然不是 ASP
        }
    }

    /** load_quant_mat：最多 64 个 8-bit 值，遇到 0 提前结束。 */
    private fun skipQuantMatrix(b: BitReader) {
        var count = 0
        while (count < 64) {
            val v = b.u(8)
            count++
            if (v == 0) break
        }
    }

    private fun ceilLog2(n: Int): Int {
        if (n <= 1) return 1
        var bits = 0
        var v = n - 1
        while (v > 0) { bits++; v = v shr 1 }
        return bits
    }

    /** 大端位读取器（MPEG-4 位流）。越界抛 [IndexOutOfBoundsException]。 */
    private class BitReader(private val buf: ByteArray, startBit: Int) {
        private var pos = startBit

        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byteIndex = pos ushr 3
                if (byteIndex >= buf.size) throw IndexOutOfBoundsException("bit $pos / ${buf.size * 8}")
                val bit = (buf[byteIndex].toInt() ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }

        fun marker() = u(1)
    }
}
