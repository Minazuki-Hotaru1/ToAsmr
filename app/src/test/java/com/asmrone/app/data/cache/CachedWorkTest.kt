package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Work
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 收藏缓存的正反序列化测试。
 *
 * 这是整个"本地优先"方案里最容易静默出错的一环：投影字段少存一个、
 * 或者解析时把 0 当成有效值，界面就会显示错误的状态/评分，
 * 而且**不会有任何报错**。所以这里把它钉住。
 */
class CachedWorkTest {

    private fun sampleWork() = Work(
        id = 123456,
        title = "测试作品",
        sourceId = "RJ0123456",
        circleName = "测试社团",
        durationSec = 1988,
        hasSubtitle = true,
        mainCoverUrl = "https://example.com/cover.jpg",
        progress = "listening",
        userRating = 5
    )

    @Test
    fun `投影往返后收藏字段不丢失`() {
        val original = sampleWork()
        val restored = CachedWork.fromJson(CachedWork.fromWork(original).toJson())!!.toWork()

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.sourceId, restored.sourceId)
        assertEquals(original.circleName, restored.circleName)
        assertEquals(original.durationSec, restored.durationSec)
        assertEquals(original.hasSubtitle, restored.hasSubtitle)
        assertEquals(original.mainCoverUrl, restored.mainCoverUrl)
        // 这两个字段是收藏页的核心，丢了就等于功能坏了
        assertEquals("listening", restored.progress)
        assertEquals(5, restored.userRating)
    }

    @Test
    fun `未标记且未评分时往返仍为 null`() {
        val bare = Work(id = 1, title = "没标记过的作品")
        val restored = CachedWork.fromJson(CachedWork.fromWork(bare).toJson())!!.toWork()
        assertNull("未标记必须是 null，不能变成空串或未知状态", restored.progress)
        assertNull("未评分必须是 null，不能变成 0 星", restored.userRating)
        assertNull(restored.collectionStatus)
    }

    @Test
    fun `空字符串的 progress 被视为未标记`() {
        // 服务端实测会返回 progress: ""，这必须等同于"没有标记"
        val o = JSONObject().apply {
            put("id", 7)
            put("title", "x")
            put("progress", "")
            put("rating", 0)
        }
        val cached = CachedWork.fromJson(o)!!
        assertNull(cached.progress)
        assertNull(cached.userRating)
    }

    @Test
    fun `没有 id 的记录被丢弃`() {
        val bad = JSONObject().apply { put("title", "缺 id") }
        assertNull(CachedWork.fromJson(bad))
    }

    // ───────────── 收藏卡片要显示的展示字段（社团 / 声优 / 评分）─────────────

    @Test
    fun `★ 声优名往返后仍能拼出 vaText`() {
        val work = Work(
            id = 5,
            title = "带声优的作品",
            circleName = "社团",
            vas = listOf(
                com.asmrone.app.data.model.NamedItem("a", "涼花みなせ"),
                com.asmrone.app.data.model.NamedItem("b", "御子柴")
            )
        )
        val restored = CachedWork.fromJson(CachedWork.fromWork(work).toJson())!!.toWork()
        assertEquals("涼花みなせ / 御子柴", restored.vaText)
        assertEquals("社团", restored.circleName)
    }

    @Test
    fun `声优数量有上限，避免缓存被撑大`() {
        val work = Work(
            id = 6,
            title = "很多声优",
            vas = (1..20).map { com.asmrone.app.data.model.NamedItem("$it", "声优$it") }
        )
        val cached = CachedWork.fromWork(work)
        assertEquals(CachedWork.MAX_CREDITS, cached.vas.size)
    }

    @Test
    fun `老缓存没有 vas 字段时为空列表`() {
        val o = JSONObject().apply {
            put("id", 8)
            put("title", "老记录")
        }
        val cached = CachedWork.fromJson(o)!!
        assertTrue(cached.vas.isEmpty())
    }

    @Test
    fun `作品页往返保留分页信息`() {
        val page = CachedWorkPage(
            items = listOf(CachedWork.fromWork(sampleWork())),
            totalCount = 37,
            loadedPages = 2
        )
        val restored = WorkPageCodec.decode(WorkPageCodec.encode(page))!!
        assertEquals(1, restored.items.size)
        assertEquals(37, restored.totalCount)
        assertEquals(2, restored.loadedPages)
    }

    // ───────────── 年龄分级（「全年龄」角标的依据）─────────────

    @Test
    fun `★ 成人作品往返后仍是成人`() {
        val adult = Work(id = 9, title = "R18 作品", nsfw = true)
        val restored = CachedWork.fromJson(CachedWork.fromWork(adult).toJson())!!.toWork()
        assertTrue("nsfw 丢了 → 会被误标成「全年龄」", restored.isAdult)
    }

    @Test
    fun `★ 靠 age_category 判定的成人作品也往返正确`() {
        val adult = Work(id = 10, title = "按年龄分级判定", ageCategory = "adult")
        val restored = CachedWork.fromJson(CachedWork.fromWork(adult).toJson())!!.toWork()
        assertTrue(restored.isAdult)
    }

    @Test
    fun `★ 全年龄作品往返后仍不是成人`() {
        val general = Work(id = 11, title = "全年龄作品", ageCategory = "general")
        val restored = CachedWork.fromJson(CachedWork.fromWork(general).toJson())!!.toWork()
        assertFalse(restored.isAdult)
    }

    // ───────────── 缓存结构版本（否则新字段永远落不了地）─────────────

    @Test
    fun `★ 缓存结构版本足够新，旧缓存会被丢弃并重拉`() {
        // 声优名 `vas` 是 v2、年龄分级 `nsfw`/`age` 是 v3 才加的字段。
        // 版本没 +1 的话：旧 JSON 缺字段，而条件请求又会一直命中 304，
        // 缓存永远不重写 —— 收藏卡片就会缺声优、且全被标成「全年龄」。
        assertTrue(
            "至少要是 3（v3 起才有 CachedWork.nsfw/age）",
            CacheFiles.SCHEMA_VERSION >= 3
        )
        val store = File(
            "src/main/java/com/asmrone/app/data/cache/SyncCacheStore.kt"
        ).readText()
        assertTrue(
            "版本不一致时必须当成没有缓存，下一次同步才会无条件重拉",
            store.contains("o.optInt(\"v\", 0) != CacheFiles.SCHEMA_VERSION")
        )
    }

    @Test
    fun `坏 JSON 不会让解码抛异常`() {
        // 解码器面向的是我们自己写的文件，但仍要能容忍损坏内容
        val empty = WorkPageCodec.decode(JSONObject())
        assertEquals(0, empty.items.size)
        assertEquals(0, empty.totalCount)
        // 默认至少算 1 页，否则"加载更多"会从第 0 页开始
        assertTrue(empty.loadedPages >= 1)
    }
}
