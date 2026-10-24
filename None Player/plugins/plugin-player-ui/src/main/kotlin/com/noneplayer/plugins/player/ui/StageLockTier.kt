package com.noneplayer.plugins.player.ui

/**
 * 播放页锁定档位（D3 裁定：半锁「只护画面」，与整页锁定并存为更轻一档）。
 *
 * - [None]：无锁定。
 * - [Half] **半锁 = 只护画面**：屏蔽画面区手势（双击 seek / 长按倍速 / 横滑 seek /
 *   竖滑亮度音量），点按唤出控制层照常；控制层、返回键、键盘全部可用，
 *   解锁钮**常驻**（不随控制层自动隐藏）——「要防误触、但还要操作」的轻一档。
 * - [Full] **整页锁定**（旧 `stageLocked` 布尔）：屏蔽全部舞台手势、返回键与键盘，
 *   顶/底控制层整体不出，屏幕上只有解锁钮——口袋/带娃的重一档。
 *
 * 同一时刻至多一档；转移收成纯函数供单测钉住语义：
 * - 解锁钮点按 = 未锁进整页锁 / 任一锁定态解锁（整页锁入口保持既有行为不变）；
 * - 键盘 `L` = 未锁进整页锁、半锁**升级**整页锁、整页锁解锁——升级而不是回半锁：
 *   按 L 的意图是「锁」与「开」，不是降档；
 * - 「半锁」菜单行 = 未锁进半锁 / 半锁解除（整页锁时控制层不出、菜单不可达）。
 */
enum class StageLockTier {
    None, Half, Full;

    /** 解锁钮点按（全屏/竖屏舞台徽标）：未锁 = 整页锁定，半锁/整页锁 = 解除。 */
    fun onBadgeTap(): StageLockTier = if (this == None) Full else None

    /** 键盘 `L`（§8.4）：未锁/半锁 = 整页锁定，整页锁 = 解除。 */
    fun onLockKey(): StageLockTier = if (this == Full) None else Full

    /** 「半锁」菜单行：未锁 = 进半锁，半锁 = 解除。 */
    fun onHalfLockMenu(): StageLockTier = if (this == Half) None else Half

    /** 任一锁定档（手势层的「停动作」开关、徽标图标都看它）。 */
    val locked: Boolean get() = this != None

    /** 整页锁定档（返回键吞没、控制层隐藏、键盘吞没等旧 stageLocked 读点）。 */
    val fullLocked: Boolean get() = this == Full
}
