package com.noneplayer.plugins.player.ui

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.noneplayer.core.media.SubtitleCharset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 字幕文件体积上限：正常字幕远小于此，防误选大文件读爆内存 */
private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

/**
 * 挂载前的字幕字符集处理（E40）：
 * 读入 → 探测（[SubtitleCharset]）→ 非 UTF-8 时落 `cache_dir/subs/` 下的 UTF-8 副本。
 * 返回可直接交给播放引擎的地址；任何失败都回退原地址（不阻断挂载）。
 */
internal suspend fun prepareSubtitleUri(context: Context, source: Uri): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)
                ?.use { stream -> stream.readBytes() }
                ?: return@runCatching source.toString()
            if (bytes.size > MAX_SUBTITLE_BYTES) return@runCatching source.toString()

            val decoded = SubtitleCharset.decode(bytes)
            if (!decoded.needsRewrite) return@runCatching source.toString()

            val dir = File(context.cacheDir, "subs").apply { mkdirs() }
            val stem = source.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.take(24)
                .orEmpty()
                .ifEmpty { "subtitle" }
            val target = File(dir, "${stem}-${source.toString().hashCode().toUInt().toString(16)}.srt")
            target.writeText(decoded.text, Charsets.UTF_8)
            target.toUri().toString()
        }.getOrDefault(source.toString())
    }
