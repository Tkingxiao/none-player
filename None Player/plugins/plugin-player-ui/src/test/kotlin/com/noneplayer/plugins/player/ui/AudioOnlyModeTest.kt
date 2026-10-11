package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仅听音频的**模式决策**（E129 / 审查 #1、#2）。
 *
 * 这一族缺陷的共性是"**取值分支**写错"——编译器看得过去，UI 走查也未必撞得上，
 * 但用户侧的表现很具体：音频文件露出一个空画面舞台、或者上一部片的仅听状态
 * 跟着跑进下一部片。故把决策从 composable 里提成 [resolveAudioOnlyMode] 钉在这里。
 *
 * 五个用例分别对应：
 * ① 音频首次打开（审查 #1）；
 * ② 视频首次打开（默认必须带画面，不能因为"没记过"就落到音频）；
 * ③ **仅听视频 → 切普通视频**（审查 #2 的回归用例，旧实现必挂）；
 * ④ 记过值与没记过必须区分（旧 `audioOnlyPreferred` 把两者压成同一个 false）；
 * ⑤ 无记忆键可读时仍按媒体类型给默认（真机实测采出的缺口，见该用例注释）。
 */
class AudioOnlyModeTest {

    @Test
    fun `音频文件没记过时默认仅听`() {
        // 审查 #1：音频文件此前会以视频舞台 + 空画面出现，用户看不出音频在不在播
        assertEquals(
            true,
            resolveAudioOnlyMode(storedPreference = null, isAudioMedia = true),
        )
    }

    @Test
    fun `视频文件没记过时默认带画面`() {
        // 对称的另一半：默认值按**媒体类型**给，不是一律 true
        assertEquals(
            false,
            resolveAudioOnlyMode(storedPreference = null, isAudioMedia = false),
        )
    }

    @Test
    fun `仅听视频切到普通视频时不再沿用上一个媒体的模式`() {
        // 审查 #2 的回归用例：上一部片是"仅听视频"（会话 audioOnly 停在 true），
        // 接着打开一个**没记过**的普通视频。旧实现只在偏好为 true 时置位，
        // 为 false 时什么都不做 → 新媒体继承 true，仍以仅听形态打开。
        assertEquals(
            false,
            resolveAudioOnlyMode(storedPreference = null, isAudioMedia = false),
        )
    }

    @Test
    fun `记过 false 与没记过必须区分开`() {
        // 同样是"音频媒体"，一个记过 false、一个没记过：前者必须原样返回 false。
        // 若把两者都压成 false（旧 AppSettings.audioOnlyPreferred 的做法），
        // 就无法给音频文件一个"没记过 → 默认仅听"的落点。
        assertEquals(
            false,
            resolveAudioOnlyMode(storedPreference = false, isAudioMedia = true),
        )
        // 记过 true 的视频：用户明确要过仅听，必须尊重
        assertEquals(
            true,
            resolveAudioOnlyMode(storedPreference = true, isAudioMedia = false),
        )
    }

    @Test
    fun `没有记忆键可读时仍按媒体类型给默认`() {
        // 真机实测采出的缺口（E129）：深链没带 id 时没有记忆键可读——E109 的孤儿桶
        // 正是不该去读它，但**"不该读记忆"不等于"不该决策"**。第一版把这一档写成
        // "不决策"，于是 `noneplayer://player?uri=…wav&mime=audio/wav`（无 id）
        // 实测落回了视频舞台 + 空画面，与审查 #1 的要求相反。
        // 决策函数因此不看键：键的有无只影响"要不要落盘"，由 setAudioOnly 自己判。
        assertEquals(
            true,
            resolveAudioOnlyMode(storedPreference = null, isAudioMedia = true),
        )
        assertEquals(
            false,
            resolveAudioOnlyMode(storedPreference = null, isAudioMedia = false),
        )
    }
}
