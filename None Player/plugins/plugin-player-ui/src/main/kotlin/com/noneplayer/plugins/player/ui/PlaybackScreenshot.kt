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
