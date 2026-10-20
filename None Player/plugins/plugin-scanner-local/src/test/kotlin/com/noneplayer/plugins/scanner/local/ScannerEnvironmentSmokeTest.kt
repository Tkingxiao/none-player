package com.noneplayer.plugins.scanner.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * R0 环境冒烟：验证扫描级回归所需的 Robolectric + DataStore + Room + 假 ContentProvider
 * + 持久化 URI 授权在 Windows 离线环境下确实可用。
 *
 * 这不是验收用例，只是测试基建的自检——失败时说明"假 Provider 注入"路线在本机不可行，
 * 需要换测试策略（见 docs/项目审查与优化路线-2026-10-20.md §6 R0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerEnvironmentSmokeTest {

    @Test
    fun `datastore room fake provider and persisted uri permission all work`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1) DataStore：扫描目录设置可写可读
        val settings = AppSettings(context)
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        settings.setScanFolders(listOf(tree))
        assertEquals(listOf(tree), settings.scanFolderUris.first())

        // 2) Room：历史可写、可清理（REV-01 断言"不删历史"依赖真实库）
        val history = HistoryRepository(context)
        val item = MediaItem(
            id = "ms-1",
            sourceId = "local",
            title = "a.mp4",
            uri = "content://media/external/video/media/1",
        )
        history.markPlayed(item)
        assertEquals(1, history.count.first())
        assertEquals(1, history.pruneOrphans(emptySet(), "local"))

        // 3) 假 MediaStore Provider：可注册、可查询
        val provider = FakeMediaStoreProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            null,
            null,
            null,
        )
        assertNotNull(cursor)
        assertEquals(0, cursor!!.count)
        cursor.close()

        // 4) 持久化 URI 授权：LocalMediaSource.revokedFolders 依赖它
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(tree),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        assertEquals(1, context.contentResolver.persistedUriPermissions.size)
    }
}

/** 极简假 MediaStore：只回应 query，返回空游标。 */
private class FakeMediaStoreProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: arrayOf(MediaStore.Video.Media._ID))

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
