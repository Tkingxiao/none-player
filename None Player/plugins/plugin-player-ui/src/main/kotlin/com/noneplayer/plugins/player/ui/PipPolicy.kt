package com.noneplayer.plugins.player.ui

// Stay inside the standard PiP range after Rational's 1/1000 rounding.
internal fun pipAspectRatio(value: Float): Float =
    (value.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)).coerceIn(0.419f, 2.39f)
