package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A9：在途装载的去重判据必须比较**意图**，而不是只比较条目（uri+id）。
 *
 * 纯决策层单测：钉住"什么情况能 join 旧任务 / 什么情况必须取消重跑"，
 * 以及"何时需要先清掉引擎里按旧参数发起的那份装载"。
 */
class StartIntentTest {

    private fun media(
        id: String = "ep1",
        uri: String = "file:///show/ep1.mkv",
        title: String = id,
        mimeType: String = "video/x-matroska",
    ) = MediaItem(id = id, sourceId = "local", title = title, uri = uri, mimeType = mimeType)

    private fun intent(
        item: MediaItem = media(),
        restorePosition: Boolean = true,
        autoPlay: Boolean = true,
        prepareOnly: Boolean = false,
        audioOnlyMode: Boolean? = null,
    ) = startIntentOf(item, restorePosition, autoPlay, prepareOnly, audioOnlyMode)

    @Test
    fun `same intent joins the pending load instead of restarting`() {
        val previous = intent()
        val requested = intent()
        assertTrue(sameStartIntent(previous, requested))
        assertEquals(
            StartDisposition.JOIN_PENDING,
            startDisposition(previous, previousActive = true, requested = requested),
        )
    }

    @Test
    fun `inactive or absent pending load always starts new`() {
        assertEquals(
            StartDisposition.START_NEW,
            startDisposition(previous = null, previousActive = true, requested = intent()),
        )
        assertEquals(
            StartDisposition.START_NEW,
            startDisposition(previous = intent(), previousActive = false, requested = intent()),
        )
    }

    @Test
    fun `restore position difference supersedes the pending load`() {
        val requested = intent(restorePosition = false) // 从头播
        assertFalse(sameStartIntent(intent(), requested))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = requested),
        )
    }

    @Test
    fun `auto play difference supersedes the pending load`() {
        val requested = intent(autoPlay = false) // 打开但保持暂停
        assertFalse(sameStartIntent(intent(), requested))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = requested),
        )
    }

    @Test
    fun `prepare only difference supersedes the pending load`() {
        val requested = intent(prepareOnly = true) // 只装载，不自动播
        assertFalse(sameStartIntent(intent(), requested))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = requested),
        )
    }

    @Test
    fun `audio only mode difference supersedes the pending load`() {
        val requested = intent(audioOnlyMode = true)
        assertFalse(sameStartIntent(intent(), requested))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = requested),
        )
    }

    @Test
    fun `different uri or id supersedes the pending load`() {
        val otherUri = intent(item = media(uri = "file:///show/ep2.mkv"))
        assertFalse(sameStartIntent(intent(), otherUri))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = otherUri),
        )

        val otherId = intent(item = media(id = "ep2"))
        assertFalse(sameStartIntent(intent(), otherId))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = otherId),
        )
    }

    @Test
    fun `mime type difference supersedes because engine selection changes`() {
        val requested = intent(item = media(mimeType = "audio/flac"))
        assertFalse(sameStartIntent(intent(), requested))
        assertEquals(
            StartDisposition.SUPERSEDE_PENDING,
            startDisposition(intent(), previousActive = true, requested = requested),
        )
    }

    @Test
    fun `display metadata alone does not count as a different intent`() {
        // 不同入口（深链还原 / 选集面板）拼出的标题、封面可能不同，却指向同一次装载
        val previous = intent(item = media(title = "第 1 集"))
        val requested = intent(item = media(title = "Show S01E01"))
        assertTrue(sameStartIntent(previous, requested))
        assertEquals(
            StartDisposition.JOIN_PENDING,
            startDisposition(previous, previousActive = true, requested = requested),
        )
    }

    @Test
    fun `engine reset required when load parameters change after the old load reached the engine`() {
        // 会话队列已指向这条媒体 = 旧任务 session.open() 已执行，引擎里可能正跑着
        // 按旧 startPositionMs / playOnLoad 发起的探测
        assertTrue(
            supersedeNeedsEngineReset(
                previous = intent(restorePosition = true),
                requested = intent(restorePosition = false),
                sessionItem = media(),
            )
        )
        assertTrue(
            supersedeNeedsEngineReset(
                previous = intent(prepareOnly = false),
                requested = intent(prepareOnly = true),
                sessionItem = media(),
            )
        )
    }

    @Test
    fun `engine reset not required for state only differences`() {
        // autoPlay / audioOnlyMode 由同媒体分支当场应用，不需要重新装载
        assertFalse(supersedeNeedsEngineReset(intent(), intent(autoPlay = false), media()))
        assertFalse(supersedeNeedsEngineReset(intent(), intent(audioOnlyMode = true), media()))
    }

    @Test
    fun `engine reset not required before the old load reached the session queue`() {
        // 旧任务还没 open()：取消后它不会再触发装载，新任务从头装载即可
        assertFalse(
            supersedeNeedsEngineReset(intent(), intent(restorePosition = false), sessionItem = null)
        )
        // 会话队列还是另一条媒体：旧任务没把这条交给引擎
        assertFalse(
            supersedeNeedsEngineReset(
                intent(),
                intent(restorePosition = false),
                sessionItem = media(id = "ep2", uri = "file:///show/ep2.mkv"),
            )
        )
    }

    @Test
    fun `engine reset not required when the media itself changes`() {
        assertFalse(
            supersedeNeedsEngineReset(
                intent(),
                intent(item = media(id = "ep2", uri = "file:///show/ep2.mkv"), restorePosition = false),
                sessionItem = media(),
            )
        )
    }
}
