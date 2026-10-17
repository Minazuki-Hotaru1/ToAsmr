package com.asmrone.app.ui.error

import com.asmrone.app.data.remote.AsmrApiException
import com.asmrone.app.data.remote.AuthRejectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 失败分类测试。
 *
 * 起因：标记失败时界面只显示一句"收藏操作失败"，用户看不出到底为什么、
 * 也不知道下一步做什么。这个分类器负责把异常翻成**原因 + 该怎么办**，
 * 所以每一类都必须能被正确识别 —— 分错了就会给出错误的操作建议
 * （比如把"网络超时"说成"去重新登录"）。
 */
class MarkFailureClassifierTest {

    @Test
    fun `401 识别为登录失效并建议重新登录`() {
        val f = classifyMarkFailure(AuthRejectedException("用户名或密码不正确", 401))
        assertEquals(MarkFailureKind.UNAUTHORIZED, f.kind)
        assertTrue("标题要说明是登录问题", f.message.contains("登录"))
        assertTrue("建议必须指向重新登录", f.hint.contains("重新登录"))
    }

    @Test
    fun `403 识别为权限不足而不是登录失效`() {
        val f = classifyMarkFailure(AuthRejectedException("服务器拒绝了这次请求", 403))
        assertEquals(MarkFailureKind.FORBIDDEN, f.kind)
        assertTrue(f.message.contains("权限"))
        // 关键：不能建议 403 去重新登录，那会误导用户
        assertTrue("403 不该建议重登", !f.hint.contains("重新登录"))
    }

    @Test
    fun `超时给出网络建议`() {
        val f = classifyMarkFailure(SocketTimeoutException("timeout"))
        assertEquals(MarkFailureKind.TIMEOUT, f.kind)
        assertTrue(f.hint.contains("网络"))
    }

    @Test
    fun `域名解析失败给出换主机的建议`() {
        val f = classifyMarkFailure(UnknownHostException("api.example.invalid"))
        assertEquals(MarkFailureKind.NETWORK, f.kind)
        assertTrue("解析失败要提到换 API 主机", f.hint.contains("服务器"))
    }

    @Test
    fun `TLS 失败提示用 VPN`() {
        val f = classifyMarkFailure(SSLException("stream closed"))
        assertEquals(MarkFailureKind.NETWORK, f.kind)
        assertTrue(f.hint.contains("代理") || f.hint.contains("VPN"))
    }

    @Test
    fun `5xx 说明是服务端问题`() {
        val f = classifyMarkFailure(AsmrApiException("服务器返回 500：internal error"))
        assertEquals(MarkFailureKind.SERVER, f.kind)
        assertTrue("要带上状态码便于反馈", f.message.contains("500"))
        assertTrue("5xx 应说明是服务端的问题", f.hint.contains("服务端"))
    }

    @Test
    fun `解析失败单独归类`() {
        val f = classifyMarkFailure(AsmrApiException("解析响应失败：unexpected token <"))
        assertEquals(MarkFailureKind.RESPONSE_FORMAT, f.kind)
    }

    @Test
    fun `未知异常也给出可执行的建议`() {
        val f = classifyMarkFailure(IllegalStateException("boom"))
        assertEquals(MarkFailureKind.UNKNOWN, f.kind)
        assertTrue("标题不能为空", f.message.isNotBlank())
        assertTrue("总要给一句下一步", f.hint.isNotBlank())
    }

    @Test
    fun `未登录是明确状态而不是异常`() {
        val f = notLoggedInFailure()
        assertEquals(MarkFailureKind.NOT_LOGGED_IN, f.kind)
        assertTrue(f.hint.contains("登录"))
    }

    @Test
    fun `原始异常文本被保留供排查`() {
        val f = classifyMarkFailure(AsmrApiException("服务器返回 502：bad gateway"))
        assertEquals("详情要保留原始文案", "服务器返回 502：bad gateway", f.detail)
    }
}
