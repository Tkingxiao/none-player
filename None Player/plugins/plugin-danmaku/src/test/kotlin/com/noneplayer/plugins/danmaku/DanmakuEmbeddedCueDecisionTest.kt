package com.noneplayer.plugins.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E224：内嵌 cue 的 spawn 决策——屏蔽词与文件通路同口径。
 * （此前 isBlocked 只过滤文件解析结果，内嵌 cue 直接 spawn 绕过了屏蔽词。）
 */
class DanmakuEmbeddedCueDecisionTest {

    private val blocklist = listOf("广告", "low", "LOW")

    @Test
    fun `normal cue spawns`() {
        assertEquals(
            EmbeddedCueDecision.Spawn,
            embeddedCueDecision("正常弹幕", lastText = null, blocklist = blocklist),
        )
    }

    @Test
    fun `duplicate text skips without touching lastText`() {
        assertEquals(
            EmbeddedCueDecision.SkipDuplicate,
            embeddedCueDecision("同一条", lastText = "同一条", blocklist = blocklist),
        )
        // 空文本 = 无 cue：跳过（调用方不更新 lastText）
        assertEquals(
            EmbeddedCueDecision.SkipDuplicate,
            embeddedCueDecision("", lastText = null, blocklist = blocklist),
        )
    }

    @Test
    fun `blocked text skips and is case insensitive`() {
        assertEquals(
            EmbeddedCueDecision.SkipBlocked,
            embeddedCueDecision("这是广告", lastText = null, blocklist = blocklist),
        )
        assertEquals(
            EmbeddedCueDecision.SkipBlocked,
            embeddedCueDecision("so low quality", lastText = null, blocklist = blocklist),
        )
    }

    @Test
    fun `blocked cue does not swallow the next different cue`() {
        // 屏蔽词命中后 lastText 记为被屏蔽文本：紧随其后的不同 cue 仍正常放行
        val blocked = embeddedCueDecision("这是广告", lastText = null, blocklist = blocklist)
        assertEquals(EmbeddedCueDecision.SkipBlocked, blocked)
        assertEquals(
            EmbeddedCueDecision.Spawn,
            embeddedCueDecision("正常弹幕", lastText = "这是广告", blocklist = blocklist),
        )
    }

    @Test
    fun `empty blocklist never blocks`() {
        assertEquals(
            EmbeddedCueDecision.Spawn,
            embeddedCueDecision("任意文本", lastText = null, blocklist = emptyList()),
        )
    }
}
