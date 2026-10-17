package com.asmrone.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PUT /api/review` 请求拼装测试。
 *
 * 这里对应一个**真实发生过的 bug**：详情页把用户在弹窗里选的星级丢掉了，
 * 于是"选了 5 星并确认"发出去的请求里根本没有 `rating` ——
 * 界面看着成功，云端却没有评分。
 *
 * 所以这组测试的核心断言是：**调用方给了什么，请求里就必须有什么**。
 */
class ReviewRequestTest {

    private val api = AsmrOneApi { ApiConfig(host = "https://example.invalid") }

    @Test
    fun `评分与状态一起提交时两者都必须在请求里`() {
        val req = api.buildReviewRequest(
            workId = 123,
            progress = "listened",
            rating = 5
        )
        assertEquals("work_id 必须存在", 123, req.body.getInt("work_id"))
        assertEquals("状态必须进 body", "listened", req.body.getString("progress"))
        assertEquals("★ 评分必须进 body（这正是曾经丢掉的那个字段）", 5, req.body.getInt("rating"))
        assertEquals("两个都给时也不带参数", "", req.query)
    }

    @Test
    fun `只评分不打状态时不带 progress 字段`() {
        val req = api.buildReviewRequest(workId = 7, progress = null, rating = 4)
        assertFalse(
            "不能塞一个空的 progress，否则服务端可能把状态清掉",
            req.body.has("progress")
        )
        assertEquals(4, req.body.getInt("rating"))
        assertEquals("只打分也**不带**查询参数（参考实现官方站就是这么发的）", "", req.query)
    }

    @Test
    fun `只打状态不评分时不带 rating 字段`() {
        val req = api.buildReviewRequest(workId = 7, progress = "marked", rating = null)
        assertEquals("marked", req.body.getString("progress"))
        assertFalse("没给评分就不要伪造一个", req.body.has("rating"))
        assertEquals(
            "官方站不使用自建站的 starOnly/progressOnly 参数",
            "",
            req.query
        )
    }

    @Test
    fun `任何组合都不带查询参数`() {
        // 这是与参考实现对齐的关键点：官方站分支从不带 queryParameters。
        // 早先照抄了自建站分支的 ?starOnly=... / ?progressOnly=...，属于凭猜测的偏差。
        val combos = listOf(
            null to null,
            null to 5,
            "marked" to null,
            "listened" to 3
        )
        combos.forEach { (progress, rating) ->
            val req = api.buildReviewRequest(workId = 1, progress = progress, rating = rating)
            assertEquals(
                "progress=$progress rating=$rating 不应带查询参数",
                "",
                req.query
            )
        }
    }

    @Test
    fun `短评也要能带上`() {
        val req = api.buildReviewRequest(
            workId = 9,
            progress = "listened",
            rating = 3,
            reviewText = "音质很好"
        )
        assertEquals("音质很好", req.body.getString("review_text"))
    }

    @Test
    fun `全为 null 时只有 work_id`() {
        val req = api.buildReviewRequest(workId = 11, progress = null, rating = null)
        assertEquals(1, req.body.length())
        assertTrue(req.body.has("work_id"))
        assertEquals("", req.query)
    }

    @Test
    fun `请求体是可序列化的合法 JSON`() {
        val req = api.buildReviewRequest(workId = 1, progress = "listened", rating = 5)
        // 能被重新解析回来，说明发出去的就是我们期望的内容
        val parsed = JSONObject(req.body.toString())
        assertEquals(5, parsed.getInt("rating"))
        assertEquals("listened", parsed.getString("progress"))
    }
}
