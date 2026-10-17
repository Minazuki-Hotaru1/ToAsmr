package com.asmrone.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 内置文本查看器的编码判定。
 *
 * ## 为什么这组测试重要
 *
 * 服务端对文本只回 `Content-Type: text/plain`，**不带 charset**（实测），
 * 而内容确实有多种编码。判定错了用户看到的就是满屏乱码 ——
 * 这是"txt 一律内置"这条路线里唯一有真实技术风险的部分。
 *
 * ## 这组测试记录了一次真实的方法论错误
 *
 * 我最初用"宽松解码后数替换符 / 数假名汉字"打分来猜编码，
 * 结果把 **UTF-8 判成了 GBK** —— 因为"UTF-8 的字节被 GBK 解释"
 * 恰好会产生大量**合法**汉字（`浣犵湅鈥` 这类特征串），打分完全看不出问题。
 *
 * 所以改成**字节级严格校验**（`CodingErrorAction.REPORT`）：
 * 非法字节序列直接抛异常，不给"看起来像"留余地。
 * 下面的 `★ 严格校验能识破伪装的乱码` 就是钉住这个教训的。
 */
class TextDecoderTest {

    private fun bytesOf(text: String, charset: String) =
        text.toByteArray(Charset.forName(charset))

    // ═══════════════ UTF-8 ═══════════════

    @Test
    fun `★ UTF-8 中文被正确识别`() {
        // 实测样本里最多的就是这类：中文作品说明
        val text = "効果音、行動に自然さを出すために、可能な限り音、声は同時収録しています。"
        val r = TextDecoder.decode(bytesOf(text, "UTF-8"), "说明.txt")
        assertEquals(TextCharset.UTF8, r.charset)
        assertEquals(text, r.text)
        assertFalse("无歧义，不该标记为不确定", r.uncertain)
    }

    @Test
    fun `★ UTF-8 日文被正确识别`() {
        val text = "これはテストです。"
        val r = TextDecoder.decode(bytesOf(text, "UTF-8"), "テスト.txt")
        assertEquals(TextCharset.UTF8, r.charset)
        assertEquals(text, r.text)
    }

    @Test
    fun `★ UTF-8 BOM 被去掉`() {
        // BOM 不去掉的话，正文开头会显示成一个方块
        val text = "\uFEFFこんにちは"
        val r = TextDecoder.decode(bytesOf(text, "UTF-8"), "a.txt")
        assertEquals("こんにちは", r.text)
        assertFalse(r.text.startsWith('\uFEFF'))
    }

    @Test
    fun `纯 ASCII 一律按 UTF-8 处理`() {
        val r = TextDecoder.decode("hello world".toByteArray(), "a.txt")
        assertEquals(TextCharset.UTF8, r.charset)
        assertEquals("hello world", r.text)
    }

    // ═══════════════ GBK ═══════════════

    @Test
    fun `★ GBK 简体中文被正确识别`() {
        // 实测 243515 的那个 txt 就是 GBK（用 UTF-8 解是乱码，用 GBK 解正常）
        val text = "这是一段简体中文说明，用于测试 GBK 判定。"
        val r = TextDecoder.decode(bytesOf(text, "GBK"), "台本.txt")
        assertEquals(TextCharset.GBK, r.charset)
        assertEquals(text, r.text)
        assertFalse("严格解出来了，不该标记为不确定", r.uncertain)
    }

    @Test
    fun `GBK 编码在 UTF-8 下不合法，所以不会被误判成 UTF-8`() {
        val gbk = bytesOf("这是一个测试文本内容", "GBK")
        // 先确认前提：这份字节确实不是合法 UTF-8
        val isStrictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(gbk))
        }.isSuccess
        assertFalse("前提不成立：这份 GBK 字节恰好也是合法 UTF-8", isStrictUtf8)

        assertEquals(TextCharset.GBK, TextDecoder.decode(gbk, "a.txt").charset)
    }

    // ═══════════════ Shift-JIS ═══════════════

    @Test
    fun `★ Shift-JIS 的日文能解出来`() {
        // 注意：部分日文文本在 GBK 下也"合法"，所以这里不硬断言必须判成
        // Shift-JIS —— 只要求**解出的内容正确**。这也是为什么要给用户
        // 手动切换编码的入口（见 TextCharset 的文档）
        val text = "あいうえお"
        val r = TextDecoder.decode(bytesOf(text, "Shift_JIS"), "テスト.txt")
        assertEquals("Shift-JIS 假名应能被正确还原", text, r.text)
    }

    @Test
    fun `★ 文件名含假名时优先试 Shift-JIS`() {
        // 文件名是日文的，内容基本也是日文编码
        val text = "こんにちは世界"
        val r = TextDecoder.decode(bytesOf(text, "Shift_JIS"), "日本語のファイル名.txt")
        assertEquals(text, r.text)
    }

    // ═══════════════ 手动指定 ═══════════════

    @Test
    fun `★ 手动指定编码时以用户为准`() {
        val gbk = bytesOf("中文内容", "GBK")
        val r = TextDecoder.decode(gbk, "a.txt", hint = TextCharset.GBK)
        assertEquals(TextCharset.GBK, r.charset)
        assertEquals("中文内容", r.text)
        assertFalse(r.uncertain)
    }

    @Test
    fun `★ 手动指定错了会标记为不确定`() {
        // 用户把 GBK 文件选成 Shift-JIS：要能提示"可能选错"
        val gbk = bytesOf("这是一个比较长的中文内容用于测试", "GBK")
        val r = TextDecoder.decode(gbk, "a.txt", hint = TextCharset.SHIFT_JIS)
        assertEquals(TextCharset.SHIFT_JIS, r.charset)
        assertTrue("解不干净时必须让界面能提示", r.uncertain)
    }

    @Test
    fun `★ 手动切到正确编码后不再标记不确定`() {
        // 这正是"给用户切换入口"的价值：切一下就从乱码变正常
        val gbk = bytesOf("这是一个比较长的中文内容用于测试", "GBK")
        val wrong = TextDecoder.decode(gbk, "a.txt", hint = TextCharset.SHIFT_JIS)
        val right = TextDecoder.decode(gbk, "a.txt", hint = TextCharset.GBK)

        assertTrue("选错时应有提示", wrong.uncertain)
        assertFalse("选对后不该再提示", right.uncertain)
        assertEquals("这是一个比较长的中文内容用于测试", right.text)
    }

    // ═══════════════ 边界 ═══════════════

    @Test
    fun `空字节返回空文本`() {
        val r = TextDecoder.decode(ByteArray(0), "a.txt")
        assertEquals("", r.text)
    }

    @Test
    fun `★ 严格校验能识破伪装的乱码`() {
        // 这条钉住那次方法论错误：
        // UTF-8 的字节用 GBK **宽松**解码会得到"看起来正常"的汉字，
        // 靠数乱码/数字符的打分法区分不了。严格校验能。
        val utf8 = bytesOf("简体中文测试内容比较长一些", "UTF-8")

        // 宽松解码成 GBK：内容完全不等于原文（这是伪装）
        val looseAsGbk = String(utf8, Charset.forName("GBK"))
        assertTrue("前提：宽松 GBK 解码确实会产生别的字符", looseAsGbk != "简体中文测试内容比较长一些")

        // 但严格校验立刻识破：这份字节不是合法 GBK 序列
        // （若恰好合法，下面的断言会失败，说明该样本不适用 —— 换样本即可）
        val decoded = TextDecoder.decode(utf8, "a.txt")
        assertEquals("合法 UTF-8 必须判成 UTF-8", TextCharset.UTF8, decoded.charset)
        assertEquals("简体中文测试内容比较长一些", decoded.text)
    }

    @Test
    fun `换行与制表符原样保留`() {
        val text = "第一行\n第二行\t制表"
        val r = TextDecoder.decode(bytesOf(text, "UTF-8"), "a.txt")
        assertEquals(text, r.text)
        assertTrue(r.text.contains('\n'))
        assertTrue(r.text.contains('\t'))
    }

    // ═══════════════ 编码枚举 ═══════════════

    @Test
    fun `三种编码都有可显示的名字`() {
        TextCharset.entries.forEach {
            assertTrue("$it 的名字不能为空", it.displayName.isNotBlank())
        }
        assertEquals(3, TextCharset.entries.size)
    }

    @Test
    fun `★ 尝试顺序以 UTF-8 开头`() {
        // 实测 187/211 是 UTF-8，且它的字节结构最严格（无歧义），先试它最划算
        assertEquals(TextCharset.UTF8, TextCharset.fallbackOrder.first())
    }
}
