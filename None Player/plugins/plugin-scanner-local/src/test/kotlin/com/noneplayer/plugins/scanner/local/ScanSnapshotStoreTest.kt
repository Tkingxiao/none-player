package com.noneplayer.plugins.scanner.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * R0/A33：扫描索引快照的原子写与限额读。
 *
 * 回归的原缺陷（见 [LocalMediaSource] 提取前的 saveSnapshot/readSnapshot）：
 *  - rename 失败时先删旧快照再重试 → 连旧快照一起丢；
 *  - readText 无字节限额；
 *  - 音频 content:// 封面被当成文件路径判存在 → 读回时全被丢掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanSnapshotStoreTest {

    private lateinit var root: File
    private lateinit var file: File
    private lateinit var store: ScanSnapshotStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "snap-test-${UUID.randomUUID()}")
        assertTrue("临时目录创建失败", root.mkdirs())
        file = File(root, "scan_index.json")
        store = ScanSnapshotStore(file)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- 1) 往返 ----

    @Test
    fun `往返_20个媒体项写入后读回完全一致`() = runTest {
        val coversDir = File(root, "covers").apply { assertTrue(mkdirs()) }
        val items = (0 until 20).map { i ->
            if (i % 2 == 0) {
                val cover = File(coversDir, "thumb_$i.jpg").apply {
                    writeBytes(byteArrayOf(0xFF.toByte(), i.toByte(), 0x00))
                }
                videoItem(i).copy(
                    coverUri = cover.absolutePath,
                    clipStartUs = if (i % 4 == 0) 1_000_000L + i else 0L,
                    clipEndUs = if (i % 4 == 0) 2_000_000L + i else 0L,
                )
            } else {
                audioItem(i)
            }
        }

        assertTrue(store.write(items))

        assertEquals(items, store.read())
    }

    // ---- 2) 覆盖写 ----

    @Test
    fun `覆盖写_第二次写入完全替换旧内容`() = runTest {
        val old = (0 until 5).map { videoItem(it) }
        val fresh = (100 until 103).map { videoItem(it) }
        assertTrue(store.write(old))
        assertTrue(store.write(fresh))

        val read = store.read()
        assertEquals(fresh, read)
        assertTrue("旧项不应残留", read!!.none { it.id in old.map(MediaItem::id) })
    }

    // ---- 3) 损坏 JSON ----

    @Test
    fun `损坏JSON_返回null且不抛异常`() = runTest {
        file.writeText("{ 这不是合法 JSON")
        assertNull(store.read())
    }

    @Test
    fun `缺失文件_返回null`() = runTest {
        assertNull(store.read())
    }

    // ---- 4) 版本不符 ----

    @Test
    fun `版本不符_返回null`() = runTest {
        file.writeText("""{"version":99,"items":[{"id":"x","uri":"y"}]}""")
        assertNull(store.read())
    }

    // ---- 5) 超字节上限 ----

    @Test
    fun `文件超过字节上限_返回null`() = runTest {
        file.writeBytes(ByteArray((ScanSnapshotStore.MAX_SNAPSHOT_BYTES + 1).toInt()))
        assertNull(store.read())
    }

    @Test
    fun `序列化后超过字节上限_不写且旧快照保留`() = runTest {
        val old = (0 until 3).map { videoItem(it) }
        assertTrue(store.write(old))

        // 每项 ~120KB 标题，20 项序列化后约 2.4MB > 2MiB 上限
        val hugeTitle = "x".repeat(120 * 1024)
        val huge = (0 until 20).map { videoItem(it).copy(title = "$hugeTitle-$it") }
        assertFalse(store.write(huge))

        // ② 超限不写：磁盘上仍是旧快照
        assertEquals(old, store.read())
    }

    // ---- 6) 写入失败保留旧快照 ----

    @Test
    fun `写入中途失败_返回false且旧快照仍可读`() = runTest {
        val old = (0 until 4).map { videoItem(it) }
        assertTrue(store.write(old))

        val fresh = (10 until 13).map { videoItem(it) }
        store.beforeReplace = { throw IOException("boom") }
        assertFalse(store.write(fresh))

        // A33 的核心断言：失败的写入绝不能连旧快照一起丢掉
        assertEquals(old, store.read())
        assertFalse("失败后不应残留临时文件", File(file.path + ".tmp").exists())
    }

    // ---- 7) 父目录缺失 ----

    @Test
    fun `父目录缺失_写入自动创建并成功`() = runTest {
        val nested = File(File(root, "nested/deep"), "scan_index.json")
        val nestedStore = ScanSnapshotStore(nested)
        val items = (0 until 3).map { videoItem(it) }

        assertTrue(nestedStore.write(items))

        assertTrue(nested.isFile)
        assertEquals(items, nestedStore.read())
    }

    // ---- 升级不丢库：旧格式（version=1）快照按现有字段与缺省解析 ----

    @Test
    fun `旧版快照JSON_沿用现有字段名与缺省值解析`() = runTest {
        file.writeText(
            """
            {"version":1,"items":[
              {"id":"ms-1","sourceId":"local","title":"a.mp4",
               "uri":"content://media/external/video/media/1","mimeType":"video/mp4",
               "durationMs":1000,"sizeBytes":2048,"lastModifiedSec":1700000001,
               "addedAtMs":1690000000001,"folder":"Movies","width":1920,"height":1080,
               "seasonNumber":1,"episodeNumber":2,"videoCodec":"HEVC",
               "clipStartUs":0,"clipEndUs":0},
              {"id":"ms-2","title":"b.mp3","uri":"content://media/external/audio/media/2",
               "mimeType":"audio/mpeg","coverUri":"content://media/external/audio/albumart/7",
               "displayTitle":"歌名"}
            ]}
            """.trimIndent(),
        )

        val read = store.read()
        assertNotNull(read)
        assertEquals(2, read!!.size)
        // sourceId / mimeType 缺省回落（与原 readSnapshot 一致）
        assertEquals("local", read[1].sourceId)
        assertEquals("audio/mpeg", read[1].mimeType)
        // 音频 content:// 封面不是文件路径，不能被 File 判存在而丢掉
        assertEquals("content://media/external/audio/albumart/7", read[1].coverUri)
        assertEquals("歌名", read[1].displayTitle)
        assertEquals("HEVC", read[0].videoCodec)
        assertEquals(1080, read[0].height)
    }

    @Test
    fun `落盘格式保留version1与原有字段名`() = runTest {
        val cover = File(root, "thumb.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val item = videoItem(0).copy(coverUri = cover.absolutePath, clipStartUs = 100, clipEndUs = 200)
        assertTrue(store.write(listOf(item)))

        val json = JSONObject(file.readText())
        assertEquals(ScanSnapshotStore.VERSION, json.getInt("version"))
        val o = json.getJSONArray("items").getJSONObject(0)
        // 提取前 saveSnapshot 写出的全部字段，一个都不能少（否则升级后旧库解析缺字段）
        for (key in listOf(
            "id", "sourceId", "title", "uri", "mimeType", "durationMs", "sizeBytes",
            "lastModifiedSec", "addedAtMs", "coverUri", "folder", "width", "height",
            "displayTitle", "seasonNumber", "episodeNumber", "videoCodec",
            "clipStartUs", "clipEndUs",
        )) {
            assertTrue("落盘 JSON 缺少字段：$key", o.has(key))
        }
    }

    @Test
    fun `视频封面缓存文件不存在时读回置空`() = runTest {
        val cover = File(root, "thumb.jpg").apply { writeBytes(byteArrayOf(1)) }
        val item = videoItem(0).copy(coverUri = cover.absolutePath)
        assertTrue(store.write(listOf(item)))
        assertTrue(cover.delete())

        assertNull(store.read()!!.single().coverUri)
    }

    // ---- 测试数据 ----

    private fun videoItem(index: Int): MediaItem = MediaItem(
        id = "ms-$index",
        sourceId = "local",
        title = "video_$index.mp4",
        uri = "content://media/external/video/media/$index",
        mimeType = "video/mp4",
        durationMs = 60_000L + index,
        sizeBytes = 10_000_000L + index,
        lastModifiedSec = 1_700_000_000L + index,
        addedAtMs = 1_690_000_000_000L + index,
        coverUri = null,
        folder = "DCIM/Camera",
        width = 1920,
        height = 1080,
        displayTitle = "第 ${index + 1} 集",
        seasonNumber = 1,
        episodeNumber = index + 1,
        videoCodec = "HEVC",
        clipStartUs = 0,
        clipEndUs = 0,
    )

    private fun audioItem(index: Int): MediaItem = MediaItem(
        id = "ms-a-$index",
        sourceId = "local",
        title = "song_$index.flac",
        uri = "content://media/external/audio/media/$index",
        mimeType = "audio/flac",
        durationMs = 200_000L + index,
        sizeBytes = 30_000_000L + index,
        lastModifiedSec = 1_700_100_000L + index,
        addedAtMs = 1_690_100_000_000L + index,
        coverUri = "content://media/external/audio/albumart/$index",
        folder = "Music/专辑 $index",
        width = 0,
        height = 0,
        displayTitle = null,
        seasonNumber = 0,
        episodeNumber = 0,
        videoCodec = null,
        clipStartUs = 0,
        clipEndUs = 0,
    )
}
