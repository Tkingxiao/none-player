package com.noneplayer.plugins.player.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §14 第 12 条：装饰波形的**确定性**与**振幅区间**。
 *
 * 波形不读任何音频数据，形状只由 `mediaKey` 决定——这条单测就是规格写的那句
 * "种子不许掺时间戳/帧号/`Random()`"的可执行版本：一旦有人把逐帧重掷塞回去，
 * 第一个断言立刻红。
 */
class WaveformAmplitudesTest {

    @Test
    fun `同一 mediaKey 两次生成逐元素相等`() {
        val first = waveformAmplitudes("primary:Movies/ink.mp4", 56)
        val second = waveformAmplitudes("primary:Movies/ink.mp4", 56)
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals("第 $i 根 bar 两次生成不一致，形状不再稳定", first[i], second[i], 0f)
        }
    }

    @Test
    fun `振幅全部落在 0_18 到 1_0 区间`() {
        // 取多个 key 与三档 bar 数（28/44/56，§9.6），覆盖正弦与随机项的叠加极值
        val keys = listOf("a.mp4", "primary:Movies/long.mkv", "content://media/external/17", "")
        for (key in keys) {
            for (bars in listOf(28, 44, 56)) {
                val amps = waveformAmplitudes(key, bars)
                assertEquals(bars, amps.size)
                for (i in 0 until bars) {
                    assertTrue("$key[$i]=${amps[i]} 越下界", amps[i] >= 0.18f)
                    assertTrue("$key[$i]=${amps[i]} 越上界", amps[i] <= 1f)
                }
            }
        }
    }

    @Test
    fun `不同 mediaKey 的分布不同`() {
        val a = waveformAmplitudes("first.mp4", 56)
        val b = waveformAmplitudes("second.mp4", 56)
        val differing = a.indices.count { abs(a[it] - b[it]) > 1e-4f }
        // 种子一变，xorshift 的整条序列就换轨：半数以上 bar 应当不同
        assertTrue("两条形谱几乎重合（$differing/56），种子可能没参与运算", differing > a.size / 2)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `bar 数非正时返回空数组不抛异常`() {
        assertEquals(0, waveformAmplitudes("any.mp4", 0).size)
        assertEquals(0, waveformAmplitudes("any.mp4", -1).size)
    }
}
