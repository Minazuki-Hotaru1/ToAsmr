package com.asmrone.app.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 收藏页的卡片样式：与首页**共用同一张 `WorkCard` + 同一个瀑布流**。
 *
 * 需求是"把收藏页的卡片调整成首页那样的效果"，所以这里钉住两件事：
 *  1. 收藏页不再有自己的行卡片布局；
 *  2. 收藏特有的「听歌状态 / 待同步」不再挤在行内，而是叠在卡片封面右上角
 *     （`WorkCard` 为此提供了可选的 `badge` 槽）。
 */
class LibraryCardStyleGuardTest {

    private val library = File(
        "src/main/java/com/asmrone/app/ui/screen/library/LibraryScreen.kt"
    ).readText()

    private val workCard = File(
        "src/main/java/com/asmrone/app/ui/component/WorkCard.kt"
    ).readText()

    @Test
    fun `★ 收藏页与首页共用同一张作品卡片`() {
        assertTrue("收藏页要用公共 WorkCard", library.contains("WorkCard("))
        assertFalse(
            "不该再保留收藏专用的行卡片（那份已删除）",
            library.contains("private fun CollectionRow(")
        )
    }

    @Test
    fun `★ 收藏页用与首页一致的瀑布流`() {
        assertTrue("标记页要改成瀑布流", library.contains("LazyVerticalStaggeredGrid("))
        assertTrue(
            "列宽与首页同一套自适应规则",
            library.contains("StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL)")
        )
        assertTrue("间距与首页一致（10dp）", library.contains("verticalItemSpacing = 10.dp"))
    }

    @Test
    fun `★ 状态角标叠在卡片右上角，而不是占一行`() {
        assertTrue("WorkCard 要提供 badge 槽", workCard.contains("badge: (@Composable BoxScope.() -> Unit)? = null"))
        assertTrue("收藏页要把状态角标传进去", library.contains("badge = {"))
        assertTrue("角标含状态", library.contains("status = work.collectionStatus"))
        assertTrue("待同步也要能在角标上重试", library.contains("onRetrySync = { onRetryPending(work.id) }"))
    }

    // ───────────── 收藏页只显示「社团 / 声优 / 星级」─────────────

    @Test
    fun `★ 收藏页显示年龄分级角标，但不显示标签`() {
        assertFalse(
            "分级角标不能被关掉，否则全年龄作品又没有标识了",
            library.contains("showAgeBadge = false")
        )
        assertTrue("标签关掉", library.contains("showTags = false"))
        assertTrue("声优信息要在卡片上显示（WorkCard 里的 vaText）", workCard.contains("work.vaText"))
        assertTrue(
            "分级要能从缓存还原（nsfw / age 都要存）",
            File("src/main/java/com/asmrone/app/data/cache/CachedModels.kt")
                .readText().contains("val nsfw: Boolean = false")
        )
    }

    @Test
    fun `★ 不再显示「未标记」标识`() {
        assertFalse(
            "没有状态时应该什么都不画",
            library.contains("\"未标记\"")
        )
    }

    @Test
    fun `★ 我的评分以星标药丸显示`() {
        assertTrue("要有评分药丸", library.contains("private fun RatingPill("))
        assertTrue("用的是用户评分", library.contains("work.userRating?.let"))
        assertTrue("星标图标", library.contains("R.drawable.ic_star_fluent"))
    }
}
