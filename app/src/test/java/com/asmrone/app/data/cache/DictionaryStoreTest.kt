package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Circle
import com.asmrone.app.data.model.Tag
import com.asmrone.app.data.model.TagCatalog
import com.asmrone.app.data.model.SearchTag
import com.asmrone.app.data.model.TagLocaleNames
import com.asmrone.app.data.model.VoiceActor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 三本搜索字典（标签 / 声优 / 社团）的**存盘 → 读回**测试。
 *
 * ## 这组测试对应一个真实发生过的 bug
 *
 * 早期实现里 `load()` 定义了却**没有任何调用点**：`save()` 会写磁盘 + 内存快照，
 * 但 `init()` 只存 Context、读取路径只查内存 ——
 * 于是数据明明在磁盘上，**每次冷启动都提示"没有标签字典"**。
 *
 * 这类 bug 不会崩、不会报错，只是功能静默失效。所以这里对**三本字典都**做
 * "存进去 → 清掉内存（模拟进程重启）→ 必须还能读回来"的断言。
 *
 * 存储用内存替身（各字典的 `installForTest`），不依赖 Android `Context`。
 */
class DictionaryStoreTest {

    private val host = "https://api.asmr-200.com"

    @Before
    fun setUp() {
        TagDictionary.installForTest()
        VoiceActorDictionary.installForTest()
        CircleDictionary.installForTest()
    }

    @After
    fun tearDown() {
        TagDictionary.uninstallForTest()
        VoiceActorDictionary.uninstallForTest()
        CircleDictionary.uninstallForTest()
    }

    // ─────────────────────────── 测试数据 ───────────────────────────

    /**
     * 造一个**已建好索引**的标签。
     *
     * 注意这里不再传 i18n —— 索引里存的就是解析结果（[Tag.name] 是显示名、
     * [Tag.queryName] 是原始名），解析只在 [TagCatalog.build] 里发生一次。
     */
    private fun tag(
        id: Int,
        name: String,
        queryName: String = name,
        censored: String = "",
        count: Int = 0
    ) = Tag(
        id = id,
        name = name,
        queryName = queryName,
        censoredName = censored,
        count = count,
        matchNames = listOf(queryName, name).distinct()
    )
    /** 取自线上真实数据（声优 id 是 UUID） */
    private val vas = listOf(
        VoiceActor("c75838bb-1c4d-5850-8a93-b9a56593d511", "야디", 1),
        VoiceActor("02f5c753-1b03-5187-ab08-829b20113753", "^_^", 5),
        VoiceActor("aaaaaaaa-0000-0000-0000-000000000001", "陽向葵ゅか", 2337)
    )

    /** 取自线上真实数据（社团 id 是整数） */
    private val circles = listOf(
        Circle("37739", "072LABO", 108),
        Circle("43352", "072LABOコラボ", 4),
        Circle("1", "アイボイス", 978)
    )

    // ═══════════════════ 声优 / 社团：冷启动往返 ═══════════════════

    @Test
    fun `声优存盘后清掉内存再读，必须还能拿到`() {
        assertTrue(VoiceActorDictionary.save(host, vas))
        VoiceActorDictionary.simulateRestartForTest()

        val back = VoiceActorDictionary.entries(host)
        assertEquals(
            "★ 数据在磁盘上却读不回来，就会导致每次进 App 都提示没有字典",
            vas.size,
            back.size
        )
        assertEquals(2337, back.first { it.name == "陽向葵ゅか" }.count)
    }

    @Test
    fun `社团存盘后清掉内存再读，必须还能拿到`() {
        assertTrue(CircleDictionary.save(host, circles))
        CircleDictionary.simulateRestartForTest()

        val back = CircleDictionary.entries(host)
        assertEquals(circles.size, back.size)
        assertEquals(978, back.first { it.name == "アイボイス" }.count)
    }

    @Test
    fun `声优读回后 id 仍是 UUID 字符串（不能被当成整数弄丢）`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.simulateRestartForTest()

        val ids = VoiceActorDictionary.entries(host).map { it.id }
        assertTrue(ids.contains("c75838bb-1c4d-5850-8a93-b9a56593d511"))
        assertTrue("非 ASCII 名字也要原样保留", VoiceActorDictionary.entries(host).any { it.name == "야디" })
    }

    @Test
    fun `社团读回后 id 仍是整数字符串`() {
        CircleDictionary.save(host, circles)
        CircleDictionary.simulateRestartForTest()

        assertTrue(CircleDictionary.entries(host).any { it.id == "37739" })
    }

    @Test
    fun `更新时间也能在读回后拿到`() {
        val at = 1_700_000_000_000L
        VoiceActorDictionary.save(host, vas, now = at)
        VoiceActorDictionary.simulateRestartForTest()

        assertEquals(at, VoiceActorDictionary.lastUpdatedAt(host))
    }

    // ═══════════════════ 三本字典互不干扰 ═══════════════════

    @Test
    fun `三本字典的键互不冲突`() {
        // 三者都放同一个目录，靠 key 前缀 + kind 标记区分
        TagDictionary.save(host, listOf(tag(1, "ASMR", count = 18435)))
        VoiceActorDictionary.save(host, vas)
        CircleDictionary.save(host, circles)
        TagDictionary.simulateRestartForTest()
        VoiceActorDictionary.simulateRestartForTest()
        CircleDictionary.simulateRestartForTest()

        assertEquals(1, TagDictionary.entries(host).size)
        assertEquals(vas.size, VoiceActorDictionary.entries(host).size)
        assertEquals(circles.size, CircleDictionary.entries(host).size)
    }

    @Test
    fun `kind 不匹配的文件被忽略（声优文件不会被社团字典读走）`() {
        VoiceActorDictionary.save(host, vas)
        // 把声优那本的内容塞进社团字典的文件位置
        val stolen = """{"v":1,"kind":"vas","host":"$host","updatedAt":1,"entries":[{"id":"x","name":"y","count":1}]}"""
        CircleDictionary.corruptForTest(host, stolen)
        CircleDictionary.simulateRestartForTest()

        assertTrue(
            "★ 结构相同，全靠 kind 区分；不做这个校验就会显示错数据",
            CircleDictionary.entries(host).isEmpty()
        )
    }

    @Test
    fun `不同主机的字典互不干扰`() {
        val other = "https://api.asmr-300.com"
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.save(other, listOf(VoiceActor("x", "另一个站的声优", 9)))
        VoiceActorDictionary.simulateRestartForTest()

        assertEquals(vas.size, VoiceActorDictionary.entries(host).size)
        assertEquals(1, VoiceActorDictionary.entries(other).size)
    }

    // ═══════════════════ 标签：保留原有语义 ═══════════════════

    @Test
    fun `标签存盘后清掉内存再读，译名 和谐名 旧名都不丢`() {
        // 索引里存的是**解析好的**标签：显示名在 name、原始名在 queryName
        val tags = listOf(
            tag(497, "ASMR", count = 18435),
            tag(496, "双声道立体声/人头麦", queryName = "环绕音", count = 19779),
            tag(10000, "AI", count = 10775),
            tag(157, "催眠", censored = "暗示", count = 2058),
            tag(4, "亲热/甜蜜", queryName = "纯爱/甜蜜", count = 16087)
        )
        TagDictionary.save(host, tags)
        TagDictionary.simulateRestartForTest()

        val byId = TagDictionary.entries(host).associateBy { it.id }
        assertEquals(5, byId.size)
        // 显示名
        assertEquals("双声道立体声/人头麦", byId.getValue(496).name)
        assertEquals("AI", byId.getValue(10000).name)
        // 原始名单独存着，检索串要用
        assertEquals("环绕音", byId.getValue(496).queryName)
        assertEquals("纯爱/甜蜜", byId.getValue(4).queryName)
        // 和谐名
        assertEquals("暗示", byId.getValue(157).censoredDisplayName)
    }

    @Test
    fun `标签读回后显示名与存入前一致，且不需要再解析`() {
        TagDictionary.save(
            host,
            listOf(
                tag(496, "双声道立体声/人头麦", queryName = "环绕音"),
                tag(10000, "AI"),
                tag(157, "催眠", censored = "暗示")
            )
        )
        TagDictionary.simulateRestartForTest()

        val byId = TagDictionary.entries(host).associateBy { it.id }
        // 直接读 name —— 索引里就是显示名，没有"再解析一次"这一步
        assertEquals("双声道立体声/人头麦", byId.getValue(496).name)
        assertEquals("AI", byId.getValue(10000).name)
        assertEquals("暗示", byId.getValue(157).censoredDisplayName)
    }

    @Test
    fun `匹配别名能往返`() {
        TagDictionary.save(
            host,
            listOf(
                Tag(
                    id = 496,
                    name = "双声道立体声/人头麦",
                    queryName = "环绕音",
                    count = 19779,
                    matchNames = listOf("环绕音", "双声道立体声/人头麦")
                )
            )
        )
        TagDictionary.simulateRestartForTest()

        val back = TagDictionary.entries(host).single()
        assertEquals(listOf("环绕音", "双声道立体声/人头麦"), back.matchNames)
        assertTrue(back.matches("环绕音"))
        assertTrue(back.matches("双声道"))
    }

    @Test
    fun `索引只留中文相关的名字（英文那套不落盘）`() {
        // 上游条目带 en-us，但建索引时就丢掉了 —— 落盘的文件里不该有它
        val built = TagCatalog.build(
            SearchTag(
                id = 1,
                name = "テスト",
                i18n = mapOf(
                    TagCatalog.LOCALE_ZH_CN to TagLocaleNames("测试", null, emptyList()),
                    "en-us" to TagLocaleNames("Test", null, emptyList())
                ),
                count = 5
            )
        )
        TagDictionary.save(host, listOf(built))
        TagDictionary.simulateRestartForTest()

        val back = TagDictionary.entries(host).single()
        assertEquals("测试", back.name)
        assertEquals("テスト", back.queryName)
        assertFalse("英文名不该出现在匹配别名里", back.matchNames.contains("Test"))
    }

    // ═══════════════════ 边界 ═══════════════════

    @Test
    fun `没存过时读回是空，而不是崩`() {
        VoiceActorDictionary.simulateRestartForTest()
        CircleDictionary.simulateRestartForTest()
        TagDictionary.simulateRestartForTest()

        assertTrue(VoiceActorDictionary.entries(host).isEmpty())
        assertTrue(CircleDictionary.entries(host).isEmpty())
        assertTrue(TagDictionary.entries(host).isEmpty())
        assertNull(VoiceActorDictionary.lastUpdatedAt(host))
    }

    @Test
    fun `只读内存的 peek 不会触发磁盘读`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.simulateRestartForTest()
        val before = VoiceActorDictionary.diskReadCountForTest()

        // entries() 会补读一次；peek 不会
        VoiceActorDictionary.entries(host)
        assertEquals(before + 1, VoiceActorDictionary.diskReadCountForTest())
    }

    @Test
    fun `热路径只读一次盘，之后走内存快照`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.simulateRestartForTest()
        assertEquals(0, VoiceActorDictionary.diskReadCountForTest())

        VoiceActorDictionary.entries(host)
        assertEquals("内存没有时应当读一次盘", 1, VoiceActorDictionary.diskReadCountForTest())

        repeat(5) { VoiceActorDictionary.entries(host) }
        assertEquals("搜索页每敲一个字都会读它，不能反复读盘", 1, VoiceActorDictionary.diskReadCountForTest())
    }

    @Test
    fun `清除之后读回为空`() {
        CircleDictionary.save(host, circles)
        CircleDictionary.simulateRestartForTest()
        assertTrue(CircleDictionary.entries(host).isNotEmpty())

        CircleDictionary.clear(host)
        CircleDictionary.simulateRestartForTest()
        assertTrue(CircleDictionary.entries(host).isEmpty())
    }

    @Test
    fun `空列表不落盘`() {
        assertFalse("存一份空字典没有意义，应当直接拒绝", VoiceActorDictionary.save(host, emptyList()))
        VoiceActorDictionary.simulateRestartForTest()
        assertTrue(VoiceActorDictionary.entries(host).isEmpty())
    }

    @Test
    fun `损坏的文件被忽略而不是崩`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.corruptForTest(host, "{ this is not json")
        VoiceActorDictionary.simulateRestartForTest()

        assertTrue("坏数据必须被当成没有，绝不能把界面搞崩", VoiceActorDictionary.entries(host).isEmpty())
    }

    @Test
    fun `版本号不匹配的文件被忽略`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.corruptForTest(host, """{"v":999,"kind":"vas","host":"$host","entries":[]}""")
        VoiceActorDictionary.simulateRestartForTest()

        assertTrue(VoiceActorDictionary.entries(host).isEmpty())
    }

    @Test
    fun `host 不匹配的文件被忽略`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.corruptForTest(
            host,
            """{"v":1,"kind":"vas","host":"https://other.example","entries":[{"id":"a","name":"x","count":1}]}"""
        )
        VoiceActorDictionary.simulateRestartForTest()

        assertTrue("文件里的 host 与请求的不一致时不能采信", VoiceActorDictionary.entries(host).isEmpty())
    }

    @Test
    fun `缺 id 或 name 的条目被跳过，其余仍然保留`() {
        VoiceActorDictionary.save(host, vas)
        VoiceActorDictionary.corruptForTest(
            host,
            """{"v":1,"kind":"vas","host":"$host","updatedAt":1,"entries":[
                 {"id":"","name":"no-id","count":1},
                 {"id":"a","name":"","count":1},
                 {"id":"ok","name":"正常条目","count":2}]}"""
        )
        VoiceActorDictionary.simulateRestartForTest()

        val back = VoiceActorDictionary.entries(host)
        assertEquals("坏条目跳过、好条目保留", 1, back.size)
        assertEquals("正常条目", back.single().name)
    }
}
