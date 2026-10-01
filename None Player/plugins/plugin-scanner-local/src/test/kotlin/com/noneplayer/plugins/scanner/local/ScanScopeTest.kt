package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E24：SAF tree docId → [ScanScope] 的纯解析逻辑。
 *
 * 核心断言：非主存储必须解析为 [ScanScope.Volume]（按卷过滤），
 * 绝不出现"解析失败即全量扫描"的旧行为。
 */
class ScanScopeTest {

    @Test
    fun `主存储路径解析为 Primary`() {
        assertEquals(ScanScope.Primary("DCIM/Camera"), parseScanScope("primary:DCIM/Camera"))
    }

    @Test
    fun `主存储根目录解析为 Primary 且相对路径为空`() {
        assertEquals(ScanScope.Primary(null), parseScanScope("primary:"))
    }

    @Test
    fun `SD卡路径解析为 Volume 含卷名与相对路径`() {
        assertEquals(
            ScanScope.Volume("1234-5678", "Movies/Anime"),
            parseScanScope("1234-5678:Movies/Anime"),
        )
    }

    @Test
    fun `SD卡根目录解析为 Volume 且相对路径为空`() {
        assertEquals(ScanScope.Volume("1234-5678", null), parseScanScope("1234-5678:"))
    }

    @Test
    fun `前后斜杠被规范化`() {
        assertEquals(ScanScope.Primary("Movies"), parseScanScope("primary:/Movies/"))
        assertEquals(ScanScope.Volume("ABCD-EF01", "Music"), parseScanScope("ABCD-EF01:/Music//"))
    }

    @Test
    fun `无法解析的 docId 一律返回 Unresolvable`() {
        assertEquals(ScanScope.Unresolvable, parseScanScope("no-colon"))
        assertEquals(ScanScope.Unresolvable, parseScanScope(":Movies"))
        assertEquals(ScanScope.Unresolvable, parseScanScope(""))
    }
}
