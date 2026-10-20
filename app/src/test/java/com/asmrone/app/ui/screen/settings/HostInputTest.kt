package com.asmrone.app.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主机地址输入的规范化与校验（`ServerSettingsScreen.normalizeHostInput`）。
 *
 * 这个函数决定"什么能进 AppPrefs.host"：写进去的值会被拼进此后每一个请求的 URL，
 * 所以它必须**宁可拒绝也不要放过半个地址** —— 一旦存了个 `api.asmr.one`（没有协议），
 * 表现是所有页面都报"网络不可用"，而用户完全看不出是自己打错了。
 */
class HostInputTest {

    @Test
    fun `缺少协议时补 https`() {
        assertEquals("https://api.asmr.one", normalizeHostInput("api.asmr.one"))
    }

    @Test
    fun `保留用户写的 http`() {
        // 自建 Kikoeru 常常没有证书，http 必须能用
        assertEquals("http://192.168.1.9:8800", normalizeHostInput("http://192.168.1.9:8800"))
    }

    @Test
    fun `去首尾空白与结尾斜杠`() {
        assertEquals("https://api.asmr.one", normalizeHostInput("  https://api.asmr.one/  "))
    }

    @Test
    fun `域名转小写但路径保持原样`() {
        // 子路径大小写敏感：/Kikoeru 与 /kikoeru 是两个地址
        assertEquals(
            "https://example.com/Kikoeru/api",
            normalizeHostInput("HTTPS://Example.COM/Kikoeru/api")
        )
    }

    @Test
    fun `保留端口`() {
        assertEquals("https://example.com:8443", normalizeHostInput("example.com:8443"))
    }

    @Test
    fun `空输入返回空`() {
        assertNull(normalizeHostInput(""))
        assertNull(normalizeHostInput("   "))
        assertNull(normalizeHostInput("/"))
    }

    @Test
    fun `没有点的主机名被拒绝`() {
        // localhost 在手机上指向手机自己，不是用户想要的那台服务器
        assertNull(normalizeHostInput("localhost:8080"))
    }

    @Test
    fun `中间有空格被拒绝`() {
        assertNull(normalizeHostInput("https://api asmr.one"))
    }

    @Test
    fun `重复协议被拒绝`() {
        assertNull(normalizeHostInput("https://https://api.asmr.one"))
    }

    @Test
    fun `协议不是 http 或 https 被拒绝`() {
        assertNull(normalizeHostInput("ftp://example.com"))
    }

    @Test
    fun `域名首尾有点被拒绝`() {
        assertNull(normalizeHostInput("https://.example.com"))
        assertNull(normalizeHostInput("https://example.com."))
    }
}
