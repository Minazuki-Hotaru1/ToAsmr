package com.asmrone.app.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「标记」页签的状态筛选条：**可收起 / 展开**。
 *
 * 交互参考 comicApp 搜索页的「偏好选择」，但有一条刻意的差异：
 * 那边是 `activeIds.isNotEmpty() || expanded` —— **选过之后就收不起来了**；
 * 这里不允许这种"选择即锁定"，折叠与否只看用户点没点。
 */
class LibraryFilterCollapseGuardTest {

    private val source = File(
        "src/main/java/com/asmrone/app/ui/screen/library/LibraryScreen.kt"
    ).readText()

    @Test
    fun `★ 状态筛选条是可收起的`() {
        assertTrue("要有展开状态", source.contains("var filtersExpanded by remember"))
        assertTrue("顶栏按钮要能切换它", source.contains("filtersExpanded = !filtersExpanded"))
        assertTrue("要有展开/收起动画", source.contains("expandVertically("))
        assertTrue("要有收起动画", source.contains("shrinkVertically("))
    }

    @Test
    fun `★ 默认是收起的`() {
        assertTrue(
            "默认收起：第一屏留给作品本身",
            source.contains("filtersExpanded by remember { mutableStateOf(false) }")
        )
    }

    @Test
    fun `★ 收起与否只看用户点击，选过状态也能收起`() {
        // 可见性必须直接由 filtersExpanded 驱动；
        // 写成 `state.filter != null || filtersExpanded` 就是 comicApp 那个"选完锁死"的逻辑。
        assertTrue(
            "可见性要直接由 filtersExpanded 驱动",
            source.contains("visible = filtersExpanded")
        )
        assertFalse(
            "不能因为选中了状态就强制展开",
            source.contains("state.filter != null ||")
        )
    }

    @Test
    fun `收起后顶栏按钮仍显示当前筛选状态`() {
        assertTrue(
            "否则收起后就不知道现在筛的是哪个状态",
            source.contains("label = collection.filter?.label ?: \"全部\"")
        )
    }
}
