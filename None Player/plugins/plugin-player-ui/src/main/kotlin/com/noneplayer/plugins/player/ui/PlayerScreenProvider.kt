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

    override val route: String = "player?uri={uri}&title={title}&mime={mime}&id={id}"
    override val deepLinks: List<String> = listOf("noneplayer://player?uri={uri}")

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(
            route = route,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("mime") { type = NavType.StringType; defaultValue = "video/mp4" },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
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
            )
        }
    }
}
