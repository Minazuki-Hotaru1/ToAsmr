package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级搜索的候选：**筛选、排序、以及每条显示哪个名字**。
 *
 * ## 这组测试对应一个真实的矛盾
 *
 * 早期渲染候选用的是 `name`（**原始名**），而结果页副标题与条件胶囊用的是
 * `displayName`（中文译名）。于是出现：
 *
 * ```
 * 搜「亲热/甜蜜」→ 候选里跳出「纯爱/甜蜜」（那是原始名）
 *              → 点进去之后结果页又显示「亲热/甜蜜」
 * ```
 *
 * 同一件事在两处显示成两个不同的名字。根因是把"发给服务端的名字"与
 * "给界面看的名字"混用了 —— 所以这里把两者都钉住。
 */
class SuggestionMergerTest {

    /** 一个 $ —— Kotlin 字符串模板里要拼一下 */
    private val D = "$"

    /**
     * 这三条都是**建好索引之后**的 [Tag]：显示名在 [Tag.name]，
     * 原始名在 [Tag.queryName]。解析只发生在 `TagCatalog.build` 里一次，
     * 到了候选这一层已经没有"该显示哪个名字"的选择余地了。
     *
     * 真实数据（2026-10）：
     *  - id=4  显示 `亲热/甜蜜`，原始名 `纯爱/甜蜜`，16087 部
     *  - id=32 显示 `纯爱`（与上一条是**不同**的标签），2886 部
     *  - id=496 显示 `双声道立体声/人头麦`，原始名 `环绕音`，19779 部
     */
    private val sweetLove = tag(4, "亲热/甜蜜", queryName = "纯爱/甜蜜", count = 16087)
    private val pureLove = tag(32, "纯爱", count = 2886)
    private val surround = tag(496, "双声道立体声/人头麦", queryName = "环绕音", count = 19779)

    private fun tag(
        id: Int,
        name: String,
        queryName: String = name,
        count: Int = 0
    ) = Tag(
        id = id,
        name = name,
        queryName = queryName,
        count = count,
        matchNames = listOf(queryName, name).distinct()
    )

    private val va = VoiceActor("83a442aa-3662-5e17-aece-757bc3cb97cd", "陽向葵ゅか", 2337)
    private val circle = Circle("41088", "Whisp", 604)

    // ═══════════════ 核心：显示名用译名 ═══════════════

    @Test
    fun `★ 标签候选显示的必须是译名，不是原始名`() {
        val s = SuggestionMerger.merge("亲热", tags = listOf(sweetLove)).single()
        assertEquals("亲热/甜蜜", s.displayName)
        assertFalse(
            "候选里出现原始名，就是那个矛盾：搜译名却看到另一个名字",
            s.displayName.contains("纯爱")
        )
    }

    @Test
    fun `★ 候选的 label 也用译名`() {
        val s = SuggestionMerger.merge("亲热", tags = listOf(sweetLove)).single()
        assertEquals("亲热/甜蜜 (16087)", s.label)
    }

    @Test
    fun `★ name 仍是原始名 —— 检索串靠它才对得上`() {
        val s = SuggestionMerger.merge("亲热", tags = listOf(sweetLove)).single()
        assertEquals("纯爱/甜蜜", s.name)
        assertEquals("$D" + "tag:纯爱/甜蜜" + "$D", SearchQueryBuilder.token(s.toCondition()))
    }

    @Test
    fun `★ 转成条件后 name 是原始名、displayName 是译名`() {
        val c = SuggestionMerger.merge("亲热", tags = listOf(sweetLove)).single().toCondition()
        assertEquals("纯爱/甜蜜", c.name)
        assertEquals("亲热/甜蜜", c.displayName)
        assertEquals(SearchNamespaces.TAG_LABEL, c.kindLabel)
    }

    // ═══════════════ 按译名也能搜到 ═══════════════

    @Test
    fun `搜译名能找到那个标签`() {
        val hits = SuggestionMerger.merge("亲热", tags = listOf(sweetLove, pureLove))
        assertEquals(1, hits.size)
        assertEquals("亲热/甜蜜", hits.single().displayName)
    }

    @Test
    fun `搜原始名也能找到同一个标签`() {
        val hits = SuggestionMerger.merge("纯爱/甜蜜", tags = listOf(sweetLove, pureLove))
        assertTrue(hits.any { it.id == "4" })
    }

    @Test
    fun `搜环绕音（原始名）能找到译名那条`() {
        val s = SuggestionMerger.merge("环绕音", tags = listOf(surround)).single()
        assertEquals("双声道立体声/人头麦", s.displayName)
        assertEquals("环绕音", s.name)
    }

    @Test
    fun `搜双声道（译名）同样能找到`() {
        val s = SuggestionMerger.merge("双声道", tags = listOf(surround)).single()
        assertEquals("环绕音", s.name)
    }

    @Test
    fun `★ 搜纯爱会同时命中两条不同的标签，但各自显示自己的译名`() {
        val hits = SuggestionMerger.merge("纯爱", tags = listOf(sweetLove, pureLove))
        val byId = hits.associateBy { it.id }

        assertEquals("两条都该在", 2, hits.size)
        // id=32 的译名就是它自己
        assertEquals("纯爱", byId.getValue("32").displayName)
        // id=4 的译名是亲热/甜蜜 —— 不能也显示成「纯爱」相关
        assertEquals("亲热/甜蜜", byId.getValue("4").displayName)
        assertFalse(byId.getValue("4").displayName == byId.getValue("4").name)
    }

    // ═══════════════ 排序 ═══════════════

    @Test
    fun `按作品数倒序`() {
        // 两条都能被「纯」命中：纯爱(2886) 与 纯爱/甜蜜(16087)
        val hits = SuggestionMerger.merge("纯", tags = listOf(pureLove, sweetLove))
        assertEquals(listOf("4", "32"), hits.map { it.id })
    }

    @Test
    fun `作品数不同时按数量降序，与类型无关`() {
        // 标签 2886 与声优 2337 都能被「纯」命中 → 标签在前
        val vaNamed = VoiceActor("v1", "纯情声优", 2337)
        val hits = SuggestionMerger.merge("纯", tags = listOf(pureLove), voiceActors = listOf(vaNamed))
        assertEquals(listOf("32", "v1"), hits.map { it.id })
    }

    @Test
    fun `作品数相同时标签优先于声优`() {
        val t = tag(1, "同名", count = 100)
        val v = VoiceActor("v", "同名", 100)
        val c = Circle("c", "同名", 100)
        val hits = SuggestionMerger.merge("同名", tags = listOf(t), voiceActors = listOf(v), circles = listOf(c))
        assertEquals(
            listOf(SearchNamespaces.TAG_LABEL, SearchNamespaces.VOICE_ACTOR_LABEL, SearchNamespaces.CIRCLE_LABEL),
            hits.map { it.kindLabel }
        )
    }

    @Test
    fun `三本字典一起参与匹配`() {
        // 用一个三类资源都能命中的词：标签「ASMR」/ 声优「ASMR Voice」/ 社团「ASMR Studio」
        val t = tag(1, "ASMR", count = 100)
        val v = VoiceActor("v", "ASMR Voice", 50)
        val c = Circle("c", "ASMR Studio", 10)
        val hits = SuggestionMerger.merge(
            "asmr", tags = listOf(t), voiceActors = listOf(v), circles = listOf(c)
        )
        assertEquals(
            setOf(SearchNamespaces.TAG, SearchNamespaces.VOICE_ACTOR, SearchNamespaces.CIRCLE),
            hits.map { it.namespace }.toSet()
        )
    }

    @Test
    fun `上限生效`() {
        val many = (1..50).map { tag(it, "标签$it", count = it) }
        assertEquals(10, SuggestionMerger.merge("标签", tags = many, limit = 10).size)
    }

    @Test
    fun `空输入没有候选`() {
        assertTrue(SuggestionMerger.merge("", tags = listOf(sweetLove)).isEmpty())
        assertTrue(SuggestionMerger.merge("   ", tags = listOf(sweetLove)).isEmpty())
    }

    @Test
    fun `所有字典都空时没有候选，且不崩`() {
        assertTrue(SuggestionMerger.merge("任何词").isEmpty())
    }

    // ═══════════════ 声优 / 社团没有译名 ═══════════════

    @Test
    fun `声优的名字原样显示（i18n 实测全空，没有译名）`() {
        val s = SuggestionMerger.merge("陽向", voiceActors = listOf(va)).single()
        assertEquals("陽向葵ゅか", s.displayName)
        assertEquals("陽向葵ゅか", s.name)
    }

    @Test
    fun `社团的名字原样显示`() {
        val s = SuggestionMerger.merge("Whisp", circles = listOf(circle)).single()
        assertEquals("Whisp", s.displayName)
        assertEquals("41088", s.id)
    }

    // ═══════════════ 缺译名时回落 ═══════════════

    @Test
    fun `没有 zh-cn 译名时显示原始名`() {
        // 真实数据：id=10000 的 `AI` 就是这类（zh-cn 为 null）
        val ai = tag(10000, "AI", count = 10775)
        val s = SuggestionMerger.merge("AI", tags = listOf(ai)).single()
        assertEquals("AI", s.displayName)
        assertEquals("AI", s.name)
    }

    @Test
    fun `大写输入也能匹配`() {
        val ai = tag(10000, "ASMR", count = 5)
        assertEquals(1, SuggestionMerger.merge("asmr", tags = listOf(ai)).size)
        assertEquals(1, SuggestionMerger.merge("ASMR", tags = listOf(ai)).size)
    }

    // ═══════════════ 把矛盾的形状钉死 ═══════════════

    @Test
    fun `★ displayName 与 name 不同时，界面该显示的一律是 displayName`() {
        // 这条测试的存在意义：以后若有人把渲染改回 name，会在这里立刻暴露
        val s = SuggestionMerger.merge("亲热", tags = listOf(sweetLove)).single()
        val shown = s.displayName   // 界面显示这个
        val sent = s.name           // 检索串用这个

        assertEquals("亲热/甜蜜", shown)
        assertEquals("纯爱/甜蜜", sent)
        assertTrue("label 必须跟着显示名走", s.label.startsWith(shown))
        assertEquals(shown, s.toCondition().displayName)
        assertEquals(sent, s.toCondition().name)
        assertTrue("匹配用的名字里应当同时含原名与译名", s.matchNames.contains(shown) && s.matchNames.contains(sent))
    }
}