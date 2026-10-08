package com.noneplayer.kernel.ui.api

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「有指针设备」的纯判据（§14 #18）。
 *
 * 设备枚举（`detectHoverable`）要 InputManager，JVM 里跑不了；能被钉住的正是判据本身：
 * 哪些输入源算"指针"。`InputDevice.SOURCE_*` 是编译期内联常量，单测无需设备。
 * 另一条（插拔鼠标要能失效）由 `rememberHoverable` 的 InputDeviceListener 负责——设备
 * 侧无法热插拔验证，见档案 §19.22 的如实记录。
 */
class PointerSourceTest {

    @Test
    fun `鼠标与触控板算指针`() {
        assertTrue(hasPointerSource(InputDevice.SOURCE_MOUSE))
        assertTrue(hasPointerSource(InputDevice.SOURCE_TOUCHPAD))
        // 组合源：触控板常同时带 MOUSE 位，键盘鼠标一体设备也常见
        assertTrue(hasPointerSource(InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_KEYBOARD))
        assertTrue(
            hasPointerSource(
                InputDevice.SOURCE_TOUCHPAD or InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    @Test
    fun `指尖与键盘不算指针`() {
        assertFalse(hasPointerSource(InputDevice.SOURCE_TOUCHSCREEN))
        assertFalse(hasPointerSource(InputDevice.SOURCE_KEYBOARD))
        assertFalse(hasPointerSource(InputDevice.SOURCE_DPAD))
        assertFalse(hasPointerSource(0))
    }
}
