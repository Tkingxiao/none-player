package com.noneplayer.kernel.ui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/** A12：内嵌弹幕轨认领文本 Cue 后，字幕层据此让位。 */
class PlaybackTextCueArbiterTest {

    @After
    fun cleanup() {
        PlaybackTextCueArbiter.release("media-a", "track-1")
        PlaybackTextCueArbiter.release("media-a", "track-2")
    }

    @Test
    fun `claim is scoped to media and track`() {
        val arbitration = PlaybackTextCueArbiter
        arbitration.claim("media-a", "track-1", "danmaku.core")

        assertTrue(arbitration.isClaimed("media-a", "track-1"))
        assertFalse(arbitration.isClaimed("media-b", "track-1"))
        assertFalse(arbitration.isClaimed("media-a", "track-2"))
        assertEquals("danmaku.core", arbitration.lastOwner)
        arbitration.release("media-a", "track-1")
    }

    @Test
    fun `release only clears matching claim`() {
        val arbitration = PlaybackTextCueArbiter
        arbitration.claim("media-a", "track-1", "danmaku.core")

        arbitration.release("media-a", "track-2")
        assertTrue(arbitration.isClaimed("media-a", "track-1"))

        arbitration.release("media-a", "track-1")
        assertFalse(arbitration.isClaimed("media-a", "track-1"))
        assertNull(arbitration.claim.value)
    }
}
