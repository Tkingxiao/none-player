package com.noneplayer.core.media.probe

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12.9.3 P0-1 验证：把 Windows + NDK 27.2 交叉编出的最小 FFmpeg（只开 wmv2/vc1
 * 解码器 + avi/asf 解封装，arm64-v8a + x86_64，16KB page）通过 `System.load(绝对路径)`
 * 加载到 Android 进程里。成功 = .so 通过 Android dynamic linker 全部检查（机器类型、
 * 依赖解析、符号表、SONAME）。
 *
 * 关键限制：模拟器主 ABI = x86_64，所以必须编 x86_64 一份并按 device 选。
 * 真实设备主 ABI 通常是 arm64-v8a，会用 `libav*.so.XX`；模拟器用 `x86_64-libav*.so.XX`。
 * `.so` 由 `D:\Code\Player\.audit\ffmpeg\build_and_push.sh` 推送（p0-1 记录文件）。
 */
@RunWith(AndroidJUnit4::class)
class FfmpegNativeLoadTest {

    companion object {
        private const val TAG = "FfmpegLoad"
        private val SO_BASE = listOf(
            "libavutil" to ".so.58",
            "libswresample" to ".so.4",
            "libavcodec" to ".so.60",
            "libavformat" to ".so.60",
            "libswscale" to ".so.7",
        )
        private const val DEVICE_TMP = "/data/local/tmp"
    }

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadAllFfpegSharedObjects() {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: error("device has no abi")
        val prefix = when (primaryAbi) {
            "x86_64" -> "x86_64-"
            "arm64-v8a" -> ""
            else -> error("unexpected primary ABI $primaryAbi, please add mapping for it")
        }
        android.util.Log.i(TAG, "DEVICE|model=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} primaryAbi=$primaryAbi abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        android.util.Log.i(TAG, "LOAD-PLAN|prefix=$prefix|fileCount=${SO_BASE.size}")

        // 1. /data/local/tmp 在 Android 10+ 是 noexec 挂载，dlopen 不能直接 mmap。
        //    所以先 cat 到 app 自己的 files/ 目录（app-owned、可执行），再 System.load。
        val ownDir = target.filesDir.absolutePath
        for ((base, ver) in SO_BASE) {
            val name = "$base$ver"
            val srcFile = "$DEVICE_TMP/$prefix$name"
            val dstFile = "$ownDir/$prefix$name"
            val present = runCommand("sh -c 'ls $srcFile'").trim() == srcFile
            android.util.Log.i(TAG, "PRESENT|src=$srcFile|onDevice=$present")
            assertTrue("缺少 $srcFile，请先用 build_and_push.sh 推送", present)
            // cat 复制（保持 ELF 段属性）
            val copied = runCommand("sh -c 'cat \"$srcFile\" > \"$dstFile\" && chmod 755 \"$dstFile\"'")
            val exists = runCommand("sh -c 'ls -l $dstFile'").trim()
            android.util.Log.i(TAG, "COPIED|src=$srcFile -> dst=$dstFile|copiedOk=${copied.isEmpty()}|listing=$exists")
            assertTrue("复制 $name 失败", exists.contains(prefix + name))
        }

        // 2. 加载：按依赖顺序（avutil → swresample → swscale → avcodec → avformat）
        val loaded = mutableMapOf<String, Long>()
        for ((base, ver) in SO_BASE) {
            val name = "$base$ver"
            val absPath = "$ownDir/$prefix$name"
            try {
                val t0 = System.nanoTime()
                System.load(absPath)
                val ms = (System.nanoTime() - t0) / 1_000_000
                loaded[name] = ms
                android.util.Log.i(TAG, "LOAD|ok|$name|${ms}ms")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "LOAD|fail|$name|${t.javaClass.simpleName}:${t.message}", t)
                throw AssertionError("FFmpeg $name 加载失败：${t.message}", t)
            }
        }
        // 3. 写报告
        val cacheDir = target.externalCacheDir ?: error("externalCacheDir null")
        val report = buildString {
            appendLine("DEVICE|model=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} primaryAbi=$primaryAbi")
            appendLine("LOADEES|")
            for ((n, ms) in loaded) appendLine("  $n = ${ms}ms")
        }
        val reportFile = File(cacheDir, "ffmpeg-load-report.txt")
        reportFile.writeText(report)
        android.util.Log.i(TAG, "REPORT|written=${reportFile.absolutePath}")
        android.util.Log.i(TAG, "VERDICT|ffmpeg-all-5-so-loaded=true abi=$primaryAbi")
    }

    private fun runCommand(cmd: String): String {
        val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }
}
