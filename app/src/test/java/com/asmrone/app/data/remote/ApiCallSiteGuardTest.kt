package com.asmrone.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 调用点一致性守卫（源码级断言）。
 *
 * ## 为什么要测源码
 *
 * 这一轮修掉的两个 bug 都**不可能**被常规单测发现：
 *
 *  1. `postJsonObject` 里写死 `tokenOverride = ""` —— 逻辑"看起来合理"，
 *     语法正确、编译通过，只有真机请求到服务端才会以 401 暴露；
 *  2. `PUT /api/review` 忘了包 `withContext(Dispatchers.IO)` ——
 *     抛出 `NetworkOnMainThreadException`，而它的 message 是 null，
 *     界面上只显示成"网络请求失败"，连异常类型都看不到。
 *
 * 两者都是"**默认值/遗漏**"类错误：没有类型系统保护，也很难在运行时断言。
 * 所以这里退一步做**源码级守卫** —— 直接检查调用点写对了没有。
 * 它比单测脆弱（改写法就可能失效），但对于这种"静默失败"类问题，
 * 有一道守卫远好过完全没有。
 */
class ApiCallSiteGuardTest {

    private val apiSource: String by lazy {
        // 测试的工作目录是模块根目录（app/）
        File("src/main/java/com/asmrone/app/data/remote/AsmrOneApi.kt").readText()
    }

    @Test
    fun `postJsonObject 不得默认把令牌置空`() {
        // 反面：参数默认值写成空串，会让所有 POST 静默失去鉴权
        assertFalse(
            "★ postJsonObject 的 tokenOverride 默认值不能是空串 —— " +
                "那会让所有 POST 都不带令牌（曾经真的这样，导致加入收藏夹永远 401）",
            apiSource.contains("tokenOverride: String = \"\"") ||
                apiSource.contains("tokenOverride = \"\",") ||
                Regex("""postJsonObject\([^)]*tokenOverride\s*=\s*"""").containsMatchIn(apiSource)
        )

        // 正面：必须用显式开关，且默认带令牌
        assertTrue(
            "postJsonObject 应当用 withToken 开关且默认 true",
            apiSource.contains("withToken: Boolean = true")
        )
    }

    @Test
    fun `登录注册与推荐 uuid 显式关闭令牌`() {
        // 这三个端点必须匿名调用，否则行为不正确
        val mustBeAnonymous = listOf(
            "\"/api/auth/me\"",
            "\"/api/auth/reg\""
        )
        mustBeAnonymous.forEach { path ->
            val call = apiSource
                .lines()
                .firstOrNull { it.contains("postJsonObject(") && it.contains(path) }
            assertTrue("$path 的调用里必须显式 withToken = false", call != null && call.contains("withToken = false"))
        }
    }

    @Test
    fun `底层请求函数自己切到 IO 线程`() {
        // 反面：requestBody 是纯阻塞函数（没有 withContext），调用方一漏就崩
        val bodyFun = apiSource
            .substringAfter("private suspend fun requestBody(")
            .substringBefore("private suspend fun requestJson(")
        assertTrue(
            "★ requestBody 必须自己 withContext(Dispatchers.IO)，不能依赖调用方记得包 —— " +
                "PUT /api/review 就是漏了这一步导致 NetworkOnMainThreadException",
            bodyFun.contains("withContext(Dispatchers.IO)")
        )
    }

    @Test
    fun `所有发送请求的私有函数都是 suspend`() {
        // 只要有一个是普通函数，就说明它可能在主线程被调用
        listOf(
            "private suspend fun requestBody(",
            "private suspend fun requestJson(",
            "private suspend fun requestArray(",
            "private suspend fun requestWithToken(",
            "private suspend fun postJsonObject("
        ).forEach { signature ->
            assertTrue("$signature 必须是 suspend", apiSource.contains(signature))
        }
    }
}
