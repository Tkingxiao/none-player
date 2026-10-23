package com.noneplayer.plugins.scanner.local

import android.util.Log
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
 * 目录级增量索引：给「已经扫过的文件夹」做二次扫描加速。
 *
 * 背景：SAF 目录遍历的每一次 $queryChildDocuments 都是一次 binder IPC，几千个目录
 * （尤其是 MediaStore 根本看不到的隐藏目录）逐层递归下来就慢到没法看。本索引把**遍历过的
 * 目录**连同该目录直接包含的媒体条目持久化到 filesDir/scan_walk_index.json，二次扫描时
 * 只要「父目录列表里看到的 lastModified 没变 + 未过 TTL + inheritedHidden 一致」，就直接
 * 合并缓存条目，**0 次 IPC**。
 *
 * ## 正确性边界（务必读完再用）
 *
 * **只对 [Entry.leaf] == true（没有子目录的叶子目录）复用。**
 *
 * - 非叶目录的深层新增只改**子目录自己**的 mtime，不会传播到祖先。若按祖先目录的 mtime
 *   复用，Anc/sub/New.mp4 这种新增（Anc 的 mtime 不变）会被整棵跳过 —— 直接漏文件。
 * - 叶子目录一旦增删/重命名自己的条目，**必然**改它自身的 mtime，而这一点在父目录的
 *   queryChildDocuments 结果里就能观察到。因此叶子目录的「mtime 未变 ⇒ 内容未变」是安全的。
 *
 * 此外以下情况**一律不复用**（见 [reusable]）：
 * - [Entry.lastModifiedMs] <= 0：SAF 有些虚拟目录不给 mtime，没有 mtime 就没有判据；
 * - lastModifiedMs 与父目录列表里看到的不一致；
 * - inheritedHidden 不一致：隐藏判定（E198 数据保护）依赖继承的隐藏位，变了就重走；
 * - TTL 过期（默认 5 分钟，见 [DEFAULT_TTL_MS]）：规避 mtime 精度、时钟回拨、Provider 侧
 *   缓存不一致等长尾问题；
 * - [Entry.payloadDropped]：条目详情已被配额丢弃，无法重建内容；
 * - 版本不符 / 文件损坏 / 解析失败：整体按空索引处理（[load] 从不抛异常）。
 *
 * ## 残留边界（已知且接受）
 *
 * **叶子目录内「同名覆盖写」在 TTL 内不会被发现**：父目录列表里只暴露子目录自己的 mtime，
 * 覆盖写一个已有文件名既不改叶子目录 mtime、也不改条目数，索引里该条目的 sizeBytes/mime
 * 就还是旧值，直到 TTL 过期才会重走。这是复用判据本身的边界，无法靠父目录观察消除，
 * 只能靠缩短 TTL 把陈旧窗口压小。
 *
 * ## 配额与落盘
 *
 * - [MAX_ENTRIES] 条上限、[MAX_PAYLOAD_BYTES] 字节上限。超配额时**先丢 items 只留骨架**
 *   （最旧的目录优先丢），骨架仍然超额时再从最旧的整条丢弃 —— 骨架本身只用于 [lookup]/
 *   统计，不能复用（[reusable] 会拒绝 payloadDropped 条目），所以丢弃不影响正确性，
 *   只影响下次能省多少 IPC。
 * - 原子写：先完整写 .tmp，再 Files.move(ATOMIC_MOVE) 替换，失败降级普通替换，
 *   **绝不先删旧文件**（旧索引原样保留），姿势与 [ScanSnapshotStore] 一致。
 * - [load] 任何异常都不外泄：索引只是加速手段，不是真相来源。
 *
 * 线程安全：本类内部用 [lock] 保护 map，可被多条遍历车道并发 [record]。
 *
 * @param file 落盘文件（生产为 filesDir/scan_walk_index.json）
 * @param ttlMs 复用有效期，默认 [DEFAULT_TTL_MS]
 * @param ioDispatcher 落盘/读取调度器（测试可注入）
 */
internal class ScanWalkIndex(
    private val file: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 一个**被遍历过**的目录的快照。
     *
     * @param lastModifiedMs 该目录自身的 lastModified（毫秒），来自**父目录列表**；<= 0 表示
     *   Provider 没给，此时永不复用
     * @param inheritedHidden 遍历到该目录时继承下来的隐藏位（E198 数据保护语义）
     * @param leaf 该目录当时**没有子目录**。只有 true 才允许复用（见类 KDoc 的正确性边界）
     * @param items 该目录直接包含的媒体条目；仅 leaf 时可能非空；被配额丢弃时为空且
     *   [payloadDropped] 为 true
     * @param walkedAtMs 本轮遍历到该目录的时刻，用于 TTL 判定与超配额时的丢弃优先级
     * @param payloadDropped [items] 是否因超配额被丢弃。true 时 [reusable] 一律返回 null
     */
    data class Entry(
        val docId: String,
        val treeUri: String,
        val lastModifiedMs: Long,
        val inheritedHidden: Boolean,
        val leaf: Boolean,
        val items: List<Indexed> = emptyList(),
        val walkedAtMs: Long,
        val payloadDropped: Boolean = false,
    )

    /** 目录直接包含的一个条目（足够重建 saf- 前缀 ID 与隐藏/垃圾判定流水线）。 */
    data class Indexed(
        val docId: String,
        val name: String,
        val mime: String?,
        val sizeBytes: Long,
        val lastModifiedSec: Long,
        val hidden: Boolean,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    // ---- 查询 / 维护（内存，同步） ----

    /** 已缓存的目录条数（含被丢弃 payload 的骨架）。 */
    fun size(): Int = synchronized(lock) { entries.size }

    /** 按 (treeUri, docId) 取原始条目，不做任何有效性判断；[reusable] 才是复用入口。 */
    fun lookup(treeUri: String, docId: String): Entry? =
        synchronized(lock) { entries[keyOf(treeUri, docId)] }

    /**
     * **是否可 0 IPC 复用**：命中则返回该条目，否则返回 null。
     *
     * 判据（全部满足才复用，任一不满足都重走）：
     * 1. 索引里存在该目录，且 [Entry.leaf] == true（非叶目录复用会漏文件，见类 KDoc）；
     * 2. 不是被配额丢掉 payload 的骨架（[Entry.payloadDropped] == false）；
     * 3. [Entry.lastModifiedMs] > 0 且**等于**本轮在父目录列表里看到的 mtime；
     * 4. [Entry.inheritedHidden] 与本轮继承的隐藏位一致；
     * 5. 未过 TTL：walkedAtMs <= nowMs <= walkedAtMs + ttlMs。时钟回拨（nowMs < walkedAtMs）
     *    按过期处理 —— 宁可多走一次 IPC，也不拿可疑时钟复用。
     *
     * @param parentSeenLastModifiedMs 本轮父目录列表里看到的该目录 mtime（毫秒）
     */
    fun reusable(
        treeUri: String,
        docId: String,
        parentSeenLastModifiedMs: Long,
        inheritedHidden: Boolean,
        nowMs: Long,
    ): Entry? {
        val e = lookup(treeUri, docId) ?: return null
        if (!e.leaf) return null
        if (e.payloadDropped) return null
        if (e.lastModifiedMs <= 0L) return null
        if (e.lastModifiedMs != parentSeenLastModifiedMs) return null
        if (e.inheritedHidden != inheritedHidden) return null
        val age = nowMs - e.walkedAtMs
        if (age < 0L || age > ttlMs) return null
        return e
    }

    /** 记录/覆盖一条目录快照（遍历车道可并发调用）。空 docId 直接忽略。 */
    fun record(e: Entry) {
        if (e.docId.isEmpty()) return
        synchronized(lock) { entries[keyOf(e.treeUri, e.docId)] = e }
    }

    /**
     * 只保留本轮**实际走到**的目录，其余裁掉 —— 防止「删除的目录/改名的树」在索引里越积越多。
     *
     * @param keys 本轮看到的所有目录，必须用 [keyOf] 构造
     */
    fun retainOnly(keys: Set<String>) {
        synchronized(lock) { entries.keys.retainAll(keys) }
    }

    /** 清空内存索引并尽力删除落盘文件（索引开关关闭 / 换树 / 测试复位时用）。 */
    fun clear() {
        synchronized(lock) { entries.clear() }
        runCatching { if (file.isFile) file.delete() }
    }

    // ---- 落盘 ----

    /**
     * 读盘。文件缺失 / 超过 [MAX_PAYLOAD_BYTES] / 版本不符 / 解析失败，一律**当作空索引**，
     * 绝不抛异常（与 [ScanSnapshotStore.read] 同一姿势）。
     */
    suspend fun load() = withContext(ioDispatcher) {
        // 块内无挂起点：catch Throwable 不会吞掉协程取消（取消由 withContext 自身传播）
        val loaded: Map<String, Entry>? = try {
            decode(readPayload())
        } catch (t: Throwable) {
            Log.w(TAG, "目录遍历索引损坏，按空索引处理", t)
            null
        }
        val size = synchronized(lock) {
            entries.clear()
            if (loaded != null) entries.putAll(loaded)
            entries.size
        }
        // 真机取证：logcat | grep ScanWalkIndex 即可看到本次复用了多少目录骨架
        Log.i(TAG, "index loaded entries=$size")
        // 同时落盘到 scan_trace.log：一次扫描的完整时间线只在一个文件里，adb pull 一份就够
        ScanTrace.log("index loaded entries=$size")
    }

    /**
     * 写盘（整份替换，原子写）。
     *
     * 超 [MAX_ENTRIES] / [MAX_PAYLOAD_BYTES] 时**先丢 items 保留骨架**（最旧的目录先丢），
     * 骨架仍超额时再从最旧的整条丢弃 —— 宁可少些加速，也不写一份读不回来的索引。
     *
     * @return true 表示已原子替换落盘；false 表示本次没写成功，**磁盘上的旧索引保持不变**
     */
    suspend fun save(): Boolean = withContext(ioDispatcher) {
        try {
            writeBlocking()
        } catch (t: Throwable) {
            Log.w(TAG, "目录遍历索引写入失败（旧索引保留）", t)
            false
        }
    }

    // ---- 内部实现（阻塞 I/O，全部在 ioDispatcher 上执行） ----

    private fun readPayload(): ByteArray? {
        if (!file.isFile) return null
        val declaredLength = file.length()
        if (declaredLength > MAX_PAYLOAD_BYTES) {
            Log.w(TAG, "目录遍历索引超过上限（${declaredLength} > $MAX_PAYLOAD_BYTES 字节），忽略")
            return null
        }
        val out = ByteArrayOutputStream(declaredLength.toInt().coerceAtLeast(0))
        file.inputStream().use { input ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_PAYLOAD_BYTES) {
                    Log.w(TAG, "目录遍历索引超过上限（已读 $total 字节），忽略")
                    return null
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** JSON 解析；版本不符 / 结构不对返回空表。字段名见 [entryObject]。 */
    private fun decode(bytes: ByteArray?): Map<String, Entry>? {
        if (bytes == null || bytes.isEmpty()) return emptyMap()
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        if (root.optInt("version") != VERSION) {
            Log.w(TAG, "目录遍历索引版本不符（${root.optInt("version")} != $VERSION），忽略")
            return emptyMap()
        }
        val arr = root.optJSONArray("entries") ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>(arr.length().coerceAtLeast(0))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val docId = o.optString("docId")
            val treeUri = o.optString("treeUri")
            if (docId.isEmpty() || treeUri.isEmpty()) continue
            val itemsArr = o.optJSONArray("items")
            // 没有 items 数组且没标 payloadDropped：视为 payload 已丢失，宁可不复用
            val payloadDropped = o.optBoolean("payloadDropped", false) || itemsArr == null
            val items = if (itemsArr == null) {
                emptyList()
            } else {
                val list = ArrayList<Indexed>(itemsArr.length())
                for (j in 0 until itemsArr.length()) {
                    val io = itemsArr.optJSONObject(j) ?: continue
                    val itemDocId = io.optString("docId")
                    if (itemDocId.isEmpty()) continue
                    list += Indexed(
                        docId = itemDocId,
                        name = io.optString("name"),
                        mime = io.optString("mime").takeIf { it.isNotEmpty() },
                        sizeBytes = io.optLong("sizeBytes"),
                        lastModifiedSec = io.optLong("lastModifiedSec"),
                        hidden = io.optBoolean("hidden", false),
                    )
                }
                list
            }
            val entry = Entry(
                docId = docId,
                treeUri = treeUri,
                lastModifiedMs = o.optLong("lastModifiedMs"),
                inheritedHidden = o.optBoolean("inheritedHidden", false),
                leaf = o.optBoolean("leaf", false),
                items = items,
                walkedAtMs = o.optLong("walkedAtMs"),
                payloadDropped = payloadDropped,
            )
            out[keyOf(treeUri, docId)] = entry
        }
        if (out.size <= MAX_ENTRIES) return out
        // 文件被手改/异常膨胀：只保留最新的 MAX_ENTRIES 条
        return out.values
            .sortedWith(compareByDescending<Entry> { it.walkedAtMs }.thenBy { keyOf(it.treeUri, it.docId) })
            .take(MAX_ENTRIES)
            .associateBy { keyOf(it.treeUri, it.docId) }
    }

    private fun writeBlocking(): Boolean {
        // ① 在内存里序列化整串（含配额裁剪）：序列化失败时磁盘上什么都还没动
        val snapshot = serializeWithinQuota(synchronized(lock) { entries.values.toList() })
        val json = snapshot.json
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_PAYLOAD_BYTES) {
            // 正常情况下 serializeWithinQuota 已保证不超；这里是最后一道闸
            Log.w(TAG, "目录遍历索引仍超过上限（${bytes.size} 字节），本次不写")
            return false
        }
        // ② 父目录可能还不存在（首启 / 缓存被清）
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            Log.w(TAG, "无法创建索引目录：$parent")
            return false
        }
        val tmp = File(file.path + TMP_SUFFIX)
        try {
            // ③ 完整写临时文件：写一半被进程杀死时旧索引仍完整
            tmp.outputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
            // ④ 原子替换（失败只降级，绝不先删旧索引）
            replaceAtomically(tmp)
            // 真机取证：entries = 落盘条数，dropped = 因字节配额丢了 payload 的条数
            Log.i(TAG, "index saved entries=${snapshot.kept} dropped=${snapshot.droppedPayload}")
            ScanTrace.log("index saved entries=${snapshot.kept} dropped=${snapshot.droppedPayload}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "目录遍历索引写入失败（旧索引保留）", t)
            runCatching { tmp.delete() }
            return false
        }
    }

    /**
     * 序列化并在配额内裁剪。
     *
     * 优先级：**保留新目录 > 保留 payload（新目录优先） > 保留骨架**。
     * 即：先按 [MAX_ENTRIES] 只留最新的条数；再按 [MAX_PAYLOAD_BYTES] 从最旧的开始丢 items；
     * 最后连骨架都放不下时，从最旧的整条丢弃。
     */
    /**
     * 一次配额裁剪的结果。
     *
     * @param json 最终要落盘的 JSON
     * @param kept 实际写进去的条目数
     * @param droppedPayload 因字节配额被丢掉 items 的条目数（只留骨架）
     * @param droppedEntries 连骨架都放不下、被整条丢弃的条目数
     */
    private data class QuotaSnapshot(
        val json: String,
        val kept: Int,
        val droppedPayload: Int,
        val droppedEntries: Int,
    )

    private fun serializeWithinQuota(all: List<Entry>): QuotaSnapshot {
        // ① 条数配额：只留最新的 MAX_ENTRIES 条（walkedAtMs 越大越新）
        val newestFirst = all
            .sortedWith(compareByDescending<Entry> { it.walkedAtMs }.thenBy { keyOf(it.treeUri, it.docId) })
            .take(MAX_ENTRIES)

        val fullLens = newestFirst.map { utf8Len(entryObject(it, includeItems = true).toString()) }
        val skelLens = newestFirst.map { utf8Len(entryObject(it, includeItems = false).toString()) }

        // ② 一条都不用丢的情况（最常见）
        if (totalBytes(newestFirst.size, fullLens) <= MAX_PAYLOAD_BYTES) {
            return QuotaSnapshot(encodeEntries(newestFirst, includeItems = true), newestFirst.size, 0, 0)
        }

        // ③ 字节配额阶段一：从最旧的目录开始丢 items，只留骨架
        val dropOrder = newestFirst.indices.sortedWith(
            compareBy<Int> { newestFirst[it].walkedAtMs }
                .thenBy { keyOf(newestFirst[it].treeUri, newestFirst[it].docId) },
        )
        var estimated = totalBytes(newestFirst.size, fullLens)
        val droppedIdx = HashSet<Int>()
        for (idx in dropOrder) {
            if (estimated <= MAX_PAYLOAD_BYTES) break
            estimated -= (fullLens[idx] - skelLens[idx]).coerceAtLeast(0L)
            droppedIdx += idx
        }
        if (estimated <= MAX_PAYLOAD_BYTES) {
            val shrunk = newestFirst.mapIndexed { i, e ->
                if (i in droppedIdx) e.copy(items = emptyList(), payloadDropped = true) else e
            }
            val json = encodeEntries(shrunk, includeItems = true)
            if (utf8Len(json) <= MAX_PAYLOAD_BYTES) {
                Log.w(TAG, "目录遍历索引超配额，丢弃 ${droppedIdx.size}/${newestFirst.size} 条 payload")
                return QuotaSnapshot(json, shrunk.size, droppedIdx.size, 0)
            }
        }

        // ④ 字节配额阶段二：骨架也放不下 → 从最旧的整条丢弃（保留最新的一段）
        var acc = PREFIX.length.toLong() + SUFFIX.length.toLong() + 1L // 空数组的 "[]" 也算 1 字节
        var keep = 0
        for (i in newestFirst.indices) {
            val add = skelLens[i] + if (i > 0) 1L else 0L
            if (acc + add > MAX_PAYLOAD_BYTES) break
            acc += add
            keep++
        }
        var skeletons = newestFirst.take(keep).map { it.copy(items = emptyList(), payloadDropped = true) }
        var json = encodeEntries(skeletons, includeItems = true)
        // 估算是精确的，这里只是最后一道兜底（例如转义差异）
        while (skeletons.isNotEmpty() && utf8Len(json) > MAX_PAYLOAD_BYTES) {
            skeletons = skeletons.dropLast(1)
            json = encodeEntries(skeletons, includeItems = true)
        }
        if (utf8Len(json) > MAX_PAYLOAD_BYTES) {
            return QuotaSnapshot(encodeEntries(emptyList(), includeItems = true), 0, 0, newestFirst.size)
        }
        Log.w(TAG, "目录遍历索引超配额，只保留骨架 ${skeletons.size}/${newestFirst.size} 条")
        return QuotaSnapshot(json, skeletons.size, skeletons.size, newestFirst.size - skeletons.size)
    }

    private fun encodeEntries(list: List<Entry>, includeItems: Boolean): String {
        val arr = JSONArray()
        list.forEach { arr.put(entryObject(it, includeItems)) }
        // 字段插入顺序固定为 version 在前，[PREFIX] 的常量估算依赖它
        return JSONObject()
            .put("version", VERSION)
            .put("entries", arr)
            .toString()
    }

    private fun entryObject(e: Entry, includeItems: Boolean): JSONObject {
        val dropped = e.payloadDropped || !includeItems
        val o = JSONObject()
            .put("docId", e.docId)
            .put("treeUri", e.treeUri)
            .put("lastModifiedMs", e.lastModifiedMs)
            .put("inheritedHidden", e.inheritedHidden)
            .put("leaf", e.leaf)
            .put("walkedAtMs", e.walkedAtMs)
            .put("payloadDropped", dropped)
        if (!dropped) {
            val arr = JSONArray()
            e.items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("docId", item.docId)
                        put("name", item.name)
                        item.mime?.let { put("mime", it) }
                        put("sizeBytes", item.sizeBytes)
                        put("lastModifiedSec", item.lastModifiedSec)
                        put("hidden", item.hidden)
                    },
                )
            }
            o.put("items", arr)
        }
        return o
    }

    /** 序列化后的精确字节数估算：前缀 + 各条 + 分隔逗号 + 后缀。 */
    private fun totalBytes(count: Int, lens: List<Long>): Long {
        if (count <= 0) return PREFIX.length.toLong() + SUFFIX.length.toLong() + 1L
        var total = PREFIX.length.toLong() + SUFFIX.length.toLong()
        for (i in 0 until count) total += lens[i]
        total += (count - 1).toLong()
        return total
    }

    private fun utf8Len(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 用 Files.move(ATOMIC_MOVE) 原子替换；文件系统不支持（或 Windows 上目标被占用）时
     * 降级为 REPLACE_EXISTING 普通替换 —— 两条路径都**不会先删旧文件**。
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
            Log.w(TAG, "原子替换失败，降级为普通替换", e)
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val TAG = "ScanWalkIndex"

        /** 落盘格式版本；结构变更时 +1，旧版本读出来按空索引处理。 */
        const val VERSION = 1

        /**
         * 复用有效期：5 分钟。过期一律重走，规避 mtime 精度/时钟回拨/Provider 缓存不一致。
         *
         * 为什么缩到 5 分钟：复用判据是「父列表里子目录的 mtime 未变」，而叶子目录内**同名覆盖写**
         * （改动已有文件的内容/大小）既不改叶子目录 mtime、也不改条目数，父目录侧完全观察不到 ——
         * TTL 就是这条陈旧窗口的长度。旧值 30 分钟意味着「刚替换过的文件」可能带着旧 size/mime
         * 被当成真相用半小时；用户拍板收窄到 5 分钟，只覆盖「一次会话内连续重扫」的典型场景
         * （退出文件夹再进、切 tab、扫描中断后重来），陈旧窗口随之压到 5 分钟以内。
         *
         * 代价可以接受：叶子复检 = 1 次目录 IPC + 逐项 size/mtime 比对（读头/解码已由基线复用挡掉，
         * 不重复付出）；超过 5 分钟的树重走一遍本来就快（文件缓存已热），多付的 IPC 远小于
         * 信息陈旧的代价。
         */
        const val DEFAULT_TTL_MS = 5L * 60 * 1000

        /** 条目数上限：20k 目录足够覆盖常见媒体树，再多也让索引文件失控。 */
        const val MAX_ENTRIES = 20_000

        /** 落盘字节上限（2 MiB），同时也是读取上限：超过即视为不可信。 */
        const val MAX_PAYLOAD_BYTES = 2L * 1024 * 1024

        private const val TMP_SUFFIX = ".tmp"
        private const val READ_CHUNK_BYTES = 8 * 1024

        /** 落盘 JSON 的固定前缀（[encodeEntries] 里 version 在前、entries 在后）。 */
        private const val PREFIX = "{\"version\":1,\"entries\":["

        /** 落盘 JSON 的固定后缀。 */
        private const val SUFFIX = "]}"

        /** 索引 map 的键：treeUri + NUL + docId，避免拼接歧义。 */
        fun keyOf(treeUri: String, docId: String): String = "$treeUri\u0000$docId"
    }
}
