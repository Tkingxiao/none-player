package com.noneplayer.plugins.player.exo

import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.homesoft.exo.video.BitmapFactoryVideoRenderer

/**
 * Media3Avi 的 [BitmapFactoryVideoRenderer]（AVI 内嵌 MJPEG 位图软解）的包装。
 *
 * 行为与其一致（认领 `video/mjpeg`、Java Canvas + BitmapFactory 出画），唯一区别是
 * 渲染走 [OwnedSurfaceCloner] 的克隆 Surface、在渲染器停用时归还 CPU 连接——
 * 原实现直接在播放器 Surface 上 lockCanvas，连接滞留会让下一条平台流的 MediaCodec
 * 起不来（实测：H264 → MJPEG → 回到 H264 报 `ERROR_CODE_DECODER_INIT_FAILED`）。
 */
@OptIn(UnstableApi::class)
class NpMjpegVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : BitmapFactoryVideoRenderer(
    allowedJoiningTimeMs,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    private val surfaces = OwnedSurfaceCloner("NpMjpegRenderer")

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        super.renderOutputBufferToSurface(outputBuffer, surfaces.surfaceFor(surface))
    }

    override fun onDisabled() {
        surfaces.release()
        super.onDisabled()
    }

    override fun onRelease() {
        surfaces.release()
        super.onRelease()
    }
}
