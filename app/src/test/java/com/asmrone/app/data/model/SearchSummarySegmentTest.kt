package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索摘要的**展示格式**（结果页大标题 / 搜索历史共用）。
 *
 * 这一组钉住的是几条产品要求：
 *  - 不再出现「且」「且排除」这类连接词，片段之间只有一个空格；
 *  - 排除项写成 `-标签「X」` / `-词`，并且带上 `exclude = true`（界面据此画红）。
 */
class SearchSummarySegmentTest {

    private fun tag(name: String, exclude: Boolean = false, display: String = name) =
        SearchCondition(
            namespace = SearchNamespaces.TAG,
            id = "1",
            name = name,
            displayName = display,
            kindLabel = SearchNamespaces.TAG_LABEL,
            exclude = exclude
        )

    // ───────────────────────── 条件 ─────────────────────────

    @Test
    fun `包含的条件写成 类型「显示名」`() {
        val segment = SearchSummarySegment.ofCondition(tag("ASMR"))
        assertEquals("标签「ASMR」", segment.text)
        assertFalse(segment.exclude)
    }

    @Test
    fun `★ 排除的条件在类型前加横杠，并标记为排除`() {
        val segment = SearchSummarySegment.ofCondition(tag("Vtuber", exclude = true))
        assertEquals("-标签「Vtuber」", segment.text)
        assertTrue("界面要据此把它画成红色", segment.exclude)
    }

    @Test
    fun `声优与社团同样适用`() {
        val va = SearchCondition(
            SearchNamespaces.VOICE_ACTOR, "v1", "涼花みなせ", "涼花みなせ",
            SearchNamespaces.VOICE_ACTOR_LABEL, exclude = true
        )
        assertEquals("-声优「涼花みなせ」", SearchSummarySegment.ofCondition(va).text)

        val circle = SearchCondition(
            SearchNamespaces.CIRCLE, "c1", "Whisp", "Whisp",
            SearchNamespaces.CIRCLE_LABEL
        )
        assertEquals("社团「Whisp」", SearchSummarySegment.ofCondition(circle).text)
    }

    @Test
    fun `条件里显示的是译名，不是原始名`() {
        val segment = SearchSummarySegment.ofCondition(
            tag("环绕音", display = "双声道立体声/人头麦")
        )
        assertEquals("标签「双声道立体声/人头麦」", segment.text)
    }

    // ───────────────────────── 关键词 ─────────────────────────

    @Test
    fun `包含的关键词包一层「关键词」`() {
        val segment = SearchSummarySegment.ofKeyword("耳かき")!!
        assertEquals("关键词「耳かき」", segment.text)
        assertFalse(segment.exclude)
    }

    @Test
    fun `★ 排除的关键词写成 -词`() {
        val segment = SearchSummarySegment.ofKeyword("耳かき", exclude = true)!!
        assertEquals("-耳かき", segment.text)
        assertTrue(segment.exclude)
    }

    @Test
    fun `空白关键词不产生片段`() {
        assertNull(SearchSummarySegment.ofKeyword(""))
        assertNull(SearchSummarySegment.ofKeyword("   "))
    }

    // ───────────────────────── 拼接 ─────────────────────────

    @Test
    fun `★ 片段之间只有一个空格，没有「且」`() {
        val text = SearchSummarySegment.join(
            listOf(
                SearchSummarySegment.ofCondition(tag("ASMR")),
                SearchSummarySegment.ofCondition(tag("Vtuber", exclude = true)),
                SearchSummarySegment.ofKeyword("耳かき")!!
            )
        )
        assertEquals("标签「ASMR」 -标签「Vtuber」 关键词「耳かき」", text)
        assertFalse("用户明确要求去掉「且」", text.contains("且"))
        assertFalse("也不该再出现「排除」二字", text.contains("排除"))
    }
}
