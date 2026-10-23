package com.noneplayer.core.media.datasource

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 本地 file/content 的可随机访问 DataSource（A1 收口）。
 *
 * media3 的 [androidx.media3.exoplayer.source.ProgressiveMediaPeriod] 在解封装器返回
 * `RESULT_SEEK` 时会 `close()` 后按新位置重新 `open()` 同一个实例——`DataSource` 接口面上
 * 没有 seek。`DefaultDataSource` 的每次 open 对 content:// 都是一次
 * `openAssetFileDescriptor`（binder + dup fd）：无 idx1 的 AVI 扫描每块一次 SEEK，
 * 两小时正片十几万块就是十几万次 binder 往返，真机首帧前的"建索引"要数分钟。
 *
 * 本类在 `close()` 时**保留**底层 FileChannel/文件描述符，下一次 `open()` 只做
 * `channel.position()`——SEEK 降为微秒级本地调用。fd 的最终回收交给空闲回收器：
 * `close()` 后 [IDLE_CLOSE_MS] 内没有新的 `open()` 才真正关闭（AVI 扫描期间
 * close→open 相隔毫秒级，fd 全程存活；正常停止/切源后 fd 在数秒内释放）。
 *
 * 非 file/content、或 provider 给出不可定位的 fd（管道型 provider）时，转发给
 * [DefaultDataSource] 走原路径；本类不改变任何远程/流式行为。transfer 事件只在
 * 本地通路上发（委托路径由 DefaultDataSource 自己发，避免 initializing/ended 失配）。
 */
@OptIn(UnstableApi::class)
class SeekableLocalDataSource(context: Context) : BaseDataSource(false) {

    private val appContext = context.applicationContext

    /** open/close/回收共用一把锁：空闲回收线程与装载线程都碰 channel */
    private val lock = Any()

    /** 委托者仅在非本地/不可定位时惰性创建，生命周期与本实例一致 */
    private var fallback: DataSource? = null

    private var channel: FileChannel? = null

    /** 持有 channel 的底层资源（RandomAccessFile / PFD 流），关它连带关 channel */
    private var channelOwner: AutoCloseable? = null

    private var openedUri: Uri? = null
    private var open = false

    /** 当前读位置（channel.position 的缓存，省一次系统调用） */
    private var position = 0L

    /** open 时 DataSpec 声明的终点；LENGTH_UNSET 表示读到 EOF */
    private var endPosition = C.LENGTH_UNSET.toLong()

    private var idleClose: ScheduledFuture<*>? = null

    override fun getUri(): Uri? = synchronized(lock) { openedUri ?: fallback?.uri }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme?.lowercase()
        if (scheme == "file" || scheme == "content") {
            synchronized(lock) {
                ensureChannelLocked(uri, scheme)
                val ch = channel ?: return@open fallback().open(dataSpec).also { open = true }
                // transferInitializing 只在本地通路上发：委托路径由 DefaultDataSource 自己发
                transferInitializing(dataSpec)
                position = dataSpec.position
                endPosition =
                    if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                        C.LENGTH_UNSET.toLong()
                    } else {
                        dataSpec.position + dataSpec.length
                    }
                runCatching { ch.position(position) }
                    .onFailure { throw IOException("seek to ${dataSpec.position} failed", it) }
                open = true
                transferStarted(dataSpec)
                val resolved = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    runCatching { ch.size() }.getOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { (it - dataSpec.position).coerceAtLeast(0) }
                        ?: C.LENGTH_UNSET.toLong()
                } else {
                    dataSpec.length
                }
                return resolved
            }
        }
        return fallback().open(dataSpec).also { open = true }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ch = synchronized(lock) { if (open) channel else null }
        if (ch != null) {
            if (length == 0) return 0
            if (endPosition != C.LENGTH_UNSET.toLong() && position >= endPosition) {
                return C.RESULT_END_OF_INPUT
            }
            val n = try {
                ch.read(ByteBuffer.wrap(buffer, offset, length))
            } catch (_: ClosedChannelException) {
                throw EOFException("channel closed at $position")
            }
            if (n < 0) return C.RESULT_END_OF_INPUT
            position += n
            bytesTransferred(n)
            return n
        }
        return fallback().read(buffer, offset, length)
    }

    override fun close() {
        val delegate = synchronized(lock) { fallback }
        if (delegate != null) {
            delegate.close()
            synchronized(lock) { open = false }
            return
        }
        synchronized(lock) {
            if (open) transferEnded()
            open = false
            // fd 不在这里关：下一次 open 可能马上就来（AVI 扫描每块一次 close→open）。
            // 空闲 IDLE_CLOSE_MS 没有新 open 才真正释放。
            idleClose?.cancel(false)
        }
        idleClose = IDLE_EXECUTOR.schedule({
            synchronized(lock) {
                if (!open) {
                    runCatching { channel?.close() }
                    runCatching { channelOwner?.close() }
                    channel = null
                    channelOwner = null
                    openedUri = null
                }
            }
        }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
    }

    /** channel 就绪（同 URI 复用）或重新打开；打开失败抛 IOException 由上层判瞬态 */
    private fun ensureChannelLocked(uri: Uri, scheme: String) {
        if (channel != null && openedUri == uri) return
        // URI 换了（同实例被复用给别的媒体）：旧 fd 先释放
        runCatching { channel?.close() }
        runCatching { channelOwner?.close() }
        channel = null
        channelOwner = null
        openedUri = null
        val pair = when (scheme) {
            "file" -> uri.path?.let { path ->
                val raf = java.io.RandomAccessFile(path, "r")
                raf.channel to (raf as AutoCloseable)
            }
            "content" -> appContext.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                stream.channel to (stream as AutoCloseable)
            }
            else -> null
        } ?: return
        // 管道型 provider（云盘/即时生成）不可定位：连 owner 一起退回，走 DefaultDataSource
        val seekable = runCatching { pair.first.size() }.isSuccess
        if (!seekable) {
            runCatching { pair.first.close() }
            runCatching { pair.second.close() }
            return
        }
        channel = pair.first
        channelOwner = pair.second
        openedUri = uri
    }

    /** 交出委托者；本地 channel 若已打开须释放（read/open 不再走本地路径） */
    private fun fallback(): DataSource {
        var delegate = synchronized(lock) { fallback }
        if (delegate == null) {
            delegate = DefaultDataSource.Factory(appContext).createDataSource()
            synchronized(lock) { fallback = delegate }
        }
        synchronized(lock) {
            runCatching { channel?.close() }
            runCatching { channelOwner?.close() }
            channel = null
            channelOwner = null
            openedUri = null
        }
        return delegate
    }

    private companion object {
        /** close 后 fd 的空闲保留窗口：覆盖 AVI 逐块扫描的 close→open 间隔，超时即回收 */
        const val IDLE_CLOSE_MS = 10_000L

        val IDLE_EXECUTOR: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "NpSeekableDsIdle").apply { isDaemon = true }
            }
    }
}

/** 工厂：与 [DefaultDataSource.Factory] 可互换；本地文件/媒体库 URI 走随机访问通道。 */
@OptIn(UnstableApi::class)
class SeekableLocalDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = SeekableLocalDataSource(context)
}
