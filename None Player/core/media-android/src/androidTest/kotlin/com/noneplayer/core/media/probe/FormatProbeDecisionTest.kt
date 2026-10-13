package com.noneplayer.core.media.probe

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 起播前探测（E39）的三条解码链判定回归。
 *
 * 探测的"能不能播"必须与播放链同源，否则会自相矛盾：
 *  - `xvid-clip.avi`（MPEG-4 SP，平台 mp4v 解码器）→ 可播；
 *  - `np-mjpeg.avi`（AVI 内嵌 MJPEG）→ 平台无解码器、NextLib 的 FFmpeg 也不含该编码，
 *    只有播放链末端的 `BitmapFactoryVideoRenderer` 能出画 → **可播 + 仅软解提示**；
 *  - `np-wmv2.avi`（WMV2）→ libnplegacy 补轨并软解 → 可播，且只有软解。
 *
 * MJPEG 这条是本次修复的靶子：修复前 `decide()` 只查平台 + FFmpeg 两条链，
 * 会把"实际能播"的 MJPEG AVI 误判为 `videoCodecUnsupported`。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FormatProbeDecisionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun probe(assetName: String): FormatProbe.Result {
        val file = File(instrumentation.targetContext.cacheDir, assetName)
        instrumentation.context.assets.open(assetName).use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        val uri: Uri = Uri.fromFile(file)
        return runBlocking { FormatProbe.probe(instrumentation.targetContext, uri.toString()) }
    }

    @Test
    fun mjpegAviPlayableViaBitmapRenderer() {
        val result = probe("np-mjpeg.avi")
        Log.i(TAG, "np-mjpeg.avi => $result")
        assertTrue(
            "MJPEG AVI 应判为可播（BitmapFactoryVideoRenderer 兜底），实际：$result",
            result is FormatProbe.Result.Playable,
        )
        result as FormatProbe.Result.Playable
        assertEquals("Motion JPEG", result.videoCodec)
        assertTrue("MJPEG 仅软解，应给 C21 前置提示", result.softwareVideoOnly)
    }

    @Test
    fun wmv2AviUnplayable() {
        val result = probe("np-wmv2.avi")
        Log.i(TAG, "np-wmv2.avi => $result")
        assertTrue(
            "WMV2 补轨后应由 libnplegacy 判为可播，实际：$result",
            result is FormatProbe.Result.Playable,
        )
        result as FormatProbe.Result.Playable
        assertEquals("WMV2", result.videoCodec)
        assertTrue("WMV2 没有平台解码器，应给 C21 前置提示", result.softwareVideoOnly)
    }

    @Test
    fun xvidAviPlayable() {
        val result = probe("xvid-clip.avi")
        Log.i(TAG, "xvid-clip.avi => $result")
        assertTrue(
            "MPEG-4 SP AVI 应判为可播，实际：$result",
            result is FormatProbe.Result.Playable,
        )
    }

    private companion object {
        const val TAG = "FormatProbeDecision"
    }
}
