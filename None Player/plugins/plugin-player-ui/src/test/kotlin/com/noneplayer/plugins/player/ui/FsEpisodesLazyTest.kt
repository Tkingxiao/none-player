package com.noneplayer.plugins.player.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.NonePlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A25：全屏选集改 LazyColumn 后，长队列只组合可见行。
 *
 * 旧实现是 `queue.forEachIndexed { FsMenuRow(...) }`，几百集一次全部进组合。
 * 这里 200 集，断言首行在、末行不在——末行没进视口就不该被组合。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FsEpisodesLazyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `长选集只组合可见行`() {
        val queue = (1..200).map { index ->
            MediaItem(
                id = "ms-$index",
                sourceId = "local",
                title = "第 $index 集",
                uri = "file:///videos/$index.mp4",
            )
        }

        compose.setContent {
            NonePlayerTheme {
                FsEpisodesMenuContent(
                    queue = queue,
                    currentIndex = 0,
                    onSelect = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("选集（200）").assertExists()
        // E232：行内序号改成独立等宽列（"01"），标题不再拼 "1. " 前缀
        compose.onNodeWithText("第 1 集").assertExists()
        compose.onNodeWithText("第 200 集").assertDoesNotExist()
    }
}
