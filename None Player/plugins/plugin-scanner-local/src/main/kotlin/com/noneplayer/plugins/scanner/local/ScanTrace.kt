package com.noneplayer.plugins.scanner.local

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 扫描取证落盘（真机排障专用，不是业务能力）。
 *
 * 背景：真机上 `adb logcat` 的用法/过滤方式一变，关键日志就抓不到；排查「这一轮扫描到底走到哪、
 * 隐藏目录遍历有没有重复入队、慢在哪一段」时不能依赖 logcat 是否可用。本对象把同样的消息**同步
 * append** 到 `filesDir/scan_trace.log`，随包带走、可事后 adb pull。
 *
 * 硬约束（取证工具绝不能把扫描搞挂）：
 * - 写失败一律吞掉（[runCatching]），磁盘满/权限异常都不影响扫描；
 * - 单次 append + 立即关闭，不长期持有 Writer（进程被杀最多丢最后一行）；
 * - 256KB 封顶，超了把当前文件轮转成 `scan_trace.log.1`（只保留两份，无限长会把用户存储写爆）；
 * - 并发安全：内部 [lock] 串行化，walk 的多条车道可以随便调用。
 *
 * 关掉：把 [enabled] 置 false（一行），或发布包里不调 [install]（消息只进 logcat）。
 */
internal object ScanTrace {

    private const val TAG = "ScanTrace"

    /** 落盘文件名（[install] 的目录下） */
    private const val FILE_NAME = "scan_trace.log"

    /** 轮转后的文件名（只保留一份旧的） */
    private const val ROTATED_NAME = "scan_trace.log.1"

    /** 单个文件上限：256KB，一轮扫描 <200 行，够存上千轮 */
    private const val MAX_BYTES = 256L * 1024L

    private val lock = Any()

    /** 落盘目录；null = 只进 logcat（未 install 时的默认态） */
    @Volatile
    private var dir: File? = null

    /** 总开关：本轮真机取证用 true，文档记为「可一行关闭」 */
    @Volatile
    var enabled: Boolean = true

    /** 指定落盘目录（通常在来源初始化时传 `context.filesDir`） */
    fun install(dir: File) {
        synchronized(lock) { this.dir = dir }
    }

    /**
     * 记一行。同时进 logcat 与落盘文件；任何一层失败都不抛。
     *
     * @param msg 单行消息（内部会补时间戳与换行，调用方不要自己加）
     */
    fun log(msg: String) {
        if (!enabled) return
        runCatching { Log.i(TAG, msg) }
        val target = dir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(target, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    // 轮转：先删旧的 .1 再改名，保证磁盘上最多两份
                    val rotated = File(target, ROTATED_NAME)
                    rotated.delete()
                    file.renameTo(rotated)
                }
                FileOutputStream(file, true).use { out ->
                    out.write((timePrefix() + msg + "\n").toByteArray())
                }
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
