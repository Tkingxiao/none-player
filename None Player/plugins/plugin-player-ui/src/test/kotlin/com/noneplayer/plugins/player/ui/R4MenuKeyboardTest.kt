package com.noneplayer.plugins.player.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noneplayer.core.ui.NonePlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R4 / A27 键盘语义：菜单 Popup 内的焦点进入、方向遍历、确认与 Esc 退出。
 *
 * 与独立验收 `R4TouchGeometryTest.dropdownConsumesEscapeAndDismisses` 互补：
 * 那条用的是「没有任何可聚焦行」的退化内容（焦点只能落在容器上），
 * 这里覆盖真实菜单（[FsMenuRow] 可聚焦）下的四条键盘路径：
 * 1. 打开菜单焦点送进第一项（此前页面级 onPreviewKeyEvent 把方向键吞掉、
 *    菜单又是独立 Popup，键盘用户根本够不到菜单项）；
 * 2. 方向键在菜单项之间移动（焦点系统遍历，不被页面根节点截胡）；
 * 3. 回车/空格的确认语义落到菜单行（点击 = 确认）；
 * 4. 焦点在菜单行上时 Esc 仍由菜单自身消费并收起（不能穿透到页面级返回）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class R4MenuKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun menuAnchor(): FsMenuAnchor =
        FsMenuAnchor(rect = Rect(0f, 120f, 140f, 160f))

    /** 打开菜单后焦点应已进入第一项（键盘用户不必先 Tab 一整圈）。 */
    @Test
    fun menuFocusEntersFirstRow() {
        compose.setContent {
            NonePlayerTheme {
                FsDropdown(anchor = menuAnchor(), onDismiss = {}) { _, _ ->
                    FsMenuRow(label = "第一项", onClick = {})
                    FsMenuRow(label = "第二项", onClick = {})
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("第一项").assertIsFocused()
    }

    /** 方向键在菜单项间遍历（页面根节点不应再抢占方向键）。 */
    @Test
    fun arrowDownMovesFocusToNextRow() {
        compose.setContent {
            NonePlayerTheme {
                FsDropdown(anchor = menuAnchor(), onDismiss = {}) { _, _ ->
                    FsMenuRow(label = "第一项", onClick = {})
                    FsMenuRow(label = "第二项", onClick = {})
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("第一项").assertIsFocused()
        compose.onNodeWithText("第一项").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("第二项").assertIsFocused()
    }

    /** 回车确认：焦点在菜单行上时按回车应触发该行的点击。 */
    @Test
    fun enterActivatesFocusedRow() {
        var picked = ""
        compose.setContent {
            NonePlayerTheme {
                FsDropdown(anchor = menuAnchor(), onDismiss = {}) { _, _ ->
                    FsMenuRow(label = "倍速", onClick = { picked = "倍速" })
                    FsMenuRow(label = "字幕", onClick = { picked = "字幕" })
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("倍速").assertIsFocused()
        compose.onNodeWithText("倍速").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals("回车应激活当前聚焦的菜单行", "倍速", picked)
    }

    /** 焦点在菜单行上时 Esc 也必须由菜单自身消费并收起（不穿透到页面级返回）。 */
    @Test
    fun escapeDismissesMenuWhenRowFocused() {
        var dismissed = false
        compose.setContent {
            NonePlayerTheme {
                FsDropdown(anchor = menuAnchor(), onDismiss = { dismissed = true }) { _, _ ->
                    FsMenuRow(label = "第一项", onClick = {})
                    FsMenuRow(label = "第二项", onClick = {})
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("第一项").assertIsFocused()
        compose.onNodeWithText("第一项").performKeyInput { pressKey(Key.Escape) }
        // 收起走退场动画（Field 档），时钟推过动画时长再看 onDismiss。
        compose.mainClock.advanceTimeBy(1_000L)
        assertTrue("Esc 必须由菜单自身消费并触发 onDismiss", dismissed)
    }
}
