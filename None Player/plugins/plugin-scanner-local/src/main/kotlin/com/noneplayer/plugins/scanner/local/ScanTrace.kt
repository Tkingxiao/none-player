package com.noneplayer.plugins.scanner.local

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * 扫描取证落盘（真机排障专用，不是业务能力）。
 *
 * 背景：真机上 `adb logcat` 的用法/过滤方式一变，关键日志就抓不到；排查「这一轮扫描到底走到哪、
 * 隐藏目录遍历有没有重复入队、慢在哪一段」时不能依赖 logcat 是否可用。本对象把同样的消息
 * append 到 [resolveScanTraceDir] 给出的目录下 `scan_trace.log`，可事后 adb pull。
 *
 * E216：落盘目录从 internal `filesDir` 挪到**应用外部专属目录**
 * （`getExternalFilesDir`，`/sdcard/Android/data/<pkg>/files/`）——internal 目录在
 * release 非 debuggable 包上 adb 拉不出来（E204 教训：取证产物留在设备里等于没留），
 * 外部专属目录 adb shell 可直读，无需 root。外部存储不可用时回落 filesDir。
 *
 * E205⑥：落盘改由**单条后台写线程**异步完成（调用线程只入队）。旧实现每次 [log] 都在
 * 调用线程上 `File.length()+FileOutputStream+close`，且要与扫描工作线程的写盘争同一把锁——
 * `requestCovers`（UI 主线程）经 [CoverPump.request] 走到这里时，主线程会阻塞到别人的
 * 写盘完成，违反 MediaSourceProvider「不阻塞调用线程」的契约。logcat 仍是同步双写，
 * 排障不依赖落盘的即时性；队列有界（满了丢——取证工具绝不能反压业务）。
 *
 * 硬约束（取证工具绝不能把扫描搞挂）：
 * - 写失败一律吞掉（[runCatching]），磁盘满/权限异常都不影响扫描；
 * - 单条 append + 立即关闭，不长期持有 Writer（进程被杀最多丢队列尾部）；
 * - 256KB 封顶，超了把当前文件轮转成 `scan_trace.log.1`（只保留两份，无限长会把用户存储写爆）；
 * - 并发安全：单写线程天然串行化；[install] 带**写屏障**（持写锁换目录并清队列），
 *   上一个目录的滞留行不会写进新目录——Robolectric 多 harness 共用本单例时也不串味。
 *
 * 关掉：把 [enabled] 置 false（一行），或发布包里不调 [install]（消息只进 logcat）。
 */
internal object ScanTrace {

    private const val TAG = "ScanTrace"

    /** 单个文件上限：256KB，一轮扫描 <200 行，够存上千轮 */
    private const val MAX_BYTES = 256L * 1024L

    /** 待写队列上限：满了直接丢（取证不能反压业务） */
    private const val QUEUE_CAPACITY = 4_096

    /** 落盘目录；null = 只进 logcat（未 install 时的默认态） */
    @Volatile
    private var dir: File? = null

    /** 总开关：本轮真机取证用 true，文档记为「可一行关闭」 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 写线程的监视锁：append 一行与 install 换目录互斥。清队列发生在锁内，
     * 保证 install 返回后旧目录不会再出现任何本对象的写入。
     */
    private val writeLock = Any()

    private val pending = LinkedBlockingQueue<String>(QUEUE_CAPACITY)

    @Volatile
    private var writerStarted = false

    /** 指定落盘目录（通常在来源初始化时传 `context.filesDir`）。换目录会清空未写队列。 */
    fun install(dir: File) {
        ensureWriter()
        synchronized(writeLock) {
            this.dir = dir
            pending.clear()
        }
    }

    /**
     * 记一行。logcat 同步、落盘异步；任何一层失败都不抛。
     *
     * @param msg 单行消息（内部会补时间戳与换行，调用方不要自己加）
     */
    fun log(msg: String) {
        if (!enabled) return
        runCatching { Log.i(TAG, msg) }
        if (dir == null) return
        ensureWriter()
        // 满了丢：丢的是最旧的取证行，不是业务正确性
        pending.offer(msg)
    }

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(writeLock) {
            if (writerStarted) return
            writerStarted = true
            Thread({
                while (true) {
                    // 取行与落盘放在**同一个锁段**：install 的「清队列 + 换目录」也是
                    // 一个锁段，两者互斥后不存在「旧行写进新目录」的窗口（阻塞 take
                    // 做不到这点——它会在锁外持有一行）。空转用 20ms 轮询，取证线程
                    // 的唤醒精度无关紧要。
                    val busy = synchronized(writeLock) {
                        val msg = pending.poll() ?: return@synchronized false
                        val target = dir ?: return@synchronized true
                        runCatching {
                            val file = File(target, FILE)
                            if (file.length() > MAX_BYTES) {
                                // 轮转：先删旧的 .1 再改名，保证磁盘上最多两份
                                val rotated = File(target, ROTATED)
                                rotated.delete()
                                file.renameTo(rotated)
                            }
                            FileOutputStream(file, true).use { out ->
                                out.write((timePrefix() + msg + "\n").toByteArray())
                            }
                        }
                        true
                    }
                    if (!busy) Thread.sleep(20L)
                }
            }, "NpScanTrace").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** 时间戳前缀：`HH:mm:ss.SSS `，一轮扫描内相对顺序比绝对日期更重要 */
    private fun timePrefix(): String {
        val now = System.currentTimeMillis()
        val secondsOfDay = (now / 1000L) % 86_400L
        val hh = secondsOfDay / 3600L
        val mm = (secondsOfDay % 3600L) / 60L
        val ss = secondsOfDay % 60L
        val ms = now % 1000L
        return buildString {
            append(hh.toString().padStart(2, '0')).append(':')
            append(mm.toString().padStart(2, '0')).append(':')
            append(ss.toString().padStart(2, '0')).append('.')
            append(ms.toString().padStart(3, '0')).append(' ')
        }
    }
}

/**
 * 取证目录解析（E216）：优先应用**外部专属目录**（release 可 `adb pull`，
 * 无需 root/debuggable），外部存储不可用（挂载中/被用户关掉）回落 internal `filesDir`。
 *
 * 升级迁移：旧版本把 trace 落在 filesDir，internal→external 是**跨卷**，
 * `renameTo` 会静默失败，所以 copy+delete 搬过去（≤两份 × 256KB，一次性）；
 * 任何一步失败都不影响返回值（取证工具绝不抛）。幂等：搬完 internal 不再存在，
 * 二次调用空转。
 */
internal fun resolveScanTraceDir(context: android.content.Context): File {
    val external = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
    runCatching {
        for (name in listOf(FILE, ROTATED)) {
            val internal = File(context.filesDir, name)
            if (internal.isFile) {
                internal.copyTo(File(external, name), overwrite = true)
                internal.delete()
            }
        }
    }
    return external
}

/** ScanTrace 与目录解析共用的文件名（防两处字面量漂移） */
private val FILE = "scan_trace.log"
private val ROTATED = "scan_trace.log.1"
