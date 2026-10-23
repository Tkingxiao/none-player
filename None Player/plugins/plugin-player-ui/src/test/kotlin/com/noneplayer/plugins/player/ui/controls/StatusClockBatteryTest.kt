package com.noneplayer.plugins.player.ui.controls

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusClockBatteryTest {

    @Test
    fun `battery percent requires valid level and scale`() {
        assertEquals(50, batteryPercentOf(50, 100))
        assertEquals(25, batteryPercentOf(50, 200))
        assertEquals(100, batteryPercentOf(100, 100))
        assertEquals(0, batteryPercentOf(0, 100))
        assertNull(batteryPercentOf(-1, 100))
        assertNull(batteryPercentOf(50, 0))
        assertNull(batteryPercentOf(50, -1))
    }

    @Test
    fun `battery percent is clamped into range`() {
        assertEquals(100, batteryPercentOf(200, 100))
    }

    @Test
    fun `only charging status counts as charging`() {
        assertTrue(isChargingStatus(BatteryManager.BATTERY_STATUS_CHARGING))
        assertFalse(isChargingStatus(BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(isChargingStatus(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(isChargingStatus(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    @Test
    fun `low battery starts below fifteen percent`() {
        assertTrue(isLowBattery(0))
        assertTrue(isLowBattery(14))
        assertFalse(isLowBattery(15))
        assertFalse(isLowBattery(null))
    }

    @Test
    fun `content description never invents a percentage`() {
        assertEquals("电量，正在充电", batteryContentDescription(BatteryInfo(41, charging = true)))
        assertEquals("电量未知", batteryContentDescription(BatteryInfo(null, charging = false)))
        assertEquals("电量", batteryContentDescription(BatteryInfo(41, charging = false)))
    }
}
