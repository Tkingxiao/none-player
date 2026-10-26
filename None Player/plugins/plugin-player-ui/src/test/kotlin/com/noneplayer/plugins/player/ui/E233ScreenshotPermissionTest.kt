package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E233：截图保存的 legacy 写权限判据（`screenshotLegacyWriteMissing`）。
 *
 * 任务文档 §4 截图条目「API26–28 保存失败（无 WRITE_EXTERNAL_STORAGE）」——
 * E233 已在清单与申请表中补 WRITE（sdkInt ≤ 29），这里把"哪一档才算缺"的边界钉住：
 * 只有 26–28 缺权限才构成硬失败；29 插入自有媒体不要求、30+ 该权限已失效。
 */
class E233ScreenshotPermissionTest {

    @Test
    fun `26-28 缺写权限时判定缺失`() {
        assertTrue(screenshotLegacyWriteMissing(sdkInt = 26, writeAccessOk = false))
        assertTrue(screenshotLegacyWriteMissing(sdkInt = 28, writeAccessOk = false))
    }

    @Test
    fun `26-28 已授权时不判定缺失`() {
        assertFalse(screenshotLegacyWriteMissing(sdkInt = 26, writeAccessOk = true))
        assertFalse(screenshotLegacyWriteMissing(sdkInt = 28, writeAccessOk = true))
    }

    @Test
    fun `29 与更新档位不依赖该权限`() {
        assertFalse(screenshotLegacyWriteMissing(sdkInt = 29, writeAccessOk = false))
        assertFalse(screenshotLegacyWriteMissing(sdkInt = 36, writeAccessOk = false))
    }
}