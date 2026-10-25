package com.noneplayer.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E230：最近搜索单条删除的语义（纯函数口径，DataStore 写侧同源）。
 * 存储是 `\n` 连接的单键——顺序保留与「删空即整键移除」由调用侧按
 * 返回值处理，这里钉住列表语义本身。
 */
class SearchHistoryRemoveTest {

    @Test
    fun `removes exact term and preserves order`() {
        // 含数字/符号的词条照常命中；前后条目与相对顺序原样保留
        assertEquals(
            listOf("a", "b", "c"),
            searchHistoryAfterRemove(listOf("a", "b", "c", "1080p"), "1080p"),
        )
        // 主场景：命中中间一条
        assertEquals(
            listOf("第一", "第三"),
            searchHistoryAfterRemove(listOf("第一", "第二", "第三"), "第二"),
        )
    }

    @Test
    fun `missing term or blank target means no change`() {
        // addSearchTerm 的大小写去重保证变体至多一条：精确匹配不到（含大小写
        // 不同的词条）就是没有变化，不写盘
        assertNull(searchHistoryAfterRemove(listOf("Nanatsu"), "nanatsu"))
        assertNull(searchHistoryAfterRemove(listOf("a"), "b"))
        assertNull(searchHistoryAfterRemove(listOf("a"), ""))
        assertNull(searchHistoryAfterRemove(listOf("a"), "   "))
        // 前后空白的目标与 addSearchTerm 的 trim 口径一致
        assertEquals(emptyList<String>(), searchHistoryAfterRemove(listOf("a"), " a "))
    }

    @Test
    fun `removing the last term yields empty not null`() {
        // 删空是**有效变化**：返回空表（调用侧据此整键移除），与「无变化」的 null 区分
        assertEquals(emptyList<String>(), searchHistoryAfterRemove(listOf("only"), "only"))
    }
}
