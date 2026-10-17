package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索串语法的**剥离与还原**。
 *
 * 检索串（`$tag:ASMR$ $tag:催眠$`）是发请求用的，**不是给人看的**。
 * 一旦它混进"裸关键词"那条通道，就会以字面形态出现在界面上 ——
 * 而这正是要修的 bug：
 *
 * ```
 * 高级搜索里点一条带标签的历史
 *   → 上方出现两个输入栏：
 *       ① [标签] ASMR        ← 正确的条件栏
 *       ② $tag:ASMR$         ← 纯文本，用户看不懂的语法
 * ```
 *
 * 根因是早期版本把检索串存进了历史的 `query` 字段，重放时它被当成裸关键词。
 */
class ConditionSyntaxSanitizerTest {

    private val D = "$"

    private fun label(ns: String) = when (ns) {
        SearchNamespaces.TAG -> SearchNamespaces.TAG_LABEL
        SearchNamespaces.VOICE_ACTOR -> SearchNamespaces.VOICE_ACTOR_LABEL
        SearchNamespaces.CIRCLE -> SearchNamespaces.CIRCLE_LABEL
        else -> ns
    }

    // ═══════════════ 剥离 ═══════════════

    @Test
    fun `★ 纯条件语法剥离之后是空的`() {
        assertEquals("", SearchQueryBuilder.stripConditionSyntax("${D}tag:ASMR$D"))
        assertEquals(
            "多个条件也一样",
            "",
            SearchQueryBuilder.stripConditionSyntax("${D}tag:ASMR$D ${D}tag:催眠$D")
        )
    }

    @Test
    fun `★ 文字加条件的组合只留下文字`() {
        assertEquals(
            "耳かき",
            SearchQueryBuilder.stripConditionSyntax("${D}tag:ASMR$D 耳かき")
        )
        assertEquals(
            "耳かき 添い寝",
            SearchQueryBuilder.stripConditionSyntax("耳かき ${D}tag:ASMR$D 添い寝")
        )
    }

    @Test
    fun `裸关键词原样保留`() {
        assertEquals("耳かき", SearchQueryBuilder.stripConditionSyntax("耳かき"))
        assertEquals("耳かき 添い寝", SearchQueryBuilder.stripConditionSyntax("耳かき 添い寝"))
    }

    @Test
    fun `非 tag 的命名空间也剥掉`() {
        assertEquals("", SearchQueryBuilder.stripConditionSyntax("${D}va:某人$D"))
        assertEquals("", SearchQueryBuilder.stripConditionSyntax("${D}circle:Whisp$D"))
        assertEquals("", SearchQueryBuilder.stripConditionSyntax("${D}age:adult$D"))
    }

    @Test
    fun `排除语法同样剥掉`() {
        assertEquals("", SearchQueryBuilder.stripConditionSyntax("${D}-tag:催眠$D"))
    }

    @Test
    fun `条件名里的斜杠不影响剥离`() {
        assertEquals(
            "",
            SearchQueryBuilder.stripConditionSyntax("${D}tag:纯爱/甜蜜$D")
        )
    }

    @Test
    fun `剥离后多余的空白被收敛`() {
        assertEquals(
            "耳かき",
            SearchQueryBuilder.stripConditionSyntax("  ${D}tag:ASMR$D   耳かき   ")
        )
    }

    @Test
    fun `不是合法条件语法的美元符号原样保留`() {
        // 用户可能真的在搜含 $ 的词；不该被误当成语法吃掉
        assertEquals("a${D}b", SearchQueryBuilder.stripConditionSyntax("a${D}b"))
        assertEquals("${D}tag:ASMR", SearchQueryBuilder.stripConditionSyntax("${D}tag:ASMR"))
    }

    // ═══════════════ 判定 ═══════════════

    @Test
    fun `★ 只有语法的文本被判为该丢弃`() {
        assertTrue(SearchQueryBuilder.isOnlyConditionSyntax("${D}tag:ASMR$D"))
        assertTrue(SearchQueryBuilder.isOnlyConditionSyntax("${D}tag:ASMR$D ${D}tag:催眠$D"))
        assertFalse(
            "含真实文字时不能丢，那是用户的关键词",
            SearchQueryBuilder.isOnlyConditionSyntax("${D}tag:ASMR$D 耳かき")
        )
        assertFalse(SearchQueryBuilder.isOnlyConditionSyntax("耳かき"))
        assertFalse("空串不算「只有语法」", SearchQueryBuilder.isOnlyConditionSyntax(""))
    }

    // ═══════════════ 还原成条件 ═══════════════

    @Test
    fun `★ 老历史里的检索串能还原成结构化条件`() {
        val restored = SearchQueryBuilder.parseConditions(
            "${D}tag:ASMR$D ${D}tag:催眠$D"
        ) { label(it) }

        assertEquals(2, restored.size)
        assertEquals(SearchNamespaces.TAG, restored[0].namespace)
        assertEquals("ASMR", restored[0].name)
        assertEquals(SearchNamespaces.TAG_LABEL, restored[0].kindLabel)
        assertEquals("催眠", restored[1].name)
    }

    @Test
    fun `还原时能识别不同类型的命名空间`() {
        val restored = SearchQueryBuilder.parseConditions(
            "${D}tag:ASMR$D ${D}circle:Whisp$D ${D}va:某人$D"
        ) { label(it) }

        assertEquals(
            listOf(
                SearchNamespaces.TAG_LABEL,
                SearchNamespaces.CIRCLE_LABEL,
                SearchNamespaces.VOICE_ACTOR_LABEL
            ),
            restored.map { it.kindLabel }
        )
    }

    @Test
    fun `还原出来的显示名是原始名，绝不会是语法本身`() {
        val restored = SearchQueryBuilder.parseConditions("${D}tag:ASMR$D") { label(it) }
        val c = restored.single()
        assertEquals("ASMR", c.name)
        assertEquals("ASMR", c.displayName)
        assertFalse("★ 显示名里绝不能出现美元符号", c.displayName.contains(D))
    }

    @Test
    fun `老历史只有文字时还原不出条件`() {
        assertTrue(SearchQueryBuilder.parseConditions("耳かき") { label(it) }.isEmpty())
    }

    @Test
    fun `老历史是文字加条件时两条都能拿到`() {
        val text = "${D}tag:ASMR$D 耳かき"
        val restored = SearchQueryBuilder.parseConditions(text) { label(it) }
        assertEquals(1, restored.size)
        assertEquals("ASMR", restored.single().name)
        assertEquals("耳かき", SearchQueryBuilder.stripConditionSyntax(text))
    }

    @Test
    fun `坏语法被跳过而不是崩`() {
        // 逐个列出坏法，避免拼串时把两个片段粘成一个合法的
        val missingClosing = "${D}tag:ASMR"        // 没有结尾 $
        val emptyNamespace = "${D}:x$D"            // 没有命名空间
        val emptyValue = "${D}tag:$D"              // 没有值
        val digitNamespace = "${D}1bad:x$D"        // 命名空间含数字
        val good = "${D}tag:好的$D"

        listOf(missingClosing, emptyNamespace, emptyValue, digitNamespace).forEach { bad ->
            assertTrue(
                "坏语法不该被还原成条件：$bad",
                SearchQueryBuilder.parseConditions(bad) { label(it) }.isEmpty()
            )
        }
        assertEquals(
            "合法的那个仍然要还原出来",
            "好的",
            SearchQueryBuilder.parseConditions(good) { label(it) }.single().name
        )
    }

    // ═══════════════ 说明那个 bug 的形状 ═══════════════

    @Test
    fun `★ 修复前的错法：把检索串当裸关键词留下的就是字面语法`() {
        // 这条测试记录 bug 的形状：如果把 entry.query（含语法）直接当裸关键词，
        // 界面上就会出现一栏写着 `$tag:ASMR$` 的纯文本
        val legacyQuery = "${D}tag:ASMR$D"
        assertTrue(
            "未经处理的文本里确实含有 $ 语法 —— 这就是用户看到的第二个输入栏",
            legacyQuery.contains("${D}tag:")
        )
        assertEquals(
            "剥掉之后它变成空串，于是不会再占一个关键词栏",
            "",
            SearchQueryBuilder.stripConditionSyntax(legacyQuery)
        )
    }
}