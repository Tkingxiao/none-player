package com.noneplayer.plugins.player.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.composable
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.ScreenProvider

/**
 * 播放页贡献：注册 `player` 路由。
 *
 * 媒体信息经 URL 编码的查询参数传递（uri/title/mime/id），
 * 由本插件自行声明并解析 navArgument——内核不感知路由结构（设计文档 §7）。
 */
class PlayerScreenProvider : ScreenProvider {

    override val route: String =
        "player?uri={uri}&title={title}&mime={mime}&id={id}&from={from}&fs={fs}"

    // 深链带全量可选参数（都有 defaultValue，query 参数可省略）：
    // 只声明 uri 的话，外部传入的 id/title/mime 会被导航静默丢弃——
    // 批次 3 实测：同名挂载/断点续播这些 per-media 记忆在深链路径下全部失灵
    //
    // A1-2 补子集模板：导航的深链匹配按**模板逐项**比对查询参数，不是"能绑几个绑几个"。
    // 只声明"全量"与"仅 uri"两条时，任何 `uri`+`id` 而缺 `title`/`mime` 的链接会落到
    // "仅 uri"那条上，`id` 被静默丢掉 → 播放页拿到的 mediaId 为空，
    // 全部 per-media 记忆挤进同一个空键桶（实测孤儿键 `audio_only_` / `display_`）。
    // 通知/锁屏/PiP 回跳的深链正是这个形状（服务侧只带 uri 与 id）。
    override val deepLinks: List<String> =
        listOf(
            "noneplayer://player?uri={uri}&title={title}&mime={mime}&id={id}&from={from}&fs={fs}",
            "noneplayer://player?uri={uri}&id={id}",
            "noneplayer://player?uri={uri}",
        )

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(
            route = route,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("mime") { type = NavType.StringType; defaultValue = "video/mp4" },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                // 「从头播放」标记（长按菜单）：非 start 一律按续播（契约增量，带默认值）
                navArgument("from") { type = NavType.StringType; defaultValue = "" },
                // 「全屏进来」标记（详情页/右栏内嵌播放的 ⛶）：1 = 落地即沉浸（§14 #16）
                navArgument("fs") { type = NavType.StringType; defaultValue = "" },
            ),
            // E26：deepLinks 声明必须真正进入导航图，否则 Manifest 注册了 scheme
            // 也落不到本页面（此前该属性在内核中从未被消费）
            deepLinks = deepLinks.map { pattern -> navDeepLink { uriPattern = pattern } },
        ) { entry ->
            PlayerScreen(
                uri = entry.arguments?.getString("uri").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                mimeType = entry.arguments?.getString("mime") ?: "video/mp4",
                mediaId = entry.arguments?.getString("id").orEmpty(),
                navigator = navigator,
                fromStart = entry.arguments?.getString("from") == "start",
                immersiveEntry = entry.arguments?.getString("fs") == "1",
            )
        }
    }
}
