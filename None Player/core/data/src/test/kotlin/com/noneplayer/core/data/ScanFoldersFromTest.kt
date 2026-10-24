package com.noneplayer.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** E211：SAF 扫描目录的读取优先级（独立文件 > 旧键 > 空）。 */
class ScanFoldersFromTest {

    @Test
    fun `independent store wins over legacy keys`() {
        assertEquals(
            listOf("content://tree/new"),
            scanFoldersFrom(
                safValue = "content://tree/new",
                legacyValue = "content://tree/old",
                legacySingle = "content://tree/older",
            ),
        )
    }

    @Test
    fun `legacy keys are read until the one-time migration runs`() {
        assertEquals(
            listOf("content://tree/a", "content://tree/b"),
            scanFoldersFrom(
                safValue = null,
                legacyValue = "content://tree/a\ncontent://tree/b",
                legacySingle = "content://tree/ignored",
            ),
        )
        assertEquals(
            listOf("content://tree/single"),
            scanFoldersFrom(null, null, "content://tree/single"),
        )
    }

    @Test
    fun `restored install without saf store falls back to empty when legacy was removed`() {
        assertEquals(emptyList<String>(), scanFoldersFrom(null, null, null))
        assertEquals(emptyList<String>(), scanFoldersFrom("", null, null))
    }
}
