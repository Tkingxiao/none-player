package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.NpTrackType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectionPolicyTest {
    @Test fun selectingTextTrackReenablesDisabledTextTracks() {
        assertTrue(shouldEnableTextTracks(NpTrackType.TEXT, enabled = false))
        assertFalse(shouldEnableTextTracks(NpTrackType.TEXT, enabled = true))
        assertFalse(shouldEnableTextTracks(NpTrackType.AUDIO, enabled = false))
    }
}
