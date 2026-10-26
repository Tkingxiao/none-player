package com.noneplayer.plugins.player.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun savePlaybackScreenshot(
    context: Context,
    surface: TextureView?,
    scope: CoroutineScope,
    blocked: Boolean,
    notify: (String) -> Unit,
) {
    if (blocked) {
        notify("当前已禁止截图")
        return
    }
    // E233：API 26–28 的 MediaStore 插入需要 WRITE_EXTERNAL_STORAGE（29+ 贡献自有
    // 媒体不需要）。权限已由 E233 的清单与申请表（sdkInt ≤ 29 → READ|WRITE）覆盖，
    // 但用户可能还没补授权——这里先给明确提示，不再让 insert 抛异常后只说"保存失败"。
    val writeAccessOk = android.os.Build.VERSION.SDK_INT > 28 ||
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (screenshotLegacyWriteMissing(android.os.Build.VERSION.SDK_INT, writeAccessOk)) {
        notify("缺少存储权限，无法保存截图（请在媒体库补授权后重试）")
        return
    }
    if (surface == null || surface.width <= 0 || surface.height <= 0) {
        notify("画面尚未就绪，稍后再试")
        return
    }
    val bitmap = runCatching { surface.getBitmap(surface.width, surface.height) }.getOrNull()
    if (bitmap == null) {
        notify("截图失败")
        return
    }
    scope.launch {
        val saved = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var outputUri: android.net.Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "noneplayer_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/None Player")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val uri = outputUri ?: return@withContext false
                val written = resolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } == true
                check(written)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                }
                true
            } catch (_: Exception) {
                outputUri?.let { runCatching { resolver.delete(it, null, null) } }
                false
            } finally {
                bitmap.recycle()
            }
        }
        notify(if (saved) "截图已保存到相册" else "截图保存失败")
    }
}

/**
 * E233：API 26–28 的截图保存是否卡在缺失的写存储权限（纯函数，便于单测）。
 *
 * 29+ 向 MediaStore 贡献自有媒体不需要 `WRITE_EXTERNAL_STORAGE`（且该权限在 30+
 * 已失效），只有 26–28 的 legacy 插入把它当硬前置——缺它时 insert 直接抛异常。
 */
internal fun screenshotLegacyWriteMissing(sdkInt: Int, writeAccessOk: Boolean): Boolean =
    sdkInt <= 28 && !writeAccessOk
