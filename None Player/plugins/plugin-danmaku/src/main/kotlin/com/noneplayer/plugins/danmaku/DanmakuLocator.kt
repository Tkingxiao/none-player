package com.noneplayer.plugins.danmaku

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.noneplayer.core.media.MediaItem

/**
 * 弹幕来源匹配（批次 6 任务②）：给一个正在播放的 [MediaItem]，
 * 找出**同目录同名**的弹幕文件（`video.mp4` → `video.xml` / `video.json` / `video.ass`）。
 *
 * 两条通路：
 * - `file://`（或裸路径）→ `java.io` 直接列目录，精确可靠；
 * - `content://`（MediaStore 扫描出的条目）→ 先按 `_id` 查回 `DATA` 真实路径
 *   拿到父目录，再按 `DATA IN (候选全集)` 一次查询命中项，回拼 `content://` Uri；
 *   查询为空时回落 `fileCandidates(data)` 直读真实路径（见下）。
 *
 * **scoped storage 的硬约束（模拟器实测）**：只持 `READ_MEDIA_*` 的 app 在
 * MediaStore.Files 查询里**看不到非媒体文件**（.xml/.ass 行对查询不可见，
 * `MediaScannerConnection.scanFile` 也被拒索引——日志 "Scanned to null"）。
 * 所以同名自动匹配的兜底是**直读 DATA 真实路径**，它只在用户授予
 * "所有文件访问"（MANAGE_EXTERNAL_STORAGE，声明于 app 清单、由弹幕设置
 * 区块引导授予）后生效；未授予时自动匹配静默退空，**手动选择（SAF）不受限**。
 *
 * 所有查询包 `runCatching`：弹幕是增益功能，权限收缩/存储不可用等任何失败
 * 都返回空列表，绝不影响播放主链路。扩展名优先级 xml > json > ass
 * （B 站官方导出最常见也最准，ASS 是转换末端）。
 *
 * **手动选择**（规格的另一半）不走这里——用户经 SAF 选中的文件由
 * `AppSettings.danmakuManualUri` 按视频 uri 持久化，渲染层优先读手动值。
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

        // ② 兄弟文件：一次 IN 查询（候选名即扩展名优先级，names 已按序排列）。
        //    scoped storage 实测：只持 READ_MEDIA_* 的 app 在 Files 集合里
        //    **看不到非媒体文件**（.xml/.ass 行对查询不可见）——所以空结果
        //    大概率不是"不存在"，而是"查不到"。
        var found = querySiblingsByData(resolver, filesUri, parent, names)
        // ③ 直读路径兜底：DATA 真实路径就在手里，直接 java.io 读——
        //    用户授予"所有文件访问"（MANAGE_EXTERNAL_STORAGE，由弹幕设置区块
        //    引导）后生效；未授予时读失败，静默回 None（手动选择走 SAF 不受限）。
        if (found.isEmpty()) {
            return@runCatching fileCandidates(data)
        }
        names.mapNotNull { name ->
            found[name]?.let { Candidate(it, DanmakuFormat.fromFileName(name)!!) }
        }
    }.getOrDefault(emptyList())

    private fun querySiblingsByData(
        resolver: ContentResolver,
        filesUri: Uri,
        parent: String,
        names: List<String>,
    ): MutableMap<String, String> {
        val found = linkedMapOf<String, String>()
        resolver.query(
            filesUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.DATA} IN (${names.joinToString(",") { "?" }})",
            names.map { "$parent/$it" }.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)?.substringAfterLast('/') ?: continue
                if (name !in found) {
                    found[name] = ContentUris.withAppendedId(filesUri, c.getLong(0)).toString()
                }
            }
        }
        return found
    }

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
