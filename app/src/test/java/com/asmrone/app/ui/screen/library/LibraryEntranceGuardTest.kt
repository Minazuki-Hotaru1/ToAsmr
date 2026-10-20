package com.asmrone.app.ui.screen.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 入场动画守卫：收藏「列表」页签与收藏夹详情必须和别的列表页**同一套动效**。
 *
 * 首页 / 搜索结果 / 收藏「标记」页都是「淡入 + 上移 40px + 按序号错峰」，
 * 但收藏「列表」页签（收藏夹卡片）和收藏夹详情页当初漏了 —— 同样是卡片，
 * 一边有动效、一边硬切，滑进去时观感断层。
 *
 * 这里钉住三件事：走 `itemsIndexed` 拿序号、调 `animateCardEntrance(index)`、
 * 用 `Modifier.cardEntrance(alpha)` 作用到图层上；以及用 animatedKeys 保证
 * 滚动回来不重播。
 */
class LibraryEntranceGuardTest {

    private val library = File(
        "src/main/java/com/asmrone/app/ui/screen/library/LibraryScreen.kt"
    ).readText()

    private val playlistDetail = File(
        "src/main/java/com/asmrone/app/ui/screen/library/PlaylistDetailScreen.kt"
    ).readText()

    /** 取 [marker] 之后 [chars] 个字符，只看这个函数体里发生了什么 */
    private fun bodyOf(source: String, marker: String, chars: Int = 4200): String {
        val at = source.indexOf(marker)
        assertTrue("源码里找不到「$marker」——它被改名或删掉了，守卫需要同步更新", at >= 0)
        return source.substring(at, (at + chars).coerceAtMost(source.length))
    }

    @Test
    fun `★ 收藏列表页签的收藏夹卡片有错落入场`() {
        val tab = bodyOf(library, "private fun PlaylistsTab(", 4200)
        assertTrue(
            "要用 itemsIndexed 拿序号（items 没有 index，错峰就无从算起）",
            tab.contains("itemsIndexed(state.playlists")
        )
        assertTrue("入场动画按序号错峰", tab.contains("animateCardEntrance(index)"))
        assertTrue("透明度和位移要作用到卡片图层上", tab.contains("Modifier.cardEntrance(alpha)"))
        assertTrue("只播一次：滚动回来不重播", tab.contains("animatedKeys"))
    }

    @Test
    fun `★ 收藏夹内部的作品卡也有错落入场`() {
        assertTrue(
            "收藏夹详情同样要 itemsIndexed",
            playlistDetail.contains("itemsIndexed(state.works")
        )
        assertTrue("入场动画按序号错峰", playlistDetail.contains("animateCardEntrance(index)"))
        assertTrue("作用到卡片图层上", playlistDetail.contains("Modifier.cardEntrance(alpha)"))
        assertTrue("只播一次", playlistDetail.contains("animatedKeys"))
    }
}
