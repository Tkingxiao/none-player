package com.noneplayer.kernel

import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.subscribe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowEventBusTest {

    @Test
    fun `订阅者收到同类型事件`() = runTest {
        val bus = FlowEventBus()
        val received = mutableListOf<KernelEvent>()

        val job = launch { bus.subscribe<KernelEvent>().take(2).toList(received) }
        advanceUntilIdle() // 等订阅者挂起就位

        bus.publish(KernelEvent.PluginInstalled("ui.library"))
        bus.publish(KernelEvent.MediaScanned("local", 3))
        advanceUntilIdle()

        assertEquals(2, received.size)
        assertEquals(KernelEvent.PluginInstalled("ui.library"), received[0])
        job.cancel()
    }

    @Test
    fun `无关类型被过滤`() = runTest {
        val bus = FlowEventBus()
        data class PrivateEvent(val tag: String)

        val received = mutableListOf<KernelEvent>()
        val job = launch { bus.subscribe<KernelEvent>().take(1).toList(received) }
        advanceUntilIdle()

        bus.publish(PrivateEvent("noise"))
        bus.publish(KernelEvent.PlaybackStarted("m1"))
        advanceUntilIdle()

        assertEquals(listOf(KernelEvent.PlaybackStarted("m1")), received)
        job.cancel()
    }

    @Test
    fun `无订阅者时发布不阻塞不报错`() {
        val bus = FlowEventBus()
        bus.publish(KernelEvent.PluginInstalled("nobody.listening"))
    }
}
