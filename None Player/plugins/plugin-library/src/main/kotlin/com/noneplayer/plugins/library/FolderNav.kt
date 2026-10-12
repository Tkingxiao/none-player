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
    val pathRequest: kotlinx.coroutines.flow.StateFlow<String?> = pending

    /**
     * 「打开扫描目录管理面板」的一次性请求（首页点区块的「管理」）。
     *
     * 与 [pending] 同机制但独立通道：管理面板是**浮层**不是层级——已经在文件夹 Tab
     * 时 Tab 切换会被内核短路，此时没有"页面重建"可借力，必须靠这枚值型信号
     * （时间戳做 key，`LaunchedEffect` 才会重新触发）通知页面把面板打开。
     */
    private val manageTick = MutableStateFlow<Long?>(null)
    val manageRequest: kotlinx.coroutines.flow.StateFlow<Long?> = manageTick

    /** 首页请求文件夹 Tab 打开某目录 */
    fun request(path: String) {
        manageTick.value = null
        pending.value = path
    }

    /** 文件夹 Tab 取用一次；取到即清空（同一请求不会被消费两次） */
    fun consume(): String? = pending.value?.also { pending.value = null }

    /** 首页请求打开「扫描目录」管理面板 */
    fun requestManage() {
        // 丢弃未消费的目录请求：否则"先点文件夹卡片、再点管理"会把陈旧请求
        // 带进文件夹 Tab，进去后被那条请求顶到某个子目录（"点管理却进了别人的文件夹"）
        pending.value = null
        manageTick.value = System.currentTimeMillis()
    }

    /** 管理面板已打开，请求消费掉（值不匹配时说明已有更新的一次请求，不动它） */
    fun consumeManage(tick: Long) {
        if (manageTick.value == tick) manageTick.value = null
    }
}
