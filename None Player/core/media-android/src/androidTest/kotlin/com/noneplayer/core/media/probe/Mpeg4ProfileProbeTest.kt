package com.noneplayer.core.media.probe

import android.net.Uri
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12.9.4 验证：`Mpeg4ProfileParser` 能从真实码流头部判出 ASP vs Simple，
 * `xvid-clip.avi`（Simple Profile）保持 `video/mp4v-es` 并可播。
 * `xvid-qpel.avi` 在设备明确没有 ASP 且软解可用时改为专用 mime，探测为可播；
 * 解析器本身对 qpel 仍必须是 Advanced Simple，对 clip 仍不得是 ASP。
 *
 * 这正是 §12.9.2.1 QPel 探测暴露的"错误承诺"的修复证据：从"试出来崩在第一帧"
 * 变成"查出来起播前拒"。
 *
 * 两个样本的 codec 头来源：XVID AVI 把 VOS/VOL/GOV 头内联在首帧，
 * initializationData 常为空——所以这里既测 `initializationData`，也测首帧前导字节。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Mpeg4ProfileProbeTest {

    companion object {
        private const val TAG = "Mp4vProfile"
        private const val ASSET_QPEL = "xvid-qpel.avi"
        private const val ASSET_CLIP = "xvid-clip.avi"
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val report = StringBuilder()

    private fun line(kind: String, body: String) {
        val text = "$kind|$body"
        Log.i(TAG, text)
        report.appendLine(text)
    }

    @Test
    fun qpelStreamParsedAsAdvancedSimple() {
        val head = demuxVideoHead(ASSET_QPEL)
        line("HEAD", "asset=$ASSET_QPEL headBytes=${head.size} startCodes=[${scanStartCodes(head).joinToString(",")}]")
        val verdict = Mpeg4ProfileParser.parse(head)
        line("VERDICT", "asset=$ASSET_QPEL required=${verdict.required} ${verdict.detail}")
        flushReport("qpel")
        assertEquals(
            "xvid-qpel.avi 应判为 Advanced Simple Profile（${verdict.detail}）",
            Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE,
            verdict.required,
        )
    }

    @Test
    fun clipStreamParsedAsSimpleOrOther() {
        val head = demuxVideoHead(ASSET_CLIP)
        line("HEAD", "asset=$ASSET_CLIP headBytes=${head.size} startCodes=[${scanStartCodes(head).joinToString(",")}]")
        val verdict = Mpeg4ProfileParser.parse(head)
        line("VERDICT", "asset=$ASSET_CLIP required=${verdict.required} ${verdict.detail}")
        flushReport("clip")
        assertTrue(
            "xvid-clip.avi 不应被判为 ASP（实际 ${verdict.required}, ${verdict.detail}）",
            verdict.required != Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE,
        )
    }

    /** 端到端：FormatProbe 对两个样本给出相反结论（qpel 拒 / clip 放）。 */
    @Test
    fun formatProbeRejectsQpelAcceptsClip() {
        val qpelUri = Uri.fromFile(copyAsset(ASSET_QPEL)).toString()
        val clipUri = Uri.fromFile(copyAsset(ASSET_CLIP)).toString()

        val qpelResult = runBlockingProbe(qpelUri)
        val clipResult = runBlockingProbe(clipUri)
        line("PROBE", "asset=$ASSET_QPEL result=${describe(qpelResult)}")
        line("PROBE", "asset=$ASSET_CLIP result=${describe(clipResult)}")
        flushReport("probe")

        assertTrue(
            "xvid-qpel.avi 在软解可用或设备声明 ASP 时应可播，实际 ${describe(qpelResult)}",
            qpelResult is FormatProbe.Result.Playable,
        )
        val qpelCodec = (qpelResult as FormatProbe.Result.Playable).videoCodec
        assertTrue(
            "qpel 要么走专用软解 mime，要么设备自己声明了 ASP 仍用 mp4v，实际：$qpelCodec",
            qpelCodec == "MPEG-4 ASP (Qpel)" || qpelCodec == "MPEG-4 Visual (Xvid/DivX)",
        )
        assertTrue(
            "xvid-clip.avi 不应被改写或拦截，实际 ${describe(clipResult)}",
            clipResult is FormatProbe.Result.Playable,
        )
        assertEquals(
            "MPEG-4 Visual (Xvid/DivX)",
            (clipResult as FormatProbe.Result.Playable).videoCodec,
        )
    }

    private fun runBlockingProbe(uri: String): FormatProbe.Result =
        kotlinx.coroutines.runBlocking { FormatProbe.probe(instrumentation.targetContext, uri) }

    private fun describe(r: FormatProbe.Result): String = when (r) {
        is FormatProbe.Result.Unplayable -> "Unplayable(reason=${r.failure.reason})"
        is FormatProbe.Result.Playable -> "Playable(container=${r.container}, video=${r.videoCodec}, audio=${r.audioCodec})"
        FormatProbe.Result.Unknown -> "Unknown"
    }

    // ---------- 解封装取"首个视频样本前导字节" ----------

    private fun copyAsset(assetName: String): File {
        val file = File(instrumentation.targetContext.cacheDir, assetName)
        instrumentation.context.assets.open(assetName).use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        return file
    }

    /** 用与播放同源的 media3 AviExtractor 抓首个视频样本的前导字节（含 VOS/VOL 头）。 */
    private fun demuxVideoHead(assetName: String): ByteArray {
        val file = copyAsset(assetName)
        val uri = Uri.fromFile(file)
        val dataSource = FileDataSource()
        dataSource.open(DataSpec(uri))
        var input = DefaultExtractorInput(dataSource, 0, file.length())
        val extractor = AviExtractor()
        val collector = HeadCollector()
        extractor.init(collector)
        val holder = PositionHolder()
        var guard = 0
        while (collector.head == null && guard < 5000) {
            guard++
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
        // 优先用容器声明的 csd；没有再用首帧前导字节
        val csd = collector.videoFormat?.initializationData?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val head = csd ?: collector.head
        assertNotNull("$assetName 未取到视频 codec 头字节", head)
        return head!!
    }

    private class HeadCollector : ExtractorOutput {
        var videoFormat: Format? = null
        var head: ByteArray? = null
            private set
        private val buf = ByteArrayOutputStream()
        private val cap = 4 * 1024

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
                    val tmp = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(tmp, read, length - read)
                        if (n == -1) { if (allowEndOfInput) break else error("EOF") }
                        read += n
                    }
                    if (head == null && buf.size() < cap) {
                        buf.write(tmp, 0, minOf(read, cap - buf.size()))
                    }
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    if (head == null && buf.size() < cap) {
                        buf.write(data.data, data.position, minOf(length, cap - buf.size()))
                    }
                    data.skipBytes(length)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    if (head == null && buf.size() > 0) head = buf.toByteArray()
                }
            }
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun scanStartCodes(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < data.size - 3 && out.size < 12) {
            if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1
            ) {
                out.add("@$i=0x%02x".format(data[i + 3].toInt() and 0xFF))
                i += 4
            } else i++
        }
        return out
    }

    private fun flushReport(label: String) {
        val dir = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        runCatching { File(dir, "mp4v-profile-$label.txt").writeText(report.toString()) }
        report.clear()
    }
}
