package com.noneplayer.plugins.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.data.LibraryView
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.TabProvider

/**
 * 首页 Tab——全部媒体封面墙（route "home"，位于主界面 Shell 首位）。
 *
 * 图标用 [NpIcon] 而不是 `androidx.compose.material.icons`：设计稿的导航图标是
 * 24 视口 / 2px 描边的 monoline，与 Material 的 filled 变体不是一套语言，
 * 混用会让底栏看起来"有一个图标是从别的 App 借来的"。
 */
class LibraryTabProvider : TabProvider {

    override val tabId: String = "library"
    override val label: String = "首页"
    override val icon: Any = NpIcon.Home
    override val selectedIcon: Any? = NpIcon.HomeFill
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
    override val icon: Any = NpIcon.Folder
    override val selectedIcon: Any? = NpIcon.FolderFill
    override val order: Int = 20
    override val route: String = "folders"
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            LibraryScreen(navigator = navigator, forcedView = LibraryView.FOLDERS)
        }
    }
}
