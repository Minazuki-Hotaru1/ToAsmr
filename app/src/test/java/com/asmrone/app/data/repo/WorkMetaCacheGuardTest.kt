package com.asmrone.app.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「详情页的落盘副本必须是真的详情响应」的守卫（源码级断言）。
 *
 * ## 背景
 *
 * 收藏 / 标记列表的缓存（[com.asmrone.app.data.cache.CachedWork]）是**卡片级**的：
 * 只有标题、封面、社团名、声优名、评分、收藏状态，没有标签、没有社团 id、
 * 也没有声优 id。它服务的是列表，不是详情页 —— 这是刻意的（体积小 20 倍）。
 *
 * 危险的是**另一份**缓存 `WorkMetaStore`：它存 `/api/work/{id}?v=2` 的原文。
 * 如果这份原文不是详情响应（早期版本、或 `v=1` 的响应里没有 `vas` / `tags`），
 * 详情页就会少掉声优、标签，而且它还会被 7 天保鲜期挡住不再联网 ——
 * 表现就是「之前看过的作品显示不正常，新点开的正常」。
 *
 * 所以这里钉两件事：
 *  1. 只有**完整响应**才允许直接当详情用（不算过期）；
 *  2. 第一帧（[AsmrRepository.cachedWorkMeta]）也不许用卡片级副本铺界面。
 */
class WorkMetaCacheGuardTest {

    private val repoSource: String by lazy {
        File("src/main/java/com/asmrone/app/data/repo/AsmrRepository.kt").readText()
    }

    private fun functionBody(signature: String): String {
        val start = repoSource.indexOf(signature)
        assertTrue("$signature 应当存在", start >= 0)
        var depth = 0
        var i = repoSource.indexOf('{', start)
        val body = StringBuilder()
        while (i < repoSource.length) {
            val c = repoSource[i]
            if (c == '{') depth++
            if (c == '}') depth--
            body.append(c)
            if (depth == 0 && body.length > 1) break
            i++
        }
        return body.toString()
    }

    @Test
    fun `只有完整响应才被当成详情缓存`() {
        assertTrue(
            "v=2 才有 vas / tags 两个数组，缺了就是卡片级数据",
            repoSource.contains(
                "private fun JSONObject.hasFullWorkFields(): Boolean = has(\"vas\") && has(\"tags\")"
            )
        )
    }

    @Test
    fun `保鲜期内也要先确认副本是完整响应`() {
        val body = functionBody("suspend fun getWorkMeta(")
        assertTrue(
            "★ 不能再只看 savedAt：卡片级副本落在保鲜期内就永远补不上声优和标签",
            body.contains("if (fresh && cached.json.hasFullWorkFields())")
        )
    }

    @Test
    fun `第一帧不用卡片级副本铺详情页`() {
        val body = functionBody("suspend fun cachedWorkMeta(")
        assertTrue(
            "★ 拿缺字段的副本先闪一版，再被网络结果替换，比多等半秒更难看",
            body.contains("if (!entry.json.hasFullWorkFields()) return null")
        )
    }
}
