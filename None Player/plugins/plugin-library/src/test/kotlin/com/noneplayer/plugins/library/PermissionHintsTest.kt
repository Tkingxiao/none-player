package com.noneplayer.plugins.library

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [partialPermissionHint] 的纯 JVM 测试（REV-13）。
 *
 * 只做集合运算，权限常量编译期内联，不需要 Robolectric。
 */
class PermissionHintsTest {

    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val audio = Manifest.permission.READ_MEDIA_AUDIO
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun `声明为空时没有可提示的权限`() {
        assertNull(partialPermissionHint(emptyList(), emptySet()))
        assertNull(partialPermissionHint(emptyList(), setOf(video)))
    }

    @Test
    fun `全部授予时不提示`() {
        assertNull(partialPermissionHint(listOf(video, audio), setOf(video, audio)))
        assertNull(partialPermissionHint(listOf(storage), setOf(storage)))
    }

    @Test
    fun `全部未授予时交给整页空态`() {
        assertNull(partialPermissionHint(listOf(video, audio), emptySet()))
        assertNull(partialPermissionHint(listOf(storage), emptySet()))
    }

    @Test
    fun `只授予视频时提示缺少音频`() {
        assertEquals(
            "仅授予了部分媒体权限（缺少音频），媒体库只显示已授权的部分。",
            partialPermissionHint(listOf(video, audio), setOf(video)),
        )
    }

    @Test
    fun `只授予音频时提示缺少视频`() {
        assertEquals(
            "仅授予了部分媒体权限（缺少视频），媒体库只显示已授权的部分。",
            partialPermissionHint(listOf(video, audio), setOf(audio)),
        )
    }

    @Test
    fun `重复声明去重后仍按缺失类别提示`() {
        assertEquals(
            "仅授予了部分媒体权限（缺少音频），媒体库只显示已授权的部分。",
            partialPermissionHint(listOf(video, audio, video), setOf(video)),
        )
    }

    @Test
    fun `未知权限原样列出而不是悄悄丢掉`() {
        val custom = "com.example.CUSTOM_MEDIA"
        assertEquals(
            "仅授予了部分媒体权限（缺少$custom），媒体库只显示已授权的部分。",
            partialPermissionHint(listOf(video, custom), setOf(video)),
        )
    }

    @Test
    fun `Android 14 完整媒体授权时缺少替代权限不算部分授权`() {
        // 用户选了"允许全部"：系统只给 VIDEO/AUDIO，VISUAL_USER_SELECTED 不会被授予
        assertNull(partialPermissionHint(listOf(video, audio, selected), setOf(video, audio)))
    }

    @Test
    fun `Android 14 仅选中授权时提示缺少完整视频与音频`() {
        val hint = partialPermissionHint(listOf(video, audio, selected), setOf(selected))
        assertTrue(hint!!.contains("视频"))
        assertTrue(hint.contains("音频"))
        assertTrue(hint.contains("部分媒体权限"))
    }

    @Test
    fun `Android 14 只授予视频时提示缺少音频`() {
        assertEquals(
            "仅授予了部分媒体权限（缺少音频），媒体库只显示已授权的部分。",
            partialPermissionHint(listOf(video, audio, selected), setOf(video)),
        )
    }
}
