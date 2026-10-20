package com.noneplayer.core.media.probe

import com.noneplayer.core.media.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-14 / A17（探测侧）：确定性缓存判据 + ASP profile 求交的唯一规则来源测试。
 *
 * 纯 JVM（无 Robolectric、无设备）：
 *  - 只测抽出的纯 Kotlin 规则，不触达 [FormatProbe] 对象本体（其属性含 Android 单例，
 *    JVM 单测初始化会抛 not mocked），也不查 MediaCodecList（JVM 无解码器设备）；
 *  - profile 求交的纯函数只收「选中的解码器是否声明 ASP / 并集是否声明 ASP」两个布尔，
 *    设备查询映射留在生产代码里由仪器测试覆盖。
 */
class ProbeCacheDeterminismTest {

    // ------------------------------------------------------------------
    // ① 缓存条件收紧：确定性结论才允许进缓存
    // ------------------------------------------------------------------

    @Test
    fun deterministicVerdictsAreCacheable() {
        val playable = FormatProbe.Result.Playable(
            container = "MP4",
            videoCodec = "H.264",
            audioCodec = "AAC",
        )
        assertTrue("Playable 是确定性结论，必须可缓存", ProbeOutcome(playable, ProbeVerdict.PLAYABLE).cacheable)
        assertTrue(
            "容器不支持是确定性结论，必须可缓存",
            ProbeOutcome(
                FormatProbe.Result.Unplayable(PlaybackFailure.containerUnsupported("RMVB")),
                ProbeVerdict.CONTAINER_UNSUPPORTED,
            ).cacheable,
        )
        assertTrue(
            "视频编码/档次不支持是确定性结论，必须可缓存",
            ProbeOutcome(
                FormatProbe.Result.Unplayable(
                    PlaybackFailure.videoCodecUnsupported("AVI", "VC-1", "MP3"),
                ),
                ProbeVerdict.VIDEO_CODEC_UNSUPPORTED,
            ).cacheable,
        )
    }

    @Test
    fun unknownAndTransientFailuresAreNotCacheable() {
        assertFalse(
            "Unknown 没有结论，不得缓存",
            ProbeOutcome(FormatProbe.Result.Unknown, ProbeVerdict.NON_DETERMINISTIC).cacheable,
        )
        assertFalse(
            "unreadable（I/O、SAF 失效、read/sniff 抛异常）是瞬态，不得缓存",
            ProbeOutcome(
                FormatProbe.Result.Unplayable(PlaybackFailure.unreadable("AVI")),
                ProbeVerdict.NON_DETERMINISTIC,
            ).cacheable,
        )
        assertFalse(
            "查询异常即便算出了结论也不得冒充确定性结论进缓存",
            ProbeOutcome(
                FormatProbe.Result.Unplayable(
                    PlaybackFailure.videoCodecUnsupported("AVI", "VC-1", "MP3"),
                ),
                ProbeVerdict.NON_DETERMINISTIC,
            ).cacheable,
        )
    }

    @Test
    fun cacheRefusesNonDeterministicOutcome() {
        val cache = ProbeResultCache(capacity = 4)
        val transient = ProbeOutcome(
            FormatProbe.Result.Unplayable(PlaybackFailure.unreadable("AVI")),
            ProbeVerdict.NON_DETERMINISTIC,
        )
        assertFalse("缓存门必须拒绝瞬态/拿不准的结论", cache.put("content://videos/a.avi", entry(transient)))
        assertNull("被拒绝的结论不得留在缓存里", cache.get("content://videos/a.avi"))

        val playable = ProbeOutcome(
            FormatProbe.Result.Playable(container = "MP4", videoCodec = "H.264", audioCodec = "AAC"),
            ProbeVerdict.PLAYABLE,
        )
        assertTrue("确定性结论必须入缓存", cache.put("content://videos/b.mp4", entry(playable)))
        assertNotNull(cache.get("content://videos/b.mp4"))
    }

    // ------------------------------------------------------------------
    // ② 缓存指纹匹配 / invalidate / LRU
    // ------------------------------------------------------------------

    @Test
    fun cacheStoresFingerprintAndInvalidates() {
        val cache = ProbeResultCache(capacity = 4)
        val playable = ProbeOutcome(
            FormatProbe.Result.Playable(container = "MP4", videoCodec = "H.264", audioCodec = "AAC"),
            ProbeVerdict.PLAYABLE,
        )
        cache.put("file:///videos/a.mp4", entry(playable, sizeBytes = 1_024L, lastModifiedSec = 7L))

        val hit = cache.get("file:///videos/a.mp4")
        assertNotNull("确定性结论应能命中", hit)
        assertEquals("指纹 sizeBytes 必须随条目保存（改动后要重新探测）", 1_024L, hit!!.sizeBytes)
        assertEquals("指纹 lastModifiedSec 必须随条目保存", 7L, hit.lastModifiedSec)

        cache.invalidate("file:///videos/a.mp4")
        assertNull("invalidate 后不得再命中（错误面板重试用）", cache.get("file:///videos/a.mp4"))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntry() {
        val cache = ProbeResultCache(capacity = 2)
        val playable = ProbeOutcome(
            FormatProbe.Result.Playable(container = "MP4", videoCodec = "H.264", audioCodec = "AAC"),
            ProbeVerdict.PLAYABLE,
        )
        cache.put("a", entry(playable))
        cache.put("b", entry(playable))
        assertNotNull(cache.get("a")) // a 变成最近使用
        cache.put("c", entry(playable))
        assertNotNull("最近使用过的条目应保留", cache.get("a"))
        assertNull("最久未使用的条目应被淘汰", cache.get("b"))
    }

    // ------------------------------------------------------------------
    // ③ ASP profile 求交：先「将被选中的那一个解码器」，并集仅兜底
    // ------------------------------------------------------------------

    @Test
    fun aspGateUsesSelectedDecoderBeforeUnion() {
        // A17 核心：平台软解在并集里广告了 ASP，但 findDecoderForFormat 真正选中的硬解只有 Simple。
        assertEquals(
            true,
            Mp4vAspGate.decideRequiresDedicated(
                selectedSupportsAsp = false,
                unionSupportsAsp = true,
            ),
        )
        assertEquals(
            false,
            Mp4vAspGate.decideRequiresDedicated(
                selectedSupportsAsp = true,
                unionSupportsAsp = false,
            ),
        )
    }

    @Test
    fun aspGateFallsBackToUnionOnlyWithoutSelectedInfo() {
        assertEquals(
            false,
            Mp4vAspGate.decideRequiresDedicated(selectedSupportsAsp = null, unionSupportsAsp = true),
        )
        assertEquals(
            true,
            Mp4vAspGate.decideRequiresDedicated(selectedSupportsAsp = null, unionSupportsAsp = false),
        )
        assertNull(
            "选中信息与并集都拿不到 = 无法判定（fail-open、结论不得入缓存）",
            Mp4vAspGate.decideRequiresDedicated(selectedSupportsAsp = null, unionSupportsAsp = null),
        )
    }

    @Test
    fun aspGateKeepsSimpleAndUnknownStreamsOnPlatformPath() {
        val simple = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0x01, 0x00)
        assertFalse(
            "Simple Profile 码流不得判需要专用链（必须继续走平台 mp4v）",
            Mp4vAspGate.requiresDedicatedDecoder(simple, null),
        )
        assertFalse(
            "头部太短（判读 UNKNOWN）必须 fail-open，不得改专用 mime",
            Mp4vAspGate.requiresDedicatedDecoder(byteArrayOf(0, 0, 1), null),
        )
    }

    @Test
    fun aspGateFailsOpenWhenDeviceQueryUnavailable() {
        val asp = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0xF5.toByte(), 0x00)
        // 纯 JVM 没有 MediaCodecList：selected 与并集都查不到 → 无法判定 → fail-open false
        assertFalse(
            "设备查询不可用的纯 JVM 环境必须 fail-open（不拿空缺信息去拒绝播放）",
            Mp4vAspGate.requiresDedicatedDecoder(asp, null),
        )
    }

    @Test
    fun platformDecoderQueryReturnsNullOnQueryFailure() {
        // 纯 JVM 里 MediaFormat.createVideoFormat 是 Android stub（调用即抛）→ 查询必须返回 null，
        // 而不是冒充「有解码器」。旧的 hasPlatformDecoder 语义仍是 fail-open（null→true）。
        val format = androidx.media3.common.Format.Builder()
            .setSampleMimeType("video/avc")
            .build()
        assertNull(PlatformDecoderSupport.platformDecoderQuery(format))
        assertTrue(PlatformDecoderSupport.hasPlatformDecoder(format))
    }

    // ------------------------------------------------------------------

    private fun entry(
        outcome: ProbeOutcome,
        sizeBytes: Long = 1_024L,
        lastModifiedSec: Long = 7L,
    ) = ProbeCacheEntry(sizeBytes = sizeBytes, lastModifiedSec = lastModifiedSec, outcome = outcome)
}
