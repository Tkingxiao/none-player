package com.noneplayer.plugins.scanner.local

/**
 * MediaStore 查询过滤条件：selection 与其绑定参数。
 *
 * [selectionArgs] 的顺序与 [selection] 中 `?` 的出现顺序一一对应。
 * [selection] 为 null 只作为"不附加过滤"的防御性表示；[ScanScopeQuery.spec] 的
 * 任何正常结果都不会返回 null（拒绝扫描用恒假谓词 `1 = 0`，而不是 null——null 会让
 * MediaStore 退化成全量查询，正是 REV-02 要修掉的越界）。
 */
internal data class ScopeQuerySpec(
    val selection: String?,
    val selectionArgs: List<String>,
)

/**
 * 授权范围（[ScanScope]）→ MediaStore 过滤条件（REV-02）。
 *
 * 两个历史问题在这里统一收口：
 * 1. **目录边界不严**：旧实现用 `relativePath + "%"`，选 `Movies` 会连 `MoviesBackup/`
 *    一起收进来。这里的前缀一律带目录分隔符（`Movies/%`），子目录（`Movies/sub`）只匹配
 *    该子树。
 * 2. **卷边界不严**：旧实现主卷分支不限定卷，主存储根/子目录可能收进其他卷同路径的媒体。
 *    这里 Q+ 一律附加 `VOLUME_NAME = ?`（主卷 = [primaryVolumeName]，可移动卷 = 卷名）；
 *    pre-Q 没有 VOLUME_NAME 列，改用完整存储根路径（`/storage/...`）的 DATA 前缀。
 *
 * 纯逻辑，不读 [android.os.Build]：SDK 由调用方以 [spec] 的 `sdkInt` 显式传入，
 * 使 26/28/29/34 全矩阵可在同一次单测里断言。
 */
internal object ScanScopeQuery {

    /** Android 10（Q）：MediaStore 开始提供 RELATIVE_PATH / VOLUME_NAME 两列。 */
    private const val SDK_Q = 29

    /**
     * 无法解析的授权范围（异常 / 非预期 docId）→ 拒绝扫描。
     *
     * 用恒假谓词而不是 null：null 会被 MediaStore 解释为"不加过滤"，把整机媒体都查回来。
     */
    private val UNRESOLVABLE = ScopeQuerySpec(selection = "1 = 0", selectionArgs = emptyList())

    /**
     * @param scope 用户授权的目录范围
     * @param sdkInt 设备 SDK（调用方传 [android.os.Build.VERSION.SDK_INT]；测试可传任意值）
     * @param relativePathColumn Q+ 的 `MediaStore.MediaColumns.RELATIVE_PATH`
     * @param dataColumn 旧版本的 `MediaStore.MediaColumns.DATA`
     * @param volumeNameColumn Q+ 的 `MediaStore.MediaColumns.VOLUME_NAME`
     * @param primaryVolumeName 主卷的 VOLUME_NAME（`MediaStore.VOLUME_EXTERNAL_PRIMARY`）
     * @param legacyPrimaryRoot pre-Q 主存储根路径（如 `/storage/emulated/0`）
     */
    fun spec(
        scope: ScanScope,
        sdkInt: Int,
        relativePathColumn: String,
        dataColumn: String,
        volumeNameColumn: String,
        primaryVolumeName: String,
        legacyPrimaryRoot: String,
    ): ScopeQuerySpec = when (scope) {
        ScanScope.Unresolvable -> UNRESOLVABLE

        is ScanScope.Primary -> if (sdkInt >= SDK_Q) {
            modernSpec(
                volumeName = primaryVolumeName,
                relativePath = scope.relativePath,
                relativePathColumn = relativePathColumn,
                volumeNameColumn = volumeNameColumn,
            )
        } else {
            legacySpec(
                root = legacyPrimaryRoot,
                relativePath = scope.relativePath,
                dataColumn = dataColumn,
            )
        }

        is ScanScope.Volume -> if (sdkInt >= SDK_Q) {
            modernSpec(
                volumeName = scope.name,
                relativePath = scope.relativePath,
                relativePathColumn = relativePathColumn,
                volumeNameColumn = volumeNameColumn,
            )
        } else {
            // 旧 Provider 没有 VOLUME_NAME，可移动卷只能按 /storage/<卷名> 的完整 DATA 路径限定。
            legacySpec(
                root = "/storage/${scope.name}",
                relativePath = scope.relativePath,
                dataColumn = dataColumn,
            )
        }
    }

    /**
     * E66：转义 SQLite LIKE 模式中的通配符，使文件夹名里的 `%`/`_` 不被当成通配符。
     *
     * 顺序必须是先 `\` 后其余：先转 `%`/`_` 会把随后插入的转义反斜杠再次翻倍。
     * 配合 selection 里的 `ESCAPE '\'` 使用。
     */
    fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** Q+：卷等值 + 目录子树前缀（前缀必须带 "/" 分隔符）。 */
    private fun modernSpec(
        volumeName: String,
        relativePath: String?,
        relativePathColumn: String,
        volumeNameColumn: String,
    ): ScopeQuerySpec {
        val normalized = normalizeRelativePath(relativePath)
        return if (normalized == null) {
            // 卷根（含主存储根）：限定卷即用户选择的全部范围
            ScopeQuerySpec(
                selection = "$volumeNameColumn = ?",
                selectionArgs = listOf(volumeName),
            )
        } else {
            ScopeQuerySpec(
                selection = "$volumeNameColumn = ? AND $relativePathColumn LIKE ? ESCAPE '\\'",
                selectionArgs = listOf(volumeName, "${escapeLike(normalized)}/%"),
            )
        }
    }

    /**
     * pre-Q：旧 Provider 没有 RELATIVE_PATH / VOLUME_NAME，用完整存储根路径的 DATA 前缀。
     *
     * `root` 先规范化（去尾部 "/"），再整体转义；目录部分同样转义后以 "/%" 结尾，
     * 保证 `Movies` 不会命中 `MoviesBackup/`，且 `%`/`_` 只按字面匹配。
     */
    private fun legacySpec(
        root: String,
        relativePath: String?,
        dataColumn: String,
    ): ScopeQuerySpec {
        val normalizedRoot = root.trimEnd('/')
        val normalizedPath = normalizeRelativePath(relativePath)
        val prefix = buildString {
            append(escapeLike(normalizedRoot))
            if (normalizedPath != null) {
                append('/')
                append(escapeLike(normalizedPath))
            }
            append("/%")
        }
        return ScopeQuerySpec(
            selection = "$dataColumn LIKE ? ESCAPE '\\'",
            selectionArgs = listOf(prefix),
        )
    }

    /** 相对目录规范化：去掉首尾 "/"；空串等价于卷根（null）。 */
    private fun normalizeRelativePath(relativePath: String?): String? =
        relativePath?.trim('/')?.ifEmpty { null }
}
