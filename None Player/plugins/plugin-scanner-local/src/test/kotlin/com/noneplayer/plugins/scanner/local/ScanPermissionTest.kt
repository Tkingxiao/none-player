package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R0 / REV-13 + REV-12 权限与版本矩阵验收（C 组）。
 *
 * 两条独立契约：
 * 1. **部分授权可用**：只给视频权限时，扫描必须照常出视频、完全不查音频集合
 *    （`audioQueryCount == 0`）、摘要提示音频未覆盖、且不清理音频历史；
 * 2. **旧 Provider 兼容**：API 26–28 的 Provider 没有 `RELATIVE_PATH`/`VOLUME_NAME`
 *    列，投影必须按 SDK 裁剪（REV-12），目录边界退化为 DATA 前缀，扫描不能整批失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanPermissionTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    @Test
    fun `C1 only video granted scans video and never queries audio`() = runBlocking {
        harness.grantVideo()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        // 之前授权音频时留下的历史（权限收缩后不能删）
        harness.seedHistory(
            harness.audioItem(11, "song-a.mp3"),
            harness.audioItem(12, "song-b.mp3"),
        )
        assertEquals(2, harness.historyCount())

        assertTrue("只授权视频也应视为可用（REV-13）", harness.source.isAvailable())

        harness.scan()

        val items = harness.items()
        assertEquals("只授权视频时应只扫到视频项", 2, items.size)
        assertTrue(
            "不应出现音频项（实际 ${items.map { it.mimeType }}）",
            items.none { it.mimeType.startsWith("audio/") },
        )
        assertEquals("没有音频权限时不应查询音频集合", 0, harness.mediaProvider.audioQueryCount)

        val note = harness.lastScanNote()
        assertTrue(
            "摘要应提示音频未覆盖（实际：$note）",
            note?.contains("音频") == true,
        )
        assertEquals("权限收缩不应删除音频历史", 2, harness.historyCount())
    }

    @Test
    fun `C2 legacy provider without relative_path columns still scans via data path`() = runBlocking {
        // sdkInt=28：投影必须不含 RELATIVE_PATH/VOLUME_NAME（REV-12）
        harness = ScanHarness(sdkInt = 28)
        harness.grantLegacyStorage()
        harness.mediaProvider.rejectUnknownColumns = true
        harness.mediaProvider.insertVideo(1, "legacy-a.mp4")
        harness.mediaProvider.insertVideo(2, "legacy-b.mp4")
        harness.setFolders(ScanTestTree.MAIN_TREE)

        // 旧 Provider 收到未知列会直接拒绝查询；扫描必须裁剪投影而不是抛异常
        harness.scan()

        val items = harness.items()
        assertEquals(
            "旧 Provider 下也应扫到 2 个视频（实际 ${items.map { it.id }}）",
            2,
            items.size,
        )
        assertTrue(
            "目录应由 DATA 推导（实际 ${items.map { it.folder }}）",
            items.all { it.folder?.contains("Movies") == true },
        )
        assertEquals("视频查询应真实发生", 1, harness.mediaProvider.videoQueryCount)
        assertFalse(
            "pre-Q 投影不应包含 relative_path（实际 ${harness.mediaProvider.lastVideoProjection}）",
            harness.mediaProvider.lastVideoProjection.orEmpty().contains("relative_path"),
        )
        assertFalse(
            "pre-Q 投影不应包含 volume_name（实际 ${harness.mediaProvider.lastVideoProjection}）",
            harness.mediaProvider.lastVideoProjection.orEmpty().contains("volume_name"),
        )
    }
}
