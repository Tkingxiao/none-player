package com.noneplayer.kernel.ui.api

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npDisplacementAllowed
import kotlin.math.roundToInt

/** A transient frame bridges the two surfaces without mounting a second player. */
object NpPlayerPaneMotion {
    private var paneUri: String? = null
    private var paneBounds: Rect? = null
    internal var flight by mutableStateOf<Flight?>(null)
        private set

    internal class Flight(val frame: Bitmap, val from: Rect, val to: Rect)

    fun recordPane(uri: String, bounds: Rect) {
        if (bounds.width > 0f && bounds.height > 0f) {
            paneUri = uri
            paneBounds = bounds
        }
    }

    fun clearPane() {
        paneUri = null
        paneBounds = null
    }

    fun returnToPane(uri: String, frame: Bitmap, from: Rect): Boolean {
        val to = paneBounds ?: return false
        if (paneUri != uri || from.width <= 0f || from.height <= 0f ||
            to.left < from.left || to.top < from.top ||
            to.right > from.right || to.bottom > from.bottom
        ) return false
        flight = Flight(frame, from, to)
        return true
    }

    internal fun finish(request: Flight) {
        if (flight === request) flight = null
    }
}

internal fun playerPaneFrame(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * p
    return Rect(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom))
}

@Composable
internal fun PlayerPaneMotionOverlay(hostBounds: Rect) {
    val request = NpPlayerPaneMotion.flight ?: return
    val progress = remember(request) { Animatable(0f) }
    val opacity = remember(request) { Animatable(1f) }
    val duration = npDuration(Motion.Shared)
    val fadeDuration = npDuration(Motion.ReduceFade)
    val displacement = npDisplacementAllowed()
    LaunchedEffect(request) {
        try {
            withFrameMillis { }
            if (displacement) progress.animateTo(1f, tween(duration, easing = NpEasing.Emphasized))
            else progress.snapTo(1f)
            opacity.animateTo(0f, tween(fadeDuration, easing = NpEasing.SilkExit))
        } finally {
            NpPlayerPaneMotion.finish(request)
        }
    }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val rect = playerPaneFrame(request.from, request.to, progress.value)
                val child = measurable.measure(
                    Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    child.place(
                        (rect.left - hostBounds.left).roundToInt(),
                        (rect.top - hostBounds.top).roundToInt(),
                    )
                }
            }
            .graphicsLayer { alpha = opacity.value }
            .background(Color.Black),
    ) {
        Image(
            bitmap = request.frame.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
        )
    }
}
