package com.noneplayer.plugins.player.exo

import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer

/**
 * 给 NextLib `FfmpegVideoRenderer` 补 Surface 克隆。
 *
 * `FfmpegVideoRenderer` 是 `final`，但它的 CPU 出画（`renderOutputBufferToSurface` →
 * native `renderToSurface`）与 MJPEG / legacy 一样会以 `NATIVE_WINDOW_API_CPU` 连上
 * 播放器 Surface；连接只随连接者销毁而断开，切到平台流时会报
 * `connect: already connected`。这里用 Kotlin 接口委托拦截 media3 的
 * `MSG_SET_VIDEO_OUTPUT`，把克隆 Surface 交给内部 renderer，其余方法原样转发；
 * `disable/reset/release` 时归还连接。没有复制 NextLib 的 GPL 源码。
 *
 * 克隆失败时 [OwnedSurfaceCloner] 回退原 Surface——画面优先，只是恢复旧的连接滞留行为。
 */
@OptIn(UnstableApi::class)
internal class NpFfmpegVideoRenderer(
    private val delegate: Renderer,
) : Renderer by delegate {

    private val surfaces = OwnedSurfaceCloner("NpFfmpegRenderer")

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) {
            delegate.handleMessage(messageType, message)
            return
        }
        val surface = message as? Surface
        if (surface == null) {
            surfaces.release()
            delegate.handleMessage(messageType, null)
        } else {
            delegate.handleMessage(messageType, surfaces.surfaceFor(surface))
        }
    }

    override fun disable() {
        delegate.disable()
        surfaces.release()
    }

    override fun reset() {
        delegate.reset()
        surfaces.release()
    }

    override fun release() {
        delegate.release()
        surfaces.release()
    }

    override fun getName(): String = delegate.name
}
