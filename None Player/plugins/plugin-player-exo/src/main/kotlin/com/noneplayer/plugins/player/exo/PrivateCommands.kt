package com.noneplayer.plugins.player.exo

import android.os.Bundle
import android.os.Process
import androidx.media3.session.SessionCommand

/**
 * 私有会话命令的唯一定义处（REV-07）。
 *
 * 背景：PlaybackService 是 exported 的 `MediaSessionService`（系统媒体通知/车机/蓝牙
 * 都要能连上，不能关 exported），而 np.scrub / np.crop / np.autoplay 是**只给本应用 UI
 * 用**的扩展命令（MediaController 上无对应公开 API，必须经会话侧 ExoPlayer 转发）。
 * 修复前 onConnect 对**所有**控制器都授予这三条命令、onCustomCommand 也不复核调用方身份，
 * 第三方 App 只要连上会话就能切换擦洗模式/画面裁剪/自动连播。
 *
 * 修复口径：
 * 1. 只有与宿主同 UID 的控制器才被授予这三条命令（见 [isSameUid] / [privateSessionCommands]）；
 * 2. onCustomCommand 收到私有命令时再次复核 UID 与参数（见 [validatePrivateCommandArgs]）——
 *    命令授予是连接期快照，不能当作授权凭据；
 * 3. 常量从 PlaybackService 迁到本文件，会话侧与引擎侧共用一份，避免两边字符串漂移。
 *
 * 本文件刻意保持"纯逻辑 + 一层薄 android 适配"：除 [privateSessionCommands] 与 Bundle 版
 * 校验外没有 Android 依赖，便于在纯 JVM 单测里覆盖 UID/参数判定。
 */

/** 擦洗命令：UI 拖动进度条时切换 seek 精度（会话侧 API 不在 MediaController 暴露） */
internal const val SCRUB_COMMAND = "np.scrub"
internal const val SCRUB_ARG_ACTIVE = "active"

/** 画面裁剪命令：UI「画面」面板切换 cover/fit（同样只在会话侧暴露） */
internal const val CROP_COMMAND = "np.crop"
internal const val CROP_ARG_ACTIVE = "active"

/**
 * 自动连播命令（设置页「播放 · 自动连播下一集」的引擎侧落点）。
 *
 * `setPauseAtEndOfMediaItems` 是 ExoPlayer 而非 `Player` 接口上的方法，MediaController
 * 调不到，与 np.scrub / np.crop 同路转发。关闭自动连播时置 true：每条播完停在末帧。
 */
internal const val AUTOPLAY_COMMAND = "np.autoplay"
internal const val AUTOPLAY_ARG_ENABLED = "enabled"

/**
 * 私有命令 action 清单（顺序即 onConnect 的授予顺序）。
 *
 * [privateSessionCommands] 由它派生，单测以本清单断言"恰好三条、action 正确"——
 * `SessionCommand` 构造要 `android.os.Bundle`，而纯 JVM 单测里 android.jar 是桩实现
 * （Bundle.EMPTY 为 null、构造抛异常），拿不到真对象。
 */
internal val PRIVATE_COMMAND_ACTIONS: List<String> =
    listOf(SCRUB_COMMAND, CROP_COMMAND, AUTOPLAY_COMMAND)

/**
 * 调用方控制器是否与宿主同 UID。
 *
 * 同进程的 MediaController（本应用 UI）uid == 宿主 uid；第三方 App、系统组件
 * （uid 1000）、root（uid 0）都不等。media3 对 legacy 控制器可能给出 `Process.INVALID_UID`
 * 之类哨兵值，一律按"不同"处理。
 */
internal fun isSameUid(controllerUid: Int, myUid: Int = Process.myUid()): Boolean =
    controllerUid == myUid

/**
 * onConnect 授予的私有命令集：只在受信（同 UID）控制器上调用。
 *
 * `Bundle.EMPTY` 只作为 SessionCommand 的占位 extras（命令参数走 onCustomCommand 的 args）。
 */
internal fun privateSessionCommands(): List<SessionCommand> =
    PRIVATE_COMMAND_ACTIONS.map { action -> SessionCommand(action, Bundle.EMPTY) }

/** 是否为三条私有命令之一（null / 标准会话命令 → false，交给 super 处理） */
internal fun isPrivateCommand(action: String?): Boolean =
    action != null && PRIVATE_COMMAND_ACTIONS.any { known -> known == action }

/** 私有命令必须携带的布尔参数键；未知 action 返回 null */
internal fun requiredArgKey(action: String?): String? = when (action) {
    SCRUB_COMMAND -> SCRUB_ARG_ACTIVE
    CROP_COMMAND -> CROP_ARG_ACTIVE
    AUTOPLAY_COMMAND -> AUTOPLAY_ARG_ENABLED
    else -> null
}

/**
 * 校验私有命令参数：必须带齐该命令的布尔参数，缺键/类型不符（Int、String…）都判非法。
 *
 * 只在 [isPrivateCommand] 为 true 时才有意义；未知 action 返回 false（保守拒绝）。
 */
internal fun validatePrivateCommandArgs(action: String?, args: Bundle): Boolean =
    validatePrivateCommandArgs(action) { key -> args.get(key) is Boolean }

/**
 * [validatePrivateCommandArgs] 的纯逻辑内核（单测入口）。
 *
 * 传进来的 [hasBooleanArg] 判断"给定参数键是否存在且为布尔值"，生产侧用 Bundle 实现，
 * 单测用 Map 实现——这样不用构造 android.os.Bundle 也能覆盖缺键/类型错/合法三分支。
 */
internal fun validatePrivateCommandArgs(action: String?, hasBooleanArg: (String) -> Boolean): Boolean {
    val key = requiredArgKey(action) ?: return false
    return hasBooleanArg(key)
}
