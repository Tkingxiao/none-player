package com.noneplayer.core.media.ffmpeg

import android.os.SystemClock
import android.util.Log
import com.noneplayer.core.media.MediaNames

/**
 * 白名单解码路由表（E204 解码白名单批）——mime → FFmpeg 解码器名的**唯一来源**。
 *
 * 播放链（NpFfmpegVideoRenderer / NpFfmpegAudioRenderer）与探测侧（FormatProbe）
 * 都查这张表，两边天然同源；表里的解码器是否真的编译进了随包的共享 libavcodec，
 * 由 [hasDecoder] 在运行时用 `avcodec_find_decoder_by_name` 复核（表只是意图，库才是真相）。
 *
 * **只收当前有解封装通路的 mime**（AVI 经 Wmv2AviExtractor/Media3Avi、MKV 的 VC-1）；
 * prores/theora/tta 等解码器虽已编译进共享库，但现有提取器不产出对应 mime——
 * 等 §9.3 的 avformat 批接上后再入表（提前入表只会让探测对"解不出来"的容器误报可播）。
 *
 * libnplegacy 保持在 WMV2 / MPEG-4 ASP 两个专用 mime 上不动（既定裁定）。
 */
object NpFfmpegCodecs {

    private const val TAG = "NpFfmpeg"

    private const val RETRY_MS = 30_000L

    /** mime → 解码器名（视频）。WVC1 是 VC-1 Advanced Profile 的 AVI/MKV fourcc。 */
    private val videoCodecs: Map<String, String> = mapOf(
        MediaNames.MIME_WMV3 to "wmv3",
        MediaNames.MIME_WMV1 to "wmv1",
        MediaNames.MIME_VC1 to "vc1",
        "video/mp42" to "msmpeg4v2",
        "video/mp43" to "msmpeg4", // FFmpeg 里 v3 解码器的注册名就是 msmpeg4（历史别名）
    )

    /** mime → 解码器名（音频，codec 名还依赖 extradata 里的 WAVE tag 时给 null）。 */
    private val audioCodecs: Map<String, String?> = mapOf(
        MediaNames.MIME_WMA to null, // wmav1/wmav2 按 extradata 的 tag 细分
        MediaNames.MIME_WMAPRO to "wmapro",
        MediaNames.MIME_WMALOSSLESS to "wmalossless",
    )

    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var failedAtElapsed = 0L

    /** 库是否加载成功（JVM 单测无 native，恒 false——探测与渲染器据此跳过）。 */
    fun isAvailable(): Boolean {
        loaded?.let { cached ->
            if (cached) return true
            if (SystemClock.elapsedRealtime() - failedAtElapsed < RETRY_MS) return false
        }
        val ok = try {
            System.loadLibrary("npffmpeg")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libnpffmpeg 加载失败，白名单软解不可用：${t.javaClass.simpleName}: ${t.message}")
            false
        }
        synchronized(this) {
            loaded = ok
            failedAtElapsed = if (ok) 0L else SystemClock.elapsedRealtime()
        }
        if (ok) {
            Log.i(TAG, "libnpffmpeg loaded ok")
            val names = videoCodecs.values.toSet() + audioCodecs.values.filterNotNull()
            names.forEach { name ->
                val has = runCatching { NpFfmpegNative.npffHasDecoder(name) }.getOrDefault(false)
                Log.i(TAG, "npff hasDecoder($name)=$has")
            }
        }
        return ok
    }

    /** A19 同款：失败后允许诊断入口主动重试。 */
    fun reset() {
        synchronized(this) {
            loaded = null
            failedAtElapsed = 0L
        }
    }

    /** mime 是否在本表（不含运行时复核；渲染器认领用「表 + 库可用」即可）。 */
    fun inTable(mime: String?): Boolean =
        mime != null && (videoCodecs.containsKey(mime) || audioCodecs.containsKey(mime))

    /** 表查询（纯内存，无可用性闸）——测试与诊断用它钉「表内容」本身。 */
    internal fun videoCodecInTable(mime: String?): String? = videoCodecs[mime]

    internal fun audioCodecInTable(mime: String?, initializationData: List<ByteArray>): String? {
        // 注意不能用 `audioCodecs[mime] ?: return null`：WMA 的表值**就是** null
        // （tag 细分留给这里），elvis 会把"值是 null"误判成"键不存在"。
        if (mime == null || !audioCodecs.containsKey(mime)) return null
        val fromTable = audioCodecs[mime]
        return fromTable ?: run {
            val tag = initializationData.firstOrNull()?.takeIf { it.size >= 2 }?.let { bytes ->
                (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            } ?: 0x0161
            when (tag) {
                0x0160 -> "wmav1"
                0x0161 -> "wmav2"
                0x0162 -> "wmapro"
                0x0163 -> "wmalossless"
                else -> "wmav2"
            }
        }
    }

    /** 视频 mime → 解码器名；不在表、库不可用或共享库没编该解码器时返回 null。 */
    fun videoCodecName(mime: String?): String? =
        videoCodecInTable(mime)?.takeIf { isAvailable() && hasDecoder(it) }

    /**
     * 音频 mime → 解码器名。`audio/x-ms-wma` 一族按 extradata 前两字节的 WAVE format tag
     * 细分（0x160=wmav1、0x161=wmav2；Media3Avi 对整个 wma 族只发这一个 mime，
     * 真正的编码身份在 WAVEFORMATEX 里）。无 extradata 时按最常见的 wmav2 兜底。
     */
    fun audioCodecName(mime: String?, initializationData: List<ByteArray>): String? =
        audioCodecInTable(mime, initializationData)?.takeIf { isAvailable() && hasDecoder(it) }

    /** 诊断口径：共享库是否真的编译了这个解码器（不查表、不判可用性）。 */
    fun hasDecoder(codecName: String): Boolean =
        runCatching { NpFfmpegNative.npffHasDecoder(codecName) }.getOrDefault(false)
}
