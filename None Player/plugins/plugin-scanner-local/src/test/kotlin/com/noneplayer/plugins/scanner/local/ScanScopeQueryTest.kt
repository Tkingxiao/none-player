package com.noneplayer.plugins.scanner.local

import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REV-02 验收：扫描目录前缀与卷边界。
 *
 * 三层断言：
 * 1. 表驱动：主卷/第二卷 × 根、Movies、Movies/sub、MoviesBackup、含 `%`/`_`/`\` 的目录名
 *    → selection 与 selectionArgs 的**字面**内容（转义、分隔符、卷限定、参数顺序）。
 * 2. SQLite 真实执行：建 `media` 表插入主卷/第二卷各一套覆盖行，用 rawQuery 执行
 *    spec 产出的 selection + args，断言命中集合**恰好**是所选卷的所选子树——
 *    `Movies/` 不命中 `MoviesBackup/`、`Movies_extra/`、`MoviesXextra/`，`Movies/sub/`
 *    只命中 sub，第二卷与主卷同名目录互不串卷。
 * 3. 旧 SDK（28）：不引用 VOLUME_NAME / RELATIVE_PATH（旧 Provider 没有这两列），
 *    用完整存储根路径的 DATA 前缀，且同样只命中对应卷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanScopeQueryTest {

    private val relativePathColumn = "relative_path"
    private val dataColumn = "_data"
    private val volumeNameColumn = "volume_name"
    private val primaryVolumeName = PRIMARY_VOLUME
    private val legacyPrimaryRoot = LEGACY_PRIMARY_ROOT

    // ---------------------------------------------------------------------
    // 1. Q+（API 29+）：卷等值 + 带分隔符的目录前缀
    // ---------------------------------------------------------------------

    @Test
    fun `Q 及以上按卷与目录分隔符限定子树`() {
        val subtree = "volume_name = ? AND relative_path LIKE ? ESCAPE '\\'"
        val volumeOnly = "volume_name = ?"
        val cases = listOf(
            QueryCase("主卷根", ScanScope.Primary(null), ScopeQuerySpec(volumeOnly, listOf(PRIMARY_VOLUME))),
            QueryCase("主卷空串规范化", ScanScope.Primary(""), ScopeQuerySpec(volumeOnly, listOf(PRIMARY_VOLUME))),
            QueryCase("主卷纯斜杠规范化", ScanScope.Primary("///"), ScopeQuerySpec(volumeOnly, listOf(PRIMARY_VOLUME))),
            QueryCase(
                "主卷 Movies",
                ScanScope.Primary("Movies"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies/%")),
            ),
            QueryCase(
                "主卷 /Movies/ 规范化",
                ScanScope.Primary("/Movies/"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies/%")),
            ),
            QueryCase(
                "主卷 Movies/sub 子目录",
                ScanScope.Primary("Movies/sub/"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies/sub/%")),
            ),
            QueryCase(
                "主卷 MoviesBackup 不越界",
                ScanScope.Primary("MoviesBackup"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "MoviesBackup/%")),
            ),
            QueryCase(
                "主卷目录含百分号",
                ScanScope.Primary("Movies%wild"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies\\%wild/%")),
            ),
            QueryCase(
                "主卷目录含下划线",
                ScanScope.Primary("Movies_extra"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies\\_extra/%")),
            ),
            QueryCase(
                "主卷目录含反斜杠",
                ScanScope.Primary("Movies\\Backup"),
                ScopeQuerySpec(subtree, listOf(PRIMARY_VOLUME, "Movies\\\\Backup/%")),
            ),
            QueryCase(
                "第二卷根",
                ScanScope.Volume(SECONDARY_VOLUME, null),
                ScopeQuerySpec(volumeOnly, listOf(SECONDARY_VOLUME)),
            ),
            QueryCase(
                "第二卷 Movies",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies"),
                ScopeQuerySpec(subtree, listOf(SECONDARY_VOLUME, "Movies/%")),
            ),
            QueryCase(
                "第二卷 Movies/sub 子目录",
                ScanScope.Volume(SECONDARY_VOLUME, "/Movies/sub/"),
                ScopeQuerySpec(subtree, listOf(SECONDARY_VOLUME, "Movies/sub/%")),
            ),
            QueryCase(
                "第二卷 MoviesBackup 不越界",
                ScanScope.Volume(SECONDARY_VOLUME, "MoviesBackup"),
                ScopeQuerySpec(subtree, listOf(SECONDARY_VOLUME, "MoviesBackup/%")),
            ),
            QueryCase(
                "第二卷目录含百分号",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies%wild"),
                ScopeQuerySpec(subtree, listOf(SECONDARY_VOLUME, "Movies\\%wild/%")),
            ),
            QueryCase(
                "第二卷目录含下划线",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies_extra"),
                ScopeQuerySpec(subtree, listOf(SECONDARY_VOLUME, "Movies\\_extra/%")),
            ),
        )
        assertCases(cases)
    }

    // ---------------------------------------------------------------------
    // 2. pre-Q（API < 29）：完整存储根路径的 DATA 前缀，不引用新列
    // ---------------------------------------------------------------------

    @Test
    fun `pre-Q 用完整存储根路径的 DATA 前缀`() {
        val selection = "_data LIKE ? ESCAPE '\\'"
        val root = PRIMARY_ROOT
        val volumeRoot = SECONDARY_ROOT
        val cases = listOf(
            QueryCase("主卷根", ScanScope.Primary(null), ScopeQuerySpec(selection, listOf("$root/%"))),
            QueryCase("主卷 Movies", ScanScope.Primary("Movies"), ScopeQuerySpec(selection, listOf("$root/Movies/%"))),
            QueryCase(
                "主卷 /Movies/sub/ 规范化",
                ScanScope.Primary("/Movies/sub/"),
                ScopeQuerySpec(selection, listOf("$root/Movies/sub/%")),
            ),
            QueryCase(
                "主卷 MoviesBackup 不越界",
                ScanScope.Primary("MoviesBackup"),
                ScopeQuerySpec(selection, listOf("$root/MoviesBackup/%")),
            ),
            QueryCase(
                "主卷目录含百分号",
                ScanScope.Primary("Movies%wild"),
                ScopeQuerySpec(selection, listOf("$root/Movies\\%wild/%")),
            ),
            QueryCase(
                "主卷目录含下划线",
                ScanScope.Primary("Movies_extra"),
                ScopeQuerySpec(selection, listOf("$root/Movies\\_extra/%")),
            ),
            QueryCase(
                "第二卷根",
                ScanScope.Volume(SECONDARY_VOLUME, null),
                ScopeQuerySpec(selection, listOf("$volumeRoot/%")),
            ),
            QueryCase(
                "第二卷 Movies",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies"),
                ScopeQuerySpec(selection, listOf("$volumeRoot/Movies/%")),
            ),
            QueryCase(
                "第二卷 Movies/sub 子目录",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies/sub"),
                ScopeQuerySpec(selection, listOf("$volumeRoot/Movies/sub/%")),
            ),
            QueryCase(
                "第二卷目录含百分号",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies%wild"),
                ScopeQuerySpec(selection, listOf("$volumeRoot/Movies\\%wild/%")),
            ),
            QueryCase(
                "第二卷目录含下划线",
                ScanScope.Volume(SECONDARY_VOLUME, "Movies_extra"),
                ScopeQuerySpec(selection, listOf("$volumeRoot/Movies\\_extra/%")),
            ),
        )
        assertCases(cases, sdkInt = 28)

        // 旧 Provider 没有 VOLUME_NAME / RELATIVE_PATH 两列：selection 绝不能引用它们
        cases.forEach { case ->
            val actual = spec(case.scope, sdkInt = 28)
            val selectionText = requireNotNull(actual.selection)
            assertFalse("${case.label} 不应引用 $volumeNameColumn", selectionText.contains(volumeNameColumn))
            assertFalse("${case.label} 不应引用 $relativePathColumn", selectionText.contains(relativePathColumn))
        }
    }

    @Test
    fun `pre-Q 主存储根尾部斜杠被规范化`() {
        fun legacySpec(scope: ScanScope, root: String): ScopeQuerySpec = ScanScopeQuery.spec(
            scope = scope,
            sdkInt = 28,
            relativePathColumn = relativePathColumn,
            dataColumn = dataColumn,
            volumeNameColumn = volumeNameColumn,
            primaryVolumeName = primaryVolumeName,
            legacyPrimaryRoot = root,
        )

        assertEquals(
            listOf("/storage/emulated/0/%"),
            legacySpec(ScanScope.Primary(null), "/storage/emulated/0/").selectionArgs,
        )
        assertEquals(
            listOf("/storage/emulated/0/Movies/%"),
            legacySpec(ScanScope.Primary("Movies"), "/storage/emulated/0//").selectionArgs,
        )
    }

    @Test
    fun `pre-Q 卷名中的通配符同样按字面匹配`() {
        val actual = spec(ScanScope.Volume("AB%CD", "Movies"), sdkInt = 28)
        assertEquals("_data LIKE ? ESCAPE '\\'", actual.selection)
        assertEquals(listOf("/storage/AB\\%CD/Movies/%"), actual.selectionArgs)
    }

    // ---------------------------------------------------------------------
    // 3. 拒绝扫描 / 转义 / 参数顺序
    // ---------------------------------------------------------------------

    @Test
    fun `无法解析的范围在全部 SDK 上用恒假谓词拒绝扫描`() {
        for (sdkInt in listOf(26, 28, 29, 34)) {
            val actual = spec(ScanScope.Unresolvable, sdkInt)
            assertEquals("sdk=$sdkInt", "1 = 0", actual.selection)
            assertTrue("sdk=$sdkInt", actual.selectionArgs.isEmpty())
        }
    }

    @Test
    fun `escapeLike 先转义反斜杠再转义通配符`() {
        assertEquals("Movies", ScanScopeQuery.escapeLike("Movies"))
        assertEquals("50\\%\\_off", ScanScopeQuery.escapeLike("50%_off"))
        // 输入一个反斜杠 → 输出两个反斜杠
        assertEquals("Movies\\\\Backup", ScanScopeQuery.escapeLike("Movies\\Backup"))
        // 输入 "\%"：先 \ → "\\%"，再 % → "\\\%"（三个反斜杠 + %）；顺序颠倒会得到四个反斜杠
        assertEquals("\\\\\\%", ScanScopeQuery.escapeLike("\\%"))
        assertEquals("\\_\\%", ScanScopeQuery.escapeLike("_%"))
    }

    @Test
    fun `selection 占位符数量与参数数量一致`() {
        val scopes = listOf(
            ScanScope.Primary(null),
            ScanScope.Primary("Movies"),
            ScanScope.Primary("Movies/sub"),
            ScanScope.Primary("Movies%wild"),
            ScanScope.Volume(SECONDARY_VOLUME, null),
            ScanScope.Volume(SECONDARY_VOLUME, "Movies_extra"),
            ScanScope.Unresolvable,
        )
        for (sdkInt in listOf(26, 28, 29, 34)) {
            for (scope in scopes) {
                val actual = spec(scope, sdkInt)
                val selectionText = requireNotNull(actual.selection) { "$scope @ sdk=$sdkInt selection 不得为 null" }
                assertEquals(
                    "$scope @ sdk=$sdkInt",
                    selectionText.count { it == '?' },
                    actual.selectionArgs.size,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 4. SQLite 真实执行：命中集合必须恰为所选卷的所选子树
    // ---------------------------------------------------------------------

    @Test
    fun `Q 及以上 SQL 真实执行只命中所选卷与目录子树`() {
        withMediaDb { db ->
            assertEquals("主卷根", (1L..8L).toSet(), hitIds(db, ScanScope.Primary(null), 34))
            assertEquals("主卷 Movies", setOf(1L, 2L), hitIds(db, ScanScope.Primary("Movies"), 34))
            assertEquals("主卷 Movies/sub", setOf(2L), hitIds(db, ScanScope.Primary("Movies/sub"), 34))
            assertEquals("主卷 MoviesBackup", setOf(3L), hitIds(db, ScanScope.Primary("MoviesBackup"), 34))
            assertEquals("主卷 Movies%wild 按字面", setOf(4L), hitIds(db, ScanScope.Primary("Movies%wild"), 34))
            assertEquals("主卷 Movies_extra 按字面", setOf(5L), hitIds(db, ScanScope.Primary("Movies_extra"), 34))
            assertEquals("第二卷根", (9L..16L).toSet(), hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, null), 34))
            assertEquals(
                "第二卷 Movies",
                setOf(9L, 10L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies"), 34),
            )
            assertEquals(
                "第二卷 Movies/sub",
                setOf(10L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies/sub"), 34),
            )
            assertEquals(
                "第二卷 MoviesBackup",
                setOf(11L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "MoviesBackup"), 34),
            )
            assertEquals(
                "第二卷 Movies%wild 按字面",
                setOf(12L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies%wild"), 34),
            )
            assertEquals(
                "第二卷 Movies_extra 按字面",
                setOf(13L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies_extra"), 34),
            )
            assertEquals("Unresolvable", emptySet<Long>(), hitIds(db, ScanScope.Unresolvable, 34))
        }
    }

    @Test
    fun `pre-Q SQL 真实执行按完整根路径只命中对应卷的所选子树`() {
        withMediaDb { db ->
            assertEquals("主卷根", (1L..8L).toSet(), hitIds(db, ScanScope.Primary(null), 28))
            assertEquals("主卷 Movies", setOf(1L, 2L), hitIds(db, ScanScope.Primary("Movies"), 28))
            assertEquals("主卷 Movies/sub", setOf(2L), hitIds(db, ScanScope.Primary("Movies/sub"), 28))
            assertEquals("主卷 MoviesBackup", setOf(3L), hitIds(db, ScanScope.Primary("MoviesBackup"), 28))
            assertEquals("主卷 Movies%wild 按字面", setOf(4L), hitIds(db, ScanScope.Primary("Movies%wild"), 28))
            assertEquals("主卷 Movies_extra 按字面", setOf(5L), hitIds(db, ScanScope.Primary("Movies_extra"), 28))
            assertEquals(
                "第二卷根",
                (9L..16L).toSet(),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, null), 28),
            )
            assertEquals(
                "第二卷 Movies",
                setOf(9L, 10L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies"), 28),
            )
            assertEquals(
                "第二卷 Movies/sub",
                setOf(10L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "Movies/sub"), 28),
            )
            assertEquals(
                "第二卷 MoviesBackup",
                setOf(11L),
                hitIds(db, ScanScope.Volume(SECONDARY_VOLUME, "MoviesBackup"), 28),
            )
            assertEquals("Unresolvable", emptySet<Long>(), hitIds(db, ScanScope.Unresolvable, 28))
        }
    }

    @Test
    fun `测试列名与 MediaStore 公开常量一致`() {
        assertEquals(MediaStore.MediaColumns.RELATIVE_PATH, relativePathColumn)
        assertEquals(MediaStore.MediaColumns.DATA, dataColumn)
        assertEquals(MediaStore.MediaColumns.VOLUME_NAME, volumeNameColumn)
        assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, primaryVolumeName)
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private fun spec(scope: ScanScope, sdkInt: Int = 34): ScopeQuerySpec = ScanScopeQuery.spec(
        scope = scope,
        sdkInt = sdkInt,
        relativePathColumn = relativePathColumn,
        dataColumn = dataColumn,
        volumeNameColumn = volumeNameColumn,
        primaryVolumeName = primaryVolumeName,
        legacyPrimaryRoot = legacyPrimaryRoot,
    )

    private fun assertCases(cases: List<QueryCase>, sdkInt: Int = 34) {
        cases.forEach { case ->
            val actual = spec(case.scope, sdkInt)
            assertEquals("${case.label}（sdk=$sdkInt）selection", case.expected.selection, actual.selection)
            assertEquals("${case.label}（sdk=$sdkInt）selectionArgs", case.expected.selectionArgs, actual.selectionArgs)
        }
    }

    private fun withMediaDb(block: (SQLiteDatabase) -> Unit) {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                "CREATE TABLE media (" +
                    "_id INTEGER PRIMARY KEY, " +
                    "_data TEXT NOT NULL, " +
                    "relative_path TEXT NOT NULL, " +
                    "volume_name TEXT NOT NULL)",
            )
            MEDIA_ROWS.forEach { row ->
                db.execSQL(
                    "INSERT INTO media (_id, _data, relative_path, volume_name) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(row.id, row.data, row.relativePath, row.volumeName),
                )
            }
            block(db)
        } finally {
            db.close()
        }
    }

    private fun hitIds(db: SQLiteDatabase, scope: ScanScope, sdkInt: Int): Set<Long> {
        val actual = spec(scope, sdkInt)
        val selectionText = requireNotNull(actual.selection) { "selection 不得为 null: $scope @ sdk=$sdkInt" }
        db.rawQuery(
            "SELECT _id FROM media WHERE $selectionText",
            actual.selectionArgs.toTypedArray(),
        ).use { cursor ->
            val ids = linkedSetOf<Long>()
            while (cursor.moveToNext()) {
                ids += cursor.getLong(0)
            }
            return ids
        }
    }

    private companion object {
        const val PRIMARY_VOLUME = "external_primary"
        const val SECONDARY_VOLUME = "1234-ABCD"
        const val LEGACY_PRIMARY_ROOT = "/storage/emulated/0"
        const val PRIMARY_ROOT = "/storage/emulated/0"
        const val SECONDARY_ROOT = "/storage/1234-ABCD"

        /**
         * 覆盖行：主卷与第二卷各一套同构目录。
         *
         * 1–8 主卷；9–16 第二卷。`MoviesZZwild/`（6/14）与 `MoviesXextra/`（7/15）是
         * 对抗行：若 `%`/`_` 未转义，`Movies%wild/%` / `Movies_extra/%` 会误命中它们。
         */
        val MEDIA_ROWS = listOf(
            MediaRow(1, "$PRIMARY_ROOT/Movies/a.mp4", "Movies/", PRIMARY_VOLUME),
            MediaRow(2, "$PRIMARY_ROOT/Movies/sub/b.mp4", "Movies/sub/", PRIMARY_VOLUME),
            MediaRow(3, "$PRIMARY_ROOT/MoviesBackup/c.mp4", "MoviesBackup/", PRIMARY_VOLUME),
            MediaRow(4, "$PRIMARY_ROOT/Movies%wild/d.mp4", "Movies%wild/", PRIMARY_VOLUME),
            MediaRow(5, "$PRIMARY_ROOT/Movies_extra/e.mp4", "Movies_extra/", PRIMARY_VOLUME),
            MediaRow(6, "$PRIMARY_ROOT/MoviesZZwild/f.mp4", "MoviesZZwild/", PRIMARY_VOLUME),
            MediaRow(7, "$PRIMARY_ROOT/MoviesXextra/g.mp4", "MoviesXextra/", PRIMARY_VOLUME),
            MediaRow(8, "$PRIMARY_ROOT/Music/h.mp3", "Music/", PRIMARY_VOLUME),
            MediaRow(9, "$SECONDARY_ROOT/Movies/i.mp4", "Movies/", SECONDARY_VOLUME),
            MediaRow(10, "$SECONDARY_ROOT/Movies/sub/j.mp4", "Movies/sub/", SECONDARY_VOLUME),
            MediaRow(11, "$SECONDARY_ROOT/MoviesBackup/k.mp4", "MoviesBackup/", SECONDARY_VOLUME),
            MediaRow(12, "$SECONDARY_ROOT/Movies%wild/l.mp4", "Movies%wild/", SECONDARY_VOLUME),
            MediaRow(13, "$SECONDARY_ROOT/Movies_extra/m.mp4", "Movies_extra/", SECONDARY_VOLUME),
            MediaRow(14, "$SECONDARY_ROOT/MoviesZZwild/n.mp4", "MoviesZZwild/", SECONDARY_VOLUME),
            MediaRow(15, "$SECONDARY_ROOT/MoviesXextra/o.mp4", "MoviesXextra/", SECONDARY_VOLUME),
            MediaRow(16, "$SECONDARY_ROOT/Music/p.mp3", "Music/", SECONDARY_VOLUME),
        )
    }
}

/** 表驱动用例：范围 + 期望的 selection/args 字面值。 */
private data class QueryCase(
    val label: String,
    val scope: ScanScope,
    val expected: ScopeQuerySpec,
)

/** 假 MediaStore 行（只保留范围过滤用到的列）。 */
private data class MediaRow(
    val id: Long,
    val data: String,
    val relativePath: String,
    val volumeName: String,
)
