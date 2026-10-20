package com.noneplayer.plugins.player.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noneplayer.core.ui.NonePlayerTheme
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.plugins.player.ui.controls.NpSeekBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R4 / A27 独立验收：触控几何与 Popup 键盘语义（只写测试，不改生产）。
 *
 * 判据来自 task-18 的任务卡与设计红线：
 * 1. 进度条竖屏拆行（Split）那条路现传 18dp —— 验收要求**组件自身**把触控带
 *    收敛到 ≥ [NpDimens.touchTarget]（44dp），而不是依赖每个调用方记得改；
 * 2. 倍速微调步进钮现为 34dp（`FsMenuStepButton` 的 `heightIn(min = 34.dp)`）——
 *    验收要求 ≥44dp；通过公共入口 [FsSpeedMenuContent] 渲染后量语义节点高度；
 * 3. 菜单 Popup（[FsDropdown]）打开时**自身**要消费 Esc 并收起；
 *    现实现只有 PopupProperties(focusable=true) 与 onDismissRequest，没有按键路径。
 *
 * 这些断言在当前 HEAD 上应为红（实现未落地），task-18 落地后转绿。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class R4TouchGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            NonePlayerTheme(content = content)
        }
    }

    /** 拆行进度条即使调用方仍传 18dp，实际触控高度也不得低于 44dp。 */
    @Test
    fun splitRowSeekBarCoercesTouchBandToRedLine() {
        setContent {
            NpSeekBar(
                durationMs = 60_000L,
                displayPositionMs = remember { mutableFloatStateOf(0f) },
                bufferedPositionMs = remember { mutableLongStateOf(0L) },
                dragging = false,
                onScrubStart = {},
                onScrubChange = {},
                onScrubEnd = {},
                onScrubCancel = {},
                modifier = Modifier.testTag("r4-seek"),
                // 复刻 PlayerScreen 竖屏拆行调用点当前传的 18dp（A27 要修的值）
                touchBand = 18.dp,
            )
        }

        compose.onNodeWithTag("r4-seek").assertHeightIsAtLeast(NpDimens.touchTarget)
    }

    /** 默认调用路径同样不得低于红线（防实现把默认值也改小）。 */
    @Test
    fun defaultSeekBarTouchBandMeetsRedLine() {
        setContent {
            NpSeekBar(
                durationMs = 60_000L,
                displayPositionMs = remember { mutableFloatStateOf(0f) },
                bufferedPositionMs = remember { mutableLongStateOf(0L) },
                dragging = false,
                onScrubStart = {},
                onScrubChange = {},
                onScrubEnd = {},
                onScrubCancel = {},
                modifier = Modifier.testTag("r4-seek-default"),
            )
        }

        compose.onNodeWithTag("r4-seek-default").assertHeightIsAtLeast(NpDimens.touchTarget)
    }

    /** 倍速页的两个微调步进钮必须 ≥44dp 命中高。 */
    @Test
    fun speedStepButtonsMeetTouchTarget() {
        setContent {
            FsSpeedMenuContent(
                speed = 1.0f,
                onPick = {},
                onNudge = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("-0.05").assertHeightIsAtLeast(NpDimens.touchTarget)
        compose.onNodeWithText("+0.05").assertHeightIsAtLeast(NpDimens.touchTarget)
    }

    /** 设置开关行必须整行可点（命中区不再只有 46×27dp 的开关本体）。 */
    @Test
    fun settingsSwitchRowIsClickableAsWholeRow() {
        val checked = mutableStateOf(false)
        setContent {
            FsMenuSwitchRow(
                label = "自动连播下一集",
                checked = checked.value,
                onCheckedChange = { checked.value = it },
            )
        }

        val row = compose.onNodeWithText("自动连播下一集")
        row.assertHeightIsAtLeast(NpDimens.touchTarget)
        row.performClick()
        assertTrue("点击整行任意位置都应切换开关", checked.value)
    }

    /** Popup 内方向键可遍历菜单行，回车确认落到当前聚焦行（键盘可用性验收）。 */
    @Test
    fun dropdownArrowAndEnterActivateFocusedRow() {
        var picked: String? = null
        setContent {
            FsDropdown(
                anchor = FsMenuAnchor(rect = Rect(0f, 120f, 140f, 160f)),
                onDismiss = {},
            ) { _, _ ->
                FsMenuRow(label = "R4 第一项", onClick = { picked = "first" })
                FsMenuRow(label = "R4 第二项", onClick = { picked = "second" })
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("R4 第一项").assertIsFocused()
        compose.onNodeWithText("R4 第一项").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("R4 第二项").assertIsFocused()
        compose.onNodeWithText("R4 第二项").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals("回车必须确认当前聚焦行（第二项）", "second", picked)
    }

    /** Popup 打开时 Esc 由菜单自身消费并收起（不能穿透到页面级返回）。 */
    @Test
    fun dropdownConsumesEscapeAndDismisses() {
        var dismissed = false
        setContent {
            FsDropdown(
                anchor = FsMenuAnchor(rect = Rect(0f, 120f, 140f, 160f)),
                onDismiss = { dismissed = true },
            ) { _, _ ->
                Text("R4 菜单项")
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("R4 菜单项").assertExists()
        compose.onNodeWithText("R4 菜单项").performKeyInput { pressKey(Key.Escape) }
        // 收起走退场动画（Field 档），时钟推过动画时长再看 onDismiss。
        compose.mainClock.advanceTimeBy(1_000L)
        assertTrue("Esc 必须由 Popup 自身消费并触发 onDismiss", dismissed)
    }
}
