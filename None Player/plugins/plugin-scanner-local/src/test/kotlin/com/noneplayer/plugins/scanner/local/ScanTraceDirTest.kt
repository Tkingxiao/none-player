package com.noneplayer.plugins.scanner.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 取证目录解析（E216）：trace 落**应用外部专属目录**（release 可 adb pull），
 * 旧 internal trace 升级时搬过去（跨卷 copy+delete，renameTo 会静默失败），
 * 外部不可用回落 filesDir。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanTraceDirTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `resolves to external app-specific dir`() {
        val dir = resolveScanTraceDir(context)
        assertTrue(dir.path.contains(context.packageName))
        assertFalse(dir.path == context.filesDir.path)
    }

    @Test
    fun `migrates legacy internal trace files and is idempotent`() {
        val legacy = File(context.filesDir, "scan_trace.log").apply {
            writeText("2026-10-25 旧目录里的证据行\n")
        }
        val legacyRotated = File(context.filesDir, "scan_trace.log.1").apply { writeText("rotated\n") }

        val dir = resolveScanTraceDir(context)

        assertEquals("2026-10-25 旧目录里的证据行\n", File(dir, "scan_trace.log").readText())
        assertEquals("rotated\n", File(dir, "scan_trace.log.1").readText())
        assertFalse("internal 原件应删除（二次调用空转）", legacy.exists())
        assertFalse(legacyRotated.exists())

        // 幂等：再解析一次不抛、不再有可迁移件
        assertEquals(dir.path, resolveScanTraceDir(context).path)
    }

    @Test
    fun `falls back to filesDir when external storage unavailable`() {
        val wrapped = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        assertEquals(context.filesDir.path, resolveScanTraceDir(wrapped).path)
    }
}
