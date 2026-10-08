package com.noneplayer.plugins.player.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话级快捷键映射表（E99）。
 *
 * 只测"哪颗键映射到什么、步进多大"——执行侧（seek 钳位、音量还原）依赖
 * Android 运行时，走设备验证。这张表是播放页与三栏浏览态共用的唯一真相，
 * 键语义一旦漂动，这里先红。
 */
class SessionKeysTest {

    @Test
    fun `空格是播放暂停`() {
        assertEquals(SessionKeyCommand.PlayPause, routeSessionKey(KeyEvent.KEYCODE_SPACE, shiftPressed = false))
    }

    @Test
    fun `左右方向键是正负 5 秒`() {
        assertEquals(
            SessionKeyCommand.Seek(5_000L),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_RIGHT, shiftPressed = false),
        )
        assertEquals(
            SessionKeyCommand.Seek(-5_000L),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_LEFT, shiftPressed = false),
        )
    }

    @Test
    fun `Shift 只放大步进不改方向语义`() {
        assertEquals(
            SessionKeyCommand.Seek(30_000L),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_RIGHT, shiftPressed = true),
        )
        assertEquals(
            SessionKeyCommand.Seek(-30_000L),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_LEFT, shiftPressed = true),
        )
    }

    @Test
    fun `上下方向键是正负 5 个百分点音量`() {
        assertEquals(
            SessionKeyCommand.Volume(5),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_UP, shiftPressed = false),
        )
        assertEquals(
            SessionKeyCommand.Volume(-5),
            routeSessionKey(KeyEvent.KEYCODE_DPAD_DOWN, shiftPressed = false),
        )
    }

    @Test
    fun `M 是静音，Esc 与 F 不进共用表`() {
        assertEquals(SessionKeyCommand.Mute, routeSessionKey(KeyEvent.KEYCODE_M, shiftPressed = false))
        // Esc 在窗口层就被系统当成"返回"投递掉，兜底派发收不到；播放页自己处理它
        // 的弹层链。F/Ctrl+O 需要导航与文件选择器，只有播放页做得到。
        assertNull(routeSessionKey(KeyEvent.KEYCODE_ESCAPE, shiftPressed = false))
        assertNull(routeSessionKey(KeyEvent.KEYCODE_F, shiftPressed = false))
    }

    @Test
    fun `音量档位换算四舍五入而不是截断`() {
        // 15 档设备上一档 ≈6.7%：33%+5%=38% 必须落到 6 档（截断会停在 5 档，"按了没反应"）
        assertEquals(6, volumeIndexFor(15, 38))
        assertEquals(5, volumeIndexFor(15, 33))
        assertEquals(0, volumeIndexFor(15, 0))
        assertEquals(15, volumeIndexFor(15, 100))
        // 越界与除零护栏
        assertEquals(15, volumeIndexFor(15, 999))
        assertEquals(0, volumeIndexFor(0, 50))
    }

    @Test
    fun `不认识的键返回空，绝不吞键`() {
        // 字母键与系统音量硬键都不在共用表里——
        // 认了就等于在浏览态把别的界面行为抢走
        assertNull(routeSessionKey(KeyEvent.KEYCODE_A, shiftPressed = false))
        assertNull(routeSessionKey(KeyEvent.KEYCODE_VOLUME_UP, shiftPressed = false))
    }
}
