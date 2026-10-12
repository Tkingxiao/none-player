package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackPolicyTest {
    @Test fun stopsOnlyWhenDisabledAndHiddenWithSession() {
        assertTrue(shouldStopInBackground(false, false, true))
        assertFalse(shouldStopInBackground(true, false, true))
        assertFalse(shouldStopInBackground(false, true, true))
        assertFalse(shouldStopInBackground(false, false, false))
    }

    @Test fun visiblePipAndRotationDoNotStopPlayback() {
        for (background in listOf(true, false)) {
            assertFalse(shouldStopInBackground(background, true, true))
        }
    }
}
