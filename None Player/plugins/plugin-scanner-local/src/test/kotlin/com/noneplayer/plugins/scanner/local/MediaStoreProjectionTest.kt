package com.noneplayer.plugins.scanner.local

import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-12 验收：旧 Provider（API 26–28）不得无条件请求 RELATIVE_PATH，
 * 且 API 26–28 视频/音频都必须可查询；Q+ 保留 RELATIVE_PATH 供相对路径分组。
 *
 * 纯 JVM 单测即可：引用的都是编译期 String/int 常量，不会触发 android.jar stub 的静态初始化。
 */
class MediaStoreProjectionTest {

    private val sdkWithRelativePath = listOf(29, 30, 34)
    private val sdkWithoutRelativePath = listOf(26, 27, 28)

    private val videoWithRelativePath = arrayOf(
        "_id",
        "_display_name",
        "duration",
        "_size",
        "date_modified",
        "date_added",
        "mime_type",
        "_data",
        "relative_path",
        "width",
        "height",
    )
    private val videoWithoutRelativePath = arrayOf(
        "_id",
        "_display_name",
        "duration",
        "_size",
        "date_modified",
        "date_added",
        "mime_type",
        "_data",
        "width",
        "height",
    )
    private val audioWithRelativePath = arrayOf(
        "_id",
        "_display_name",
        "duration",
        "_size",
        "date_modified",
        "date_added",
        "mime_type",
        "_data",
        "relative_path",
        "album_id",
        "artist",
        "title",
        "is_music",
    )
    private val audioWithoutRelativePath = arrayOf(
        "_id",
        "_display_name",
        "duration",
        "_size",
        "date_modified",
        "date_added",
        "mime_type",
        "_data",
        "album_id",
        "artist",
        "title",
        "is_music",
    )

    // ---------------------------------------------------------------------
    // hasRelativePath
    // ---------------------------------------------------------------------

    @Test
    fun `hasRelativePath 以 API 29 为界`() {
        sdkWithoutRelativePath.forEach { sdkInt ->
            assertFalse("sdk=$sdkInt", MediaStoreProjection.hasRelativePath(sdkInt))
        }
        sdkWithRelativePath.forEach { sdkInt ->
            assertTrue("sdk=$sdkInt", MediaStoreProjection.hasRelativePath(sdkInt))
        }
    }

    // ---------------------------------------------------------------------
    // video
    // ---------------------------------------------------------------------

    @Test
    fun `Q 及以上视频投影包含相对路径且列顺序固定`() {
        sdkWithRelativePath.forEach { sdkInt ->
            val projection = MediaStoreProjection.video(sdkInt)
            assertArrayEquals("sdk=$sdkInt", videoWithRelativePath, projection)
            assertTrue("sdk=$sdkInt 缺少 relative_path", projection.contains("relative_path"))
            assertEquals("sdk=$sdkInt relative_path 位置", 8, projection.indexOf("relative_path"))
        }
    }

    @Test
    fun `Q 以下视频投影不含相对路径`() {
        sdkWithoutRelativePath.forEach { sdkInt ->
            val projection = MediaStoreProjection.video(sdkInt)
            assertArrayEquals("sdk=$sdkInt", videoWithoutRelativePath, projection)
            assertFalse("sdk=$sdkInt 不应请求 relative_path", projection.contains("relative_path"))
            assertTrue("sdk=$sdkInt 投影为空", projection.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------------
    // audio
    // ---------------------------------------------------------------------

    @Test
    fun `Q 及以上音频投影包含相对路径与曲目列`() {
        sdkWithRelativePath.forEach { sdkInt ->
            val projection = MediaStoreProjection.audio(sdkInt)
            assertArrayEquals("sdk=$sdkInt", audioWithRelativePath, projection)
            assertTrue("sdk=$sdkInt 缺少 relative_path", projection.contains("relative_path"))
            assertEquals("sdk=$sdkInt relative_path 位置", 8, projection.indexOf("relative_path"))
            assertTrue("sdk=$sdkInt 缺少 album_id", projection.contains("album_id"))
            assertTrue("sdk=$sdkInt 缺少 title", projection.contains("title"))
            assertTrue("sdk=$sdkInt 缺少 is_music", projection.contains("is_music"))
        }
    }

    @Test
    fun `Q 以下音频投影不含相对路径且保留曲目列`() {
        sdkWithoutRelativePath.forEach { sdkInt ->
            val projection = MediaStoreProjection.audio(sdkInt)
            assertArrayEquals("sdk=$sdkInt", audioWithoutRelativePath, projection)
            assertFalse("sdk=$sdkInt 不应请求 relative_path", projection.contains("relative_path"))
            assertTrue("sdk=$sdkInt 投影为空", projection.isNotEmpty())
            assertTrue("sdk=$sdkInt 缺少 album_id", projection.contains("album_id"))
            assertTrue("sdk=$sdkInt 缺少 title", projection.contains("title"))
            assertTrue("sdk=$sdkInt 缺少 is_music", projection.contains("is_music"))
        }
    }

    // ---------------------------------------------------------------------
    // 边界与防御
    // ---------------------------------------------------------------------

    @Test
    fun `API 26 到 28 视频与音频投影均非空且含公共列`() {
        // REV-12 验收：旧 Provider 上视频/音频都必须可查询，公共列一个不能少
        for (sdkInt in sdkWithoutRelativePath) {
            for ((kind, projection) in listOf(
                "video" to MediaStoreProjection.video(sdkInt),
                "audio" to MediaStoreProjection.audio(sdkInt),
            )) {
                assertTrue("$kind sdk=$sdkInt 投影为空", projection.isNotEmpty())
                listOf("_id", "_display_name", "duration", "_size", "date_modified", "date_added", "mime_type", "_data")
                    .forEach { column ->
                        assertTrue("$kind sdk=$sdkInt 缺少 $column", projection.contains(column))
                    }
            }
        }
    }

    @Test
    fun `每次调用返回新数组`() {
        for (sdkInt in listOf(26, 34)) {
            val firstVideo = MediaStoreProjection.video(sdkInt)
            val secondVideo = MediaStoreProjection.video(sdkInt)
            assertNotSame("video sdk=$sdkInt", firstVideo, secondVideo)
            firstVideo[0] = "mutated"
            assertArrayEquals(
                "video sdk=$sdkInt 返回值被修改后污染了后续调用",
                if (sdkInt >= 29) videoWithRelativePath else videoWithoutRelativePath,
                MediaStoreProjection.video(sdkInt),
            )

            val firstAudio = MediaStoreProjection.audio(sdkInt)
            val secondAudio = MediaStoreProjection.audio(sdkInt)
            assertNotSame("audio sdk=$sdkInt", firstAudio, secondAudio)
            firstAudio[0] = "mutated"
            assertArrayEquals(
                "audio sdk=$sdkInt 返回值被修改后污染了后续调用",
                if (sdkInt >= 29) audioWithRelativePath else audioWithoutRelativePath,
                MediaStoreProjection.audio(sdkInt),
            )
        }
    }

    @Test
    fun `投影列名与 MediaStore 公开常量一致`() {
        assertEquals("_id", MediaStore.MediaColumns._ID)
        assertEquals("_display_name", MediaStore.MediaColumns.DISPLAY_NAME)
        assertEquals("duration", MediaStore.Video.Media.DURATION)
        assertEquals("_size", MediaStore.MediaColumns.SIZE)
        assertEquals("date_modified", MediaStore.MediaColumns.DATE_MODIFIED)
        assertEquals("date_added", MediaStore.MediaColumns.DATE_ADDED)
        assertEquals("mime_type", MediaStore.MediaColumns.MIME_TYPE)
        assertEquals("_data", MediaStore.MediaColumns.DATA)
        assertEquals("relative_path", MediaStore.MediaColumns.RELATIVE_PATH)
        assertEquals("width", MediaStore.Video.Media.WIDTH)
        assertEquals("height", MediaStore.Video.Media.HEIGHT)
        assertEquals("album_id", MediaStore.Audio.Media.ALBUM_ID)
        assertEquals("artist", MediaStore.Audio.Media.ARTIST)
        assertEquals("title", MediaStore.Audio.Media.TITLE)
        assertEquals("is_music", MediaStore.Audio.Media.IS_MUSIC)
    }
}
