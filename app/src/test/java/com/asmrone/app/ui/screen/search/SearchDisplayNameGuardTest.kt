package com.asmrone.app.ui.screen.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「界面显示的是哪个名字」的源码级守卫。
 *
 * ## 为什么需要这种测试
 *
 * 曾经出现过一个**编译与单测都发现不了**的矛盾：
 *
 * ```
 * 搜「亲热/甜蜜」→ 候选里跳出「纯爱/甜蜜」（那是原始名）
 *              → 点进去之后结果页又显示「亲热/甜蜜」
 * ```
 *
 * 根因是渲染时用了 `SuggestionItem.name`（发给服务端的原始名）而不是
 * `displayName`（中文译名）。这类错误：
 *
 *  - **编译通过** —— 两个字段都是 `String`
 *  - **单测通过** —— 数据层的 `TagNameResolverTest` 全绿，
 *    因为算译名的那段逻辑本身没错，错的是**没人去读它**
 *  - 只有真机上肉眼看才能发现
 *
 * 所以这里直接断言源码：渲染候选与回显已选项时必须用 `displayName`。
 * 与 `CollectionSyncGuardTest` 同一套路数 —— 那一组守的是"同步口径"，
 * 这一组守的是"显示口径"。
 */
class SearchDisplayNameGuardTest {

    private val sourceDir = File("src/main/java/com/asmrone/app")

    private fun read(relative: String): String {
        val f = File(sourceDir, relative)
        assertTrue("找不到源文件：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** 取出某个函数的函数体（靠大括号配对），避免匹配到别的函数 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val braceStart = source.indexOf('{', start)
        assertTrue("函数没有函数体：$signature", braceStart >= 0)

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
        error("大括号不配对：$signature")
    }

    private val searchScreen by lazy { read("ui/screen/search/SearchScreen.kt") }
    private val searchViewModel by lazy { read("ui/screen/search/SearchViewModel.kt") }

    // ═══════════════ 候选列表 ═══════════════

    @Test
    fun `★ 候选行显示的是 displayName 而不是 name`() {
        val body = functionBody(searchScreen, "private fun SuggestionDropdown(")
        assertTrue(
            "候选行必须用 displayName（译名）渲染",
            body.contains("text = item.displayName")
        )
        assertFalse(
            "★ 用 item.name 渲染候选就是那个矛盾的来源：搜译名却看到原始名",
            body.contains("text = item.name")
        )
    }

    @Test
    fun `★ 已选中的输入栏胶囊显示的也是 displayName`() {
        // 条件行把 displayName 交给胶囊；胶囊只负责排版，不能自己去读 name
        val row = functionBody(searchScreen, "private fun ConditionRow(")
        assertTrue(
            "胶囊是用户刚点出来的，必须与候选上看到的名字一致",
            row.contains("label = selected.displayName")
        )
        assertFalse(row.contains("label = selected.name"))

        val chip = functionBody(searchScreen, "private fun SelectedTagChip(")
        assertFalse("胶囊里不该出现原始名", chip.contains(".name"))
    }

    @Test
    fun `★ 条件行必须有包含排除与删除按钮`() {
        // 用户明确要求：高级搜索的条件行要像 comicApp 一样有「包含/排除」与「删除」
        val row = functionBody(searchScreen, "private fun ConditionRow(")
        assertTrue("缺少包含/排除 chip", row.contains("AsmrFilterChip("))
        assertTrue("缺少删除按钮", row.contains("ic_delete_fluent"))
    }

    @Test
    fun `★ 高级搜索的输入框提示统一为「搜索关键词」`() {
        // 用户要求：高级搜索里的每一栏都用同一句提示，不再写「第 N 栏：…」
        assertTrue(
            "提示统一为「搜索关键词」",
            searchScreen.contains("placeholder = \"搜索关键词\"")
        )
        assertFalse(
            "不该再有「第 N 栏：…」这种说明",
            searchScreen.contains("第 \${index + 1} 栏")
        )
    }

    @Test
    fun `★ 高级搜索的输入框与普通搜索一样高`() {
        // FilterChip 默认会把布局撑到 48dp，行高就比普通搜索的 40dp 输入框高；
        // 关掉最小触控尺寸后，行高由输入框决定。
        val row = functionBody(searchScreen, "private fun ConditionRow(")
        assertTrue(
            "把最小触控尺寸设成 40dp（否则 Material 会撑到 48dp，行高比普通搜索高）",
            row.contains("LocalMinimumInteractiveComponentSize provides 40.dp")
        )
    }

    @Test
    fun `★ 结果数量放在列表第一行，而不是顶栏副标题`() {
        val result = read("ui/screen/search/SearchResultScreen.kt")
        assertTrue("数量交给列表首行", result.contains("headerText ="))
        assertFalse(
            "顶栏不再挂数量副标题",
            result.contains("subtitle = if (state.totalCount")
        )
        val grid = read("ui/component/WorkResultGrid.kt")
        assertTrue("公共列表要支持首行说明", grid.contains("headerText: String?"))
    }

    // ═══════════════ 选中之后回显 ═══════════════

    @Test
    fun `★ 选中候选后栏里回显 displayName`() {
        val body = functionBody(searchViewModel, "fun selectSuggestion(")
        assertTrue(
            "回显原始名会让「我点的那个」与「栏里留下的」对不上",
            body.contains("query = item.displayName")
        )
        assertFalse(body.contains("query = item.name"))
    }

    @Test
    fun `★ 重放历史时栏里回显 displayName`() {
        val body = functionBody(searchViewModel, "fun replay(entry: SearchHistoryEntry)")
        assertTrue(
            "高级搜索重放时，条件栏回显的必须是译名（与结果页副标题一致）",
            body.contains("query = condition.displayName")
        )
        assertFalse(body.contains("query = condition.name"))
    }

    // ═══════════════ 摘要 ═══════════════

    @Test
    fun `★ 结果页与历史的摘要用 displayName`() {
        // 摘要现在由分段拼成：VM 只把 slot 交给 SearchSummarySegment，
        // 真正取名字的那一步在 ofCondition 里 —— 两处都要守。
        val segments = functionBody(searchViewModel, "val descriptionSegments")
        assertTrue(
            "摘要里出现原始名，用户会以为搜的是另一个标签",
            segments.contains("SearchSummarySegment.ofCondition")
        )
        assertFalse(segments.contains("selected.name"))

        // SearchSummary.kt 是一个只负责"怎么拼文本"的小文件，整文件断言更稳
        // （ofCondition 是表达式函数体，没有大括号可配对）
        val summary = read("data/model/SearchSummary.kt")
        assertTrue(summary.contains("condition.displayName"))
        assertFalse(summary.contains("condition.name"))
    }

    @Test
    fun `★ 排除项以 - 开头，界面据此画红`() {
        val summary = read("data/model/SearchSummary.kt")
        assertTrue("排除项要加前缀横杠", summary.contains("if (condition.exclude) \"-\""))
        assertTrue(
            "分段要带上 exclude，界面才知道该画红",
            summary.contains("exclude = condition.exclude")
        )
    }

    @Test
    fun `检索串仍然用 name —— 语法认原始名`() {
        // 这一条是反向守卫：上面几条都在推"显示用 displayName"，
        // 但**发给服务端的必须还是原始名**，别一起改过头了
        val token = functionBody(read("data/model/SearchCondition.kt"), "fun token(condition: SearchCondition)")
        assertTrue("检索串必须用 name", token.contains("condition.name"))
    }

    @Test
    fun `条件持久化时两个名字都存下来`() {
        val json = functionBody(read("data/model/SearchCondition.kt"), "fun toJson(): org.json.JSONObject")
        assertTrue("原始名要存（检索串用）", json.contains("\"name\""))
        assertTrue("显示名要存（历史里回显用）", json.contains("\"display\""))
    }
}
