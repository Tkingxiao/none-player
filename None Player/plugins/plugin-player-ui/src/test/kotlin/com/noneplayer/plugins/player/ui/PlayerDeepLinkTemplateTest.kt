package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页深链的查询参数形状（A1-2）。
 *
 * 导航的深链匹配按**模板逐项**比对查询参数，不是"能绑几个绑几个"：一条只声明了
 * `?uri={uri}` 的模板会把 `?uri=…&id=…` 的链接接走，`id` 于是静默丢掉，
 * 播放页拿到空 mediaId → 全部 per-media 记忆挤进同一个空键桶
 * （实测孤儿键 `audio_only_` / `display_`）。
 *
 * 所以**应用自己发出的每一种参数组合都必须有一条同形状的模板**。
 * 这里把"发出的形状"与"声明的模板"两边对齐钉住：以后新增一条发射形状而忘了
 * 加模板，这条测试先红。
 */
class PlayerDeepLinkTemplateTest {

    /** 形状按参数出现顺序写——与 `Uri.Builder.appendQueryParameter` 的调用顺序一致 */
    private fun shape(vararg keys: String) = "noneplayer://player?" + keys.joinToString("&") { "$it={$it}" }

    private val emitted = listOf(
        shape("uri", "title", "mime", "id", "from", "fs", "audio"),
        // 内部导航与详情页/右栏：全量形状
        shape("uri", "title", "mime", "id", "from", "fs"),
        // E218：外部「全屏播放」深链（adb 验收 / 第三方入口）——不补模板时 fs
        // 会被"仅 uri"模板静默丢掉，冷启落竖屏而非全屏（真机实锤）
        shape("uri", "title", "mime", "id", "fs"),
        shape("uri", "id", "fs"),
        shape("uri", "fs"),
        shape("uri", "title", "mime", "id", "audio"),
        shape("uri", "mime"),
        // 通知/锁屏/PiP 回跳（PlaybackService.deepLinkPendingIntent）：只带 uri 与权威 id
        shape("uri", "id"),
        // 外部应用最简深链
        shape("uri"),
    )

    @Test
    fun `应用发出的每种深链形状都有对应模板`() {
        val declared = PlayerScreenProvider().deepLinks.toSet()
        val missing = emitted.filterNot { it in declared }
        assertTrue(
            "缺深链模板（导航会把它降级成更短的模板并丢掉后面的参数）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `模板声明顺序是全量在前仅 uri 在后`() {
        val declared = PlayerScreenProvider().deepLinks
        assertTrue(
            "最短的模板必须排在最后，否则它会先接走带更多参数的链接",
            declared.last() == shape("uri"),
        )
    }
}
