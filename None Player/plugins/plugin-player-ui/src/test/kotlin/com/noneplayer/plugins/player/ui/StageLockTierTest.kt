package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定档转移语义（D3 半锁，任务文档 §3）：
 * - 解锁钮点按：未锁 → 整页锁（既有行为不变），任一锁定态 → 解除；
 * - 键盘 `L`：未锁/半锁 → 整页锁（半锁**升级**，不降回），整页锁 → 解除；
 * - 「半锁」菜单行：未锁 ↔ 半锁互切。
 */
class StageLockTierTest {

    @Test
    fun `badge tap enters full lock from none`() {
        assertEquals(StageLockTier.Full, StageLockTier.None.onBadgeTap())
    }

    @Test
    fun `badge tap unlocks any locked tier`() {
        assertEquals(StageLockTier.None, StageLockTier.Half.onBadgeTap())
        assertEquals(StageLockTier.None, StageLockTier.Full.onBadgeTap())
    }

    @Test
    fun `lock key escalates half lock to full and unlocks full`() {
        assertEquals(StageLockTier.Full, StageLockTier.None.onLockKey())
        assertEquals(StageLockTier.Full, StageLockTier.Half.onLockKey())
        assertEquals(StageLockTier.None, StageLockTier.Full.onLockKey())
    }

    @Test
    fun `half lock menu toggles between none and half`() {
        assertEquals(StageLockTier.Half, StageLockTier.None.onHalfLockMenu())
        assertEquals(StageLockTier.None, StageLockTier.Half.onHalfLockMenu())
    }

    @Test
    fun `locked and fullLocked predicates match tier layout`() {
        assertEquals(false, StageLockTier.None.locked)
        assertEquals(true, StageLockTier.Half.locked)
        assertEquals(true, StageLockTier.Full.locked)
        assertEquals(false, StageLockTier.Half.fullLocked)
        assertEquals(true, StageLockTier.Full.fullLocked)
    }
}
