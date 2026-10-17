package com.asmrone.app.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **建标签索引**：一次性决定每个标签显示什么、用什么检索、能用哪些别名匹配。
 *
 * ## 为什么要测这个而不是测"显示名解析"
 *
 * 上一版是"原始条目 + 运行时解析"：每个显示点各自调 `TagNameResolver.displayName()`
 * 去算该显示哪个名字，于是出现同一个标签在两处显示成两个名字
 * （候选显示 `纯爱/甜蜜`、结果页显示 `亲热/甜蜜`）—— 因为**有人忘了调**。
 *
 * 现在 [TagCatalog.build] 是唯一一次"选择"，结果落在 [Tag.name] 上。
 * 所以这组测试钉的是**索引里的字段本身**：只要索引对，消费点就不可能显示错。
 *
 * 用的都是线上真实数据（2026-10，共 422 个标签）。
 */
class TagCatalogTest {

    /** 一个 $ —— Kotlin 字符串模板里要拼一下 */
    private val D = "$"


    private fun raw(
        id: Int,
        name: String,
        zhName: String? = null,
        zhCensored: String? = null,
        history: List<String> = emptyList(),
        count: Int = 0
    ) = SearchTag(
        id = id,
        name = name,
        i18n = mapOf(
            TagCatalog.LOCALE_ZH_CN to TagLocaleNames(zhName, zhCensored, history)
        ),
        count = count
    )

    // ═══════════════ 显示名：索引里就定下来 ═══════════════

    @Test
    fun `★ 有译名时索引里的 name 就是译名`() {
        val t = TagCatalog.build(raw(496, "环绕音", zhName = "双声道立体声/人头麦", count = 19779))
        assertEquals("双声道立体声/人头麦", t.name)
        assertEquals(19779, t.count)
    }

    @Test
    fun `★ 原始名另存 queryName，不在 name 里露面`() {
        val t = TagCatalog.build(raw(496, "环绕音", zhName = "双声道立体声/人头麦"))
        assertEquals("环绕音", t.queryName)
        assertFalse("显示名里不该混进原始名", t.name == t.queryName)
    }

    @Test
    fun `译名与原始名相同时两者一致`() {
        val t = TagCatalog.build(raw(157, "催眠", zhName = "催眠", count = 2058))
        assertEquals("催眠", t.name)
        assertEquals("催眠", t.queryName)
    }

    @Test
    fun `★ zh-cn 缺失时回落到原始名`() {
        // 实测这类有 26 个：AI / 女性向 / 大叔 / 無理矢理 / 変態 ……
        val ai = TagCatalog.build(raw(10000, "AI", zhName = null, count = 10775))
        assertEquals("AI", ai.name)

        val muri = TagCatalog.build(raw(307, "無理矢理", zhName = null, count = 31))
        assertEquals("無理矢理", muri.name)
    }

    @Test
    fun `译名为纯空白时回落原始名`() {
        // build 里用 takeIf { it.isNotBlank() } 判过，所以"看起来有值但都是空格"
        // 等同于没有译名 —— 直接用它当显示名会得到一片空白
        assertEquals("原名", TagCatalog.build(raw(1, "原名", zhName = "   ")).name)
        assertEquals("原名", TagCatalog.build(raw(2, "原名", zhName = "")).name)
        assertEquals("原名", TagCatalog.build(raw(3, "原名", zhName = null)).name)
    }

    // ═══════════════ 那个真实矛盾的复现 ═══════════════

    @Test
    fun `★ 索引建成之后，搜亲热看到的就只有亲热甜蜜`() {
        // 真实数据：id=4 原始名「纯爱/甜蜜」，译名「亲热/甜蜜」，16087 部作品
        val t = TagCatalog.build(
            raw(4, "纯爱/甜蜜", zhName = "亲热/甜蜜", history = listOf("纯爱/甜蜜"), count = 16087)
        )
        assertEquals("亲热/甜蜜", t.name)
        assertEquals("纯爱/甜蜜", t.queryName)
        // 这是关键：显示名里再也不会出现「纯爱」
        assertFalse(t.name.contains("纯爱"))
    }

    @Test
    fun `★ 检索用的是原始名 —— 用译名服务端搜不到`() {
        val t = TagCatalog.build(raw(4, "纯爱/甜蜜", zhName = "亲热/甜蜜"))
        assertEquals("${D}tag:纯爱/甜蜜$D", SearchQueryBuilder.token(t.toCondition()))
    }

    // ═══════════════ 匹配别名 ═══════════════

    @Test
    fun `匹配别名含原始名与译名 —— 两种写法都能搜到`() {
        val t = TagCatalog.build(raw(496, "环绕音", zhName = "双声道立体声/人头麦"))
        assertTrue(t.matches("环绕音"))
        assertTrue(t.matches("双声道"))
        assertTrue(t.matches("人头麦"))
    }

    @Test
    fun `匹配别名含和谐名`() {
        val t = TagCatalog.build(raw(157, "催眠", zhName = "催眠", zhCensored = "暗示"))
        assertTrue("搜「暗示」也该找到这个标签", t.matches("暗示"))
        assertTrue(t.matches("催眠"))
    }

    @Test
    fun `★ 匹配别名不含旧名（否则会把语义不同的标签混在一起）`() {
        // 真实数据的坑：
        //   id=32  count=2886   name='纯爱'        zh-cn='纯爱'
        //   id=4   count=16087  name='纯爱/甜蜜'   zh-cn='亲热/甜蜜'   旧名='纯爱/甜蜜'
        // id=4 的 history 里出现「纯爱/甜蜜」只是因为它改过显示名。
        // 把旧名纳入匹配会让搜「纯爱」同时命中这两个语义不同的标签。
        val t = TagCatalog.build(
            raw(4, "纯爱/甜蜜", zhName = "亲热/甜蜜", history = listOf("纯爱/甜蜜"))
        )
        assertFalse(
            "旧名是「同一个标签的旧称呼」，不是「另一个标签的别名」",
            t.matchNames.contains("纯爱")
        )
    }

    @Test
    fun `匹配别名去重`() {
        val t = TagCatalog.build(raw(128, "内射/中出", zhName = "内射/中出"))
        assertEquals(listOf("内射/中出"), t.matchNames)
    }

    @Test
    fun `匹配大小写不敏感`() {
        val t = TagCatalog.build(raw(1, "ASMR", zhName = "ASMR"))
        assertTrue(t.matches("asmr"))
        assertTrue(t.matches("ASMR"))
    }

    @Test
    fun `空查询不匹配任何东西`() {
        val t = TagCatalog.build(raw(1, "ASMR", zhName = "ASMR"))
        assertFalse(t.matches(""))
        assertFalse(t.matches("   "))
    }

    // ═══════════════ 和谐名 ═══════════════

    @Test
    fun `没有和谐名时 censoredDisplayName 就是 name`() {
        val t = TagCatalog.build(raw(1, "ASMR", zhName = "ASMR"))
        assertEquals("ASMR", t.censoredDisplayName)
        assertEquals("", t.censoredName)
    }

    @Test
    fun `有和谐名时 censoredDisplayName 是和谐名`() {
        val t = TagCatalog.build(raw(157, "催眠", zhName = "催眠", zhCensored = "暗示"))
        assertEquals("催眠", t.name)
        assertEquals("暗示", t.censoredName)
        assertEquals("暗示", t.censoredDisplayName)
    }

    @Test
    fun `★ censoredDisplayName 永远是可直接显示的名字`() {
        // 消费点不需要再判断"有没有和谐名"—— 这又是一个会被忘记的分支，
        // 所以在索引层面就消掉
        val withCensored = TagCatalog.build(raw(1, "a", zhName = "A", zhCensored = "B"))
        val withoutCensored = TagCatalog.build(raw(2, "c", zhName = "C"))
        assertEquals("B", withCensored.censoredDisplayName)
        assertEquals("C", withoutCensored.censoredDisplayName)
    }

    // ═══════════════ 批量建索引 ═══════════════

    @Test
    fun `批量建索引保持顺序与数量`() {
        val tags = TagCatalog.build(
            listOf(
                raw(1, "a", zhName = "甲", count = 3),
                raw(2, "b", zhName = null, count = 2),
                raw(3, "c", zhName = "丙", count = 1)
            )
        )
        assertEquals(3, tags.size)
        assertEquals(listOf("甲", "b", "丙"), tags.map { it.name })
    }

    @Test
    fun `★ label 用的是索引里的显示名`() {
        val t = TagCatalog.build(raw(496, "环绕音", zhName = "双声道立体声/人头麦", count = 19779))
        assertEquals("双声道立体声/人头麦 (19779)", t.label)
    }

    // ═══════════════ 索引的存取往返 ═══════════════

    @Test
    fun `★ 索引落盘往返之后字段一个不少`() {
        val original = TagCatalog.build(
            raw(157, "催眠", zhName = "催眠", zhCensored = "暗示", count = 2058)
        )
        val back = Tag.fromJson(JSONObject(original.toJson().toString()))!!

        assertEquals(original.id, back.id)
        assertEquals(original.name, back.name)
        assertEquals(original.queryName, back.queryName)
        assertEquals(original.censoredName, back.censoredName)
        assertEquals(original.count, back.count)
        assertEquals(original.matchNames, back.matchNames)
        // 读回之后**不需要再解析**：显示名就在 name 里
        assertEquals("催眠", back.name)
        assertEquals("暗示", back.censoredDisplayName)
    }

    @Test
    fun `落盘往返不改变 label`() {
        val original = TagCatalog.build(raw(4, "纯爱/甜蜜", zhName = "亲热/甜蜜", count = 16087))
        val back = Tag.fromJson(JSONObject(original.toJson().toString()))!!
        assertEquals(original.label, back.label)
        assertEquals("亲热/甜蜜 (16087)", back.label)
    }

    // ═══════════════ 上游 JSON 解析 ═══════════════

    @Test
    fun `上游条目解析后能建出正确的索引`() {
        val json = JSONObject(
            """
            {"id":1,"name":"ABO","count":100,
             "i18n":{"zh-cn":{"name":"ABO世界观"},
                     "en-us":{"name":"ABO"},
                     "ja-jp":{"name":"ABO"}}}
            """.trimIndent()
        )
        val built = TagCatalog.build(SearchTag.fromJson(json)!!)
        assertEquals("ABO世界观", built.name)
        assertEquals("ABO", built.queryName)
        assertTrue(built.matches("ABO"))
        assertTrue(built.matches("世界观"))
    }

    @Test
    fun `上游条目带 history 时不会污染匹配别名`() {
        val json = JSONObject(
            """
            {"id":2,"name":"旧名","count":5,
             "i18n":{"zh-cn":{"name":"新名","history":[{"name":"更旧的"}]}}}
            """.trimIndent()
        )
        val built = TagCatalog.build(SearchTag.fromJson(json)!!)
        assertEquals("新名", built.name)
        assertFalse(built.matchNames.contains("更旧的"))
    }

    @Test
    fun `缺 id 或 name 的上游条目被拒绝`() {
        assertNull(SearchTag.fromJson(JSONObject("""{"name":"x"}""")))
        assertNull(SearchTag.fromJson(JSONObject("""{"id":1}""")))
        assertNull(SearchTag.fromJson(JSONObject("""{"id":1,"name":"  "}""")))
    }

    @Test
    fun `只有 id 与 name 的最小条目也能建索引`() {
        val built = TagCatalog.build(SearchTag.fromJson(JSONObject("""{"id":9,"name":"测试","count":1}"""))!!)
        assertEquals("测试", built.name)
        assertEquals("测试", built.queryName)
        assertEquals(listOf("测试"), built.matchNames)
    }

    // ═══════════════ 老索引的兜底 ═══════════════

    @Test
    fun `老索引缺 query 字段时回落 name`() {
        val back = Tag.fromJson(JSONObject("""{"id":1,"name":"旧格式","count":3}"""))!!
        assertEquals("旧格式", back.queryName)
    }

    @Test
    fun `老索引缺 match 字段时至少能用两个名字匹配`() {
        val back = Tag.fromJson(JSONObject("""{"id":1,"name":"甲","query":"a","count":3}"""))!!
        assertTrue(back.matches("甲"))
        assertTrue(back.matches("a"))
    }

    @Test
    fun `索引条目缺 id 或 name 时被拒绝`() {
        assertNull(Tag.fromJson(JSONObject("""{"name":"x"}""")))
        assertNull(Tag.fromJson(JSONObject("""{"id":1}""")))
        assertNull(Tag.fromJson(JSONObject("""{"id":1,"name":"  "}""")))
    }
}