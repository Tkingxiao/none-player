package com.noneplayer.plugins.player.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.PlayerEngineProvider
import com.noneplayer.kernel.api.VideoPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * 最小 EventBus 替身：只记录发布的事件，不提供订阅流。
 */
internal class FakeEventBus : EventBus {
    val published: MutableList<Any> = mutableListOf()

    override fun publish(event: Any) {
        published += event
    }

    override fun <T : Any> subscribe(clazz: KClass<T>): Flow<T> = flowOf()
}

/**
 * 测试条目构造：sourceId 固定 `local`（与 `mediaMemoryKey` 的默认前缀一致），
 * 便于用 `HistoryEntry.keyOf(item)` 直接断言历史键。
 */
internal fun testItem(
    id: String,
    uri: String = "content://media/$id",
    mimeType: String = "video/mp4",
    durationMs: Long = 60_000L,
    clipStartUs: Long = 0L,
    clipEndUs: Long = 0L,
): MediaItem = MediaItem(
    id = id,
    sourceId = "local",
    title = id,
    uri = uri,
    mimeType = mimeType,
    durationMs = durationMs,
    clipStartUs = clipStartUs,
    clipEndUs = clipEndUs,
)

/**
 * 复位 `AppSettings` 的 DataStore 单例（照搬 plugin-scanner-local 的
 * `ScanTestSupport.DataStoreTestReset`）。
 *
 * `preferencesDataStore` 的委托把 DataStore 实例缓存在**静态字段**里（首次访问时用
 * 当时的 `applicationContext.filesDir` 决定文件路径）。Robolectric 每个测试方法都会
 * 换一个全新的临时目录，但静态单例还指着上一个已删除的目录——第二个用例开始的写入
 * 就会 FileNotFoundException。这里在 `@Before` 把缓存的 DataStore 清掉。
 *
 * 注意：**不能** cancel 委托的 `scope` 字段——cancel 它会让后续所有 DataStore 写入
 * 立刻抛 JobCancellationException。只清 INSTANCE 即可。
 */
internal object DataStoreTestReset {
    fun reset() {
        // E211 起 AppSettings 有两份 DataStore（主设置 + SAF 目录）；两份都要清掉，
        // 否则后一份会继续指向上一个 Robolectric 测试已删除的临时目录。
        listOf("dataStore\$delegate", "safFoldersStore\$delegate").forEach { fieldName ->
            runCatching {
                val fileClass = Class.forName("com.noneplayer.core.data.AppSettingsKt")
                val delegateField = fileClass.getDeclaredField(fieldName)
                delegateField.isAccessible = true
                val delegate = delegateField.get(null) ?: return@forEach
                val instanceField = delegate.javaClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                instanceField.set(delegate, null)
            }
        }
    }
}

/**
 * R1 会话级回归的公共夹具：Robolectric（SDK 34）+ 真 `Context` / `AppSettings` /
 * `HistoryRepository`（Room 在本地 JVM 可用）+ 假引擎 + 真 [VideoPlayerFactory]。
 *
 * 主线程用 [UnconfinedTestDispatcher]：`PlaybackSession` 内部 `scope` 绑
 * `Dispatchers.Main`，Unconfined 让 init 与 open/stop 里的协程在调用点同步跑到第一个
 * 挂起点，测试不必驱动 Robolectric 主 Looper；真实 IO（Room 写入、DataStore 读盘）仍在
 * `Dispatchers.IO` 上异步完成，用 [awaitCondition] 轮询等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal abstract class SessionRobolectricTest {

    protected lateinit var context: Context
    protected lateinit var settings: AppSettings
    protected lateinit var history: HistoryRepository
    protected lateinit var events: FakeEventBus
    protected lateinit var registry: FakeExtensionRegistry
    protected lateinit var engineLog: MutableList<String>
    protected lateinit var exoProvider: FakePlayerEngineProvider
    protected lateinit var ffmpegProvider: FakePlayerEngineProvider
    protected lateinit var factory: VideoPlayerFactory
    protected lateinit var session: PlaybackSession

    @Before
    fun setUpSessionHarness() {
        context = ApplicationProvider.getApplicationContext()
        DataStoreTestReset.reset()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = AppSettings(context)
        history = HistoryRepository(context)
        events = FakeEventBus()
        registry = FakeExtensionRegistry()
        engineLog = mutableListOf()
        // exo 同时支持 mp4/mkv（同引擎不同容器不换代）；ffmpeg 只接 avi（换代场景）。
        exoProvider = FakePlayerEngineProvider(
            engineId = "exo",
            priority = 100,
            mimeTypes = setOf("video/mp4", "video/x-matroska"),
            lifecycleLog = engineLog,
        )
        ffmpegProvider = FakePlayerEngineProvider(
            engineId = "ffmpeg",
            priority = 10,
            mimeTypes = setOf("video/avi"),
            lifecycleLog = engineLog,
        )
        registry.register(PlayerEngineProvider::class.java, exoProvider)
        registry.register(PlayerEngineProvider::class.java, ffmpegProvider)
        factory = VideoPlayerFactory(registry)
        session = PlaybackSession(context, factory, registry, settings, events, history)
    }

    @After
    fun tearDownSessionHarness() {
        // 停掉会话持有的循环协程，避免跨用例串扰（PlaybackSession 没有 close API）。
        runCatching { session.stop() }
        Dispatchers.resetMain()
    }

    /** 当前会话引擎（必须是假引擎；真引擎不该出现在这些用例里） */
    protected val fakePlayer: FakeVideoPlayer
        get() {
            val player = session.player
            if (player !is FakeVideoPlayer) {
                fail("会话没有创建假引擎（engineError=${session.engineError.value}）")
            }
            return player as FakeVideoPlayer
        }

    /** 轮询等待异步条件成立；超时则静默返回，让随后的断言给出具体失败信息。 */
    protected fun awaitCondition(timeoutMs: Long = 3_000, condition: suspend () -> Boolean) {
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(10)
            }
        }
    }

    /** 让真实 IO 上在途的写入/读盘再落一会儿（断言「不得写入」前用）。 */
    protected fun settleIo() {
        runBlocking { delay(150) }
    }

    protected fun positionOf(item: MediaItem): Long? =
        runBlocking { history.positionOf(HistoryEntry.keyOf(item)) }

    /** 等待某项断点落盘到 [expected]；超时则返回当前值（由断言给出失败信息）。 */
    protected fun awaitPosition(item: MediaItem, expected: Long, timeoutMs: Long = 3_000): Long? {
        awaitCondition(timeoutMs) { history.positionOf(HistoryEntry.keyOf(item)) == expected }
        return positionOf(item)
    }

    /**
     * 断言某项**没有断点进度**。
     *
     * 注意 `open()` 里的 `markPlayed` 会为进入的条目建一行 `positionMs = 0` 的
     * play_count 记录（"播过但没进度"也要出现在历史里），所以"无记录"在断点语义上
     * 是"没有非 0 进度"，不是"库里没有这一行"。
     */
    protected fun assertNoBreakpoint(item: MediaItem, message: String) {
        val position = positionOf(item)
        org.junit.Assert.assertEquals(
            "$message（实际 positionMs=${position ?: "null"}，键=${HistoryEntry.keyOf(item)}）",
            0L,
            position ?: 0L,
        )
    }
}
