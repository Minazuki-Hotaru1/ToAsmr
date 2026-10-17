package com.asmrone.app.data.prefs

import com.asmrone.app.data.model.SearchCondition
import com.asmrone.app.data.model.SearchNamespaces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 搜索历史的**存盘 → 读回**往返测试。
 *
 * ## 为什么这组测试非写不可
 *
 * 搜索历史是"用户自己产生的"数据：丢了不会崩、不会报错，只是用户发现
 * "我搜过的记录不见了"。而这类 bug 在这个项目里**真的发生过** ——
 * 早期标签字典就是 `save` 双写磁盘与内存、但读取路径只查内存，
 * 导致每次冷启动都读不回。
 *
 * 所以这里对搜索历史做同样的事：存进去 → **清掉内存（模拟进程重启）** → 必须还能读回来。
 *
 * ## 历史要能"重放"
 *
 * 所以记录里除了展示文本，还要存 [SearchHistoryEntry.query] 与
 * [SearchHistoryEntry.conditions]，且**三样都要能完整往返** ——
 * 否则点历史填不回界面，功能就是残的。
 *
 * 存储用内存替身（[SearchHistoryStore.installForTest]），不依赖 Android `Context`。
 */
class SearchHistoryStoreTest {

    @Before
    fun setUp() {
        SearchHistoryStore.installForTest()
    }

    @After
    fun tearDown() {
        SearchHistoryStore.uninstallForTest()
    }

    private val visible get() = SearchHistoryStore.entries.value

    private fun tag(name: String, id: String = "497", display: String = name) =
        SearchCondition(SearchNamespaces.TAG, id, name, display, SearchNamespaces.TAG_LABEL)

    private fun circle(name: String, id: String = "41088") =
        SearchCondition(SearchNamespaces.CIRCLE, id, name, name, SearchNamespaces.CIRCLE_LABEL)

    // ═════════════════════ 核心：冷启动往返 ═════════════════════

    @Test
    fun `存盘后清掉内存再读，记录必须还在`() {
        SearchHistoryStore.push("催眠", emptyList())
        SearchHistoryStore.push("ASMR", emptyList())

        SearchHistoryStore.simulateRestartForTest()

        assertEquals(
            "★ 数据在磁盘上却读不回来，用户就会觉得记录凭空消失了",
            listOf("ASMR", "催眠"),
            visible.map { it.query }
        )
    }

    @Test
    fun `时间戳往返不丢`() {
        SearchHistoryStore.push("催眠", emptyList(), now = 12345L)
        SearchHistoryStore.simulateRestartForTest()
        assertEquals(12345L, visible.single().at)
    }

    // ═════════════════════ 条件也要完整往返 ═════════════════════

    @Test
    fun `★ 纯条件记录：条件本身必须往返`() {
        SearchHistoryStore.push("", listOf(tag("ASMR")))
        SearchHistoryStore.simulateRestartForTest()

        val entry = visible.single()
        assertEquals("", entry.query)
        assertEquals(1, entry.conditions.size)
        assertEquals(SearchNamespaces.TAG, entry.conditions.single().namespace)
        assertEquals("497", entry.conditions.single().id)
        assertEquals("ASMR", entry.conditions.single().name)
    }

    @Test
    fun `★ 文字加条件的组合必须两项都往返`() {
        SearchHistoryStore.push("耳かき", listOf(tag("ASMR")))
        SearchHistoryStore.simulateRestartForTest()

        val entry = visible.single()
        assertEquals("耳かき", entry.query)
        assertEquals("ASMR", entry.conditions.single().name)
    }

    @Test
    fun `★ 标签的中文译名与类型标记也要往返`() {
        // 真实数据：标签 `环绕音` 的 zh-cn 译名是 `双声道立体声/人头麦`。
        // 译名来自本地字典，不存下来就会在历史里显示成原始名。
        SearchHistoryStore.push("", listOf(tag("环绕音", id = "496", display = "双声道立体声/人头麦")))
        SearchHistoryStore.simulateRestartForTest()

        val condition = visible.single().conditions.single()
        assertEquals("环绕音", condition.name)
        assertEquals("双声道立体声/人头麦", condition.displayName)
        assertEquals(SearchNamespaces.TAG_LABEL, condition.kindLabel)
    }

    @Test
    fun `多条件记录按原顺序往返`() {
        SearchHistoryStore.push("", listOf(tag("ASMR"), circle("Whisp"), tag("Vtuber", id = "2")))
        SearchHistoryStore.simulateRestartForTest()

        assertEquals(
            listOf("ASMR", "Whisp", "Vtuber"),
            visible.single().conditions.map { it.name }
        )
    }

    @Test
    fun `缺 kind 字段的老数据能从命名空间反查类型`() {
        SearchHistoryStore.corruptForTest(
            """[{"q":"","at":1,"conds":[{"ns":"va","id":"x","name":"某人","display":"某人"}]}]"""
        )
        SearchHistoryStore.simulateRestartForTest()

        assertEquals(
            SearchNamespaces.VOICE_ACTOR_LABEL,
            visible.single().conditions.single().kindLabel
        )
    }

    @Test
    fun `缺 display 字段时退回原名`() {
        SearchHistoryStore.corruptForTest(
            """[{"q":"","at":1,"conds":[{"ns":"tag","id":"1","name":"ASMR"}]}]"""
        )
        SearchHistoryStore.simulateRestartForTest()

        assertEquals("ASMR", visible.single().conditions.single().displayName)
    }

    // ═════════════════════ 摘要文本（算出来的）═════════════════════

    @Test
    fun `纯文字记录的摘要就是关键词`() {
        SearchHistoryStore.push("耳かき", emptyList())
        assertEquals("耳かき", visible.single().summary)
    }

    @Test
    fun `纯条件记录的摘要是各条件的组合`() {
        SearchHistoryStore.push("", listOf(tag("ASMR"), circle("Whisp")))
        assertEquals("标签「ASMR」 且 社团「Whisp」", visible.single().summary)
    }

    @Test
    fun `文字加条件的摘要把两者都写上`() {
        SearchHistoryStore.push("耳かき", listOf(tag("ASMR")))
        assertEquals("标签「ASMR」 + 关键词「耳かき」", visible.single().summary)
    }

    @Test
    fun `摘要用译名而不是原始名`() {
        SearchHistoryStore.push("", listOf(tag("环绕音", id = "496", display = "双声道立体声/人头麦")))
        assertTrue(visible.single().summary.contains("双声道立体声/人头麦"))
        assertFalse(visible.single().summary.contains("环绕音"))
    }

    @Test
    fun `总结了哪些条件`() {
        SearchHistoryStore.push("", listOf(tag("ASMR"), circle("Whisp")))
        val entry = visible.single()
        assertTrue(entry.hasConditions)
        assertEquals(listOf("ASMR"), entry.tagConditions.map { it.name })
    }

    // ═════════════════════ 顺序与去重 ═════════════════════

    @Test
    fun `最新的排在最前面`() {
        SearchHistoryStore.push("第一个", emptyList())
        SearchHistoryStore.push("第二个", emptyList())
        SearchHistoryStore.push("第三个", emptyList())
        assertEquals(listOf("第三个", "第二个", "第一个"), visible.map { it.query })
    }

    @Test
    fun `同一个词重复搜只留一条，并提到最前`() {
        SearchHistoryStore.push("A", emptyList())
        SearchHistoryStore.push("B", emptyList())
        SearchHistoryStore.push("A", emptyList())
        assertEquals("反复搜同一个词不该把列表占满", listOf("A", "B"), visible.map { it.query })
    }

    @Test
    fun `去重也发生在重启之后`() {
        SearchHistoryStore.push("A", emptyList())
        SearchHistoryStore.push("B", emptyList())
        SearchHistoryStore.simulateRestartForTest()
        SearchHistoryStore.push("A", emptyList())
        assertEquals(listOf("A", "B"), visible.map { it.query })
    }

    @Test
    fun `★ 同样文字但条件不同的两次检索算两条`() {
        SearchHistoryStore.push("耳かき", listOf(tag("ASMR")))
        SearchHistoryStore.push("耳かき", listOf(tag("Vtuber", id = "2")))
        assertEquals(
            "文字相同、条件不同是两次不同的检索，不该被去重掉",
            2,
            visible.size
        )
    }

    @Test
    fun `★ 条件相同但文字不同的两次检索算两条`() {
        SearchHistoryStore.push("耳かき", listOf(tag("ASMR")))
        SearchHistoryStore.push("添い寝", listOf(tag("ASMR")))
        assertEquals(2, visible.size)
    }

    @Test
    fun `超过上限时丢掉最旧的`() {
        repeat(SearchHistoryStore.MAX_ENTRIES + 5) { i ->
            SearchHistoryStore.push("词$i", emptyList())
        }
        assertEquals(SearchHistoryStore.MAX_ENTRIES, visible.size)
        assertFalse(visible.any { it.query == "词0" })
        assertTrue(visible.any { it.query == "词${SearchHistoryStore.MAX_ENTRIES + 4}" })
    }

    @Test
    fun `上限在重启后依然生效`() {
        repeat(SearchHistoryStore.MAX_ENTRIES + 3) { i ->
            SearchHistoryStore.push("词$i", emptyList())
        }
        SearchHistoryStore.simulateRestartForTest()
        assertEquals(SearchHistoryStore.MAX_ENTRIES, visible.size)
    }

    // ═════════════════════ 删除与清空 ═════════════════════

    @Test
    fun `删掉一条之后重启不会又冒出来`() {
        SearchHistoryStore.push("A", emptyList())
        SearchHistoryStore.push("B", emptyList())

        SearchHistoryStore.remove(visible.first { it.query == "A" })
        SearchHistoryStore.simulateRestartForTest()

        assertEquals("★ 只删内存不删磁盘，重启后记录会复活", listOf("B"), visible.map { it.query })
    }

    @Test
    fun `删带条件的记录不会误删同文字但条件不同的另一条`() {
        SearchHistoryStore.push("耳かき", listOf(tag("ASMR")))
        SearchHistoryStore.push("耳かき", listOf(tag("Vtuber", id = "2")))

        SearchHistoryStore.remove(visible.first { it.conditions.single().name == "ASMR" })
        SearchHistoryStore.simulateRestartForTest()

        assertEquals(1, visible.size)
        assertEquals("Vtuber", visible.single().conditions.single().name)
    }

    @Test
    fun `删不存在的记录不影响其余`() {
        SearchHistoryStore.push("A", emptyList())
        SearchHistoryStore.remove(SearchHistoryEntry("不存在", emptyList(), 0L))
        assertEquals(listOf("A"), visible.map { it.query })
    }

    @Test
    fun `清空之后重启也是空的`() {
        SearchHistoryStore.push("A", emptyList())
        SearchHistoryStore.push("B", listOf(tag("ASMR")))

        SearchHistoryStore.clear()
        SearchHistoryStore.simulateRestartForTest()

        assertTrue("★ 只清内存不清磁盘，重启后整列会复活", visible.isEmpty())
        assertNull(SearchHistoryStore.rawForTest())
    }

    // ═════════════════════ 边界 ═════════════════════

    @Test
    fun `文字与条件都空时不记录`() {
        SearchHistoryStore.push("", emptyList())
        SearchHistoryStore.push("   ", emptyList())
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `只有条件、没有文字时照样记录`() {
        SearchHistoryStore.push("", listOf(tag("ASMR")))
        assertEquals(1, visible.size)
    }

    @Test
    fun `记录前会去掉文字两端空白`() {
        SearchHistoryStore.push("  催眠  ", emptyList())
        assertEquals("催眠", visible.single().query)
    }

    @Test
    fun `去空白之后相同的词算同一条`() {
        SearchHistoryStore.push("催眠", emptyList())
        SearchHistoryStore.push("  催眠  ", emptyList())
        assertEquals(1, visible.size)
    }

    @Test
    fun `没有存过时读回是空，而不是崩`() {
        SearchHistoryStore.simulateRestartForTest()
        assertTrue(visible.isEmpty())
        assertNull(SearchHistoryStore.rawForTest())
    }

    @Test
    fun `损坏的数据被忽略而不是崩`() {
        SearchHistoryStore.corruptForTest("{ 这不是 JSON")
        SearchHistoryStore.simulateRestartForTest()
        assertTrue("坏数据必须被当成没有，绝不能把启动搞崩", visible.isEmpty())
    }

    @Test
    fun `数组里混入坏条目时只跳过那一条`() {
        SearchHistoryStore.corruptForTest(
            """[{"q":"好的","at":1},
                {"at":2},
                {"q":"","at":3},
                {"q":"带条件的","at":4,"conds":[{"ns":"tag","id":"1","name":"ASMR","display":"ASMR","kind":"标签"}]}]"""
        )
        SearchHistoryStore.simulateRestartForTest()

        assertEquals(listOf("好的", "带条件的"), visible.map { it.query })
        assertEquals(1, visible[1].conditions.size)
    }

    @Test
    fun `条件里缺关键字段时只跳过那一条条件`() {
        SearchHistoryStore.corruptForTest(
            """[{"q":"x","at":1,"conds":[
                 {"ns":"","id":"1","name":"没有命名空间"},
                 {"ns":"tag","id":"2","name":""},
                 {"ns":"tag","id":"3","name":"好的","display":"好的","kind":"标签"}]}]"""
        )
        SearchHistoryStore.simulateRestartForTest()

        assertEquals(1, visible.single().conditions.size)
        assertEquals("好的", visible.single().conditions.single().name)
    }

    @Test
    fun `没有 q 字段的老数据按空文字处理`() {
        SearchHistoryStore.corruptForTest(
            """[{"at":1,"conds":[{"ns":"tag","id":"1","name":"ASMR","display":"ASMR","kind":"标签"}]}]"""
        )
        SearchHistoryStore.simulateRestartForTest()

        assertEquals("", visible.single().query)
        assertEquals("标签「ASMR」", visible.single().summary)
    }
}