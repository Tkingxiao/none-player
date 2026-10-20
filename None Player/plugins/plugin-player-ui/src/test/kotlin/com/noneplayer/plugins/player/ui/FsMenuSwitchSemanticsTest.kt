package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noneplayer.core.ui.NonePlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A27/REV-14：独占播放页「字幕设置」里 [FsMenuSwitchRow] 的开关语义。
 *
 * 该行此前是 `clickable(Role.Button)` 整行 + 里层 [com.noneplayer.core.ui.NpSwitch]
 * `toggleable`，真机 uiautomator dump 里一行会同时出现「行」与「开关」两个可聚焦节点，
 * 读屏会把同一个开关报两遍。这里钉住：**整行只有一个可拨动节点**，报 On/Off 且
 * Role=Switch，且点整行能切换。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FsMenuSwitchSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `switch row exposes a single toggleable node`() {
        val state = mutableStateOf(false)
        compose.setContent {
            NonePlayerTheme {
                FsMenuSwitchRow(
                    label = "字幕背景条",
                    checked = state.value,
                    onCheckedChange = { state.value = it },
                )
            }
        }

        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable())
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
    }
}
