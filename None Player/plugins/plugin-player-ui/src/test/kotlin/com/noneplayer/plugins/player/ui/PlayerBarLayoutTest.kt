package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底部控制条的三档退让：完整单行 → 省掉总时长的单行 → 才拆两行。
 *
 * 旧口径只有"放不下就拆两行"，360dp 手机上预算 356dp 对 328dp 可用宽被判定拆行，
 * 竖屏那条 215dp 高的画面因此常驻少一行。省掉总时长换单行是更划算的那一步。
 */
class PlayerBarLayoutTest {
    @Test fun largeTextAlwaysSplitsEvenWhenASingleRowIsPreferred() {
        assertEquals(
            PlayerBarLayout.Split,
            playerBarLayout(
                maxWidthDp = 800f,
                fontScale = 2f,
                preferSingleRow = true,
                showTransport = false,
                hasLeading = true,
                iconButtonCount = 3,
            ),
        )
        assertEquals(
            PlayerBarLayout.Split,
            playerBarLayout(1280f, 1.31f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
    }

    @Test fun normalPhonePortraitStaysOneRowAndNarrowOnesDropDurationFirst() {
        // 360dp 竖屏：两枚时间码都放得下，维持完整单行
        assertEquals(
            PlayerBarLayout.SingleRow,
            playerBarLayout(360f, 1f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
        // 320dp：完整单行差 28dp，省掉总时长就够——不再拆两行
        assertEquals(
            PlayerBarLayout.SingleRowWithoutDuration,
            playerBarLayout(320f, 1f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
        // 480dp 带传输排 + 四枚图标：同样先省总时长
        assertEquals(
            PlayerBarLayout.SingleRowWithoutDuration,
            playerBarLayout(480f, 1f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertEquals(
            PlayerBarLayout.SingleRow,
            playerBarLayout(800f, 1f, preferSingleRow = true, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
    }

    @Test fun splitsOnlyWhenEvenTheLeanRowDoesNotFit() {
        // 连"一枚时间码 + 最短进度条 + 三枚图标"都塞不下，才回落到两行
        assertEquals(
            PlayerBarLayout.Split,
            playerBarLayout(300f, 1f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
        // 全屏形态没表态单行优先时，窄窗照旧拆行
        assertEquals(
            PlayerBarLayout.Split,
            playerBarLayout(559f, 1f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        assertEquals(
            PlayerBarLayout.SingleRow,
            playerBarLayout(800f, 1f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        // 量不到宽度时不猜：按单行排，交给测量下一帧再定
        assertEquals(
            PlayerBarLayout.SingleRow,
            playerBarLayout(Float.NaN, 2f, preferSingleRow = false, showTransport = true, hasLeading = false, iconButtonCount = 4),
        )
        // 1.3 是"还能凑单行"的上沿：大字号下先省总时长
        assertEquals(
            PlayerBarLayout.SingleRowWithoutDuration,
            playerBarLayout(360f, 1.3f, preferSingleRow = true, showTransport = false, hasLeading = true, iconButtonCount = 3),
        )
    }
}
