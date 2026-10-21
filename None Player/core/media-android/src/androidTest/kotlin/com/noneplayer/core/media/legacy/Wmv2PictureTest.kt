package com.noneplayer.core.media.legacy

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.extract.Wmv2AviExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WMV2 解码内容回归：样本是 ffmpeg testsrc 彩条，解出的第 0/8 帧既要"不空不花"，
 * 还要逐点命中六段纯色（花屏回归闸，见 [assertColorBars]），并把帧写成 PNG 留证。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Wmv2PictureTest {

    @Test
    fun decodedFramesAreNotFlatNoise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "np-wmv2.avi")
        instrumentation.context.assets.open("np-wmv2.avi").use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        assertTrue("libnplegacy 加载失败", LegacyDecoderLibrary.isAvailable())
        val samples = demux(file)
        assertTrue("no wmv2 samples", samples.packets.isNotEmpty())
        // FFmpeg 生成的 WMV2 AVI 在 strf 的 BITMAPINFOHEADER 之后带 4 字节
        // extradata（解码器 decode_ext_header 要用它裁定 j_type/abt/mbrl 等标志位）。
        // 提取器把它丢了就会花屏——这里钉死提取链路。
        assertTrue("extradata 缺失 size=${samples.extra.size}", samples.extra.size == 4)
        val codec = LegacyCodec("wmv2", samples.extra, samples.width, samples.height)
        val dir = instrumentation.targetContext.getExternalFilesDir(null)!!
        var saved = 0
        codec.use { decoder ->
            samples.packets.forEachIndexed { index, packet ->
                decoder.send(packet.data, packet.timeUs, false)
                while (true) {
                    val frame = decoder.receive() ?: break
                    if (index == 0 || index == 8) {
                        val pixels = yuvFrameToPixels(frame)
                        writePng(pixels, frame.width, frame.height, File(dir, "wmv2-yuv-$index.png"))
                        saved++
                        val spread = lumaSpread(frame)
                        assertTrue("frame $index looks empty or flat: spread=$spread", spread > 12)
                        assertColorBars(pixels, frame.width, index)
                    }
                }
            }
        }
        assertTrue("saved=$saved", saved >= 1)
    }

    /**
     * 队列只有 4 帧，EOS 时必须继续从解码器取尾帧，且 isEof 只在真正排空后置位。
     * 45 个 AVI 视频包对应 45 帧（WMV2 无 B 帧），末帧仍要是彩条而不是池复用后的残影。
     */
    @Test
    fun eosDrainsTailFramesAfterBoundedQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "np-wmv2.avi")
        instrumentation.context.assets.open("np-wmv2.avi").use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        assertTrue("libnplegacy 加载失败", LegacyDecoderLibrary.isAvailable())
        val samples = demux(file)
        var decoded = 0
        var tail: LegacyFrame? = null
        LegacyCodec("wmv2", samples.extra, samples.width, samples.height).use { decoder ->
            samples.packets.forEach { packet ->
                decoder.send(packet.data, packet.timeUs, false)
                while (true) {
                    tail = decoder.receive() ?: break
                    decoded++
                }
            }
            assertTrue("普通包结束时不应已 EOF", !decoder.isEof)
            decoder.send(ByteArray(0), 0L, true)
            while (true) {
                tail = decoder.receive() ?: break
                decoded++
            }
            assertTrue("EOS 后 isEof 仍为假", decoder.isEof)
        }
        assertEquals("packets=${samples.packets.size}", samples.packets.size, decoded)
        val last = checkNotNull(tail)
        assertTrue("尾帧 PTS=${last.ptsUs}", last.ptsUs > 0)
        assertTrue("尾帧疑似被池复用污染", lumaSpread(last) > 12)
        assertColorBars(yuvFrameToPixels(last), last.width, samples.packets.size - 1)
    }

    /**
     * 花屏回归闸：彩条在任意帧一致（y=230 处六段纯色横贯，段中心约 x=10/80/135/185/235/290，
     * 参考值来自离线 ffmpeg 解码）。缺 extradata 的宏块脱轨是噪声画面，
     * 六个抽样点同时落进纯色分类的概率可忽略。
     */
    private fun assertColorBars(pixels: IntArray, width: Int, frameIndex: Int) {
        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = pixels[y * width + x]
            return Triple((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }
        fun bright(v: Int) = v > 150
        fun dark(v: Int) = v < 110
        val y = 230
        val red = rgb(10, y)
        val green = rgb(80, y)
        val yellow = rgb(135, y)
        val blue = rgb(185, y)
        val magenta = rgb(235, y)
        val cyan = rgb(290, y)
        assertTrue("frame $frameIndex 红条异常 $red", bright(red.first) && dark(red.second) && dark(red.third))
        assertTrue("frame $frameIndex 绿条异常 $green", bright(green.second) && dark(green.first) && dark(green.third))
        assertTrue("frame $frameIndex 黄条异常 $yellow", bright(yellow.first) && bright(yellow.second) && dark(yellow.third))
        assertTrue("frame $frameIndex 蓝条异常 $blue", bright(blue.third) && dark(blue.first) && dark(blue.second))
        assertTrue("frame $frameIndex 品红条异常 $magenta", bright(magenta.first) && bright(magenta.third) && dark(magenta.second))
        assertTrue("frame $frameIndex 青条异常 $cyan", bright(cyan.second) && bright(cyan.third) && dark(cyan.first))
    }

    private fun demux(file: File): Demux {
        val extractor = Wmv2AviExtractor()
        val source = FileDataSource()
        source.open(DataSpec(Uri.fromFile(file)))
        var input = androidx.media3.extractor.DefaultExtractorInput(source, 0, file.length())
        check(extractor.sniff(input))
        input.resetPeekPosition()
        val packets = mutableListOf<Packet>()
        var format: Format? = null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                private val bytes = java.io.ByteArrayOutputStream()
                override fun format(fmt: Format) {
                    if (fmt.sampleMimeType == MediaNames.MIME_WMV2) format = fmt
                }
                override fun sampleData(
                    reader: androidx.media3.common.DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val tmp = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(tmp, read, length - read)
                        if (n == C.RESULT_END_OF_INPUT) break
                        read += n
                    }
                    bytes.write(tmp, 0, read)
                    return read
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val start = data.position
                    bytes.write(data.data, start, length.coerceAtMost(data.bytesLeft()))
                    data.skipBytes(length)
                }
                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    val all = bytes.toByteArray()
                    bytes.reset()
                    if (all.isNotEmpty()) packets.add(Packet(all, timeUs))
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val holder = PositionHolder()
        while (true) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                // 随机访问要重开 DataSource（与 FormatProbe 的探测循环同一约定）
                source.close()
                source.open(DataSpec(Uri.fromFile(file), holder.position, C.LENGTH_UNSET.toLong()))
                input = androidx.media3.extractor.DefaultExtractorInput(
                    source,
                    holder.position,
                    file.length() - holder.position,
                )
                continue
            }
            if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        extractor.release()
        source.close()
        val fmt = checkNotNull(format)
        return Demux(
            packets,
            fmt.initializationData.firstOrNull() ?: ByteArray(0),
            fmt.width,
            fmt.height,
        )
    }

    private fun writePng(pixels: IntArray, width: Int, height: Int, file: File) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /** YUV420P(limited, BT.601) → ARGB，与 nativeRender 的换算一致 */
    private fun yuvFrameToPixels(frame: LegacyFrame): IntArray {
        val pixels = IntArray(frame.width * frame.height)
        val y = frame.y.duplicate()
        val u = frame.u.duplicate()
        val v = frame.v.duplicate()
        y.position(0)
        u.position(0)
        v.position(0)
        for (row in 0 until frame.height) {
            for (col in 0 until frame.width) {
                val yy = y.get(row * frame.yStride + col).toInt() and 0xff
                val uu = u.get((row / 2) * frame.uvStride + col / 2).toInt() and 0xff
                val vv = v.get((row / 2) * frame.uvStride + col / 2).toInt() and 0xff
                val c = yy - 16
                val d = uu - 128
                val e = vv - 128
                val r = (298 * c + 409 * e + 128).shr(8).coerceIn(0, 255)
                val g = (298 * c - 100 * d - 208 * e + 128).shr(8).coerceIn(0, 255)
                val b = (298 * c + 516 * d + 128).shr(8).coerceIn(0, 255)
                pixels[row * frame.width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun lumaSpread(frame: LegacyFrame): Int {
        val y = frame.y.duplicate()
        y.position(0)
        var min = 255
        var max = 0
        val step = (frame.width / 16).coerceAtLeast(1)
        var row = 0
        while (row < frame.height) {
            var col = 0
            while (col < frame.width) {
                val value = y.get(row * frame.yStride + col).toInt() and 0xff
                if (value < min) min = value
                if (value > max) max = value
                col += step
            }
            row += step
        }
        return max - min
    }

    private class Packet(val data: ByteArray, val timeUs: Long)
    private class Demux(val packets: List<Packet>, val extra: ByteArray, val width: Int, val height: Int)
}
