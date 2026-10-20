package com.noneplayer.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R4-17/REV-14：公共组件的**选中/开关语义**回归。
 *
 * 设计稿的选中态只靠字重、颜色与勾位表达——读屏软件看不到这些，只能读到
 * "菜单项 / 按钮"。这里钉住的是语义层：单选组必须暴露
 * `selectableGroup + selectable(selected) + Role.RadioButton`，开关必须暴露
 * `toggleable(Role.Switch)` 的 On/Off，普通动作不得冒充"已选中"。
 *
 * 用 Robolectric + Compose 测试床（SDK 34 / NATIVE 图形模式）：语义树是 Compose
 * 自己的产物，只有真实组合才拿得到（纯 JVM 单测没有语义树可断言）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NpSelectSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- 单选：NpFilterMenu（下拉筛选）----

    @Test
    fun `filter menu exposes single selection semantics`() {
        compose.setContent {
            NpFilterMenu(
                options = listOf("全部", "电影"),
                selected = "电影",
                onSelect = {},
            )
        }

        compose.onNodeWithText("电影")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("全部")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        // 组语义：读屏会把这一列当成"N 选 1"，而不是散落的 N 个按钮。
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .assertExists()
    }

    // ---- 单选：NpSegmented（分段控件）----

    @Test
    fun `segmented control exposes single selection semantics`() {
        compose.setContent {
            NpSegmented(
                options = listOf("网格", "列表"),
                selected = "列表",
                onSelect = {},
            )
        }

        compose.onNodeWithText("列表")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("网格").assertIsNotSelected()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .assertExists()
    }

    // ---- 开关：NpSwitch 已有 toggleable(Role.Switch)，钉住 On/Off 作为回归护栏 ----

    @Test
    fun `switch exposes on and off toggle semantics`() {
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            NpSwitch(checked = on, onCheckedChange = { on = it })
        }

        compose.onNode(isToggleable())
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
    }

    // ---- 普通动作：可点、Button 角色，但不得冒充「已选中」----

    @Test
    fun `plain action is clickable but carries no selected state`() {
        compose.setContent {
            Box(Modifier.size(48.dp).npClickable {}) {
                Text("普通动作")
            }
        }

        compose.onNodeWithText("普通动作")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    // ---- 语义修饰符（公共 API，供复用组件接线）----

    @Test
    fun `npSelectable exposes selected state and radio role`() {
        compose.setContent {
            Box(Modifier.size(48.dp).npSelectable(selected = true, role = Role.RadioButton) {}) {
                Text("选项 A")
            }
        }

        compose.onNodeWithText("选项 A")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun `npToggleable exposes switch on and off state`() {
        compose.setContent {
            var on by remember { mutableStateOf(true) }
            Box(Modifier.size(48.dp).npToggleable(value = on, onValueChange = { on = it })) {
                Text("开关行")
            }
        }

        compose.onNode(isToggleable())
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
    }
}
