package com.asmrone.app.ui.screen.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「点搜索历史」的行为守卫。
 *
 * 两种形态的期望完全不同，而且都不体现在类型上，所以用源码级断言钉住：
 *
 * | 形态 | 期望 |
 * |---|---|
 * | **普通搜索** | 直接去结果页 —— **不动输入框**（没有"先填进去再搜"这一步） |
 * | **高级搜索** | 文字与条件各归各位地填好，**不立刻搜**，由用户决定何时搜 |
 *
 * 另外这里还守着那个"同一件事显示成两份"的 bug：
 * 老历史把检索串存在 `query` 里，重放时它会多占一栏纯文本、字面显示
 * `$tag:ASMR$` —— 于是用户看到"一栏胶囊 + 一栏语法"。
 */
class SearchReplayGuardTest {

    private val viewModel by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/search/SearchViewModel.kt").readText()
    }

    /** 取出某个函数的函数体（大括号配对） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val braceStart = source.indexOf('{', start)
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

    private val replay by lazy { functionBody(viewModel, "fun replay(entry: SearchHistoryEntry)") }

    // ═══════════════ 普通搜索：直接搜，不动输入框 ═══════════════

    @Test
    fun `★ 普通搜索的分支里不写 query`() {
        val simpleBranch = replay
            .substringAfter("if (_state.value.mode == SearchMode.SIMPLE) {")
            .substringBefore("// ── 高级搜索")
        assertFalse(
            "★ 写 query 就等于「把内容放到输入框中」——正是要避免的那一步",
            simpleBranch.contains("query =")
        )
    }

    @Test
    fun `★ 普通搜索直接发起检索`() {
        val simpleBranch = replay
            .substringAfter("if (_state.value.mode == SearchMode.SIMPLE) {")
            .substringBefore("// ── 高级搜索")
        assertTrue(
            "要直接搜（而不是只填好等用户点）",
            simpleBranch.contains("startSearch(")
        )
    }

    @Test
    fun `★ 普通搜索重放必须把条件拼回检索串`() {
        // 这个 bug 的表现：在普通搜索页点一条"多个标签"的历史，结果是全库。
        // 根因是只把裸文字当关键词发出去，conditions 根本没进检索串。
        val simpleBranch = replay
            .substringAfter("if (_state.value.mode == SearchMode.SIMPLE) {")
            .substringBefore("// ── 高级搜索")
        assertTrue(
            "要用 SearchQueryBuilder.build(conditions, cleanQuery) 拼出完整检索串",
            simpleBranch.contains("SearchQueryBuilder.build(conditions, cleanQuery)")
        )
        assertTrue(
            "发出去的关键词必须是拼好的那一份（单条件可留空走 id 端点）",
            simpleBranch.contains("keyword = if (useEndpoint) \"\" else builtQuery")
        )
    }

    @Test
    fun `★ 重放历史不再记一次历史`() {
        assertTrue(
            "record = false：那条本来就来自历史，再记一次只会把它重复提到最前",
            replay.contains("record = false")
        )
    }

    // ═══════════════ 高级搜索：填好但不搜 ═══════════════

    @Test
    fun `★ 高级搜索只填栏位并返回 false`() {
        val advancedTail = replay.substringAfter("// ── 高级搜索")
        assertTrue("要填 slots", advancedTail.contains("slots ="))
        assertFalse(
            "★ 高级搜索分支里不能有 startSearch —— 那会让用户一点历史就跳走",
            advancedTail.contains("startSearch(")
        )
        assertTrue("返回值表示没搜", replay.contains("return false"))
    }

    @Test
    fun `高级搜索仍然保留末尾多留一个空栏`() {
        assertTrue(
            "否则重放一条每栏都填满的记录之后，界面没有可以打字的地方",
            replay.contains("withFreshIds(filled + InputSlotState())")
        )
    }

    // ═══════════════ 剥掉检索串语法 ═══════════════

    @Test
    fun `★ 重放前要剥掉老历史里的检索串语法`() {
        // 语法剥离与"重复片段"清理都收进了 SearchHistoryEntry.replayText，
        // 重放只读这一处 —— 否则展示与重放会各剥各的、口径不一。
        assertTrue(
            "重放要用清理过的自由文字",
            replay.contains("val cleanQuery = entry.replayText")
        )
        val entrySource = File("src/main/java/com/asmrone/app/data/prefs/SearchHistoryStore.kt")
            .readText()
        assertTrue(
            "replayText 要剥掉检索语法",
            entrySource.contains("SearchQueryBuilder.stripConditionSyntax(query)")
        )
        assertTrue(
            "能还原成条件的还要还原出来（老历史）",
            replay.contains("SearchQueryBuilder.parseConditions(entry.query)")
        )
    }

    @Test
    fun `★ 历史的自由文字里不能混进已选标签的名字`() {
        // 这个 bug 的表现：高级搜索后，历史显示「标签「ASMR」 关键词「ASMR」」。
        // 根因是选中标签后栏里回显的译名被当成关键词又存了一份。
        val inputs = functionBody(viewModel, "private fun searchInputs(")
        assertTrue(
            "存历史时只取**没选中条件**的栏",
            inputs.contains(".filter { it.selected == null }")
        )
    }

    @Test
    fun `★ 只有语法的 query 不会被填进关键词栏`() {
        // cleanQuery 为空时不 add 一个文字栏
        assertTrue(
            "要用 takeIf { it.isNotEmpty() } 挡住空串",
            replay.contains("cleanQuery.takeIf { it.isNotEmpty() }?.let")
        )
    }

    @Test
    fun `条件以结构化字段为准，老历史才用还原结果`() {
        assertTrue(replay.contains("entry.conditions.ifEmpty { restored }"))
    }

    // ═══════════════ startSearch 与 runSearch 的分工 ═══════════════

    @Test
    fun `★ runSearch 只做一件事：读当前输入并搜`() {
        val body = functionBody(viewModel, "private fun runSearch()")
        assertTrue(body.contains("searchInputs()"))
        assertTrue(body.contains("startSearch("))
    }

    @Test
    fun `★ startSearch 接受外部给定的检索内容`() {
        assertTrue(
            "要能搜「界面上没写着的东西」，否则点历史只能先填输入框",
            viewModel.contains("private fun startSearch(")
        )
        val body = functionBody(viewModel, "private fun startSearch(")
        // 注意 functionBody 从 `{` 开始，**不含参数表**；
        // 而参数恰恰是这条测试要断言的东西，所以从中截到函数体结尾再往前接上签名
        val declaration = viewModel.substring(
            viewModel.indexOf("private fun startSearch("),
            viewModel.indexOf("private fun startSearch(") + body.length + 120
        )
        assertTrue("条件由参数给", declaration.contains("conditions: List<SearchCondition>"))
        assertTrue("关键词由参数给", declaration.contains("keyword: String"))
        assertTrue("要能选择不记历史", declaration.contains("record: Boolean = true"))
        assertTrue("record 为假时不写历史", body.contains("if (record) {"))
    }
}