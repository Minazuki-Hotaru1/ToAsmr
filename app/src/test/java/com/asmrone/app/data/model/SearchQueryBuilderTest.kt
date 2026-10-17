package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级搜索的检索串拼装。
 *
 * ## 这组测试钉住的是三条**实测出来的**服务端语法规则
 *
 * 对着 api.asmr-200.com 一条条试出来的。写错任何一条都会让检索**静默返回 0**
 * （服务端不报错、只是没结果），所以必须用测试钉住：
 *
 * 1. 条件之间**必须有空格** —— 粘在一起返回 0，加空格才有结果（730）
 * 2. 值用**名称**不用 id —— 传 uuid 返回 0
 * 3. 名称含空格时**不要加引号** —— 加引号返回 0，不加才有结果（345）
 *
 * Kotlin 里 $ 在字符串中是模板前缀，所以统一用 D 常量拼接。
 */
class SearchQueryBuilderTest {

    /** 一个 $；拼起来比到处写转义清爽 */
    private val D = "$"

    // displayName 默认与 name 相同；标签在真实数据里会是中文译名，
    // 但检索串只用 name，所以这里不必区分（要区分的场景见下面的专项测试）

    private fun tag(name: String, id: String = "497", displayName: String = name) =
        SearchCondition(
            SearchNamespaces.TAG, id, name, displayName, SearchNamespaces.TAG_LABEL
        )

    private fun va(name: String, id: String = "83a442aa-3662-5e17-aece-757bc3cb97cd") =
        SearchCondition(
            SearchNamespaces.VOICE_ACTOR, id, name, name, SearchNamespaces.VOICE_ACTOR_LABEL
        )

    private fun circle(name: String, id: String = "41088") =
        SearchCondition(
            SearchNamespaces.CIRCLE, id, name, name, SearchNamespaces.CIRCLE_LABEL
        )

    @Test
    fun `★ 检索串用原始名，不用界面上的译名`() {
        // 真实数据：标签 `环绕音` 的 zh-cn 译名是 `双声道立体声/人头麦`。
        // 界面上给用户看译名，但发给服务端的必须是原始名 —— 用译名会搜不到。
        val t = tag("环绕音", id = "496", displayName = "双声道立体声/人头麦")
        assertEquals("${D}tag:环绕音$D", SearchQueryBuilder.token(t))
        assertFalse(
            "译名不能出现在检索串里",
            SearchQueryBuilder.token(t).orEmpty().contains("双声道立体声")
        )
    }

    // ---------------------- 单条件 ----------------------

    @Test
    fun `单个标签条件拼成 tag 命名空间`() {
        assertEquals("${D}tag:ASMR$D", SearchQueryBuilder.token(tag("ASMR")))
    }

    @Test
    fun `单个声优条件拼成 va 命名空间`() {
        assertEquals("${D}va:N/A$D", SearchQueryBuilder.token(va("N/A")))
    }

    @Test
    fun `单个社团条件拼成 circle 命名空间`() {
        assertEquals("${D}circle:Whisp$D", SearchQueryBuilder.token(circle("Whisp")))
    }

    // ---------------------- 规则 1：空格分隔 ----------------------

    @Test
    fun `多个条件之间用空格分隔`() {
        val q = SearchQueryBuilder.build(listOf(tag("ASMR"), tag("Vtuber", id = "2")))
        assertEquals("${D}tag:ASMR$D ${D}tag:Vtuber$D", q)
    }

    @Test
    fun `两个条件绝不能粘在一起`() {
        val q = SearchQueryBuilder.build(listOf(tag("ASMR"), tag("Vtuber", id = "2")))
        assertTrue("两个条件之间必须有一个空格", q.contains(" "))
        assertFalse(
            "粘在一起服务端会返回 0：实测无空格 0、有空格 730",
            q == "${D}tag:ASMR$D${D}tag:Vtuber$D"
        )
    }

    @Test
    fun `跨类型多条件按加入顺序拼`() {
        val q = SearchQueryBuilder.build(
            listOf(tag("ASMR"), circle("Whisp"), tag("Vtuber", id = "2"))
        )
        assertEquals("${D}tag:ASMR$D ${D}circle:Whisp$D ${D}tag:Vtuber$D", q)
    }

    // ---------------------- 规则 2：用名称 ----------------------

    @Test
    fun `拼进去的是名称而不是 id`() {
        val q = SearchQueryBuilder.token(
            va("陽向葵ゅか", id = "83a442aa-3662-5e17-aece-757bc3cb97cd")
        )
        assertEquals("${D}va:陽向葵ゅか$D", q)
        assertFalse("id 不该出现在检索串里", q.orEmpty().contains("83a442aa"))
    }

    // ---------------------- 规则 3：不加引号 ----------------------

    @Test
    fun `名称含空格时不加引号`() {
        val q = SearchQueryBuilder.token(circle("ajaib suara"))
        assertEquals(
            "实测加引号返回 0、不加返回 345（网站前端那套加引号的做法在这里是错的，别照抄）",
            "${D}circle:ajaib suara$D",
            q
        )
        assertFalse("不能出现引号", q.orEmpty().contains("\""))
    }

    @Test
    fun `名称含空格的多条件里，空格只用来分隔条件`() {
        val q = SearchQueryBuilder.build(
            listOf(circle("ajaib suara"), circle("VOICE LOVER", id = "2"))
        )
        assertEquals("${D}circle:ajaib suara$D ${D}circle:VOICE LOVER$D", q)
    }

    // ---------------------- 与裸关键词混用 ----------------------

    @Test
    fun `条件与裸关键词混用`() {
        val q = SearchQueryBuilder.build(listOf(tag("ASMR")), "耳かき")
        assertEquals("${D}tag:ASMR$D 耳かき", q)
    }

    @Test
    fun `没有条件时只剩关键词`() {
        assertEquals("耳かき", SearchQueryBuilder.build(emptyList(), "耳かき"))
    }

    @Test
    fun `没有条件也没有关键词时是空串`() {
        assertEquals("", SearchQueryBuilder.build(emptyList(), ""))
        assertEquals("", SearchQueryBuilder.build(emptyList(), "   "))
    }

    @Test
    fun `关键词两端空白被去掉`() {
        assertEquals("耳かき", SearchQueryBuilder.build(emptyList(), "  耳かき  "))
    }

    // ---------------------- 非法输入 ----------------------

    @Test
    fun `名称为空的条件被跳过，其余照常`() {
        val q = SearchQueryBuilder.build(listOf(tag("ASMR"), tag("   ", id = "2")))
        assertEquals("${D}tag:ASMR$D", q)
    }

    @Test
    fun `名称为空时 token 返回 null`() {
        assertNull(SearchQueryBuilder.token(tag("")))
        assertNull(SearchQueryBuilder.token(tag("   ")))
    }

    @Test
    fun `命名空间为空时 token 返回 null`() {
        assertNull(SearchQueryBuilder.token(SearchCondition("", "1", "ASMR", "ASMR", "标签")))
    }

    // ------------------ 单条件优先走专用端点 ------------------

    @Test
    fun `单个标签条件可以走 tagId 端点`() {
        val f = SearchQueryBuilder.singleConditionEndpoint(listOf(tag("ASMR", id = "497")), "")
        assertEquals(497, f?.tagId)
        assertNull(f?.vaId)
        assertNull(f?.circleId)
    }

    @Test
    fun `单个声优条件可以走 vaId 端点`() {
        val f = SearchQueryBuilder.singleConditionEndpoint(listOf(va("N/A")), "")
        assertEquals("83a442aa-3662-5e17-aece-757bc3cb97cd", f?.vaId)
    }

    @Test
    fun `单个社团条件可以走 circleId 端点`() {
        val f = SearchQueryBuilder.singleConditionEndpoint(listOf(circle("Whisp")), "")
        assertEquals(41088, f?.circleId)
    }

    @Test
    fun `多个条件时不能走专用端点（端点只接受一个筛选）`() {
        assertNull(
            SearchQueryBuilder.singleConditionEndpoint(
                listOf(tag("ASMR"), circle("Whisp")),
                ""
            )
        )
    }

    @Test
    fun `有裸关键词时不能走专用端点`() {
        assertNull(SearchQueryBuilder.singleConditionEndpoint(listOf(tag("ASMR")), "耳かき"))
    }

    @Test
    fun `没有条件时不走端点`() {
        assertNull(SearchQueryBuilder.singleConditionEndpoint(emptyList(), ""))
    }

    @Test
    fun `id 不是整数时标签与社团条件退回检索串`() {
        assertNull(SearchQueryBuilder.singleConditionEndpoint(listOf(tag("ASMR", id = "abc")), ""))
        assertNull(SearchQueryBuilder.singleConditionEndpoint(listOf(circle("x", id = "abc")), ""))
    }

    @Test
    fun `age 这类没有专用端点的命名空间退回检索串`() {
        val age = SearchCondition(SearchNamespaces.AGE, "adult", "adult", "adult", "年龄")
        assertNull(SearchQueryBuilder.singleConditionEndpoint(listOf(age), ""))
        assertEquals("${D}age:adult$D", SearchQueryBuilder.token(age))
    }

    // ---------------------- 去重键 ----------------------

    @Test
    fun `去重键含命名空间，同名不同类型不算重复`() {
        val a = SearchCondition(SearchNamespaces.TAG, "1", "同名", "同名", "标签")
        val b = SearchCondition(SearchNamespaces.VOICE_ACTOR, "1", "同名", "同名", "声优")
        assertTrue(a.dedupeKey != b.dedupeKey)
    }

    @Test
    fun `同一个资源的重复加入去重键相同`() {
        assertEquals(tag("ASMR").dedupeKey, tag("ASMR").dedupeKey)
    }

    // ------------------ 高级搜索：每个输入栏各贡献什么 ------------------

    private fun slot(condition: SearchCondition? = null, text: String = "") =
        SearchQueryBuilder.SlotPart(condition, text)

    @Test
    fun `只有文字的栏贡献裸关键词`() {
        val q = SearchQueryBuilder.buildFromSlots(listOf(slot(text = "耳かき")))
        assertEquals("耳かき", q)
        assertFalse("裸文字不该被包成条件语法", q.contains("$D"))
    }

    @Test
    fun `选了条目的栏贡献条件语法`() {
        val q = SearchQueryBuilder.buildFromSlots(listOf(slot(condition = tag("ASMR"))))
        assertEquals("${D}tag:ASMR$D", q)
    }

    @Test
    fun `★ 文字与标签混搭：两栏各按各的写法`() {
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(slot(condition = tag("ASMR")), slot(text = "耳かき"))
        )
        assertEquals("${D}tag:ASMR$D 耳かき", q)
    }

    @Test
    fun `★ 多栏混合时按栏的先后输出`() {
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(
                slot(text = "耳かき"),
                slot(condition = tag("ASMR")),
                slot(condition = circle("Whisp")),
                slot(text = "添い寝")
            )
        )
        assertEquals(
            "顺序与用户看到的那一屏一致，排查时才对得上",
            "耳かき ${D}tag:ASMR$D ${D}circle:Whisp$D 添い寝",
            q
        )
    }

    @Test
    fun `空栏不贡献任何东西`() {
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(slot(), slot(condition = tag("ASMR")), slot(), slot(text = "   "))
        )
        assertEquals("${D}tag:ASMR$D", q)
    }

    @Test
    fun `全是空栏时是空串`() {
        assertEquals("", SearchQueryBuilder.buildFromSlots(listOf(slot(), slot())))
        assertEquals("", SearchQueryBuilder.buildFromSlots(emptyList()))
    }

    @Test
    fun `栏里的文字两端空白被去掉`() {
        val q = SearchQueryBuilder.buildFromSlots(listOf(slot(text = "  耳かき  ")))
        assertEquals("耳かき", q)
    }

    @Test
    fun `已选栏以条件优先，栏里的文字不重复计入`() {
        // 选中之后 query 会被设成该条目的显示名；不该既出条件又出裸文字
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(slot(condition = tag("ASMR"), text = "ASMR"))
        )
        assertEquals("${D}tag:ASMR$D", q)
    }

    @Test
    fun `名称为空的条件栏被跳过`() {
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(slot(condition = tag("   ")), slot(text = "耳かき"))
        )
        assertEquals("耳かき", q)
    }

    @Test
    fun `多个纯文字栏各自成为关键词`() {
        val q = SearchQueryBuilder.buildFromSlots(
            listOf(slot(text = "耳かき"), slot(text = "添い寝"))
        )
        assertEquals("耳かき 添い寝", q)
    }
}