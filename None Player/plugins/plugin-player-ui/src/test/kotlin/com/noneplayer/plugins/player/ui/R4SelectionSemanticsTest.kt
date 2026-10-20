package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noneplayer.core.ui.NonePlayerTheme
import com.noneplayer.core.ui.NpFilterMenu
import com.noneplayer.core.ui.NpSegmented
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R4 / REV-14 独立验收：公共选项组件的**选中语义**（只写测试，不改生产）。
 *
 * 验收对象与判据（task-17 的合约，不抄实现方断言）：
 * 1. 单选组：选中项 `assertIsSelected`、未选中项 `assertIsNotSelected`，
 *    且组内**恰有一项**选中；
 * 2. 角色：单选行的 Role 必须是 [Role.RadioButton]（不能继续用 npClickable 的
 *    Button 冒充"选中态"）；分段控件的角色不得是 Button（Tab/RadioButton 都算合格）；
 * 3. 开关：ToggleableState + [Role.Switch] 语义（`assertIsOn/assertIsOff` 可用）；
 * 4. 普通动作（NpSettingRow 的 onClick 行）：有点击动作、**没有** Selectable/Selected
 *    语义，不许冒充"单选"。
 *
 * 说明：组件是 core:ui 的公共组件，本模块（player-ui）依赖它，从本模块测试目录引用
 * 公共 API 做集成级验收，等价于在 core:ui 内部验收其对外语义。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class R4SelectionSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            NonePlayerTheme(content = content)
        }
    }

    /** 单选行：选中项必须同时具备 Selected 语义与 RadioButton 角色。 */
    @Test
    fun filterMenuMarksExactlyOneOptionSelectedWithRadioRole() {
        setContent {
            NpFilterMenu(
                options = listOf("选项甲", "选项乙", "选项丙"),
                selected = "选项乙",
                onSelect = {},
            )
        }

        compose.onNodeWithText("选项乙").assertIsSelected()
        compose.onNodeWithText("选项甲").assertIsNotSelected()
        compose.onNodeWithText("选项丙").assertIsNotSelected()

        // 角色：选中行必须报 RadioButton；沿用 Button 角色说明语义没改。
        compose.onNodeWithText("选项乙")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

        // 组语义：同一时刻只允许一个"选中"项。
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }

    /** 分段控件：选中项有 Selected 语义，且角色不能是普通 Button。 */
    @Test
    fun segmentedMarksSelectedSegmentAndDoesNotClaimButtonRole() {
        setContent {
            NpSegmented(
                options = listOf("列表", "网格", "详情"),
                selected = "网格",
                onSelect = {},
            )
        }

        compose.onNodeWithText("网格").assertIsSelected()
        compose.onNodeWithText("列表").assertIsNotSelected()
        compose.onNodeWithText("详情").assertIsNotSelected()
        compose.onAllNodes(isSelected()).assertCountEquals(1)

        // 允许 Tab/RadioButton 等单选取值；只禁止"Button 冒充选中"。
        val role = compose.onNodeWithText("网格")
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Role)
        assertNotEquals("分段控件的选中项不得报 Button 角色", Role.Button, role)
    }

    /** 开关：on/off 可被读屏读出，且点击切换会回调。 */
    @Test
    fun switchExposesOnOffStateAndToggles() {
        val kept = mutableStateOf(true)
        setContent {
            NpSwitch(checked = kept.value, onCheckedChange = { kept.value = it })
        }

        compose.onNode(isToggleableSwitch()).assertIsOn()
        compose.onNode(isToggleableSwitch()).performClick()
        assertEquals("点击开关必须把状态翻到 off", false, kept.value)
        compose.onNode(isToggleableSwitch()).assertIsOff()
    }

    /** 普通动作行：整行可点、有动作语义，但**不得**出现 Selected（那不是单选）。 */
    @Test
    fun plainSettingActionDoesNotPretendToBeSelected() {
        var clicks = 0
        setContent {
            NpSettingRow(
                title = "打开本地文件",
                onClick = { clicks++ },
            )
        }

        val row = compose.onNode(hasText("打开本地文件") and hasClickAction())
        row.assertExists()
        row.assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        row.performClick()
        assertEquals("整行点击回调一次", 1, clicks)
        // 行高须满足 44dp 红线（A27 同一判据，在几何用例里另有专项）。
        val bounds = row.getBoundsInRoot()
        val heightDp = (bounds.bottom - bounds.top).value
        assertTrue(
            "设置行高度不得低于 44dp（实际 ${heightDp}dp）",
            heightDp >= 44f,
        )
    }

    private fun isToggleableSwitch(): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
}
