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
 * **只收当前有解封装通路的 mime**（AVI 经 Wmv2AviExtractor/Media3Avi、MKV 的 VC-1；
 * E207 起 avformat 五容器 asf/rm/flv/mpegps/nut 产出 FLV1/VP6/RV/FFV1/Theora/Real 系音频）。
 * prores/huffyuv 等 NUT 理论可载但无夹具验证的编码暂不映射——提取器不产出 mime，
 * 探测按"编码无法识别"诚实失败。
 *
 * E207 口径：**一个解码器一个 mime**。vp6/vp6f/vp6a、rv10–rv40、atrac3/al/pal 在
 * FFmpeg 是独立解码器 ID，提取器按流内编码名产出精确 mime（本表是唯一消费方），
 * 表项与流内编码名严格互逆——这比 WMA 族"一个 mime + extradata 细分"更简单直接
 * （WMA 那样做是因为 Media3Avi 的生产端不可改；本提取器是我们自己的）。
 * 表里的解码器是否真的编译进了随包的共享 libavcodec，
 * 由 [hasDecoder] 在运行时用 `avcodec_find_decoder_by_name` 复核（表只是意图，库才是真相）。
 *
 * libnplegacy 保持在 WMV2 / MPEG-4 ASP 两个专用 mime 上不动（既定裁定）。
 */
object NpFfmpegCodecs {

    private const val TAG = "NpFfmpeg"

    private const val RETRY_MS = 30_000L

    /** mime → 解码器名（视频）。WVC1 是 VC-1 Advanced Profile 的 AVI/MKV fourcc。
     * E207：avformat 五容器的编码按"一个解码器一个 mime"入表（见类注释）。 */
    private val videoCodecs: Map<String, String> = mapOf(
        MediaNames.MIME_WMV3 to "wmv3",
        MediaNames.MIME_WMV1 to "wmv1",
        MediaNames.MIME_VC1 to "vc1",
        "video/mp42" to "msmpeg4v2",
        "video/mp43" to "msmpeg4", // FFmpeg 里 v3 解码器的注册名就是 msmpeg4（历史别名）
        // FLV（NpFfmpegExtractor flv demuxer）。注意 FFmpeg 7.1 命名分裂：流的编码名
        // （avcodec_get_name，描述符表）是 "flv1"，而解码器注册名（find_decoder_by_name）是
        // "flv"——configure 组件名同为 "flv"（build-ffmpeg.sh 同坑，曾误写 flv1 被静默忽略）
        "video/x-flv1" to "flv",
        "video/x-vp6" to "vp6",
        "video/x-vp6f" to "vp6f",
        "video/x-vp6a" to "vp6a",
        // RM（rv demuxer）：四代 RealVideo 是四个独立解码器
        "video/x-pn-realvideo" to "rv10",
        "video/x-pn-realvideo2" to "rv20",
        "video/x-pn-realvideo3" to "rv30",
        "video/x-pn-realvideo4" to "rv40",
        // NUT
        "video/x-ffv1" to "ffv1",
        "video/theora" to "theora",
        // MPEG-1/2 视频（PS 容器）：平台 OMX mpeg2 在部分设备（SD860/MIUI）实测坏，
        // npff 软解为准——见 NpFfmpegExtractor.mimeForCodecName 注释
        "video/x-np-mpeg1" to "mpeg1video",
        "video/x-np-mpeg2" to "mpeg2video",
    )

    /** mime → 解码器名（音频，codec 名还依赖 extradata 里的 WAVE tag 时给 null）。 */
    private val audioCodecs: Map<String, String?> = mapOf(
        MediaNames.MIME_WMA to null, // wmav1/wmav2 按 extradata 的 tag 细分
        MediaNames.MIME_WMAPRO to "wmapro",
        MediaNames.MIME_WMALOSSLESS to "wmalossless",
        // E207：RM/NUT 的音频族（atrac3al/pal 提取器产出独立 mime 但不入表——
        // 没有对应路由的独立解码器，留给探测给 A11"音频不支持"提示）
        "audio/x-cook" to "cook",
        "audio/x-sipr" to "sipr",
        "audio/x-atrac3" to "atrac3",
        "audio/x-wavpack" to "wavpack",
        "audio/x-tta" to "tta",
        // PS 的 mp1/mp2（mp2 音频平台普遍无 L2 解码器、NextLib 也不含——
        // npff 软解补位；无人认领会导致媒体时钟冻结，E207 真机实录）
        "audio/mpeg-L1" to "mp1",
        "audio/mpeg-L2" to "mp2",
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

    /** WMA 三兄弟（wmav1/2 / wmapro / wmalossless）：csd 语义统一由 WAVEFORMATEX 承载。 */
    fun isWmaFamily(mime: String?): Boolean =
        mime == MediaNames.MIME_WMA || mime == MediaNames.MIME_WMAPRO ||
            mime == MediaNames.MIME_WMALOSSLESS

    /**
     * 从 csd 里识别「完整 WAVEFORMATEX」并取 nBlockAlign（字节 [12..13]）。
     *
     * 两种生产者的 csd 语义不同，必须区分：
     * - 本项目的 Wmv2AviExtractor / NpFfmpegExtractor：整段 WAVEFORMATEX（18B 头 + cbSize 尾）；
     * - Media3Avi：只发 cbSize 字节的 codec data（18B 头已被剥掉，也没有 tag/blockAlign）。
     *
     * 判定用 cbSize 自洽（`[16..17] == size - 18`）+ tag 在 WMA 区间，避免把任意 codec data
     * 的头两字节误读成 tag。没有完整头 = 拿不到 blockAlign（wmav1/wmav2/wmapro 解码器
     * open 必需），调用方应放弃解码而不是猜。
     */
    fun waveFormatExOf(initializationData: List<ByteArray>): WaveFormatEx? {
        val csd = initializationData.firstOrNull { it.isNotEmpty() } ?: return null
        return waveFormatExOf(csd)
    }

    internal fun waveFormatExOf(csd: ByteArray): WaveFormatEx? {
        if (csd.size < WAVEFORMATEX_HEADER_SIZE) return null
        val tag = u16(csd, 0)
        if (tag !in WMA_TAG_RANGE) return null
        if (u16(csd, WAVEFORMATEX_HEADER_SIZE - 2) != csd.size - WAVEFORMATEX_HEADER_SIZE) return null
        return WaveFormatEx(tag = tag, blockAlign = u16(csd, 12))
    }

    /** 是否有可用的完整 WAVEFORMATEX（决定 WMA 族能否真解码）。 */
    fun hasWaveFormatEx(initializationData: List<ByteArray>): Boolean =
        waveFormatExOf(initializationData) != null

    data class WaveFormatEx(val tag: Int, val blockAlign: Int)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private val WMA_TAG_RANGE = 0x0160..0x0163
    private const val WAVEFORMATEX_HEADER_SIZE = 18

    /** 表查询（纯内存，无可用性闸）——测试与诊断用它钉「表内容」本身。 */
    internal fun videoCodecInTable(mime: String?): String? = videoCodecs[mime]

    internal fun audioCodecInTable(mime: String?, initializationData: List<ByteArray>): String? {
        // 注意不能用 `audioCodecs[mime] ?: return null`：WMA 的表值**就是** null
        // （tag 细分留给这里），elvis 会把"值是 null"误判成"键不存在"。
        if (mime == null || !audioCodecs.containsKey(mime)) return null
        val fromTable = audioCodecs[mime]
        return fromTable ?: run {
            // 只在「完整 WAVEFORMATEX」或测试用的裸 tag（2 字节）时读 tag；Media3Avi 的
            // codec-data-only csd 没有 tag，按最常见的 wmav2 兜底（真实 blockAlign 缺失
            // 会在渲染器 supportsFormat 阶段被拒，不会带着猜的值进解码器）。
            val csd = initializationData.firstOrNull { it.isNotEmpty() }
            val tag = when {
                csd == null -> 0x0161
                csd.size == 2 -> u16(csd, 0)
                else -> waveFormatExOf(csd)?.tag ?: 0x0161
            }
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
