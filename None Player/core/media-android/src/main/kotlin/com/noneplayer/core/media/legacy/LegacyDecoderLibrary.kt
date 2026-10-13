package com.noneplayer.core.media.legacy

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

    @Volatile
    private var loaded: Boolean? = null

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
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("nplegacy")
                true
            } catch (t: Throwable) {
                Log.d(TAG, "libnplegacy 加载失败，退回不可播：${t.javaClass.simpleName}: ${t.message}")
                false
            }
            loaded = ok
            return ok
        }
    }
}
