package com.noneplayer.plugins.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.data.LibraryView
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.TabProvider

/**
 * 首页 Tab——全部视频封面墙（route "home"，位于主界面 Shell 首位）。
 */
class LibraryTabProvider : TabProvider {

    override val tabId: String = "library"
    override val label: String = "首页"
    override val icon: Any = Icons.Filled.Home
    override val order: Int = 10
    override val route: String = "home"
    override val deepLinks: List<String> = listOf("noneplayer://library")

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            LibraryScreen(navigator = navigator)
        }
    }
}

/**
 * 文件夹 Tab——按文件夹分组浏览（视图固定为 FOLDERS）。
 */
class FoldersTabProvider : TabProvider {

    override val tabId: String = "folders"
    override val label: String = "文件夹"
    override val icon: Any = Icons.AutoMirrored.Filled.List
    override val order: Int = 20
    override val route: String = "folders"
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            LibraryScreen(navigator = navigator, forcedView = LibraryView.FOLDERS)
        }
    }
}
