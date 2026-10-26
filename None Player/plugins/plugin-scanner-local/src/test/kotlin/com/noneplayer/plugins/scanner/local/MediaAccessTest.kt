package com.noneplayer.plugins.scanner.local

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaAccess] / [MediaAccessPolicy] 的纯 JVM 测试（REV-13）。
 *
 * 不碰 Android 运行时：`evaluate` / `declaredPermissions` 只做集合与版本号判断，
 * 权限常量在编译期内联，所以这里连 Robolectric 都不需要。
 */
class MediaAccessTest {

    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val audio = Manifest.permission.READ_MEDIA_AUDIO
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE
    private val writeStorage = Manifest.permission.WRITE_EXTERNAL_STORAGE

    // ---- declaredPermissions ----

    @Test
    fun `Android 10 及以下申请读写存储权限`() {
        // E233：26–29 档删除设备文件只能靠写权限（无「所有文件访问」/删除请求）
        assertEquals(listOf(storage, writeStorage), MediaAccessPolicy.declaredPermissions(26))
        assertEquals(listOf(storage, writeStorage), MediaAccessPolicy.declaredPermissions(29))
    }

    @Test
    fun `Android 11-12L 只申请读存储`() {
        // 30 起 WRITE_EXTERNAL_STORAGE 失效（清单 maxSdkVersion=29），不该再申请
        assertEquals(listOf(storage), MediaAccessPolicy.declaredPermissions(30))
        assertEquals(listOf(storage), MediaAccessPolicy.declaredPermissions(32))
    }

    @Test
    fun `Android 13 申请视频与音频两条`() {
        assertEquals(
            listOf(video, audio),
            MediaAccessPolicy.declaredPermissions(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun `Android 14 追加仅选中照片与视频权限`() {
        assertEquals(
            listOf(video, audio, selected),
            MediaAccessPolicy.declaredPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    // ---- SDK 28：存储权限一条覆盖视频与音频 ----

    @Test
    fun `sdk 28 无授权时不可用`() {
        val access = MediaAccessPolicy.evaluate(28, emptySet(), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.canQueryVideo)
        assertFalse(access.canQueryAudio)
        assertFalse(access.hasMediaPermission)
        assertFalse(access.isAvailable)
        assertFalse(access.isPartial)
        assertNull(access.note())
        assertEquals(listOf("视频", "音频"), access.deniedCategories())
    }

    @Test
    fun `sdk 28 授予存储权限后视频与音频都可见`() {
        val access = MediaAccessPolicy.evaluate(28, setOf(storage), safFolderCount = 0, allFilesAccess = false)
        assertTrue(access.legacyStorageGranted)
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.hasMediaPermission)
        assertTrue(access.isAvailable)
        assertFalse(access.isPartial)
        assertNull(access.note())
    }

    @Test
    fun `sdk 28 不认识的细分权限不算数`() {
        // 旧系统上 READ_MEDIA_VIDEO 根本不存在：声明了也拿不到，不能当成可见
        val access = MediaAccessPolicy.evaluate(28, setOf(video), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.canQueryVideo)
        assertFalse(access.canQueryAudio)
        assertFalse(access.hasMediaPermission)
    }

    @Test
    fun `sdk 28 所有文件访问绕开媒体权限`() {
        val access = MediaAccessPolicy.evaluate(28, emptySet(), safFolderCount = 0, allFilesAccess = true)
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.hasMediaPermission)
        assertFalse(access.isPartial)
    }

    // ---- SDK 33：视频/音频分开 ----

    @Test
    fun `sdk 33 无授权时不可用`() {
        val access = MediaAccessPolicy.evaluate(33, emptySet(), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.hasMediaPermission)
        assertFalse(access.isAvailable)
        assertFalse(access.isPartial)
        assertNull(access.note())
    }

    @Test
    fun `sdk 33 只授予视频时音频缺失且仍可用`() {
        val access = MediaAccessPolicy.evaluate(33, setOf(video), safFolderCount = 0, allFilesAccess = false)
        assertTrue(access.videoGranted)
        assertFalse(access.audioGranted)
        assertTrue(access.canQueryVideo)
        assertFalse(access.canQueryAudio)
        assertTrue(access.hasMediaPermission)
        assertTrue(access.isAvailable)
        assertTrue(access.isPartial)
        assertEquals("未授予音频权限，媒体库仅显示视频", access.note())
        assertEquals(listOf("音频"), access.deniedCategories())
    }

    @Test
    fun `sdk 33 只授予音频时视频缺失且仍可用`() {
        val access = MediaAccessPolicy.evaluate(33, setOf(audio), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.hasMediaPermission)
        assertTrue(access.isAvailable)
        assertTrue(access.isPartial)
        assertEquals("未授予视频权限，媒体库仅显示音频", access.note())
        assertEquals(listOf("视频"), access.deniedCategories())
    }

    @Test
    fun `sdk 33 全部授予时完整可用`() {
        val access = MediaAccessPolicy.evaluate(33, setOf(video, audio), safFolderCount = 0, allFilesAccess = false)
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.isAvailable)
        assertFalse(access.isPartial)
        assertNull(access.note())
        assertTrue(access.deniedCategories().isEmpty())
    }

    @Test
    fun `sdk 33 所有文件访问时两类都可见`() {
        val access = MediaAccessPolicy.evaluate(33, emptySet(), safFolderCount = 0, allFilesAccess = true)
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.isAvailable)
        assertFalse(access.isPartial)
        assertNull(access.note())
    }

    // ---- SDK 34：追加"仅选中的照片/视频" ----

    @Test
    fun `sdk 34 无授权时不可用`() {
        val access = MediaAccessPolicy.evaluate(34, emptySet(), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.hasMediaPermission)
        assertFalse(access.isAvailable)
        assertFalse(access.isPartial)
    }

    @Test
    fun `sdk 34 仅选中授权时视频可见`() {
        val access = MediaAccessPolicy.evaluate(34, setOf(selected), safFolderCount = 0, allFilesAccess = false)
        assertTrue(access.selectedVisualGranted)
        assertFalse(access.videoGranted)
        assertFalse(access.audioGranted)
        assertTrue(access.canQueryVideo)
        assertFalse(access.canQueryAudio)
        assertTrue(access.isAvailable)
        assertTrue(access.isPartial)
        assertEquals("未授予音频权限，媒体库仅显示视频", access.note())
    }

    @Test
    fun `sdk 34 只授予视频时音频缺失`() {
        val access = MediaAccessPolicy.evaluate(34, setOf(video), safFolderCount = 0, allFilesAccess = false)
        assertTrue(access.canQueryVideo)
        assertFalse(access.canQueryAudio)
        assertTrue(access.isPartial)
        assertEquals(listOf("音频"), access.deniedCategories())
    }

    @Test
    fun `sdk 34 只授予音频时视频缺失`() {
        val access = MediaAccessPolicy.evaluate(34, setOf(audio), safFolderCount = 0, allFilesAccess = false)
        assertFalse(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.isPartial)
        assertEquals(listOf("视频"), access.deniedCategories())
    }

    @Test
    fun `sdk 34 全部授予时完整可用`() {
        val access = MediaAccessPolicy.evaluate(
            34,
            setOf(video, audio, selected),
            safFolderCount = 0,
            allFilesAccess = false,
        )
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertFalse(access.isPartial)
        assertNull(access.note())
    }

    @Test
    fun `sdk 34 所有文件访问时两类都可见`() {
        val access = MediaAccessPolicy.evaluate(34, emptySet(), safFolderCount = 0, allFilesAccess = true)
        assertTrue(access.canQueryVideo)
        assertTrue(access.canQueryAudio)
        assertTrue(access.isAvailable)
        assertFalse(access.isPartial)
    }

    // ---- 可用性与 SAF ----

    @Test
    fun `没有媒体权限但有 SAF 目录时来源仍可用`() {
        val access = MediaAccessPolicy.evaluate(34, emptySet(), safFolderCount = 2, allFilesAccess = false)
        assertFalse(access.hasMediaPermission)
        assertTrue(access.isAvailable)
        assertFalse(access.isPartial)
    }

    @Test
    fun `SAF 目录与部分媒体权限可以叠加`() {
        val access = MediaAccessPolicy.evaluate(33, setOf(video), safFolderCount = 1, allFilesAccess = false)
        assertTrue(access.isAvailable)
        assertTrue(access.isPartial)
    }
}
