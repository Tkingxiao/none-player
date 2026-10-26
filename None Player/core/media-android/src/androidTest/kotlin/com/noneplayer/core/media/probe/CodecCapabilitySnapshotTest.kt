package com.noneplayer.core.media.probe

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R0/T1：整机编解码能力快照导出。
 *
 * 产物落 `targetContext.getExternalFilesDir(null)/codec-capability-snapshot.json`，
 * 路径与字节数写入 logcat（tag=CodecCapability）。快照只描述“平台声明”，
 * 不代表首帧/转码实测；两台基线设备的导出结果回填到
 * `docs/decoding/codec-capability-matrix-2026-10-22.md`。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CodecCapabilitySnapshotTest {

    private companion object {
        private const val TAG = "CodecCapability"
        private const val MIME_HEVC = "video/hevc"
        private const val MIME_AVC = "video/avc"
        private const val FILE_NAME = "codec-capability-snapshot.json"

        private val lock = Any()
        private var cached: CodecCapabilitySnapshot? = null

        private fun snapshot(): CodecCapabilitySnapshot = synchronized(lock) {
            cached ?: CodecCapabilityProbe.capture(appVersion = appVersion()).also { cached = it }
        }

        private fun appVersion(): String? = runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        private fun targetFile(): File {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            return File(dir, FILE_NAME)
        }
    }

    @Test
    fun captureExportsJson() {
        val snapshot = snapshot()
        val path = targetFile()
        val json = snapshot.toJson()
        path.parentFile?.mkdirs()
        path.writeText(json)
        Log.i(
            TAG,
            "SNAPSHOT|path=${path.absolutePath}|bytes=${path.length()}|" +
                "codecs=${snapshot.codecs.size}|decoders=${snapshot.decoders.size}|" +
                "encoders=${snapshot.encoders.size}|errors=${snapshot.errors.size}|" +
                "cacheKey=${snapshot.cacheKey()}",
        )
        snapshot.errors.forEach { Log.w(TAG, "SNAPSHOT_ERROR|$it") }

        assertTrue("fingerprint 不能为空", snapshot.device.fingerprint.isNotBlank())
        assertTrue("SDK 必须可读", snapshot.device.sdkInt > 0)
        assertTrue("至少要有解码器", snapshot.decoders.isNotEmpty())
        assertTrue("至少要有一种编码器", snapshot.encoders.isNotEmpty())
        assertTrue(
            "每个 codec 至少要有一个 mime 类型",
            snapshot.codecs.all { it.name.isNotBlank() && it.types.isNotEmpty() },
        )
        assertEquals("快照顺序不影响缓存键", snapshot.cacheKey(), snapshot.copy(codecs = snapshot.codecs.reversed()).cacheKey())
        assertNotEquals(
            "应用版本变化必须让缓存键失效",
            snapshot.cacheKey(),
            snapshot.copy(device = snapshot.device.copy(appVersion = "different")).cacheKey(),
        )

        val parsed = JSONObject(path.readText())
        assertEquals(1, parsed.getInt("schema"))
        assertEquals(snapshot.codecs.size, parsed.getJSONArray("codecs").length())
        assertEquals(snapshot.cacheKey(), parsed.getString("cacheKey"))
    }

    @Test
    fun hevcMain10VerdictHasEvidence() {
        val snapshot = snapshot()
        val decoders = snapshot.decoders.filter { it.type(MIME_HEVC) != null }
        if (decoders.isEmpty()) {
            Log.w(TAG, "HEVC|NO_PLATFORM_DECODER")
            return
        }
        decoders.forEach { codec ->
            val type = codec.type(MIME_HEVC)!!
            Log.i(
                TAG,
                "HEVC_DECODER|name=${codec.name}|hw=${codec.isHardwareAccelerated}|" +
                    "profiles=${type.profileLevels.joinToString { "${it.profile}@${it.level}" }}|" +
                    "size=${type.video?.minWidth}x${type.video?.minHeight}-" +
                    "${type.video?.maxWidth}x${type.video?.maxHeight}|instances=${type.maxInstances}",
            )
            assertTrue(
                "HEVC 解码器必须导出 profileLevels（否则 Main10 无法求交）: ${codec.name}",
                type.profileLevels.isNotEmpty(),
            )
        }

        val main10 = syntheticHevcFormat(profileIdc = 2, levelIdc = 153) // Main10 @ 5.1
        val main10Decision = CodecCapabilityProbe.decoderDecision(main10)
        val selected = main10Decision.codecName?.let { name -> snapshot.codecs.firstOrNull { it.name == name } }
        val selectedType = selected?.type(MIME_HEVC)
        val main10Supported = selectedType?.profileLevels?.any { profileLevel ->
            CodecProfiles.matches(MIME_HEVC, setOf(CodecProfiles.HEVC_MAIN10), profileLevel.profile) &&
                (CodecProfiles.levelOf(MIME_HEVC, profileLevel.level)?.rank ?: 0.0) >= 5.1
        } ?: false
        Log.i(
            TAG,
            "HEVC_MAIN10_5_1|codec=${main10Decision.codecName}|verdict=${main10Decision.verdict}|" +
                "profileAndLevelDeclared=$main10Supported|reasons=${main10Decision.reasons.joinToString(";")}",
        )
        assertNotEquals(
            "能解析 hvcC 且存在 HEVC 解码器时不得返回 UNKNOWN",
            CapabilityVerdict.UNKNOWN,
            main10Decision.verdict,
        )
        if (main10Supported) {
            assertEquals(CapabilityVerdict.SUPPORTED, main10Decision.verdict)
        }

        val main8 = syntheticHevcFormat(profileIdc = 1, levelIdc = 120) // Main 8bit @ 4.0
        val main8Decision = CodecCapabilityProbe.decoderDecision(main8)
        val main8Selected = main8Decision.codecName?.let { name -> snapshot.codecs.firstOrNull { it.name == name } }
        val main8Supported = main8Selected?.type(MIME_HEVC)?.profileLevels?.any { profileLevel ->
            CodecProfiles.matches(MIME_HEVC, setOf(CodecProfiles.HEVC_MAIN), profileLevel.profile) &&
                (CodecProfiles.levelOf(MIME_HEVC, profileLevel.level)?.rank ?: 0.0) >= 4.0
        } ?: false
        Log.i(
            TAG,
            "HEVC_MAIN_4_0|codec=${main8Decision.codecName}|verdict=${main8Decision.verdict}|" +
                "profileAndLevelDeclared=$main8Supported|reasons=${main8Decision.reasons.joinToString(";")}",
        )
        if (main8Supported) {
            assertEquals(CapabilityVerdict.SUPPORTED, main8Decision.verdict)
        }
    }

    @Test
    fun encoderCapabilitiesAreExported() {
        val snapshot = snapshot()
        val videoEncoders = snapshot.encoders.filter { codec ->
            codec.types.any { it.video != null }
        }
        Log.i(TAG, "ENCODERS|video=${videoEncoders.size}")
        videoEncoders.forEach { codec ->
            codec.types.filter { it.video != null }.forEach { type ->
                Log.i(
                    TAG,
                    "ENCODER|name=${codec.name}|mime=${type.mime}|hw=${codec.isHardwareAccelerated}|" +
                        "surface=${type.surfaceColorFormat}|modes=${type.bitrateModes.joinToString("+")}|" +
                        "instances=${type.maxInstances}|size=${type.video?.minWidth}x${type.video?.minHeight}-" +
                        "${type.video?.maxWidth}x${type.video?.maxHeight}",
                )
            }
        }
        if (videoEncoders.isNotEmpty()) {
            assertTrue(
                "至少一个视频编码器必须声明 Surface 输入（Surface→Surface 转码的地基）",
                videoEncoders.any { codec -> codec.types.any { it.surfaceColorFormat } },
            )
        }

        val hevcEncoders = snapshot.encoders.filter { it.type(MIME_HEVC) != null }
        if (hevcEncoders.isNotEmpty()) {
            val cq = CodecCapabilityProbe.encoderDecision(
                mime = MIME_HEVC,
                width = 1920,
                height = 1080,
                frameRate = 30.0,
                bitrate = 8_000_000,
                bitrateMode = BitrateMode.CQ,
            )
            Log.i(
                TAG,
                "HEVC_ENCODER_CQ|codec=${cq.codecName}|verdict=${cq.verdict}|" +
                    "reasons=${cq.reasons.joinToString(";")}",
            )
            // 骁龙机的 .hevc.cq 只声明到 512x512，1080p CQ 会选不中；记录小尺寸是否可用。
            val cqSmall = CodecCapabilityProbe.encoderDecision(
                mime = MIME_HEVC,
                width = 512,
                height = 512,
                frameRate = 30.0,
                bitrate = 1_000_000,
                bitrateMode = BitrateMode.CQ,
            )
            Log.i(
                TAG,
                "HEVC_ENCODER_CQ_512|codec=${cqSmall.codecName}|verdict=${cqSmall.verdict}|" +
                    "reasons=${cqSmall.reasons.joinToString(";")}",
            )
            val vbr = CodecCapabilityProbe.encoderDecision(
                mime = MIME_HEVC,
                width = 1920,
                height = 1080,
                frameRate = 30.0,
                bitrate = 8_000_000,
                bitrateMode = BitrateMode.VBR,
            )
            Log.i(
                TAG,
                "HEVC_ENCODER_VBR|codec=${vbr.codecName}|verdict=${vbr.verdict}|" +
                    "reasons=${vbr.reasons.joinToString(";")}",
            )
        }

        val avcEncoders = snapshot.encoders.filter { it.type(MIME_AVC) != null }
        if (avcEncoders.isNotEmpty()) {
            val vbr = CodecCapabilityProbe.encoderDecision(
                mime = MIME_AVC,
                width = 1920,
                height = 1080,
                frameRate = 30.0,
                bitrate = 8_000_000,
                bitrateMode = BitrateMode.VBR,
            )
            Log.i(
                TAG,
                "AVC_ENCODER_VBR|codec=${vbr.codecName}|verdict=${vbr.verdict}|" +
                    "reasons=${vbr.reasons.joinToString(";")}",
            )
        }
    }

    private fun syntheticHevcFormat(profileIdc: Int, levelIdc: Int): Format {
        val hvcC = ByteArray(23).apply {
            this[0] = 1
            this[1] = profileIdc.toByte()
            this[12] = levelIdc.toByte()
        }
        return Format.Builder()
            .setSampleMimeType(MIME_HEVC)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(23.976f)
            .setInitializationData(listOf(hvcC))
            .build()
    }
}

private fun CodecCapabilitySnapshot.toJson(): String {
    val root = JSONObject()
    root.put("schema", 1)
    root.put("capturedAt", System.currentTimeMillis())
    root.put("cacheKey", cacheKey())
    root.put(
        "device",
        JSONObject().apply {
            put("sdkInt", device.sdkInt)
            put("release", device.release)
            put("fingerprint", device.fingerprint)
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("device", device.device)
            put("board", device.board)
            put("hardware", device.hardware)
            put("socManufacturer", device.socManufacturer ?: JSONObject.NULL)
            put("socModel", device.socModel ?: JSONObject.NULL)
            put("supportedAbis", JSONArray(device.supportedAbis))
            put("appVersion", device.appVersion ?: JSONObject.NULL)
        },
    )
    root.put("codecs", JSONArray().apply { codecs.forEach { put(it.toJson()) } })
    root.put("errors", JSONArray(errors))
    return root.toString(2)
}

private fun CodecCapability.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("isEncoder", isEncoder)
    put("isHardwareAccelerated", isHardwareAccelerated ?: JSONObject.NULL)
    put("isSoftwareOnly", isSoftwareOnly ?: JSONObject.NULL)
    put("isVendor", isVendor ?: JSONObject.NULL)
    put("isAlias", isAlias ?: JSONObject.NULL)
    put("types", JSONArray().apply { types.forEach { put(it.toJson()) } })
}

private fun CodecTypeCapability.toJson(): JSONObject = JSONObject().apply {
    put("mime", mime)
    put(
        "profileLevels",
        JSONArray().apply {
            profileLevels.forEach {
                put(
                    JSONObject().apply {
                        put("profile", it.profile)
                        put("level", it.level)
                        put("profileName", profileName(mime, it.profile))
                        put("levelRank", CodecProfiles.levelOf(mime, it.level)?.rank ?: JSONObject.NULL)
                        put("levelTier", CodecProfiles.levelOf(mime, it.level)?.tier ?: JSONObject.NULL)
                    },
                )
            }
        },
    )
    put(
        "video",
        video?.let {
            JSONObject().apply {
                put("minWidth", it.minWidth)
                put("maxWidth", it.maxWidth)
                put("minHeight", it.minHeight)
                put("maxHeight", it.maxHeight)
                put("minFrameRate", it.minFrameRate)
                put("maxFrameRate", it.maxFrameRate)
                put("minBitrate", it.minBitrate)
                put("maxBitrate", it.maxBitrate)
                put("widthAlignment", it.widthAlignment)
                put("heightAlignment", it.heightAlignment)
            }
        } ?: JSONObject.NULL,
    )
    put("maxInstances", maxInstances)
    put("surfaceColorFormat", surfaceColorFormat)
    put("bitrateModes", JSONArray(bitrateModes.map { it.name }))
    put("colorFormats", JSONArray(colorFormats))
    put("bitDepths", JSONArray(bitDepths.sorted()))
    put("chromaSubsamplings", JSONArray(chromaSubsamplings.sorted()))
}

private fun profileName(mime: String, profile: Int): String = when {
    mime.equals("video/hevc", ignoreCase = true) -> when (profile) {
        CodecProfiles.HEVC_MAIN -> "HEVC Main"
        CodecProfiles.HEVC_MAIN10 -> "HEVC Main10"
        CodecProfiles.HEVC_MAIN_STILL -> "HEVC MainStill"
        CodecProfiles.HEVC_MAIN10_HDR10 -> "HEVC Main10 HDR10"
        CodecProfiles.HEVC_MAIN10_HDR10_PLUS -> "HEVC Main10 HDR10+"
        else -> "HEVC 0x${Integer.toHexString(profile)}"
    }

    mime.equals("video/avc", ignoreCase = true) -> when (profile) {
        0x01 -> "AVC Baseline"
        0x02 -> "AVC Main"
        0x04 -> "AVC Extended"
        0x08 -> "AVC High"
        0x10 -> "AVC High10"
        0x20 -> "AVC High422"
        0x40 -> "AVC High444"
        0x10000 -> "AVC ConstrainedBaseline"
        0x80000 -> "AVC ConstrainedHigh"
        else -> "AVC 0x${Integer.toHexString(profile)}"
    }

    mime.equals("video/av01", ignoreCase = true) -> when (profile) {
        0x01 -> "AV1 Main8"
        0x02 -> "AV1 Main10"
        0x1000 -> "AV1 Main10 HDR10"
        0x2000 -> "AV1 Main10 HDR10+"
        else -> "AV1 0x${Integer.toHexString(profile)}"
    }

    mime.equals("video/x-vnd.on2.vp9", ignoreCase = true) -> when (profile) {
        0x01 -> "VP9 Profile0"
        0x02 -> "VP9 Profile1"
        0x04 -> "VP9 Profile2"
        0x08 -> "VP9 Profile3"
        0x1000 -> "VP9 Profile2 HDR"
        0x2000 -> "VP9 Profile3 HDR"
        0x4000 -> "VP9 Profile2 HDR10+"
        0x8000 -> "VP9 Profile3 HDR10+"
        else -> "VP9 0x${Integer.toHexString(profile)}"
    }

    else -> "0x${Integer.toHexString(profile)}"
}
