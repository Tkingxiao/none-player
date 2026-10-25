package com.noneplayer.plugins.player.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E228 播放页键盘增补两件的纯函数面：
 *  - 数字 0-9（含小键盘）跳到片长 × n/10；
 *  - 括号键在 [SPEED_PRESETS] 六档表上步进。
 *
 * 键位识别（[digitForKeyCode]）放 SessionKeys 与共用表同址，这里连同页级语义
 * 一起钉住——档位语义一旦漂动，这里先红。
 */
class PlayerPageKeysTest {

    @Test
    fun `主键盘与小键盘数字都认`() {
        assertEquals(0, digitForKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(9, digitForKeyCode(KeyEvent.KEYCODE_9))
        assertEquals(0, digitForKeyCode(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(9, digitForKeyCode(KeyEvent.KEYCODE_NUMPAD_9))
        // 非数字不认（返回 null → 播放页不吞键，落回默认路径）
        assertNull(digitForKeyCode(KeyEvent.KEYCODE_A))
        assertNull(digitForKeyCode(KeyEvent.KEYCODE_SPACE))
        assertNull(digitForKeyCode(KeyEvent.KEYCODE_NUMPAD_DOT))
    }

    @Test
    fun `数字跳转目标是片长乘十分位`() {
        assertEquals(180_000L, digitJumpTargetMs(600_000L, 3))
        // 0 = 回片头
        assertEquals(0L, digitJumpTargetMs(600_000L, 0))
        // 整除向下取整，永不越片长
        assertEquals(488_897L, digitJumpTargetMs(543_219L, 9))
        // 时长未知（装载中/直播型）没有目标可去，调用侧据此不吞键
        assertNull(digitJumpTargetMs(0L, 5))
        assertNull(digitJumpTargetMs(-1L, 5))
    }

    @Test
    fun `括号键在档上时移到相邻档`() {
        assertEquals(0.5f, stepSpeedPreset(1.0f, up = false))
        assertEquals(1.5f, stepSpeedPreset(1.0f, up = true))
    }

    @Test
    fun `括号键在档间值时先就近归表`() {
        // 0.05 微调的产物 1.25：下调归 1.0、上调归 1.5
        assertEquals(1.0f, stepSpeedPreset(1.25f, up = false))
        assertEquals(1.5f, stepSpeedPreset(1.25f, up = true))
    }

    @Test
    fun `端点钳制不出表`() {
        assertEquals(3.0f, stepSpeedPreset(3.0f, up = true))
        assertEquals(0.25f, stepSpeedPreset(0.25f, up = false))
        // 表外值（微调上限 4.0）：就近归表——上调回落端档 3.0，下调也归到 3.0（最近档）
        assertEquals(3.0f, stepSpeedPreset(4.0f, up = true))
        assertEquals(3.0f, stepSpeedPreset(4.0f, up = false))
    }
}
