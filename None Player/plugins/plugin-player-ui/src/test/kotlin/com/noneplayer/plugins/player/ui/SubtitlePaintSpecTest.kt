package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.SubtitleCueStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A12 第二期：用户设置与 ASS/SSA 容器样式的合并口径。 */
class SubtitlePaintSpecTest {

    @Test
    fun `container color wins over user color`() {
        val spec = subtitlePaintSpec(
            userColorArgb = 0xFFFF0000L,
            userItalic = false,
            cueStyle = SubtitleCueStyle(colorArgb = 0xFF00FF00L),
        )
        assertEquals(0xFF00FF00L, spec.colorArgb)
    }

    @Test
    fun `user color fills in when container has none`() {
        assertEquals(
            0xFFFF0000L,
            subtitlePaintSpec(0xFFFF0000L, false, null).colorArgb,
        )
        assertEquals(
            0xFFFF0000L,
            subtitlePaintSpec(
                0xFFFF0000L,
                false,
                SubtitleCueStyle(italic = true),
            ).colorArgb,
        )
    }

    @Test
    fun `container decorations pass through and italic is additive`() {
        val spec = subtitlePaintSpec(
            userColorArgb = 0xFFFFFFFFL,
            userItalic = false,
            cueStyle = SubtitleCueStyle(
                bold = true,
                italic = true,
                underline = true,
                strikethrough = true,
                backgroundColorArgb = 0x80000000L,
            ),
        )
        assertTrue(spec.bold)
        assertTrue(spec.italic)
        assertTrue(spec.underline)
        assertTrue(spec.strikethrough)
        assertEquals(0x80000000L, spec.backgroundColorArgb)
    }

    @Test
    fun `user italic is kept when container has no style`() {
        val spec = subtitlePaintSpec(0xFFFFFFFFL, userItalic = true, cueStyle = null)
        assertTrue(spec.italic)
        assertFalse(spec.bold)
        assertFalse(spec.underline)
        assertFalse(spec.strikethrough)
        assertNull(spec.backgroundColorArgb)
    }
}
