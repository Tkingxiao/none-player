package com.noneplayer.plugins.player.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E144：外挂字幕轨的"该不该抢一次选中"。
 *
 * 背景：外挂轨与内嵌轨都带 `SELECTION_FLAG_DEFAULT`，media3 平票时按轨道顺序选，
 * 容器里先出现的内嵌轨赢——自动挂上的外挂字幕于是只在菜单里留一行"已挂载"，
 * 屏上照旧出内嵌文案。修法是在"刚挂上/刚切到带外挂字幕的条目"那一拍强制选中
 * 外挂轨一次；用户之后手选内嵌轨时（selectTrack 清标记）不再干预。
 */
class ExternalSubtitleSelectionTest {

    @Test
    fun `pending mount with an available external track forces the selection`() {
        assertTrue(
            needsExternalSubtitleSelection(
                pending = true,
                textTracksEnabled = true,
                externalTrackPresent = true,
                externalTrackSelected = false,
            ),
        )
    }

    @Test
    fun `nothing happens without a pending mount`() {
        // 用户手选过内嵌轨之后标记已清：后续轨道回调不许把选择抢回来
        assertFalse(
            needsExternalSubtitleSelection(
                pending = false,
                textTracksEnabled = true,
                externalTrackPresent = true,
                externalTrackSelected = false,
            ),
        )
    }

    @Test
    fun `text tracks disabled stays disabled`() {
        assertFalse(
            needsExternalSubtitleSelection(
                pending = true,
                textTracksEnabled = false,
                externalTrackPresent = true,
                externalTrackSelected = false,
            ),
        )
    }

    @Test
    fun `no external track group means nothing to select`() {
        // 纯内嵌字幕（或外挂尚未进入轨道表）：保持 media3 自己的选择
        assertFalse(
            needsExternalSubtitleSelection(
                pending = true,
                textTracksEnabled = true,
                externalTrackPresent = false,
                externalTrackSelected = false,
            ),
        )
    }

    @Test
    fun `already selected needs no override`() {
        // 1080p 无内嵌轨的宿主：外挂轨本来就是唯一选择，不再重复写参数
        assertFalse(
            needsExternalSubtitleSelection(
                pending = true,
                textTracksEnabled = true,
                externalTrackPresent = true,
                externalTrackSelected = true,
            ),
        )
    }

    @Test
    fun `external track id is stable and distinct from embedded ids`() {
        assertEquals("np.external.subtitle", EXTERNAL_SUBTITLE_TRACK_ID)
        // 内嵌轨道没有稳定 id（media3 会给语言/序号），不可能撞上这个键
        assertFalse(EXTERNAL_SUBTITLE_TRACK_ID.startsWith("TEXT_"))
    }
}
