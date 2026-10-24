package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-07 纯 JVM 单测：私有命令的身份口径（UID）与参数校验。
 *
 * 说明：`privateSessionCommands()` / `validatePrivateCommandArgs(action, Bundle)` 依赖
 * `android.os.Bundle`，而本模块纯 JVM 单测的 android.jar 是桩实现（Bundle.EMPTY 为 null、
 * 构造即抛），无法构造真对象；因此命令清单用 [PRIVATE_COMMAND_ACTIONS] 断言，参数校验走
 * 纯逻辑重载 `validatePrivateCommandArgs(action, hasBooleanArg)`。生产侧 Bundle 版只是
 * 一层 `args.get(key) is Boolean` 适配（见 PrivateCommands.kt）。
 */
class PrivateCommandsTest {

    // ---------- isSameUid：只有同 UID 才算受信 ----------

    @Test
    fun sameUidIsTrusted() {
        assertTrue(isSameUid(10123, 10123))
    }

    @Test
    fun differentUidIsRejected() {
        assertFalse(isSameUid(10200, 10123))
    }

    @Test
    fun systemUidIsNotTrusted() {
        // 系统组件（uid 1000）能连会话，但不是本应用 UI，拿不到私有命令
        assertFalse(isSameUid(1000, 10123))
    }

    @Test
    fun rootUidIsNotTrusted() {
        assertFalse(isSameUid(0, 10123))
    }

    @Test
    fun crossUserUidIsRejected() {
        // 多用户/工作资料：同一 appId、不同 userId 的 UID 也不受信
        assertFalse(isSameUid(1010123, 1020123))
    }

    @Test
    fun invalidUidSentinelIsNotTrusted() {
        // legacy 控制器可能给出无效 UID 哨兵值，一律按"不同"处理
        assertFalse(isSameUid(-1, 10123))
    }

    // ---------- 私有命令清单 ----------

    @Test
    fun privateCommandActionsAreExactlyThreeAndOrdered() {
        assertEquals(listOf("np.scrub", "np.crop", "np.autoplay"), PRIVATE_COMMAND_ACTIONS)
        assertEquals(3, PRIVATE_COMMAND_ACTIONS.size)
        assertEquals(
            "私有命令 action 不允许重复",
            PRIVATE_COMMAND_ACTIONS.size,
            PRIVATE_COMMAND_ACTIONS.distinct().size,
        )
    }

    @Test
    fun privateActionsAreRecognised() {
        PRIVATE_COMMAND_ACTIONS.forEach { action -> assertTrue(action, isPrivateCommand(action)) }
    }

    @Test
    fun standardAndUnknownActionsAreNotPrivate() {
        assertFalse(isPrivateCommand(null))
        assertFalse(isPrivateCommand(""))
        assertFalse(isPrivateCommand("np.unknown"))
        assertFalse(isPrivateCommand("androidx.media3.session.SESSION_COMMAND_PLAY"))
    }

    // ---------- 每条私有命令的参数键 ----------

    @Test
    fun eachPrivateCommandRequiresItsOwnBooleanKey() {
        assertEquals(SCRUB_ARG_ACTIVE, requiredArgKey(SCRUB_COMMAND))
        assertEquals(CROP_ARG_ACTIVE, requiredArgKey(CROP_COMMAND))
        assertEquals(AUTOPLAY_ARG_ENABLED, requiredArgKey(AUTOPLAY_COMMAND))
    }

    @Test
    fun unknownActionHasNoRequiredArgKey() {
        assertEquals(null, requiredArgKey("np.unknown"))
        assertEquals(null, requiredArgKey(null))
    }

    // ---------- 参数校验 ----------

    @Test
    fun missingArgIsRejected() {
        assertFalse(validatePrivateCommandArgs(SCRUB_COMMAND) { false })
    }

    @Test
    fun nonBooleanArgIsRejected() {
        val args = mapOf<String, Any?>(SCRUB_ARG_ACTIVE to 1)
        assertFalse(validatePrivateCommandArgs(SCRUB_COMMAND) { key -> args[key] is Boolean })
    }

    @Test
    fun stringArgIsRejected() {
        // "true" 是字符串而非布尔，不能当成合法参数（防跨进程类型混淆）
        val args = mapOf<String, Any?>(AUTOPLAY_ARG_ENABLED to "true")
        assertFalse(validatePrivateCommandArgs(AUTOPLAY_COMMAND) { key -> args[key] is Boolean })
    }

    @Test
    fun booleanArgIsAccepted() {
        val args = mapOf<String, Any?>(SCRUB_ARG_ACTIVE to true)
        assertTrue(validatePrivateCommandArgs(SCRUB_COMMAND) { key -> args[key] is Boolean })
    }

    @Test
    fun falseIsStillAValidBoolean() {
        // 关擦洗 / 关裁剪 / 关自动连播：false 是合法值，不能被当成"缺参数"
        val args = mapOf<String, Any?>(CROP_ARG_ACTIVE to false)
        assertTrue(validatePrivateCommandArgs(CROP_COMMAND) { key -> args[key] is Boolean })
    }

    @Test
    fun eachCommandValidatesAgainstItsOwnKey() {
        assertTrue(validatePrivateCommandArgs(CROP_COMMAND) { it == CROP_ARG_ACTIVE })
        assertTrue(validatePrivateCommandArgs(AUTOPLAY_COMMAND) { it == AUTOPLAY_ARG_ENABLED })
        // 拿别的命令的键名冒充 → 拒
        assertFalse(validatePrivateCommandArgs(AUTOPLAY_COMMAND) { it == SCRUB_ARG_ACTIVE })
    }

    @Test
    fun unknownActionIsRejectedEvenWithValidLookingArgs() {
        assertFalse(validatePrivateCommandArgs("np.unknown") { true })
        assertFalse(validatePrivateCommandArgs(null) { true })
    }

    // ---------- 擦洗结束的单条命令参数 ----------

    @Test
    fun scrubStartNeedsOnlyActive() {
        assertTrue(validateScrubArgs(active = true, targetMs = null, resume = null))
    }

    @Test
    fun scrubFinishAcceptsTargetAndResume() {
        assertTrue(validateScrubArgs(active = false, targetMs = 12_345L, resume = true))
        assertTrue(validateScrubArgs(active = false, targetMs = null, resume = null))
    }

    @Test
    fun scrubFinishRejectsWrongTypes() {
        assertFalse(validateScrubArgs(active = false, targetMs = "12345", resume = true))
        assertFalse(validateScrubArgs(active = false, targetMs = 12_345L, resume = 1))
    }
}
