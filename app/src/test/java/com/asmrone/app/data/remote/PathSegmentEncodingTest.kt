package com.asmrone.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * 检索串放进 **URL 路径段** 时的编码。
 *
 * ## 对应一个真实 bug：多标签搜索永远 0 条
 *
 * 现象是"单标签正常、多标签没结果"，而且**两个标签单独搜都有结果**。
 *
 * 根因：`URLEncoder.encode` 是 `application/x-www-form-urlencoded` 的编码器，
 * 把空格编成 `+`。而 `+` **只在 query string 里**代表空格；
 * 在**路径段**里它就是一个字面的加号。
 *
 * 检索串走的是 `/api/search/{检索串}` —— 检索串在路径里，多个条件之间靠**空格**分隔。
 * 编成 `+` 之后服务端收到的是含 `+` 的检索串，匹配不到任何东西。
 *
 * 实测（2026-10，`api.asmr-200.com`）：
 *
 * | 编码 | 结果 |
 * |---|---|
 * | `%20` | **405** ✅ |
 * | `+`   | **0** ❌ |
 *
 * ## 为什么只影响多标签
 *
 * 单标签走 `/api/tags/{id}/works`：`id` 是数字、页面参数在 query 里，
 * **完全不经过路径段编码**。只有多条件才必须拼检索串走 `/api/search/`。
 */
class PathSegmentEncodingTest {

    private val api = AsmrOneApi { ApiConfig(host = "https://example.invalid", token = "") }

    private val twoTags = "${'$'}tag:ASMR${'$'} ${'$'}tag:催眠${'$'}"

    @Test
    fun `★ 空格必须编成百分号二十，不能是加号`() {
        val encoded = api.encPathSegment(twoTags)
        assertTrue(
            "路径段里的空格只能是 %20；+ 在路径里是字面加号，服务端会当成检索串的一部分",
            encoded.contains("%20")
        )
        assertFalse(
            "★ URLEncoder 会把空格编成 +，这正是多标签搜不到结果的原因",
            encoded.contains("+")
        )
    }

    @Test
    fun `与 URLEncoder 的差异恰好只有空格`() {
        val naive = URLEncoder.encode(twoTags, "UTF-8")
        val fixed = api.encPathSegment(twoTags)
        assertEquals(
            "除了 + → %20 之外不该有任何差别",
            naive.replace("+", "%20"),
            fixed
        )
        assertEquals("原始编码里确实有 +", 1, naive.count { it == '+' })
    }

    @Test
    fun `编码之后的检索串能还原回原样`() {
        val encoded = api.encPathSegment(twoTags)
        assertEquals(
            "解码应当得到原检索串（含那个用于分隔条件的空格）",
            twoTags,
            java.net.URLDecoder.decode(encoded, "UTF-8")
        )
    }

    @Test
    fun `条件里的美元符号与冒号都被编码`() {
        val encoded = api.encPathSegment("${'$'}tag:ASMR${'$'}")
        assertEquals("%24tag%3AASMR%24", encoded)
    }

    @Test
    fun `斜杠被编码，不会被当成路径分隔`() {
        // 标签名里真的含 `/`（如 `纯爱/甜蜜`、`内射/中出`）
        val encoded = api.encPathSegment("${'$'}tag:纯爱/甜蜜${'$'}")
        assertFalse("斜杠必须被编码，否则会被当成路径分隔符", encoded.contains("/"))
        assertTrue("应当编成 %2F", encoded.contains("%2F"))
    }

    @Test
    fun `中文被正确编码`() {
        val encoded = api.encPathSegment("催眠")
        assertEquals("%E5%82%AC%E7%9C%A0", encoded)
    }

    @Test
    fun `单个条件不含空格，两种编码一致`() {
        val one = "${'$'}tag:ASMR${'$'}"
        assertEquals(
            "单条件没有空格，所以它不受这个 bug 影响 —— 与「单标签正常」的现象吻合",
            api.encPathSegment(one),
            URLEncoder.encode(one, "UTF-8")
        )
    }

    @Test
    fun `三个条件之间的两个空格都保住`() {
        val three = "${'$'}tag:ASMR${'$'} ${'$'}tag:催眠${'$'} ${'$'}tag:内射/中出${'$'}"
        val encoded = api.encPathSegment(three)
        assertFalse(encoded.contains("+"))
        assertEquals("两个分隔空格都要保住", 2, Regex("%20").findAll(encoded).count())
    }

    @Test
    fun `裸关键词里的空格也要正确编码`() {
        val encoded = api.encPathSegment("耳かき 添い寝")
        assertFalse("按字面加号搜「耳かき+添い寝」是搜不到的", encoded.contains("+"))
        assertTrue(
            "空格处应为 %20",
            encoded.startsWith("%E8%80%B3%E3%81%8B%E3%81%8D%20")
        )
    }
}