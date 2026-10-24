package com.noneplayer.core.media.extract

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.noneplayer.core.media.MediaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A11：WMV2 AVI 的 WAVE tag / PCM 位深映射（纯 JVM，不依赖设备）。 */
class Wmv2AudioMappingTest {

    @Test
    fun `pcm tags map to raw`() {
        assertEquals(MimeTypes.AUDIO_RAW, wmvAudioMimeForTag(0x0001))
        assertEquals(MimeTypes.AUDIO_RAW, wmvAudioMimeForTag(0x0003))
    }

    @Test
    fun `mpeg tags map to mp3`() {
        assertEquals(MimeTypes.AUDIO_MPEG, wmvAudioMimeForTag(0x0050))
        assertEquals(MimeTypes.AUDIO_MPEG, wmvAudioMimeForTag(0x0055))
    }

    @Test
    fun `wma family keeps a track with wma mime`() {
        listOf(0x0160, 0x0161).forEach { tag ->
            assertEquals(MediaNames.MIME_WMA, wmvAudioMimeForTag(tag))
        }
        assertEquals(MediaNames.MIME_WMAPRO, wmvAudioMimeForTag(0x0162))
        assertEquals(MediaNames.MIME_WMALOSSLESS, wmvAudioMimeForTag(0x0163))
    }

    @Test
    fun `unknown tag is not invented`() {
        assertNull(wmvAudioMimeForTag(0x2000))
    }

    @Test
    fun `only wma family gets WAVEFORMATEX csd`() {
        val strf = ByteArray(24)
        // WMA：整段 strf 作 csd（tag/flags/blockAlign 都在里面）
        assertEquals(
            listOf(strf),
            wmvAudioInitializationData(MediaNames.MIME_WMA, strf),
        )
        assertEquals(
            listOf(strf),
            wmvAudioInitializationData(MediaNames.MIME_WMAPRO, strf),
        )
        assertEquals(
            listOf(strf),
            wmvAudioInitializationData(MediaNames.MIME_WMALOSSLESS, strf),
        )
        // MP3/PCM 绝不能带 WAVE 头（E208：c2 MP3 会首帧 Error 0xe）
        assertNull(wmvAudioInitializationData(MimeTypes.AUDIO_MPEG, strf))
        assertNull(wmvAudioInitializationData(MimeTypes.AUDIO_RAW, strf))
        assertNull(wmvAudioInitializationData(MediaNames.MIME_WMA, null))
    }

    @Test
    fun `bits per sample map to media3 pcm encodings`() {
        assertEquals(C.ENCODING_PCM_8BIT, pcmEncodingForBits(8))
        assertEquals(C.ENCODING_PCM_16BIT, pcmEncodingForBits(16))
        assertEquals(C.ENCODING_PCM_24BIT, pcmEncodingForBits(24))
        assertEquals(C.ENCODING_PCM_32BIT, pcmEncodingForBits(32))
        assertEquals(C.ENCODING_PCM_16BIT, pcmEncodingForBits(0))
    }
}
