package com.noneplayer.plugins.library

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 「首页 → 文件夹 Tab 并打开指定目录」的一次性传递槽。
 *
 * **为什么需要它**：首页与文件夹是两个**独立的 Tab 页面实例**，各自持有自己的
 * `rememberSaveable` 选中目录。从首页点某个文件夹时，只切 Tab 是不够的——
 * 文件夹 Tab 会停在自己上次的层级（或恢复成根层），用户点到 A 却看到根目录。
 *
 * 用"待消费路径"而不是把路径塞进路由参数，是因为 **Tab 路由不带参数**：
 * `folders` 是 `TabProvider.route`，内核的 Tab 切换用 `saveState/restoreState`
 * 成对保留状态，往里拼 `folders?path=...` 会变成一个新路由、连带毁掉状态保留语义。
 *
 * 与 `SessionHolder` 同一模式：插件间禁止互相依赖，共享点落在插件内部，
 * 由本插件的两个 Tab 页面共同读写。**消费即清空**，避免"退出文件夹再切回来又被顶进去"。
 */
internal object FolderNav {
    private val pending = MutableStateFlow<String?>(null)

    /** 首页请求文件夹 Tab 打开某目录 */
    fun request(path: String) {
        pending.value = path
    }

    /** 文件夹 Tab 取用一次；取到即清空（同一请求不会被消费两次） */
    fun consume(): String? = pending.value?.also { pending.value = null }

    /**
     * 丢弃未消费的请求（首页点「管理」时调用）。
     *
     * 不丢的话会出现这种情况：先点某个文件夹卡片（请求入队），但 Tab 切换因为
     * 已经在文件夹 Tab 上而被内核的 `if (currentRoute == route) return` 短路，
     * 请求就留在队里；用户回到首页点「管理」，进去后被那条**陈旧请求**顶到某个子目录——
     * "点管理却进了别人的文件夹"。
     */
    fun clear() {
        pending.value = null
    }
}
