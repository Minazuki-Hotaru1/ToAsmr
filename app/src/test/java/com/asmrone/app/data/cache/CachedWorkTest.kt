package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Work
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
