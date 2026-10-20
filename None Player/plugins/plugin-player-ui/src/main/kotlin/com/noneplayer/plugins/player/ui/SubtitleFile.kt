package com.noneplayer.plugins.player.ui

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.noneplayer.core.media.SubtitleCharset
import com.noneplayer.core.media.SubtitleEffectDetector
import com.noneplayer.core.media.SubtitleEffectInfo
import com.noneplayer.core.media.SubtitleEffectNotice
import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.NpSubtitleFormat
import com.noneplayer.kernel.api.npSubtitleFormatOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 字幕文件体积上限：正常字幕远小于此，防误选大文件读爆内存 */
private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

/**
 * 挂载前处理的结果。
 *
 * [uri] 可直接交给播放引擎；[format] 是该字幕文件的容器格式（A3），由**真实文件名**判出，
 * 必须跟着 uri 一起交给引擎——引擎自己从 uri 末段判不出格式（MediaStore / SAF 的 uri 末段
 * 是 id）。[sourceMissing] = 源地址**打不开**（文件被移走 / SAF 授权失效），此时 [uri] 只是
 * 原样回退，播放器大概率也拿不到内容——调用方据此给一条「字幕文件已失效」提示
 * （L7：此前静默降级，用户以为是自己没挂上）。
 */
internal data class PreparedSubtitle(
    val uri: String,
    val format: NpSubtitleFormat? = null,
    val sourceMissing: Boolean = false,
)

/**
 * 挂载前的字幕字符集处理（E40）：
 * 读入 → 探测（[SubtitleCharset]）→ 非 UTF-8 时落 `cache_dir/subs/` 下的 UTF-8 副本。
 * 返回可直接交给播放引擎的地址；任何失败都回退原地址（不阻断挂载）。
 *
 * 副本**沿用源文件的格式扩展名**（A3）：写死 `.srt` 会让转码后的 ASS/VTT 被引擎按
 * SubRip 解——`[Script Info]` 与 `Dialogue:` 对不上时间轴，一条 Cue 都不出，而菜单上
 * 仍显示「已挂载」（读的是原始 uri）。
 */
internal suspend fun prepareSubtitleUri(context: Context, source: Uri): PreparedSubtitle =
    withContext(Dispatchers.IO) {
        // A12 第一期：每次挂载都刷新「含特效」提示；本次没检测到就 publish(null) 覆盖旧提示
        SubtitleEffectNotice.publish(null)
        // 打开失败与"读得太多判定超限"是两码事：前者是源没了（要提示），
        // 后者文件还在、只是不像字幕（不该说"失效"）
        var missing = false
        // 格式先判：不重写时返回的是原 uri，那条 uri 末段往往是 id，事后就判不出来了
        val format = npSubtitleFormatOf(
            queryDisplayName(context.contentResolver, source) ?: source.toString(),
        )
        val uri = runCatching {
            // E63 修复：字幕选择器 arrayOf("*/*") 不限制文件类型，用户可能误选一个
            // 几 GB 的视频文件"当字幕"。此前 readBytes() 先把整个文件读入内存、
            // 再检查 MAX_SUBTITLE_BYTES，保护形同虚设。改为有界读取：
            // 读满上限 +1 字节立即中止，不等整份数据落地。
            // 流打开失败或 readBounded 判定超限（返回 null）都统一回退原地址
            val stream = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
            if (stream == null) {
                missing = true
                return@runCatching source.toString()
            }
            val bytes = stream.use { readBounded(it, MAX_SUBTITLE_BYTES) }
                ?: return@runCatching source.toString()

            val decoded = SubtitleCharset.decode(bytes)
            // A12 第一期：media3 产出 Cue 前就把 ASS 覆盖标签 strip 掉了（SsaParser），
            // 只能在这里用原始文本检测；非 ASS/SSA 格式不扫描。
            SubtitleEffectNotice.publish(
                subtitleEffectInfoFor(format, source.toString(), decoded.text),
            )
            if (!decoded.needsRewrite) return@runCatching source.toString()

            val dir = File(context.cacheDir, "subs").apply { mkdirs() }
            val stem = source.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.take(24)
                .orEmpty()
                .ifEmpty { "subtitle" }
            // E72 修复：32 位 hashCode() 碰撞概率不可忽视，改用 SHA-256 截断
            // （已核查非路径穿越风险：stem 不含 '/'，File(dir, name) 不会逃出 dir；
            //  扩展名取自 NpSubtitleFormat 白名单，不是用户文件名上的任意串）
            val target = File(dir, "${stem}-${sourceDigest(source.toString())}.${subtitleCopyExtension(format)}")
            target.writeText(decoded.text, Charsets.UTF_8)
            target.toUri().toString()
        }.getOrDefault(source.toString())
        PreparedSubtitle(uri, format, missing)
    }

/**
 * A12 第一期：这份外挂字幕里有没有当前渲染层做不到的特效（只对 ASS/SSA 有意义）。
 *
 * - 只扫 `NpSubtitleFormat.SSA`（扩展名 ass/ssa；SRT/VTT/TTML 里的 `\pos` 只是正文）；
 * - 检测不到特效 → null（调用方 publish(null)，提示不出现）；
 * - [text] 为 null（编码探测失败等）也返回 null，绝不因为字幕异常影响挂载。
 *
 * 抽成纯函数是为了纯 JVM 单测能钉住「ASS 有特效才提示」的口径，不依赖 ContentResolver。
 */
internal fun subtitleEffectInfoFor(
    format: NpSubtitleFormat?,
    sourceUri: String,
    text: String?,
): SubtitleEffectInfo? {
    if (format != NpSubtitleFormat.SSA) return null
    val report = SubtitleEffectDetector.detect(text)
    if (!report.hasEffects) return null
    return SubtitleEffectInfo(sourceUri = sourceUri, tags = report.tags)
}

/** 转码副本的扩展名：沿用源格式，判不出来才退回 `.srt`（A3） */
internal fun subtitleCopyExtension(format: NpSubtitleFormat?): String = format?.extension ?: "srt"

/** 取真实文件名（判字幕格式用）；查不到返回 null，由调用方退回 uri 末段 */
private fun queryDisplayName(resolver: android.content.ContentResolver, source: Uri): String? =
    runCatching {
        resolver.query(
            source,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
        }
    }.getOrNull()

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

/**
 * 字幕转码缓存的清理入口（批次 7「插件清缓存」，[CacheProvider]）。
 *
 * `cache_dir/subs/` 下都是按内容摘要命名的临时 UTF-8 副本，删掉后同名挂载
 * 会自动重新转码，删除无副作用。由 [PlayerUiPlugin] 注册进扩展注册表。
 */
internal class SubtitleCacheProvider(context: Context) : CacheProvider {

    private val dir = File(context.cacheDir, "subs")

    override val cacheLabel: String = "字幕转码缓存"

    override fun cacheSizeBytes(): Long =
        runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    override fun clearCache(): Long {
        val before = cacheSizeBytes()
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        return before
    }
}
