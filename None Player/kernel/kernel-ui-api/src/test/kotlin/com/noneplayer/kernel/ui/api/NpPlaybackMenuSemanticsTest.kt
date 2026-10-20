package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R4-17/REV-14：公共组件 `npPlaybackMenuSwitchRow` 的开关语义回归。
 *
 * 这一类"整行可点 = 拨动开关"的行，此前外层是 `clickable`（Role.Button）、里层
 * [com.noneplayer.core.ui.NpSwitch] 又是 `toggleable`（Role.Switch），读屏会把一行
 * 读成两个节点。这里钉住两件事：**只有一个可拨动节点**、它报 On/Off 且 Role=Switch。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NpPlaybackMenuSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `switch row exposes a single toggleable node`() {
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            npPlaybackMenuSwitchRow(
                label = "弹幕",
                checked = on,
                onCheckedChange = { on = it },
                dark = true,
            )
        }

        // 整行语义 + 里层开关的语义若重复，这里会数出 2。
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable())
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
    }
}
