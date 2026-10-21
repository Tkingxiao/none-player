package com.noneplayer.plugins.player.exo

import android.os.Parcel
import android.util.Log
import android.view.Surface
import com.noneplayer.core.media.SurfaceCloneMemo

/**
 * CPU 渲染要用的 Surface 持有器。
 *
 * `ANativeWindow_lock` / `lockCanvas` 会把共享的 BufferQueue 以 `NATIVE_WINDOW_API_CPU`
 * 连上，而该连接只会随**执行连接的 Surface 对象被销毁**才断开（`~Surface` 里
 * `disconnect(CPU)`；NDK 没有任何公开 disconnect API）。若直接在播放器持有的
 * Surface 上 CPU 锁定，连接会一直留着，下一条平台流 `MediaCodec.configure` 以
 * `connect: already connected (cur=2 req=3)` 失败（实测：切到 H264 报
 * `ERROR_CODE_DECODER_INIT_FAILED`）。
 *
 * 这里从给定 Surface 做一次 Parcel 往返，得到一个**我们自己持有**的克隆；
 * 所有 CPU 渲染都走克隆，[release] 时克隆析构即归还连接。克隆失败退回原
 * Surface（功能优先，仅恢复"连接滞留"的旧行为，不吞掉画面）。
 *
 * 线程约束：与渲染器同一线程使用（media3 播放线程），不做跨线程同步。
 */
internal class OwnedSurfaceCloner(private val tag: String) {

    private var source: Surface? = null
    private var owned: Surface? = null

    /** 给定 Surface 的渲染用替身：同一 source 复用同一克隆，source 变化时换新 */
    fun surfaceFor(surface: Surface): Surface {
        if (source === surface) return owned ?: surface
        release()
        val clone = clone(surface)
        source = surface
        owned = clone
        return clone ?: surface
    }

    fun release() {
        owned?.release()
        owned = null
        source = null
    }

    private fun clone(surface: Surface): Surface? = try {
        val parcel = Parcel.obtain()
        try {
            surface.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Surface.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    } catch (t: Throwable) {
        val detail = "${t.javaClass.simpleName}: ${t.message}"
        Log.w(tag, "Surface 克隆失败，退回直渲：$detail")
        SurfaceCloneMemo.recordFailure(tag, detail)
        null
    }
}
