package com.noneplayer.plugins.player.ui.controls

internal data class WaveformScrub(val fraction: Double) {
    fun dragBy(deltaPx: Float, trackWidthPx: Float): WaveformScrub {
        if (trackWidthPx <= 0f || !trackWidthPx.isFinite() || !deltaPx.isFinite()) return this
        return WaveformScrub((fraction - deltaPx / trackWidthPx.toDouble()).coerceIn(0.0, 1.0))
    }

    fun positionMs(durationMs: Long): Long =
        (fraction.coerceIn(0.0, 1.0) * durationMs.coerceAtLeast(0L)).toLong()
}

internal fun waveformOriginPx(viewportWidthPx: Float, trackWidthPx: Float, fraction: Double): Float =
    viewportWidthPx / 2f - (fraction.coerceIn(0.0, 1.0) * trackWidthPx).toFloat()
