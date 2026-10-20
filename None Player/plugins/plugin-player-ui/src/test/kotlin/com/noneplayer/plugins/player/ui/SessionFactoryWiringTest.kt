package com.noneplayer.plugins.player.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.database.PlaybackHistoryStore
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.VideoPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * 装配回归：会话工厂从 ServiceLocator 取依赖时，必须使用**内核实际注册的键**。
 *
 * 背景（2026-10-20 真机 Release/R8 冒烟）：R2 把 `PlaybackSession.history` 的参数类型
 * 收窄成契约接口 `PlaybackHistoryStore` 后，`services.get()` 的 reified 类型推断随之
 * 变成该接口 → 按 `PlaybackHistoryStore::class` 查表，而内核只注册了
 * `HistoryRepository::class` → 插件 onBind 抛「内核服务未注册」，整次内核引导
 * 落到 `BootState.Failed`（Debug 单测全绿也照样漏过：没有一个用例走 Kernel.boot）。
 *
 * 本用例用**记录型** ServiceLocator 直接断言取用键集合：多取/少取、按接口键取，
 * 都会红。以后会话依赖增删，也要同步更新此清单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class SessionFactoryWiringTest {

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `会话工厂只按内核已注册的服务键取依赖`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = FakeExtensionRegistry()
        val locator = RecordingServiceLocator(
            mapOf(
                Context::class to context,
                VideoPlayerFactory::class to VideoPlayerFactory(registry),
                ExtensionRegistry::class to registry,
                AppSettings::class to AppSettings(context),
                EventBus::class to FakeEventBus(),
                HistoryRepository::class to HistoryRepository(context),
            ),
        )

        val session = sessionFactoryOf(locator).newSession()
        try {
            assertEquals(
                "会话依赖必须全部命中内核已注册的服务键（Kernel.registerKernelServices）",
                setOf(
                    Context::class,
                    VideoPlayerFactory::class,
                    ExtensionRegistry::class,
                    AppSettings::class,
                    EventBus::class,
                    HistoryRepository::class,
                ),
                locator.requested.toSet(),
            )
            assertFalse(
                "PlaybackHistoryStore 是会话契约接口而非内核服务键；按接口键查找会导致真机引导失败",
                locator.requested.contains(PlaybackHistoryStore::class),
            )
        } finally {
            session.stop()
        }
    }
}

/** 记录每次 get 的 KClass；未配置的服务直接抛，避免返回错类型掩盖装配问题。 */
private class RecordingServiceLocator(
    private val services: Map<KClass<*>, Any>,
) : ServiceLocator {

    val requested: MutableList<KClass<*>> = mutableListOf()

    override fun <T : Any> register(clazz: KClass<T>, service: T) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: KClass<T>): T {
        requested += clazz
        return services[clazz] as? T
            ?: throw IllegalArgumentException("测试定位器未配置服务: ${clazz.qualifiedName}")
    }
}
