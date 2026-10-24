package com.noneplayer.plugins.scanner.local

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 封面否定缓存：记住「这个文件抽不出封面」，下次别再为它重复付费。
 *
 * ## 为什么需要
 *
 * 抽帧失败的典型耗时并不比成功低：坏容器要解析到失败点、加密/截断文件要等解码器返回、
 * 某些 mime 误判的文件要白跑一次 [android.media.MediaMetadataRetriever]。这些结论对**同一个文件**
 * 是稳定的，但抽帧队列每轮扫描都会重建，不落盘就等于每轮重试一遍（用户抱怨「二次扫描还是慢」）。
 *
 * ## 判据：文件指纹，不是文件 id
 *
 * key = [keyOf]（`id|sizeBytes|lastModifiedSec`）。文件被替换/重新压缩后 size 或 mtime 一定变，
 * 指纹随之变化，缓存自然失效、重新尝试。这样既不依赖文件的「逻辑 id 稳定」，也不需要任何失效钩子。
 *
 * ## 生命周期
 *
 * - 抽帧失败 → [markFailed]（内部按 [MIN_SAVE_INTERVAL_MS] 节流落盘，不会每失败一项就写一次盘）；
 * - 抽帧前 → [isFailed]（过期的条目顺手清掉，读到即淘汰，不需要后台清扫协程）；
 * - 收尾/静默 → [flush] 强制落盘；用户要求清缓存/重置索引 → [clear]。
 *
 * ## 容错
 *
 * 文件损坏、版本不符、超过 [MAX_PAYLOAD_BYTES] 一律当**空表**（缓存只是省时间的提示，
 * 不能因为它坏掉而影响抽帧正确性；大不了重试一轮）。落盘用 [writeBlocking] 的 `.tmp` + 原子替换，
 * 写失败保留旧文件、内存里的结论仍然有效（下次再写）。
 *
 * @param file 落盘文件（生产环境 `File(filesDir, FILE_NAME)`；测试可注入临时目录）
 * @param ttlMs 结论有效期（默认 7 天：足够覆盖「用户过几天又打开同一堆文件」，又不至于让改过的
 *   文件在指纹失效前被永久拒绝——虽然指纹机制已经兜住了后者，TTL 是第二道保险）
 * @param maxEntries 条目上限（默认 2000，超出按最旧淘汰：坏文件通常是少数派，2000 足够覆盖常见的
 *   「一堆损坏的下载文件」场景，又不会让 JSON 失控）
 * @param minSaveIntervalMs 内部节流间隔（[save] 最少间隔多久写一次盘）
 * @param nowMs 当前时间（毫秒；仅用于 [save] 的节流判断，[isFailed]/[markFailed] 的时间由调用方显式传入）
 * @param log 日志回调（默认走 Log.i，TAG=ScanCoverNegativeCache；测试注入收集器）
 */
internal class ScanCoverNegativeCache(
    private val file: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val minSaveIntervalMs: Long = MIN_SAVE_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /** 保护下面全部可变状态：抽帧线程与 UI 侧 requestCovers 可能并发访问同一个实例。 */
    private val lock = Any()

    /**
     * 落盘串行锁（E208 审查补）：真正的 tmp 写入/原子替换只在**这把**锁里做，状态 [lock]
     * 仍然只护内存。原因有两条：
     * - 多个抽帧 worker 同时 `markFailed` 时，旧实现会并发写同一个 `.tmp` 并同时 rename，
     *   可能让旧快照覆盖新快照或 rename 失败；
     * - `clear()` 与在途落盘交错时，已经 claim 的旧 JSON 会在 clear 之后把文件写回来。
     * 加锁顺序固定为 [ioLock] → [lock]（save/clear 都按此顺序），读取路径只拿 [lock]。
     */
    private val ioLock = Any()

    /** key → 首次判定失败的时间戳（毫秒）。插入顺序即「被发现失败」的顺序，淘汰时同毫秒按它兜底。 */
    private val entries = LinkedHashMap<String, Long>()

    private var loaded = false
    private var dirty = false

    /** 上次真实写盘时间；null 表示本次进程还没写过（首次写不受节流限制）。 */
    private var lastWriteAtMs: Long? = null

    /**
     * 这个文件是否已知抽不出封面。
     *
     * @param key 文件指纹，用 [keyOf] 构造
     * @param nowMs 当前时间（毫秒）：过期的结论顺手删除并返回 false（读到即淘汰）
     * @return true = 已知失败，跳过抽帧；false = 没结论或结论已过期（应当重试）
     */
    fun isFailed(key: String, nowMs: Long): Boolean = synchronized(lock) {
        ensureLoaded()
        val failedAt = entries[key] ?: return false
        if (isExpired(failedAt, nowMs)) {
            entries.remove(key)
            dirty = true
            return false
        }
        true
    }

    /**
     * 记录一次抽帧失败。
     *
     * 幂等：同一指纹已有**未过期**结论时不再改写（结论与时间无关，重复刷新时间戳只会让坏文件
     * 更久被重试，没有收益），返回 false。落盘在**锁外**做（E205⑦）：markFailed 的锁被泵的
     * 全部 worker 与 needsCover 查表共用，持锁写盘会把抽帧线程串到磁盘 IO 上。
     *
     * @return true = 新增了一条否定结论（[dirty] 已置位，会随 [save]/[flush] 落盘）
     */
    fun markFailed(key: String, nowMs: Long): Boolean {
        val added = synchronized(lock) {
            ensureLoaded()
            val failedAt = entries[key]
            if (failedAt != null && !isExpired(failedAt, nowMs)) return false
            entries[key] = nowMs
            evictIfNeeded()
            dirty = true
            true
        }
        if (added) save()
        return true
    }

    /**
     * 节流落盘：距上次写盘不足 [minSaveIntervalMs] 时直接跳过（返回 false），
     * 让「连续几十个失败」只产生一次 IO。没有待写内容时也不写。
     *
     * @return true = 本次真的写了文件
     */
    fun save(): Boolean = saveInternal(force = false)

    /**
     * 强制落盘：忽略节流。扫描收尾/静默时调用一次，保证下次冷启能直接命中。
     *
     * @return true = 本次真的写了文件
     */
    fun flush(): Boolean = saveInternal(force = true)

    /**
     * E205⑦ 的落盘结构：锁内只做「该不该写 + 配额淘汰 + 序列化 + 节流预约」（纯内存），
     * 真正的 tmp 写文件与原子替换在**锁外**。节流时间在**预约时**推进而不是写成功后——
     * 磁盘满/目录只读时不会退化成「每条失败都试一次全量写」的写放大；写失败由 dirty
     * 复位兜着，下个节流窗口或 flush() 会再试。
     */
    private fun saveInternal(force: Boolean): Boolean = synchronized(ioLock) {
        val claim = synchronized(lock) {
            ensureLoaded()
            if (!dirty) return false
            val now = nowMs()
            val last = lastWriteAtMs
            if (!force && last != null && now - last < minSaveIntervalMs) return false
            lastWriteAtMs = now
            dirty = false
            serializeWithinQuota() to entries.size
        }
        val (json, count) = claim
        val ok = writeClaimed(json, count)
        if (!ok) synchronized(lock) { dirty = true }
        ok
    }

    /** 锁外执行的实际写盘：tmp + 原子替换；失败保留旧文件并置回 dirty。 */
    private fun writeClaimed(json: String, count: Int): Boolean {
        val tmp = File(file.path + TMP_SUFFIX)
        return try {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeText(json, Charsets.UTF_8)
            replaceAtomically(tmp)
            log("cover negative saved entries=" + count)
            true
        } catch (t: Throwable) {
            // 写失败保留旧文件（绝不先删），内存结论仍然有效，下次再写。
            Log.w(TAG, "封面否定缓存落盘失败", t)
            tmp.delete()
            false
        }
    }

    /** 清空结论并删除落盘文件（用户「清除缓存」/重置索引时调用）。 */
    fun clear() {
        // 先等在途落盘结束，再清内存+删文件：否则在途的旧 JSON 会在 clear 后把文件写回。
        synchronized(ioLock) {
            synchronized(lock) {
                entries.clear()
                dirty = false
                lastWriteAtMs = null
                loaded = true
            }
            file.delete()
            File(file.path + TMP_SUFFIX).delete()
        }
    }

    /** 当前持有的条目数（已淘汰过期项；仅用于日志/自检）。 */
    fun failedCount(): Int = synchronized(lock) {
        ensureLoaded()
        entries.size
    }

    // ---------------- 加载 ----------------

    /** 惰性加载：首次访问时读一次盘（构造不碰磁盘，方便在任意线程 new）。任何异常都退化成空表。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) {
            log("cover negative loaded entries=0")
            return
        }
        try {
            // E205⑩：先按 length 预检再读入——被外部写坏成超大文件时不再先做一次全量分配
            val len = file.length()
            if (len > MAX_PAYLOAD_BYTES) {
                log("cover negative loaded entries=0 oversized=" + len)
                return
            }
            val bytes = file.readBytes()
            if (bytes.size > MAX_PAYLOAD_BYTES) {
                // 超限文件可能是坏数据，也可能是别的版本写的：都当空表，别拿它当真相。
                log("cover negative loaded entries=0 oversized=" + bytes.size)
                return
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) {
                log("cover negative loaded entries=0")
                return
            }
            val obj = root.optJSONObject("entries") ?: run {
                log("cover negative loaded entries=0")
                return
            }
            val now = nowMs()
            val parsed = ArrayList<Pair<String, Long>>()
            val keyIt = obj.keys()
            while (keyIt.hasNext()) {
                val key = keyIt.next()
                val failedAt = obj.optLong(key, -1L)
                if (failedAt <= 0L) continue
                if (isExpired(failedAt, now)) continue
                parsed.add(key to failedAt)
            }
            // 上限在读取侧也执行一遍（文件可能由旧版本/被手工改过写成超限）。
            val kept = parsed.sortedByDescending { it.second }.take(maxEntries).sortedBy { it.second }
            for ((key, failedAt) in kept) entries[key] = failedAt
            log("cover negative loaded entries=" + entries.size)
        } catch (t: Throwable) {
            // JSON 损坏 / 编码错误 / 读盘异常：当空表，缓存绝对不能影响抽帧正确性。
            entries.clear()
            log("cover negative loaded entries=0")
            Log.w(TAG, "封面否定缓存读取失败，按空表处理", t)
        }
    }

    // ---------------- 落盘 ----------------

    /**
     * 序列化，并保证**落盘永远不超过** [MAX_PAYLOAD_BYTES]：超限就从最旧的条目开始丢。
     *
     * 为什么在这里丢而不是拒绝写：条目是「省时间的提示」，丢掉最旧的几条只是让它们下次重试一次，
     * 而写一个超限文件会让下次 load 直接当空表 —— 那才是真正的全量失效。
     */
    private fun serializeWithinQuota(): String {
        var json = toJson()
        while (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES && entries.isNotEmpty()) {
            // 每轮按 10% 批量淘汰：几千条超限时逐条重序列化太慢（每次都要重建整串 JSON）。
            val drop = maxOf(1, entries.size / 10)
            val oldestKeys = entries.entries.sortedBy { it.value }.take(drop).map { it.key }
            if (oldestKeys.isEmpty()) break
            for (key in oldestKeys) entries.remove(key)
            json = toJson()
        }
        return json
    }

    private fun toJson(): String {
        val obj = JSONObject()
        for ((key, failedAt) in entries) obj.put(key, failedAt)
        return JSONObject()
            .put("version", VERSION)
            .put("entries", obj)
            .toString()
    }

    private fun replaceAtomically(tmp: File) {
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Log.w(TAG, "文件系统不支持原子替换，降级为普通替换", e)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Log.w(TAG, "原子替换失败，降级为普通替换", e)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------------- 私有工具 ----------------

    /**
     * 结论是否过期。
     *
     * [failedAt] <= 0 或「来自未来」（nowMs < failedAt，通常是系统时间被回拨）都按过期处理：
     * 宁可多试一次，也不要因为一条可疑时间戳永久拒绝一个好文件。
     */
    private fun isExpired(failedAt: Long, nowMs: Long): Boolean {
        if (failedAt <= 0L) return true
        val age = nowMs - failedAt
        if (age < 0L) return true
        return age > ttlMs
    }

    /** 超出 [maxEntries] 就丢最旧的（同时间戳按插入顺序取先插入的那条）。 */
    private fun evictIfNeeded() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value } ?: return
            entries.remove(oldest.key)
        }
    }

    companion object {
        private const val TAG = "ScanCoverNegativeCache"

        /** 落盘格式版本；结构变更时 +1，旧版本按空表处理。 */
        const val VERSION = 1

        /** 落盘文件名（放 filesDir 下，与 scan_walk_index.json / scan_lanes.json 同级）。 */
        const val FILE_NAME = "cover_negative.json"

        /** 结论有效期：7 天。 */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** 条目上限：超出按最旧淘汰。 */
        const val MAX_ENTRIES = 2_000

        /** 落盘字节上限（256 KiB），同时也是读取上限：超过即视为不可信。 */
        const val MAX_PAYLOAD_BYTES = 256L * 1024

        /** 内部节流的最小写盘间隔。 */
        const val MIN_SAVE_INTERVAL_MS = 1_000L

        private const val TMP_SUFFIX = ".tmp"

        /**
         * 文件指纹：`id|sizeBytes|lastModifiedSec`。
         *
         * 用「id + 大小 + 修改时间」而不是裸 id：文件被替换/重新压缩后这一串必然变化，
         * 否定结论自动失效、重新尝试，既不会误拒好文件，也不需要任何失效钩子。
         */
        fun keyOf(id: String, sizeBytes: Long, lastModifiedSec: Long): String =
            id + "|" + sizeBytes + "|" + lastModifiedSec
    }
}
