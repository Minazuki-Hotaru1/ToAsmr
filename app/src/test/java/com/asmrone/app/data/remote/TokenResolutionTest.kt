package com.asmrone.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌解析的语义测试 —— 针对两个真实发生过的静默 bug。
 *
 * ## bug 1：所有 POST 都不带令牌
 *
 * `postJsonObject` 原来把 `tokenOverride` **写死成 `""`**（那是给登录/注册用的），
 * 于是加入收藏夹、移出收藏夹、新建收藏夹等**全部 POST** 都变成"明确不带令牌"，
 * 服务端一律回 `401 No authorization token was found`；
 * 而 GET 读取走的是另一条语句、令牌正常 —— 所以现象是
 * "能看但不能写"，极容易被误判成服务端问题。
 *
 * ## bug 2：写请求跑在主线程
 *
 * `requestBody` 原本是纯阻塞函数，只靠调用方各自记得包 `withContext(IO)`；
 * `PUT /api/review` 漏了，于是抛 `NetworkOnMainThreadException`。
 * 那个异常的 `message` 是 null，界面上只显示成一句"网络请求失败"。
 * 现在 IO 切换收进了 [AsmrOneApi] 内部（`requestBody` 自己就是 suspend + IO），
 * 结构上不可能再漏，所以这里不需要测试 —— 而是**类型系统**保证。
 */
class TokenResolutionTest {

    private val api = AsmrOneApi { ApiConfig(host = "https://example.invalid", token = "") }

    @Test
    fun `null 表示使用配置里的令牌（默认行为）`() {
        assertEquals(
            "绝大多数请求都该走这条：用登录得到的令牌",
            "T0KEN",
            api.resolveToken(tokenOverride = null, configured = "T0KEN")
        )
    }

    @Test
    fun `空串表示明确不带令牌`() {
        assertEquals(
            "登录/注册专用：必须不带旧令牌",
            "",
            api.resolveToken(tokenOverride = "", configured = "OLD_TOKEN")
        )
    }

    @Test
    fun `没有登录时解析结果为空`() {
        assertEquals("", api.resolveToken(tokenOverride = null, configured = ""))
    }

    @Test
    fun `必须匿名调用的端点只有登录注册与推荐 uuid`() {
        val anonymous = api.authEndpointsThatMustNotSendToken()
        assertTrue("登录接口", anonymous.contains("/api/auth/me"))
        assertTrue("注册接口", anonymous.contains("/api/auth/reg"))
        assertTrue("取推荐 uuid（带令牌会返回推荐结果而不是注册信息）", anonymous.contains("/api/recommender/recommend-for-user"))

        // 关键：写接口**不能**出现在这个集合里 —— 它们必须带令牌
        assertFalse("标记作品绝不能匿名", anonymous.any { it.contains("review") })
        assertFalse("收藏夹写操作绝不能匿名", anonymous.any { it.contains("playlist") })
    }

    @Test
    fun `三本搜索字典是公开端点，明确不带令牌`() {
        val public = api.publicEndpointsThatMustNotSendToken()
        assertEquals(
            "只有这三本字典；多一个都要重新做一次匿名对照实验",
            setOf("/api/tags/", "/api/vas/", "/api/circles/"),
            public
        )

        // 实测依据（2026-10）：三者匿名与带假 token 的响应**字节数完全相同**
        // （65070 / 447362 / 302143），而 /api/review 与 /api/playlist/get-playlists
        // 在同一时刻匿名回 401（44 字节）、假 token 回 401（25 字节）——
        // 说明服务端确实校验令牌，只是这三本字典不在受保护范围内。
        val anonymous = api.authEndpointsThatMustNotSendToken()
        public.forEach { assertTrue("$it 应当在匿名名单里", anonymous.contains(it)) }
    }

    @Test
    fun `公开端点名单不含任何需要鉴权的路径`() {
        val public = api.publicEndpointsThatMustNotSendToken()
        listOf("review", "playlist", "auth", "recommender").forEach { risky ->
            assertFalse(
                "「$risky」相关的端点必须带令牌，不能出现在公开名单里",
                public.any { it.contains(risky) }
            )
        }
    }

    @Test
    fun `收藏夹相关端点都不在匿名名单里`() {
        val anonymous = api.authEndpointsThatMustNotSendToken()
        listOf(
            "/api/playlist/add-works-to-playlist",
            "/api/playlist/remove-works-from-playlist",
            "/api/playlist/create-playlist",
            "/api/playlist/edit-playlist-metadata",
            "/api/playlist/delete-playlist",
            "/api/playlist/get-playlists"
        ).forEach { path ->
            assertFalse("$path 必须带令牌", anonymous.contains(path))
        }
    }
}
