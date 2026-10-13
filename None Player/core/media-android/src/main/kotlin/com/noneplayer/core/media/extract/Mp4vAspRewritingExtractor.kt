package com.noneplayer.core.media.extract

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.legacy.LegacyDecoderLibrary
import com.noneplayer.core.media.probe.Mp4vAspGate
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * 在第一份 `video/mp4v-es` Format 交给播放器之前，用与 profile 拦截同一套规则决定要不要改 mime。
 *
 * 不认领、也不改写 Simple Profile、设备已声明 ASP、或 profile 列表为空的流。
 * 软解加载失败时同样不改 mime，探测维持今天的 `videoProfileUnsupported`。
 */
@OptIn(UnstableApi::class)
class Mp4vAspRewritingExtractor(private val inner: Extractor) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(RewritingOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    private class RewritingOutput(private val downstream: ExtractorOutput) : ExtractorOutput {
        private var video: RewritingVideoTrack? = null
        private var endPending = false
        private var ended = false

        override fun track(id: Int, type: Int): TrackOutput {
            val track = downstream.track(id, type)
            if (type != C.TRACK_TYPE_VIDEO) return track
            return RewritingVideoTrack(track, onFormatReady = ::onVideoFormatReady).also { video = it }
        }

        override fun endTracks() {
            video?.flushPending()
            // 头在首帧里时，endTracks 先到、样本后到。先把 endTracks 压住，
            // 否则探测看到「有音频、无视频」就会停，视频 mime 永远交不出去。
            if (video?.awaitingSample == true) {
                endPending = true
                return
            }
            finish()
        }

        private fun onVideoFormatReady() {
            if (endPending) finish()
        }

        private fun finish() {
            if (ended) return
            ended = true
            downstream.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)
    }

    private class RewritingVideoTrack(
        private val downstream: TrackOutput,
        private val onFormatReady: () -> Unit,
    ) : TrackOutput {
        var awaitingSample: Boolean = false
            private set
        private var pendingFormat: Format? = null
        private var decided = false
        private val hold = ByteArrayOutputStream()

        override fun format(format: Format) {
            if (decided || format.sampleMimeType != MIME_MP4V) {
                downstream.format(format)
                decided = true
                pendingFormat = null
                return
            }
            val csd = format.initializationData.firstOrNull { it.isNotEmpty() }
            if (csd != null) {
                downstream.format(rewrite(format, csd))
                decided = true
            } else {
                pendingFormat = format
                awaitingSample = true
            }
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            if (decided) return downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val tmp = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(tmp, read, length - read)
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) break
                    throw EOFException("mp4v sample")
                }
                read += n
            }
            if (read > 0) hold.write(tmp, 0, read)
            if (hold.size() >= HEAD_CAP) {
                decide()
                flushHold(sampleDataPart)
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (decided) {
                downstream.sampleData(data, length, sampleDataPart)
                return
            }
            val start = data.position
            val take = minOf(length, data.bytesLeft())
            if (take > 0) hold.write(data.data, start, take)
            data.skipBytes(length)
            if (hold.size() >= HEAD_CAP) {
                decide()
                flushHold(sampleDataPart)
            }
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (!decided) {
                decide()
                flushHold(TrackOutput.SAMPLE_DATA_PART_MAIN)
            }
            downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        fun flushPending() {
            // AviExtractor 在读到 movi 样本之前就 endTracks。这时还没有 VOS/VOL，
            // 不能把 video/mp4v-es 先交出去，否则后面的样本来了也改不了 mime。
            if (decided || pendingFormat == null || hold.size() == 0) return
            decide()
            flushHold(TrackOutput.SAMPLE_DATA_PART_MAIN)
        }

        private fun decide() {
            if (decided) return
            val format = pendingFormat
            if (format == null) {
                decided = true
                return
            }
            val header = hold.toByteArray()
            downstream.format(if (header.isEmpty()) format else rewrite(format, header))
            decided = true
            awaitingSample = false
            pendingFormat = null
            onFormatReady()
        }

        private fun flushHold(sampleDataPart: Int) {
            if (hold.size() == 0) return
            val bytes = hold.toByteArray()
            hold.reset()
            downstream.sampleData(ParsableByteArray(bytes), bytes.size, sampleDataPart)
        }

        private fun rewrite(format: Format, header: ByteArray): Format {
            val legacy = Mp4vAspGate.requiresDedicatedDecoder(header) &&
                LegacyDecoderLibrary.supports(MediaNames.MIME_MPEG4_ASP)
            if (!legacy) return format
            Log.d(TAG, "mp4v-es -> ${MediaNames.MIME_MPEG4_ASP}")
            return format.buildUpon().setSampleMimeType(MediaNames.MIME_MPEG4_ASP).build()
        }
    }

    private companion object {
        const val TAG = "Mp4vAsp"
        const val MIME_MP4V = "video/mp4v-es"
        const val HEAD_CAP = 4 * 1024
    }
}
