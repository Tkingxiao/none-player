package com.noneplayer.kernel

import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.api.register
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RebuildService(val generation: Int)

private interface RebuildPoint : ExtensionPoint

private class StoppedExtension : RebuildPoint

private class RebootedExtension : RebuildPoint

/**
 * REV-16：`Kernel.shutdown()` 的“重建一套新服务/扩展”契约。
 *
 * Kernel 本身带 Android 依赖，JVM 测试不能直接 boot；这里钉住它在插件 onStop 后
 * 必须清空的两个容器：服务清空后同类型可再注册（否则 boot 撞重复注册），扩展清空后
 * 旧实例消失、新实例可查（否则重引导会同时拿到停机与在役的两份）。
 */
class KernelRebuildContractTest {

    @Test
    fun `清空服务后同类型可重新注册，重复注册仍然拒绝`() {
        val locator = DefaultServiceLocator()
        locator.register(RebuildService(1))
        assertThrows(IllegalStateException::class.java) {
            locator.register(RebuildService(2))
        }

        locator.clear()
        assertThrows(IllegalArgumentException::class.java) {
            locator.get<RebuildService>()
        }

        locator.register(RebuildService(2))
        assertEquals(2, locator.get<RebuildService>().generation)
    }

    @Test
    fun `清空扩展后旧实例不再可查，新实例正常注册`() {
        val registry = DefaultExtensionRegistry()
        registry.register(RebuildPoint::class.java, StoppedExtension())
        assertEquals(1, registry.query<RebuildPoint>().size)

        registry.clear()
        assertTrue(registry.query<RebuildPoint>().isEmpty())

        val fresh = RebootedExtension()
        registry.register(RebuildPoint::class.java, fresh)
        assertEquals(listOf(fresh), registry.query<RebuildPoint>())
    }
}
