package com.noneplayer.plugins.scanner.local

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 扫描索引快照的落盘存取（R0/A33 + REV-03 从 [LocalMediaSource] 提取）。
 *
 * 原实现有两个数据丢失缺陷：
 *  - 写入：`tmp.renameTo(file)` 失败时先 `snapshotFile.delete()` 再重试——重试再失败
 *    就连**旧快照**一起丢了（下次冷启动回到"没有基线"）。
 *  - 读取：`file.readText()` 没有任何字节限额，损坏/被写坏的超大文件会整份读进内存。
 *
 * 本类的契约：
 *  - [read]：缺失 / 损坏 / 版本不符 / 超 [MAX_SNAPSHOT_BYTES] 一律返回 `null`，
 *    **绝不抛异常**（快照只是冷启动加速，不是真相来源）。
 *  - [write]：先在内存里序列化整串，再完整写 `.tmp`，最后原子替换；
 *    任何一步失败都只 `Log.w` + 返回 `false`，**旧快照原封不动**。
 *  - 落盘 JSON 格式与提取前的 `saveSnapshot`/`readSnapshot` 完全一致
 *    （`version=1`，items 数组字段同名同缺省），升级后旧快照仍可读，不丢库。
 */
internal class ScanSnapshotStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 测试注入点：临时文件写完、原子替换之前调用一次。
     *
     * 生产为 `null`。回归测试用它模拟"写到一半失败"（抛 [IOException]），
     * 断言旧快照仍可读、[write] 返回 `false`。异常由 [write] 捕获，不会外泄。
     */
    @VisibleForTesting
    internal var beforeReplace: ((File) -> Unit)? = null

    /**
     * 读快照。文件缺失 / 损坏 / 版本不符 / 超过 [MAX_SNAPSHOT_BYTES] 都返回 `null`，
     * 由调用方按"确实还没扫过"处理；任何解析异常也一并吞掉并记日志。
     */
    suspend fun read(): List<MediaItem>? = withContext(ioDispatcher) {
        // 块内无挂起点：catch Throwable 不会吞掉协程取消（取消由 withContext 自身传播）
        try {
            val bytes = readPayload() ?: return@withContext null
            decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "扫描索引快照损坏，按无快照处理", t)
            null
        }
    }

    /**
     * 写快照（整份替换）。
     *
     * @return `true` 表示新内容已原子替换落盘；`false` 表示本次没写成功，
     *   **磁盘上的旧快照保持不变**（调用方可继续用旧基线）。
     */
    suspend fun write(items: List<MediaItem>): Boolean = withContext(ioDispatcher) {
        try {
            writeBlocking(items)
        } catch (t: Throwable) {
            Log.w(TAG, "扫描索引快照写入失败（旧快照保留）", t)
            false
        }
    }

    // ---- 内部实现（阻塞 I/O，全部在 ioDispatcher 上执行） ----

    /** 读原始字节；缺失返回 null，超限返回 null（带日志）。 */
    private fun readPayload(): ByteArray? {
        if (!file.isFile) return null
        val declaredLength = file.length()
        if (declaredLength > MAX_SNAPSHOT_BYTES) {
            Log.w(TAG, "扫描索引快照超过上限（$declaredLength > $MAX_SNAPSHOT_BYTES 字节），忽略")
            return null
        }
        // 即使 stat 报小也按上限截断读：读的过程中文件可能被替换成超大文件
        val out = ByteArrayOutputStream(declaredLength.toInt().coerceAtLeast(0))
        file.inputStream().use { input ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SNAPSHOT_BYTES) {
                    Log.w(TAG, "扫描索引快照超过上限（已读 $total 字节），忽略")
                    return null
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** JSON 解析；版本不符 / 结构不对返回 null。字段名与缺省值沿用原 readSnapshot。 */
    private fun decode(bytes: ByteArray): List<MediaItem>? {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        if (root.optInt("version") != VERSION) {
            Log.w(TAG, "扫描索引快照版本不符（${root.optInt("version")} != $VERSION），忽略")
            return null
        }
        val arr = root.optJSONArray("items") ?: return null
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val uri = o.optString("uri")
            if (id.isEmpty() || uri.isEmpty()) continue
            out += MediaItem(
                id = id,
                sourceId = o.optString("sourceId").ifEmpty { LOCAL_MEDIA_SOURCE_ID },
                title = o.optString("title"),
                uri = uri,
                mimeType = o.optString("mimeType").ifEmpty { "video/mp4" },
                durationMs = o.optLong("durationMs"),
                sizeBytes = o.optLong("sizeBytes"),
                lastModifiedSec = o.optLong("lastModifiedSec"),
                addedAtMs = o.optLong("addedAtMs"),
                // 视频封面是 cacheDir 下的本地缓存文件（清缓存后必须置空，沿用旧行为）；
                // 音频封面是相册 content:// URI，不是文件系统路径，不能拿 File 判存在
                coverUri = o.optString("coverUri")
                    .takeIf { it.isNotEmpty() }
                    ?.takeIf { it.startsWith("content://") || File(it).isFile },
                folder = o.optString("folder").takeIf { it.isNotEmpty() },
                width = o.optInt("width"),
                height = o.optInt("height"),
                displayTitle = o.optString("displayTitle").takeIf { it.isNotEmpty() },
                seasonNumber = o.optInt("seasonNumber"),
                episodeNumber = o.optInt("episodeNumber"),
                videoCodec = o.optString("videoCodec").takeIf { it.isNotEmpty() },
                clipStartUs = o.optLong("clipStartUs"),
                clipEndUs = o.optLong("clipEndUs"),
            )
        }
        return out
    }

    private fun writeBlocking(items: List<MediaItem>): Boolean {
        // ① 内存中序列化整串：序列化失败时磁盘上什么都还没动
        val bytes = try {
            encode(items).toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "扫描索引快照序列化失败，本次不写", t)
            return false
        }
        // ② 超限不写：宁可下次冷启动慢，也不写一份读不回来的快照
        if (bytes.size.toLong() > MAX_SNAPSHOT_BYTES) {
            Log.w(TAG, "扫描索引快照超过上限（${bytes.size} > $MAX_SNAPSHOT_BYTES 字节），本次不写")
            return false
        }
        // ③ 父目录可能还不存在（首启 / 缓存被清）
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            Log.w(TAG, "无法创建快照目录：$parent")
            return false
        }
        val tmp = File(file.path + TMP_SUFFIX)
        try {
            // ④ 完整写临时文件：写一半被进程杀死时旧快照仍完整
            tmp.outputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
            // ⑤ 测试注入点：模拟替换前失败
            beforeReplace?.invoke(tmp)
            // ⑥ 原子替换（失败只降级，绝不先删旧快照）
            replaceAtomically(tmp)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "扫描索引快照写入失败（旧快照保留）", t)
            // 只清理临时文件；旧快照绝不删除
            runCatching { tmp.delete() }
            return false
        }
    }

    /** 序列化：字段名、类型与缺省值必须与提取前 `saveSnapshot` 逐项一致（升级不丢库）。 */
    private fun encode(items: List<MediaItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("sourceId", item.sourceId)
                    put("title", item.title)
                    put("uri", item.uri)
                    put("mimeType", item.mimeType)
                    put("durationMs", item.durationMs)
                    put("sizeBytes", item.sizeBytes)
                    put("lastModifiedSec", item.lastModifiedSec)
                    put("addedAtMs", item.addedAtMs)
                    item.coverUri?.let { put("coverUri", it) }
                    item.folder?.let { put("folder", it) }
                    put("width", item.width)
                    put("height", item.height)
                    item.displayTitle?.let { put("displayTitle", it) }
                    put("seasonNumber", item.seasonNumber)
                    put("episodeNumber", item.episodeNumber)
                    item.videoCodec?.let { put("videoCodec", it) }
                    put("clipStartUs", item.clipStartUs)
                    put("clipEndUs", item.clipEndUs)
                },
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("items", arr)
            .toString()
    }

    /**
     * 用 `Files.move(ATOMIC_MOVE)` 原子替换；文件系统不支持（或 Windows 上目标被占用）
     * 时降级为 `REPLACE_EXISTING` 普通替换——两条路径都**不会先删旧文件**。
     * 替换失败向上抛，由 [writeBlocking] 兜底返回 `false`。
     */
    private fun replaceAtomically(tmp: File) {
        val source = tmp.toPath()
        val target = file.toPath()
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: AtomicMoveNotSupportedException) {
            Log.w(TAG, "文件系统不支持原子替换，降级为普通替换", e)
        } catch (e: IOException) {
            // 例如 Windows 上目标文件被短暂占用：ATOMIC_MOVE 会失败，普通替换可以成功
            Log.w(TAG, "原子替换失败，降级为普通替换", e)
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val TAG = "ScanSnapshotStore"

        /** 快照文件大小上限：超过即视为损坏/不可信，不读也不写（2 MiB 足够几十万项）。 */
        const val MAX_SNAPSHOT_BYTES = 2L * 1024 * 1024

        /** 落盘格式版本；与提取前 `LocalMediaSource.SNAPSHOT_VERSION` 一致（1）。 */
        const val VERSION = 1

        private const val TMP_SUFFIX = ".tmp"
        private const val READ_CHUNK_BYTES = 8 * 1024
    }
}
