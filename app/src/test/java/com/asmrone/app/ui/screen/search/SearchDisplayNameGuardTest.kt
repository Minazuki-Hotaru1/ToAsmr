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
        val body = functionBody(searchScreen, "private fun SuggestionList(")
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
        val body = functionBody(searchScreen, "private fun SlotField(")
        assertTrue(
            "胶囊是用户刚点出来的，必须与候选上看到的名字一致",
            body.contains("text = selected.displayName")
        )
        assertFalse(body.contains("text = selected.name"))
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
        val state = functionBody(searchViewModel, "val description: String")
        assertTrue(
            "摘要里出现原始名，用户会以为搜的是另一个标签",
            state.contains("selected.displayName")
        )
        assertFalse(state.contains("selected.name"))
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
