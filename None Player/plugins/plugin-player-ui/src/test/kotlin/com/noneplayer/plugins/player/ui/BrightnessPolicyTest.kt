package com.noneplayer.plugins.player.ui

import com.noneplayer.plugins.player.ui.brightness.BRIGHTNESS_FALLBACK_PERCENT
import com.noneplayer.plugins.player.ui.brightness.BRIGHTNESS_NONE
import com.noneplayer.plugins.player.ui.brightness.BrightnessPolicy
import com.noneplayer.plugins.player.ui.brightness.SystemBrightnessSource
import com.noneplayer.plugins.player.ui.brightness.WindowBrightnessTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A8 亮度纯策略判据：捕获/恢复 NONE 与 override、真实百分比读取、clamp 与"变了才写"。
 *
 * 设备侧（离开播放页/全屏、PiP、配置变化后窗口亮度真的还原）仍需真机验收；
 * 这里用 fake 平台边界把策略语义钉死。
 */
class BrightnessPolicyTest {

    private class FakeWindowBrightnessTarget(
        var current: Float = BRIGHTNESS_NONE,
    ) : WindowBrightnessTarget {
        var writes = 0
        var lastWritten: Float? = null

        override fun readWindowBrightness(): Float = current

        override fun writeWindowBrightness(value: Float) {
            writes++
            lastWritten = value
            current = value
        }
    }

    private class FakeSystemBrightness(
        var raw: Int? = null,
    ) : SystemBrightnessSource {
        var reads = 0

        override fun readSystemBrightnessRaw(): Int? {
            reads++
            return raw
        }
    }

    private fun policy(
        window: FakeWindowBrightnessTarget = FakeWindowBrightnessTarget(),
        system: FakeSystemBrightness = FakeSystemBrightness(),
    ): BrightnessPolicy = BrightnessPolicy(window, system)

    @Test
    fun `capture keeps none override as original value`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window)
        assertEquals(BRIGHTNESS_NONE, policy.capture(), 0f)
    }

    @Test
    fun `restore writes back none exactly`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window)
        val original = policy.capture()
        // 用户在页内滑过亮度 → 窗口覆盖被写成 0.3。
        policy.setPercent(30)
        assertEquals(1, window.writes)
        policy.restore(original)
        // 原值是"无覆盖"：必须写回 -1（清除覆盖），而不是 0.62。
        assertEquals(BRIGHTNESS_NONE, window.current, 0f)
        assertEquals(BRIGHTNESS_NONE, window.lastWritten!!, 0f)
    }

    @Test
    fun `restore skips write when window already at original value`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window)
        policy.restore(BRIGHTNESS_NONE)
        assertEquals(0, window.writes)
    }

    @Test
    fun `restore writes back previous override`() {
        val window = FakeWindowBrightnessTarget(current = 0.4f)
        val policy = policy(window)
        val original = policy.capture()
        assertEquals(0.4f, original, 0f)
        policy.setPercent(80)
        assertEquals(0.8f, window.current, 0f)
        policy.restore(original)
        assertEquals(0.4f, window.current, 0f)
    }

    @Test
    fun `current percent prefers window override and skips system read`() {
        val window = FakeWindowBrightnessTarget(current = 0.5f)
        val system = FakeSystemBrightness(raw = 255)
        val policy = policy(window, system)
        assertEquals(50, policy.currentPercent())
        assertEquals(0, system.reads)
    }

    @Test
    fun `current percent uses system brightness when window has no override`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val system = FakeSystemBrightness(raw = 128)
        val policy = policy(window, system)
        // 128 / 255 ≈ 50.2% → 50%；绝不能再是进页写死的 62。
        assertEquals(50, policy.currentPercent())
        assertEquals(1, system.reads)
        assertNotEquals(62, policy.currentPercent())
    }

    @Test
    fun `current percent maps system range boundaries`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        assertEquals(100, policy(window, FakeSystemBrightness(raw = 255)).currentPercent())
        assertEquals(1, policy(window, FakeSystemBrightness(raw = 0)).currentPercent())
    }

    @Test
    fun `current percent falls back to neutral when platform unknown`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window, FakeSystemBrightness(raw = null))
        assertEquals(BRIGHTNESS_FALLBACK_PERCENT, policy.currentPercent())
        assertNotEquals(62, policy.currentPercent())
        // 系统值越界同样视作"读不到"。
        assertEquals(
            BRIGHTNESS_FALLBACK_PERCENT,
            policy(window, FakeSystemBrightness(raw = 300)).currentPercent(),
        )
    }

    @Test
    fun `set percent clamps to one through hundred and writes override`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window)
        assertEquals(1, policy.setPercent(-20))
        assertEquals(0.01f, window.current, 0.0001f)
        assertEquals(100, policy.setPercent(150))
        assertEquals(1f, window.current, 0.0001f)
        assertEquals(1, policy.setPercent(0))
        assertEquals(0.01f, window.current, 0.0001f)
    }

    @Test
    fun `set percent skips window write when platform already at target`() {
        val window = FakeWindowBrightnessTarget(current = 0.5f)
        val policy = policy(window)
        assertEquals(50, policy.setPercent(50))
        assertEquals(0, window.writes)
        assertTrue(window.current == 0.5f)
    }

    @Test
    fun `first slider write overrides a none window`() {
        val window = FakeWindowBrightnessTarget(current = BRIGHTNESS_NONE)
        val policy = policy(window)
        assertEquals(30, policy.setPercent(30))
        assertEquals(1, window.writes)
        assertEquals(0.3f, window.current, 0.0001f)
    }
}
