package com.noneplayer.kernel.api

/**
 * 会话级键盘快捷键（E99）。
 *
 * **为什么需要内核这一层兜底派发**：快捷键原本整组挂在播放页根节点的
 * `onPreviewKeyEvent` 上，而类 PC 三栏浏览态渲染的是右栏播放栏
 * （`DetailPaneProvider` 的另一份实现），播放页根本不在组合树里——于是
 * "用户正在浏览列表、按空格暂停"这条最典型的桌面语义完全失灵。
 * Compose 的键事件只沿**焦点路径**下发，把处理函数放进右栏子树也救不了：
 * 用户点一下列表卡片，焦点就离开了那一栏。
 *
 * 所以派发点选在窗口层的"未被消费"分支（[android.app.Activity.dispatchKeyEvent]
 * 的 `super` 返回 false 之后）：Compose 树里任何组件处理过的键——文本框里的空格、
 * 播放页自己的快捷键——都优先，本回调只接没人要的键，天然不会与页面级快捷键打架。
 *
 * 内核仍然不认识"播放"：它只负责把未消费的键转给注册者，语义全在插件里。
 * 只在 **ACTION_UP** 上调用（长按会被系统重复投递，见播放页 C19 同一条约束）。
 * 返回 `true` 表示已消费。
 */
interface SessionKeyProvider : ExtensionPoint {

    /**
     * @param keyCode [android.view.KeyEvent.KEYCODE_*]
     * @param shiftPressed 修饰键状态（决定步进大小）
     * @param ctrlPressed 供"打开文件"一类组合键使用；本接口的实现者不认的组合键必须返回 false
     */
    fun onSessionKey(keyCode: Int, shiftPressed: Boolean, ctrlPressed: Boolean): Boolean
}
