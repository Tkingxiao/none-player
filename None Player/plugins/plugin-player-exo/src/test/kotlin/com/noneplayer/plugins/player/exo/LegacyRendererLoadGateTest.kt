package com.noneplayer.plugins.player.exo

import com.noneplayer.core.media.MediaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E208 回归：legacy 后端认领 WMV2/ASP 前必须先确认 `libnplegacy` 可加载。
 *
 * E206 参数化后 `supportsFormat` 只查 `codecName()`（纯字符串映射），真机冷进程会在
 * 库未加载时认领，随后 `LegacyNative.nativeCreate` 抛 UnsatisfiedLinkError 直接崩溃。
 * [legacyClaimableCodec] 把「可认领 = 有编码器名 ∧ 库可用」收成纯函数；这里钉住两条
 * 分支，渲染器的默认 lambda 直接调用它。
 */
class LegacyRendererLoadGateTest {

    @Test
    fun `unavailable legacy library must not claim WMV2 or ASP`() {
        assertNull(legacyClaimableCodec(MediaNames.MIME_WMV2, libraryAvailable = false))
        assertNull(legacyClaimableCodec(MediaNames.MIME_MPEG4_ASP, libraryAvailable = false))
    }

    @Test
    fun `available legacy library keeps the codec name`() {
        assertEquals("wmv2", legacyClaimableCodec(MediaNames.MIME_WMV2, libraryAvailable = true))
        assertEquals(
            "mpeg4",
            legacyClaimableCodec(MediaNames.MIME_MPEG4_ASP, libraryAvailable = true),
        )
        // 表外 mime 即便库可用也不认领
        assertNull(legacyClaimableCodec("video/avc", libraryAvailable = true))
    }
}
