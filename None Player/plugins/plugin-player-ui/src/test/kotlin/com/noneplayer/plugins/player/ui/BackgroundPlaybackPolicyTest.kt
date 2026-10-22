package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackPolicyTest {
    @Test fun stopsOnlyWhenDisabledAndHiddenWithSession() {
        assertTrue(shouldStopInBackground(false, false, true, pickerOpen = false))
        assertFalse(shouldStopInBackground(true, false, true, pickerOpen = false))
        assertFalse(shouldStopInBackground(false, true, true, pickerOpen = false))
        assertFalse(shouldStopInBackground(false, false, false, pickerOpen = false))
    }

    @Test fun visiblePipAndRotationDoNotStopPlayback() {
        for (background in listOf(true, false)) {
            assertFalse(shouldStopInBackground(background, true, true, pickerOpen = false))
        }
    }

    @Test fun inAppPickerHoldsPlaybackWhileHidden() {
        assertFalse(shouldStopInBackground(false, false, true, pickerOpen = true))
        assertTrue(shouldStopInBackground(false, false, true, pickerOpen = false))
    }
}
