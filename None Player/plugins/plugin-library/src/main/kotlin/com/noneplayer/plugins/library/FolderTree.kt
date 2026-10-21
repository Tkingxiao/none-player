package com.noneplayer.plugins.library

import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind

/** 文件夹层级节点（path 为相对路径，如 "Movies/[SAIO-Raws]"；虚拟根为 ""） */
data class FolderNode(
    val path: String,
    val name: String,
    val directVideos: List<MediaItem>,
    val children: List<FolderNode>,
) {
    /**
     * 含所有子层级的媒体（角标与封面用），保持列表排序顺序。
     *
     * 在**构造期**算好而不是写成 `get()`：子树是自底向上构建的（`toNode` 先递归
     * children 再建父节点），父节点构造时子节点的聚合已就绪，整棵树只算一遍。
     * 此前是惰性 getter，而文件夹网格里每张卡都要读 `totalCount`/`videoCount`——
     * 每张卡每次重组都重新递归整棵子树并分配中间列表，深目录 + 滚动时就是
     * O(节点数 × 子树大小) 的重复分配。
     */
    val allVideos: List<MediaItem> = directVideos + children.flatMap { it.allVideos }
    val totalCount: Int = allVideos.size

    /** 只数视频的条目数（设计稿 `.folder .pill` 的「N 个视频」口径） */
    val videoCount: Int = allVideos.count { it.kind == MediaKind.VIDEO }
}

/**
 * 由媒体的相对路径构建文件夹层级树（"A/B/C" → A > B > C，自动补齐中间层）。
 *
 * A24：旧实现每个节点都 `nodes.values.filter` 扫一遍全部节点（O(节点²)），
 * 且在主线程组合期对两个 Tab 各建一次。这里先一次 `groupBy` 建好父子索引，
 * 再自底向上递归；节点数与媒体数都是线性量级，首页只依赖第一层时也不再物化全树之外的东西。
 */
internal fun buildFolderTree(items: List<MediaItem>): FolderNode {
    val directByPath = HashMap<String, MutableList<MediaItem>>()
    val allPaths = HashSet<String>()

    items.forEach { item ->
        val folder = item.folder ?: return@forEach
        directByPath.getOrPut(folder) { mutableListOf() } += item
        var path = folder
        while (path.isNotEmpty() && allPaths.add(path)) {
            path = path.substringBeforeLast('/', "")
        }
        allPaths.add("")
    }

    val childrenByParent = allPaths
        .filter { it.isNotEmpty() }
        .groupBy { it.substringBeforeLast('/', "") }

    fun toNode(path: String): FolderNode = FolderNode(
        path = path,
        name = path.substringAfterLast('/'),
        directVideos = directByPath[path].orEmpty(),
        children = childrenByParent[path].orEmpty()
            .sortedBy { it.substringAfterLast('/').lowercase() }
            .map(::toNode),
    )

    return toNode("")
}
