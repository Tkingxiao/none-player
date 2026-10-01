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
            // E63 修复：字幕选择器 arrayOf("*/*") 不限制文件类型，用户可能误选一个
            // 几 GB 的视频文件"当字幕"。此前 readBytes() 先把整个文件读入内存、
            // 再检查 MAX_SUBTITLE_BYTES，保护形同虚设。改为有界读取：
            // 读满上限 +1 字节立即中止，不等整份数据落地。
            // 流打开失败或 readBounded 判定超限（返回 null）都统一回退原地址
            val bytes = context.contentResolver.openInputStream(source)
                ?.use { stream -> readBounded(stream, MAX_SUBTITLE_BYTES) }
                ?: return@runCatching source.toString()

            val decoded = SubtitleCharset.decode(bytes)
            if (!decoded.needsRewrite) return@runCatching source.toString()

            val dir = File(context.cacheDir, "subs").apply { mkdirs() }
            val stem = source.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.take(24)
                .orEmpty()
                .ifEmpty { "subtitle" }
            // E72 修复：32 位 hashCode() 碰撞概率不可忽视，改用 SHA-256 截断
            // （已核查非路径穿越风险：stem 不含 '/'，File(dir, name) 不会逃出 dir）
            val target = File(dir, "${stem}-${sourceDigest(source.toString())}.srt")
            target.writeText(decoded.text, Charsets.UTF_8)
            target.toUri().toString()
        }.getOrDefault(source.toString())
    }

/** 有界读取：读满 limit+1 字节即返回 null（超限），不把超限数据整份读入内存 */
private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > limit) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/** 源地址的短哈希（SHA-256 截断 16 位十六进制），用作缓存文件名的碰撞抵抗后缀 */
private fun sourceDigest(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
