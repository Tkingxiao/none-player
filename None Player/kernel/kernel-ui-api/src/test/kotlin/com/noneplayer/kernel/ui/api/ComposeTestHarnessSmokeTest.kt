package com.noneplayer.kernel.ui.api

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric + Compose 单测基座的冒烟用例。
 *
 * 存在的理由不是断言 `Text` 能画出来，而是把 E132 那批行为级回归的**前置条件**钉住：
 * NATIVE 图形模式、SDK 34（Robolectric 4.14 支持的上限，compileSdk 36 还没有对应镜像）、
 * 以及 `ui-test-manifest` 提供的 Activity 进得到单测资源。这三条任何一条塌了，
 * `NpFormHandoffBehaviorTest` 会以"起不来"而不是"判错"的形式失败——先让它响在这里。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComposeTestHarnessSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compose rule recomposes with side effects`() {
        var effects = 0
        compose.setContent {
            androidx.compose.runtime.SideEffect { effects++ }
            Text("harness")
        }
        compose.onNodeWithText("harness").assertExists()
        // 首次组合的 SideEffect 必须已经跑过——行为级用例全靠这个时序。
        org.junit.Assert.assertEquals(1, effects)
    }
}
