package com.noneplayer.core.media.legacy

import android.os.SystemClock
import android.util.Log
import com.noneplayer.core.media.MediaNames

/**
 * `libnplegacy.so` 的加载闸。
 *
 * 失败必须是 false，不能 fail-open 成可播。加载放在第一次能力查询，不放进渲染器构造函数：
 * [androidx.media3.exoplayer.source.preload.DefaultPreloadManager] 会把工厂再跑一遍，
 * 构造期 `System.load` 失败会让播放服务起不来。
 */
object LegacyDecoderLibrary {

    private const val TAG = "LegacyDecoder"
    private const val RETRY_MS = 30_000L

    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var failedAtElapsed = 0L

    fun codecName(mime: String?): String? = when (mime) {
        MediaNames.MIME_WMV2 -> "wmv2"
        MediaNames.MIME_MPEG4_ASP -> "mpeg4"
        else -> null
    }

    fun supports(mime: String?): Boolean {
        if (codecName(mime) == null) return false
        return isAvailable()
    }

    fun isAvailable(): Boolean {
        loaded?.let { cached ->
            if (cached) return true
            if (SystemClock.elapsedRealtime() - failedAtElapsed < RETRY_MS) return false
        }
        // A19：dlopen 不占着锁；并发重复加载由 ClassLoader 自身串行化，失败仍是否定结果。
        val ok = try {
            System.loadLibrary("nplegacy")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libnplegacy 加载失败，退回不可播：${t.javaClass.simpleName}: ${t.message}")
            false
        }
        synchronized(this) {
            loaded = ok
            failedAtElapsed = if (ok) 0L else SystemClock.elapsedRealtime()
        }
        return ok
    }

    /** A19：失败后允许诊断入口主动重试（清掉否定缓存）。 */
    fun reset() {
        synchronized(this) {
            loaded = null
            failedAtElapsed = 0L
        }
    }
}
