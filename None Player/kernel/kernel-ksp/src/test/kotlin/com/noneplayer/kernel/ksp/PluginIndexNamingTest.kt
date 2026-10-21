package com.noneplayer.kernel.ksp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexNamingTest {

    @Test
    fun `跨模块同名插件生成不同的索引类名`() {
        val alpha = generatedIndexName("com.alpha.LibraryPlugin")
        val beta = generatedIndexName("com.beta.LibraryPlugin")

        assertNotEquals(alpha, beta)
        assertTrue(alpha.startsWith("LibraryPluginIndex_"))
        assertTrue(beta.startsWith("LibraryPluginIndex_"))
        assertTrue(alpha.endsWith("com_alpha"))
        assertTrue(beta.endsWith("com_beta"))
    }

    @Test
    fun `现有插件的索引名稳定`() {
        assertEquals(
            "LibraryPluginIndex_com_noneplayer_plugins_library",
            generatedIndexName("com.noneplayer.plugins.library.LibraryPlugin"),
        )
        assertEquals(
            "LocalScannerPluginIndex_com_noneplayer_plugins_scanner_local",
            generatedIndexName("com.noneplayer.plugins.scanner.local.LocalScannerPlugin"),
        )
        assertEquals(
            generatedIndexName("com.noneplayer.plugins.library.LibraryPlugin"),
            generatedIndexName("com.noneplayer.plugins.library.LibraryPlugin"),
        )
    }

    @Test
    fun `默认包与反引号标识符也生成合法类名`() {
        val root = generatedIndexName("LibraryPlugin")
        assertTrue(root.endsWith("_root"))

        val weird = generatedIndexName("some.pkg.Weird-Plugin")
        assertTrue(weird.all { it.isLetterOrDigit() || it == '_' })
        assertTrue(weird.startsWith("Weird_PluginIndex_"))
    }
}
