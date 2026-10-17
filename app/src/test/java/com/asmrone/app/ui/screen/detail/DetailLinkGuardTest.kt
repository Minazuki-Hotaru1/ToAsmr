package com.asmrone.app.ui.screen.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 详情页「社团 / 声优 / 标签 → 按它搜索」的守卫。
 *
 * ## 架构变更后这里守什么
 *
 * 原先详情页点一项要**先跳到搜索页**、把条件放进交接位、再触发搜索 ——
 * 等于"看一个标签的作品"必须走一遍提搜索条件的流程。
 *
 * 现在改成**直达**：结果展示抽成了公共组件 `WorkResultGrid`，
 * `DictionaryResultScreen` 自包含地拉数据并渲染，搜索页完全不参与。
 *
 * 所以守卫重点也变了：不再守"交接位"，而是守
 *  1. 三处点击是**链接样式**、逐个可点
 *  2. 点击**直接导航**到字典结果页（不经过搜索 tab）
 *  3. 那条检索**记进搜索历史**（在结果页的 ViewModel 里记，不在搜索页）
 */
class DetailLinkGuardTest {

    private val screen by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/detail/DetailScreen.kt").readText()
    }

    private val nav by lazy {
        File("src/main/java/com/asmrone/app/ui/navigation/AppNavigation.kt").readText()
    }

    private val dictionaryViewModel by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/dictionary/DictionaryResultViewModel.kt").readText()
    }

    /**
     * 取出某个函数的**定义体**。
     *
     * ## ⚠️ 两个坑，都踩过
     *
     * **坑一：不能只按函数名找。** `indexOf` 会先命中最上面的**调用点**，
     * 从那里往后找大括号就截出一段无关代码，断言于是全部假失败。
     * 判定规则：签名之后紧跟 `)` 的是**调用**（无参调用），否则是**定义**。
     *
     * **坑二：参数表里也可能有大括号。** 本项目的 Compose 组件常有
     * 默认参数是 lambda（`onTagClick: (Int) -> Unit = {}`），于是"定义之后
     * 第一个 `{`"落在**参数表里面**，截出来的函数体少一截。
     * 所以要**先配对括号**找到参数表结尾，再从那里找函数体的 `{`。
     */
    private fun functionBody(source: String, definitionPrefix: String): String {
        var at = source.indexOf(definitionPrefix)
        while (at >= 0) {
            val after = at + definitionPrefix.length
            if (source.getOrNull(after) != ')') break
            at = source.indexOf(definitionPrefix, at + 1)
        }
        assertTrue("找不到函数定义：$definitionPrefix", at >= 0)

        val parenOpen = source.indexOf('(', at)
        assertTrue("$definitionPrefix 后面没有参数表", parenOpen >= 0)
        var pd = 0
        var p = parenOpen
        while (p < source.length) {
            when (source[p]) {
                '(' -> pd++
                ')' -> {
                    pd--
                    if (pd == 0) break
                }
            }
            p++
        }

        val braceStart = source.indexOf('{', p)
        assertTrue("$definitionPrefix 定义之后没有函数体", braceStart >= 0)
        var depth = 0
        var i = braceStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(braceStart, i + 1)
                }
            }
            i++
        }
        error("大括号不配对：$definitionPrefix")
    }

    /** 签名 + 函数体（[functionBody] 不含参数表，断言参数时要用这个） */
    private fun signatureAndBody(source: String, definitionPrefix: String): String {
        val at = source.indexOf(definitionPrefix)
        assertTrue("找不到函数定义：$definitionPrefix", at >= 0)
        val body = functionBody(source, definitionPrefix)
        val bodyStart = source.indexOf(body, at)
        return source.substring(at, bodyStart + body.length)
    }

    // ═══════════════ 链接样式 ═══════════════

    @Test
    fun `★ 链接文字有下划线，不只靠颜色`() {
        val body = functionBody(screen, "private fun LinkText(")
        assertTrue(
            "只靠颜色的话，在不少主题里与正文差别很小，看不出能点",
            body.contains("TextDecoration.Underline")
        )
        assertTrue("主色以示可点", body.contains("colorScheme.primary"))
        assertTrue("点击区域要够大", body.contains("TouchTarget.minimum"))
    }

    @Test
    fun `★ 社团用链接样式渲染`() {
        val body = functionBody(screen, "private fun StatsBlock(")
        assertTrue("社团要标成链接", body.contains("link = work.circleId != null"))
        assertTrue("并接上跳转回调", body.contains("onOpenCircle"))
    }

    @Test
    fun `★ 多位声优逐个可点，而不是整串一起点`() {
        val body = functionBody(screen, "private fun StatsBlock(")
        assertTrue("要传 links 列表（每人一个回调）", body.contains("links = work.vas.map"))
        assertFalse(
            "★ 拼成一整串再整体可点的话，点进去只能猜是哪一位",
            body.contains("work.vas.joinToString")
        )
        assertTrue(body.contains("onOpenVoiceActor"))
    }

    @Test
    fun `MetaItem 支持多值且每值各自可点`() {
        val declaration = signatureAndBody(screen, "private fun MetaItem(")
        assertTrue(declaration.contains("links: List<Pair<String, () -> Unit>> = emptyList()"))
        val body = functionBody(screen, "private fun MetaItem(")
        assertTrue("多值要各自渲染成链接", body.contains("LinkText(text = text, onClick = action)"))
        assertTrue("分隔符用次要色，不抢链接的注意力", body.contains("\" / \""))
    }

    // ═══════════════ 标签墙 ═══════════════

    @Test
    fun `★ 标签墙可点，并带上显示名`() {
        val body = functionBody(screen, "private fun TagsBlock(")
        assertTrue(
            "标签 chip 要接上跳转回调，并带上显示名（结果页顶栏要用）",
            body.contains("onTagClick(tag.id, tag.displayName)")
        )
        assertTrue("标签墙由详情页接上 onOpenTag", screen.contains("onTagClick = onOpenTag"))
    }

    // ═══════════════ 直达导航（不经过搜索页）═══════════════

    @Test
    fun `★ 详情页暴露的三个回调都带上名字`() {
        val declaration = signatureAndBody(screen, "fun DetailScreen(")
        assertTrue(declaration.contains("onOpenTag: (id: Int, name: String)"))
        assertTrue(declaration.contains("onOpenVoiceActor: (id: String, name: String)"))
        assertTrue(declaration.contains("onOpenCircle: (id: Int, name: String)"))
    }

    @Test
    fun `★ 点击直接导航到字典结果页，不再切搜索 tab`() {
        val detail = nav.substringAfter("route = Routes.DETAIL")
        assertTrue(
            "要走 dictionaryResult 路由",
            detail.contains("Routes.dictionaryResult(")
        )
        assertFalse(
            "★ 不该再往搜索 tab 跳 —— 那正是「先跳搜索页再搜索」的来源",
            detail.contains("navController.navigate(Routes.SEARCH)")
        )
    }

    @Test
    fun `★ 交接位机制已经彻底移除`() {
        val searchScreen = File("src/main/java/com/asmrone/app/ui/screen/search/SearchScreen.kt").readText()
        assertFalse("导航侧不该再引用它", nav.contains("PendingDictionarySearch"))
        assertFalse("搜索页不该再检查它", searchScreen.contains("consumePendingDictionaryRequest"))
        assertFalse(
            "文件本身也不该还在",
            File("src/main/java/com/asmrone/app/ui/screen/search/PendingDictionarySearch.kt").exists()
        )
    }

    @Test
    fun `字典结果页是自包含的（名字走路由）`() {
        assertTrue("路由要把名字带上", nav.contains("Routes.dictionaryResult(SearchNamespaces.TAG, tagId.toString(), name)"))
        val route = nav.substringAfter("const val DICTIONARY_RESULT").substringBefore("\n")
        assertTrue("路由要含 kind / id / name", route.contains("{kind}") && route.contains("{id}") && route.contains("{name}"))
    }

    // ═══════════════ 结果展示抽成公共组件 ═══════════════

    @Test
    fun `★ 两个结果页共用同一个公共组件`() {
        val searchResult = File("src/main/java/com/asmrone/app/ui/screen/search/SearchResultScreen.kt").readText()
        val dictResult = File("src/main/java/com/asmrone/app/ui/screen/dictionary/DictionaryResultScreen.kt").readText()
        assertTrue("搜索结果页要用公共组件", searchResult.contains("WorkResultGrid("))
        assertTrue("字典结果页要用同一个", dictResult.contains("WorkResultGrid("))
        assertFalse(
            "★ 搜索结果页里不该再有自己的瀑布流实现（重复的那份应已删除）",
            searchResult.contains("private fun ResultGrid(")
        )
    }

    @Test
    fun `公共组件不依赖搜索概念`() {
        val grid = File("src/main/java/com/asmrone/app/ui/component/WorkResultGrid.kt").readText()
        assertFalse("组件不该认识搜索状态", grid.contains("SearchUiState"))
        assertFalse("组件不该认识搜索模式", grid.contains("SearchMode"))
        assertTrue("它只要数据与回调", grid.contains("works: List<Work>"))
        assertTrue(grid.contains("onLoadMore: () -> Unit"))
    }

    // ═══════════════ 计入搜索历史 ═══════════════

    @Test
    fun `★ 直达结果页时把这次检索记进历史`() {
        val body = functionBody(dictionaryViewModel, "fun start(")
        assertTrue(
            "这条路径同样算一次搜索，不记的话用户在历史里找不到它",
            body.contains("recordDictionarySearch(")
        )
    }

    @Test
    fun `★ 记历史时没有关键词（这类检索只有条件）`() {
        val sync = File("src/main/java/com/asmrone/app/data/repo/DictionarySync.kt").readText()
        val body = functionBody(sync, "fun recordDictionarySearch(")
        assertTrue(
            "★ 传了关键词会让历史显示成「条件 + 关键词」，而用户没输入过任何词",
            body.contains("query = \"\"")
        )
        assertTrue(body.contains("conditions = listOf(query.condition)"))
    }
}