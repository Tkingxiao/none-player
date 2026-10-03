package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MediaItem.kind]：媒体库要同时容纳音频与视频，分类依据只能是 mimeType。
 *
 * 核心断言：音频 MIME（audio/ 开头）判 AUDIO；其余（含空串与未知）一律落到 VIDEO，
 * 保证旧数据/异常 OEM 取值下不会把视频误判成音频。
 */
class MediaKindTest {

    private fun kindOf(mimeType: String) = MediaItem(
        id = "ms-1",
        sourceId = "local",
        title = "t",
        uri = "content://x/1",
        mimeType = mimeType,
    ).kind

    @Test
    fun `audio mpeg 判为 AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindOf("audio/mpeg"))
    }

    @Test
    fun `audio flac 判为 AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindOf("audio/flac"))
    }

    @Test
    fun `video mp4 判为 VIDEO`() {
        assertEquals(MediaKind.VIDEO, kindOf("video/mp4"))
    }

    @Test
    fun `空 mimeType 判为 VIDEO`() {
        assertEquals(MediaKind.VIDEO, kindOf(""))
    }

    @Test
    fun `大写 AUDIO 前缀也能判为 AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindOf("AUDIO/MP3"))
    }
}
