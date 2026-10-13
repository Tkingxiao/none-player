package com.noneplayer.core.media.probe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12.9.2 方案①：QPel 的多解码器显式试探 harness。
 *
 * 三个实验，全部产出结构化日志（tag=QpelProbe）+ 报告文件：
 *  1. [enumerateMpeg4Decoders] — 枚举全部解码器，打印 mp4v 候选的 profile/level 声明；
 *  2. [qpelExplicitComponentProbe] — 绕过 findDecoderForFormat，按组件名逐个 create 并喂
 *     xvid-qpel.avi 码流；失败时再试"VOL header 放 csd-0"变体；同解码器跑 xvid-clip.avi 对照；
 *  3. [baselinePlaysViaDefaultSelection] — 对照组：findDecoderForFormat 默认路径解 xvid-clip
 *     必须成功（否则 harness 本身有问题，一切结论无效）。
 *
 * 判定口径（对齐 §12.9.2）：61 个输入样本全部解出（输出帧数 == 输入样本数）且全程无
 * CodecException → PASS；有输出但不全 → PARTIAL；其余 → FAIL（记录 errorCode/diagnosticInfo）。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class QpelCodecProbeTest {

    companion object {
        private const val TAG = "QpelProbe"
        private const val MIME_MP4V = "video/mp4v-es"
        private const val ASSET_QPEL = "xvid-qpel.avi"
        private const val ASSET_CLIP = "xvid-clip.avi"
        private const val TIMEOUT_US = 10_000L
        private const val TRIAL_DEADLINE_MS = 90_000L
        private const val STALL_MS = 15_000L
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val report = StringBuilder()

    private fun line(kind: String, body: String) {
        val text = "$kind|$body"
        Log.i(TAG, text)
        report.appendLine(text)
    }

    // ---------- 实验 1：枚举 ----------

    @Test
    fun enumerateMpeg4Decoders() {
        line("DEVICE", "model=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString("/")}")
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        for (info in infos) {
            val types = runCatching { info.supportedTypes.toList() }.getOrElse { listOf("<err:${it.message}>") }
            line(
                "ENUM",
                "decoder=${info.name}|hw=${info.isHardwareAcceleratedSafe()}|sw=${info.isSoftwareOnlySafe()}|vendor=${info.isVendorSafe()}|alias=${info.isAliasSafe()}|types=${types.joinToString(",")}",
            )
        }
        line("ENUM-MP4V", "---- video/mp4v-es candidates ----")
        for (info in infos) {
            val types = runCatching { info.supportedTypes.toList() }.getOrDefault(emptyList())
            val mime = types.firstOrNull { it.equals(MIME_MP4V, ignoreCase = true) } ?: continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
            if (caps == null) {
                line("ENUM-MP4V", "decoder=${info.name}|capabilities=<unavailable>")
                continue
            }
            val profileLevels = caps.profileLevels?.joinToString(",") {
                "${mpeg4ProfileName(it.profile)}/${mpeg4LevelName(it.level)}"
            } ?: "<none>"
            val vc = caps.videoCapabilities
            val size = if (vc != null) "${vc.supportedWidths.lower}x${vc.supportedHeights.lower}-${vc.supportedWidths.upper}x${vc.supportedHeights.upper}" else "?"
            line(
                "ENUM-MP4V",
                "decoder=${info.name}|hw=${info.isHardwareAcceleratedSafe()}|alias=${info.isAliasSafe()}|size=$size|profileLevels=$profileLevels",
            )
        }
        flushReport("enum")
    }

    // ---------- 实验 2：逐个显式试探 ----------

    @Test
    fun qpelExplicitComponentProbe() {
        line("DEVICE", "model=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        val qpel = demux(ASSET_QPEL)
        val clip = demux(ASSET_CLIP)
        line(
            "DEMUX",
            "asset=$ASSET_QPEL mime=${qpel.format.sampleMimeType} ${qpel.format.width}x${qpel.format.height} samples=${qpel.samples.size} csd=${qpel.format.initializationData.size}B",
        )
        line(
            "DEMUX",
            "asset=$ASSET_CLIP mime=${clip.format.sampleMimeType} ${clip.format.width}x${clip.format.height} samples=${clip.samples.size} csd=${clip.format.initializationData.size}B",
        )
        val qFirst = qpel.samples.first().data
        val cFirst = clip.samples.first().data
        line("DEMUX-HEX", "qpel.firstSample.size=${qFirst.size} head=${qFirst.take(32).joinToString(" ") { "%02x".format(it) }} startCodes=[${scanStartCodes(qFirst).joinToString(",")}]")
        line("DEMUX-HEX", "clip.firstSample.size=${cFirst.size} head=${cFirst.take(32).joinToString(" ") { "%02x".format(it) }} startCodes=[${scanStartCodes(cFirst).joinToString(",")}]")

        val candidates = mpeg4DecoderNames()
        line("CANDIDATES", candidates.joinToString(","))

        var anyPass = false
        for (name in candidates) {
            // 主试探：与 ExoPlayer 相同的喂法（VOL header 随帧内联，无 csd-0）
            val r1 = runTrial(name, qpel, useCsd = false)
            line("TRY", "codec=$name|clip=qpel|mode=inline|$r1")
            var passed = r1.startsWith("PASS")
            // 变体：VOL header 提出来放 csd-0（§12.9.2 第 4 步，仅主试探失败时做）
            if (!passed) {
                val r2 = runTrial(name, qpel, useCsd = true)
                line("TRY", "codec=$name|clip=qpel|mode=csd0|$r2")
                passed = r2.startsWith("PASS")
            }
            // 对照：同一解码器解 xvid-clip（SP 基线，§12.9.2 第 3 步）
            val rb = runTrial(name, clip, useCsd = false)
            line("TRY", "codec=$name|clip=baseline-sp|mode=inline|$rb")
            anyPass = anyPass || passed
        }
        line("VERDICT", "qpel-any-decoder-pass=$anyPass")
        flushReport("qpel")
    }

    // ---------- 实验 3：对照组必须能播（harness 自检） ----------

    @Test
    fun baselinePlaysViaDefaultSelection() {
        val clip = demux(ASSET_CLIP)
        val format = MediaFormat.createVideoFormat(MIME_MP4V, clip.format.width, clip.format.height)
        val picked = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        line("BASELINE", "findDecoderForFormat picked=$picked")
        val r = runTrial(picked ?: error("no mp4v decoder"), clip, useCsd = false)
        line("BASELINE", "clip=baseline-sp|$r")
        flushReport("baseline")
        assertTrue("对照组 xvid-clip.avi 必须能被默认解码器解出，实际：$r", r.startsWith("PASS"))
    }

    // ---------- 解码试探 ----------

    private fun runTrial(codecName: String, demuxed: Demuxed, useCsd: Boolean): String {
        val samples: List<Sample>
        val format = MediaFormat.createVideoFormat(
            MIME_MP4V,
            demuxed.format.width.coerceAtLeast(1),
            demuxed.format.height.coerceAtLeast(1),
        )
        if (useCsd) {
            // 从首帧里切出 VOP 起始码（00 00 01 b6）之前的头作为 csd-0
            val first = demuxed.samples.first().data
            val vopAt = indexOfVopStartCode(first)
            line("TRY-CSD-DBG", "codec=$codecName firstSize=${first.size} vopAt=$vopAt scan=${scanStartCodes(first).joinToString(",")}")
            if (vopAt <= 0) return "SKIP reason=no-vop-start-code-in-first-sample"
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(first.copyOfRange(0, vopAt)))
            val rest = first.copyOfRange(vopAt, first.size)
            samples = listOf(demuxed.samples.first().copy(data = rest)) + demuxed.samples.drop(1)
        } else {
            // 如实记录容器是否自带 csd；XVID AVI 通常没有（头在帧内）
            demuxed.format.initializationData.firstOrNull()?.let {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it))
            }
            samples = demuxed.samples
        }

        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (t: Throwable) {
            return "FAIL stage=create error=${t.javaClass.simpleName}:${t.message}"
        }
        var frames = 0
        var outputBytes = 0L
        var formatChanges = 0
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputIndex = 0
            var inputDone = false
            var outputDone = false
            var lastProgress = System.currentTimeMillis()
            val deadline = lastProgress + TRIAL_DEADLINE_MS
            while (!outputDone) {
                val now = System.currentTimeMillis()
                if (now > deadline) return "FAIL stage=loop error=DEADLINE frames=$frames/$inputIndex"
                if (now - lastProgress > STALL_MS) return "FAIL stage=loop error=STALLED frames=$frames/$inputIndex"

                if (!inputDone) {
                    when (val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)) {
                        -1 -> Unit // TRY_AGAIN_LATER
                        else -> {
                            lastProgress = System.currentTimeMillis()
                            val buf = codec.getInputBuffer(inIdx)
                                ?: return "FAIL stage=input error=null-buffer"
                            if (inputIndex < samples.size) {
                                val s = samples[inputIndex]
                                buf.clear()
                                if (s.data.size > buf.capacity()) {
                                    return "FAIL stage=input error=sample-too-big(${s.data.size}>${buf.capacity()})"
                                }
                                buf.put(s.data)
                                codec.queueInputBuffer(
                                    inIdx, 0, s.data.size, s.timeUs,
                                    if (s.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                                )
                                inputIndex++
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        formatChanges++
                        line("TRY-FMT", "codec=$codecName outFormat=${codec.outputFormat}")
                    }
                    else -> {
                        lastProgress = System.currentTimeMillis()
                        if (info.size > 0) {
                            frames++
                            outputBytes += info.size
                            codec.releaseOutputBuffer(outIdx, false)
                        } else {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return if (frames == samples.size) {
                "PASS frames=$frames/${samples.size} bytes=$outputBytes fmtChanges=$formatChanges"
            } else {
                "PARTIAL frames=$frames/${samples.size} bytes=$outputBytes fmtChanges=$formatChanges"
            }
        } catch (e: MediaCodec.CodecException) {
            return "FAIL stage=codec error=CodecException(0x${Integer.toHexString(e.errorCode)})" +
                " recoverable=${e.isRecoverable} transient=${e.isTransient}" +
                " diag=${e.diagnosticInfo} frames=$frames"
        } catch (t: Throwable) {
            return "FAIL stage=config/loop error=${t.javaClass.simpleName}:${t.message} frames=$frames"
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ---------- 解封装（与播放同源的 media3 AviExtractor） ----------

    private class Sample(val data: ByteArray, val timeUs: Long, val keyframe: Boolean) {
        fun copy(data: ByteArray) = Sample(data, timeUs, keyframe)
    }

    private class Demuxed(val format: Format, val samples: List<Sample>)

    private fun demux(assetName: String): Demuxed {
        val context = instrumentation.context
        val file = File(instrumentation.targetContext.cacheDir, assetName)
        context.assets.open(assetName).use { src -> file.outputStream().use { src.copyTo(it) } }

        val uri = Uri.fromFile(file)
        val dataSource = FileDataSource()
        dataSource.open(DataSpec(uri))
        var input = DefaultExtractorInput(dataSource, 0, file.length())
        val extractor = AviExtractor()
        val collector = VideoCollector()
        extractor.init(collector)
        val holder = PositionHolder()
        while (true) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) {
                dataSource.close()
                dataSource.open(DataSpec(uri, holder.position, C.LENGTH_UNSET.toLong()))
                input = DefaultExtractorInput(dataSource, holder.position, file.length() - holder.position)
            }
        }
        extractor.release()
        runCatching { dataSource.close() }
        val format = collector.videoFormat ?: error("no video track in $assetName")
        return Demuxed(format, collector.samples)
    }

    private class VideoCollector : ExtractorOutput {
        var videoFormat: Format? = null
        val samples = mutableListOf<Sample>()
        private val pending = ByteArrayOutputStream()

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_VIDEO) return DiscardingTrackOutput()
            return object : TrackOutput {
                override fun format(format: Format) {
                    videoFormat = format
                }

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val buf = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(buf, read, length - read)
                        if (n == -1) {
                            if (allowEndOfInput) return read
                            error("unexpected EOF in sampleData read=$read/$length")
                        }
                        read += n
                    }
                    pending.write(buf, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    pending.write(data.data, data.position, length)
                    data.skipBytes(length)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    val all = pending.toByteArray()
                    val bytes = when {
                        offset == 0 && size == all.size -> all
                        size in 1..(all.size - offset).coerceAtLeast(0) -> all.copyOfRange(offset, offset + size)
                        else -> all
                    }
                    samples.add(Sample(bytes, timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0))
                    pending.reset()
                }
            }
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    // ---------- 工具 ----------

    private fun mpeg4DecoderNames(): List<String> {
        val names = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && !it.isAliasSafe() }
            .filter { info ->
                runCatching { info.supportedTypes.any { it.equals(MIME_MP4V, true) } }.getOrDefault(false)
            }
            .map { it.name }
        // §12.9.2 指定顺序：qti → c2.android → 其它
        return names.sortedWith(
            compareBy(
                { if (it.contains("qti", true)) 0 else if (it.startsWith("c2.android", true)) 1 else 2 },
                { it },
            ),
        )
    }

    private fun indexOfVopStartCode(data: ByteArray): Int {
        // Kotlin Byte.toInt() 是有符号扩展；这里所有值都按无符号字节比较
        for (i in 0 until data.size - 3) {
            if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1 && (data[i + 3].toInt() and 0xFF) == 0xB6
            ) {
                return i
            }
        }
        return -1
    }

    /** 列出所有 00 00 01 XX 起始码的位置与 XX 值，前 10 个。诊断 csd-0 切分用。 */
    private fun scanStartCodes(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < data.size - 3 && out.size < 10) {
            if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1
            ) {
                out.add("@$i=0x%02x".format(data[i + 3].toInt() and 0xFF))
                i += 4
            } else i++
        }
        return out
    }

    private fun mpeg4ProfileName(profile: Int): String = when (profile) {
        0x01 -> "Simple"
        0x02 -> "SimpleScalable"
        0x04 -> "Core"
        0x08 -> "Main"
        0x10 -> "NBit"
        0x20 -> "ScalableTexture"
        0x40 -> "SimpleFace"
        0x80 -> "SimpleFBA"
        0x100 -> "BasicAnimated"
        0x200 -> "Hybrid"
        0x400 -> "AdvancedRealTime"
        0x800 -> "CoreScalable"
        0x1000 -> "AdvancedCoding"
        0x2000 -> "AdvancedCore"
        0x4000 -> "AdvancedScalable"
        0x8000 -> "AdvancedSimple"
        else -> "0x${Integer.toHexString(profile)}"
    }

    private fun mpeg4LevelName(level: Int): String = when (level) {
        0x01 -> "L0"
        0x02 -> "L0b"
        0x04 -> "L1"
        0x08 -> "L2"
        0x10 -> "L3"
        0x18 -> "L3b"
        0x20 -> "L4"
        0x40 -> "L4a"
        0x80 -> "L5"
        0x100 -> "L6"
        else -> "0x${Integer.toHexString(level)}"
    }

    private fun MediaCodecInfo.isHardwareAcceleratedSafe(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else !name.startsWith("OMX.google", true)

    private fun MediaCodecInfo.isSoftwareOnlySafe(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isSoftwareOnly else name.startsWith("OMX.google", true)

    private fun MediaCodecInfo.isVendorSafe(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isVendor else false

    private fun MediaCodecInfo.isAliasSafe(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isAlias else false

    private fun flushReport(label: String) {
        val dir = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        val file = File(dir, "qpel-probe-$label.txt")
        runCatching {
            file.writeText(report.toString())
            line("REPORT", "written=${file.absolutePath}")
            file.writeText(report.toString())
        }
        report.clear()
    }
}
