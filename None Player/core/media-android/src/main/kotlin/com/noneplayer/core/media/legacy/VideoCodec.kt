package com.noneplayer.core.media.legacy

import android.view.Surface
import java.nio.ByteBuffer

/**
 * 视频软解 codec 的统一口面（E204 白名单批抽出来）。
 *
 * [LegacyVideoRenderer] 原本硬编码 [LegacyCodec]（libnplegacy 静态最小 avcodec）；
 * 抽出接口后同一渲染器可以驱动白名单批新增的 [com.noneplayer.core.media.ffmpeg.NpFfmpegVideoCodec]
 * （NextLib 共享 libavcodec 后端），行为语义（send/receive/isEof/flush/render/close）
 * 两个实现完全一致。
 */
interface VideoCodec : AutoCloseable {
    /** 送一个 packet；direct buffer 直送 JNI，非 direct 由实现兜底复制。 */
    fun send(data: ByteBuffer?, ptsUs: Long, eos: Boolean)

    /** 取一帧解码输出；无输出返回 null。 */
    fun receive(): LegacyFrame?

    /** 解码器已把 EOS 前的所有帧交出来。 */
    val isEof: Boolean

    fun flush()

    fun render(
        y: ByteBuffer,
        yStride: Int,
        u: ByteBuffer,
        uvStride: Int,
        v: ByteBuffer,
        width: Int,
        height: Int,
        colorspace: Int,
        matrix: Int,
        surface: Surface,
    )
}
