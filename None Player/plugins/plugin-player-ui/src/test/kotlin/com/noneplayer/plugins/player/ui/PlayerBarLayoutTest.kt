package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBarLayoutTest {
    @Test fun largeTextAlwaysSplitsEvenWhenASingleRowIsPreferred() {
        assertTrue(
            playerBarSplitsProgress(
                maxWidthDp = 800f,
                fontScale = 2f,
                preferSingleRow = true,
                showTransport = false,
                hasLeading = true,
                iconButtonCount = 3,
            ),
        )
        assertTrue(
            playerBarSplitsProgress(1280f, 1.31f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
    }

    @Test fun normalPhonePortraitStaysOneRowAndTheNarrowPaneDoesNot() {
        assertFalse(
            playerBarSplitsProgress(360f, 1f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
        assertTrue(
            playerBarSplitsProgress(320f, 1f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
        assertTrue(
            playerBarSplitsProgress(480f, 1f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertFalse(
            playerBarSplitsProgress(800f, 1f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
    }

    @Test fun fullscreenNarrowWindowsSplitUntilThereIsRoomForOneRow() {
        assertTrue(
            playerBarSplitsProgress(559f, 1f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertFalse(
            playerBarSplitsProgress(800f, 1f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertFalse(
            playerBarSplitsProgress(Float.NaN, 2f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertTrue(
            playerBarSplitsProgress(360f, 1.3f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
    }
}
