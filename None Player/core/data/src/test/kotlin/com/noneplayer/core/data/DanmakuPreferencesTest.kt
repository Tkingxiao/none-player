package com.noneplayer.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuPreferencesTest {

    @Test
    fun `无键时取默认值`() {
        assertEquals(
            DanmakuPreferences(),
            AppSettings.danmakuPreferencesOf(emptyPreferences()),
        )
    }

    @Test
    fun `六项一次解析并按既有规则钳制`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[booleanPreferencesKey("danmaku_enabled")] = false
            this[stringPreferencesKey("danmaku_area")] = "0.33"
            this[stringPreferencesKey("danmaku_speed")] = "2.0"
            this[intPreferencesKey("danmaku_density")] = 80
            this[stringPreferencesKey("danmaku_font_scale")] = "9.0"
            this[stringPreferencesKey("danmaku_alpha")] = "0.25"
        }

        val result = AppSettings.danmakuPreferencesOf(prefs)

        assertEquals(false, result.enabled)
        assertEquals(0.33f, result.areaFraction, 0.001f)
        assertEquals(2.0f, result.speed, 0.001f)
        assertEquals(80, result.maxOnScreen)
        assertEquals(3.0f, result.fontScale, 0.001f)
        assertEquals(0.25f, result.alpha, 0.001f)
    }

    @Test
    fun `无法解析的字符串回落到默认值`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("danmaku_area")] = "not-a-number"
            this[stringPreferencesKey("danmaku_speed")] = ""
            this[stringPreferencesKey("danmaku_alpha")] = "NaN"
        }

        val result = AppSettings.danmakuPreferencesOf(prefs)

        assertEquals(0.5f, result.areaFraction, 0.001f)
        assertEquals(1.0f, result.speed, 0.001f)
        // "NaN".toFloatOrNull() 是 NaN，保持原解析行为（不额外改动语义）
        assertEquals(true, result.alpha.isNaN())
    }
}
