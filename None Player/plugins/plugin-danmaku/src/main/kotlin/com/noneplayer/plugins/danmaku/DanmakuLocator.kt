package com.noneplayer.plugins.danmaku

import android.content.Context
import android.provider.MediaStore
import com.noneplayer.core.media.MediaItem

/**
 * 弹幕来源匹配（批次 6 任务②）：给一个正在播放的 [MediaItem]，
 * 找出**同目录同名**的弹幕文件（`video.mp4` → `video.xml` / `video.json` / `video.ass`）。
 *
 * 两条通路：
 * - `file://`（或裸路径）→ `java.io` 直接列目录，精确可靠；
 * - `content://`（MediaStore 扫描出的条目）→ 先按 `_id` 查回 `DATA` 真实路径
 *   拿到父目录，再按 `DATA IN (候选全集)` 一次查询命中项，回拼 `content://` Uri。
 *
 * 所有查询包 `runCatching`：弹幕是增益功能，权限收缩/存储不可用等任何失败
 * 都返回空列表，绝不影响播放主链路。扩展名优先级 xml > json > ass
 * （B 站官方导出最常见也最准，ASS 是转换末端）。
 *
 * **手动选择**（规格的另一半）不走这里——用户经 SAF 选中的文件由
 * [AppSettings.danmakuManualUri] 按视频 uri 持久化，渲染层优先读手动值。
 */
class DanmakuLocator(private val context: Context) {

    data class Candidate(val uri: String, val format: DanmakuFormat)

    fun candidatesFor(item: MediaItem): List<Candidate> {
        val uri = item.uri
        return when {
            uri.startsWith("file://") -> fileCandidates(uri.removePrefix("file://"))
            uri.startsWith("/") -> fileCandidates(uri)
            uri.startsWith("content://") -> mediaStoreCandidates(uri)
            else -> emptyList()
        }
    }

    // ---- file:// ----

    private fun fileCandidates(path: String): List<Candidate> = runCatching {
        val video = java.io.File(path)
        val parent = video.parentFile ?: return@runCatching emptyList()
        siblingNames(video.name).mapNotNull { name ->
            val f = java.io.File(parent, name)
            if (f.isFile) {
                val format = DanmakuFormat.fromFileName(name) ?: return@mapNotNull null
                Candidate(f.toURI().toString(), format)
            } else {
                null
            }
        }
    }.getOrDefault(emptyList())

    // ---- content:// (MediaStore) ----

    private fun mediaStoreCandidates(uri: String): List<Candidate> = runCatching {
        val id = uri.substringAfterLast('/').toLongOrNull() ?: return@runCatching emptyList()
        val resolver = context.contentResolver
        val filesUri = MediaStore.Files.getContentUri("external")

        // ① 视频自身：拿 DATA（真实路径）定父目录与同名候选
        val data = resolver.query(
            filesUri,
            arrayOf(MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: return@runCatching emptyList()
        val parent = data.substringBeforeLast('/')
        val names = siblingNames(data.substringAfterLast('/'))
        if (names.isEmpty()) return@runCatching emptyList()

        // ② 兄弟文件：一次 IN 查询；候选名即扩展名优先级（names 已按序排列）
        val found = linkedMapOf<String, String>()
        resolver.query(
            filesUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.DATA} IN (${names.joinToString(",") { "?" }})",
            names.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)?.substringAfterLast('/') ?: continue
                if (name !in found) {
                    found[name] = android.content.ContentUris.withAppendedId(filesUri, c.getLong(0)).toString()
                }
            }
        }
        names.mapNotNull { name ->
            found[name]?.let { Candidate(it, DanmakuFormat.fromFileName(name)!!) }
        }
    }.getOrDefault(emptyList())

    companion object {
        /** `video.mp4` → [video.xml, video.json, video.ass]（按扩展名优先级排序） */
        internal fun siblingNames(videoName: String): List<String> {
            val stem = videoName.substringAfterLast('/')
                .substringBeforeLast('.')
                .trim()
            if (stem.isEmpty()) return emptyList()
            return listOf(
                "$stem.${DanmakuFormat.BilibiliXml.extension}",
                "$stem.${DanmakuFormat.ConverterJson.extension}",
                "$stem.${DanmakuFormat.Ass.extension}",
            )
        }
    }
}
