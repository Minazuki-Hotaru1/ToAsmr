package com.asmrone.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字典更新结果的文案与判定。
 *
 * 这些字符串会**直接显示给用户**，而且是"重试还是跳过"的依据 ——
 * 所以它必须说清**是哪一本、为什么**没成功，而不是笼统一句"更新失败"。
 */
class DictionaryUpdateTest {

    private fun outcome(succeeded: Int, counts: Map<String, Int>, failures: List<String>) =
        DictionaryUpdateOutcome(succeeded, counts, failures)

    // ═══════════════ 成功 ═══════════════

    @Test
    fun `三本都成功时说明总条数`() {
        val o = outcome(3, mapOf("标签" to 422, "声优" to 5552, "社团" to 5882), emptyList())
        assertTrue(o.isComplete)
        assertFalse(o.isPartial)
        assertEquals("更新成功，共 11856 条", o.message(11856))
    }

    // ═══════════════ 部分成功 ═══════════════

    @Test
    fun `部分成功时要点出哪一本没更新到`() {
        val o = outcome(1, mapOf("标签" to 422), listOf("声优（连接超时）", "社团（连接超时）"))
        assertTrue(o.isPartial)
        assertFalse(o.isComplete)
        assertEquals(
            "更新成功，共 422 条；但声优（连接超时）、社团（连接超时）没更新到",
            o.message(422)
        )
    }

    @Test
    fun `服务端返回空内容也算没更新到，且说清是这个原因`() {
        val o = outcome(2, mapOf("标签" to 422, "声优" to 5552), listOf("社团（服务端没有返回内容）"))
        assertTrue(o.message(5974).contains("服务端没有返回内容"))
    }

    // ═══════════════ 全失败 ═══════════════

    @Test
    fun `全失败时带上每一本的原因`() {
        val o = outcome(0, emptyMap(), listOf("标签（连接超时）", "声优（无法解析服务器地址）", "社团（连接超时）"))
        assertFalse(o.isComplete)
        assertFalse("一本都没成功就不算部分成功", o.isPartial)
        assertEquals(
            "更新失败：标签（连接超时）；声优（无法解析服务器地址）；社团（连接超时）",
            o.message(0)
        )
    }

    @Test
    fun `★ 失败文案绝不会是空串`() {
        // 这条很重要：失败提示是用户唯一的线索。
        // 早先"飞一下就去首页"的观感里，就有一部分是提示不够明确造成的。
        val cases = listOf(
            outcome(0, emptyMap(), listOf("标签（连接超时）")),
            outcome(0, emptyMap(), listOf("标签（服务端没有返回内容）")),
            outcome(0, emptyMap(), listOf("标签（x）", "声优（y）"))
        )
        cases.forEach {
            assertTrue("失败文案不能为空：$it", it.message(0).isNotBlank())
            assertTrue("要点出是哪一本：$it", it.message(0).contains("标签"))
        }
    }

    @Test
    fun `失败文案里每一条原因都保留`() {
        val o = outcome(0, emptyMap(), listOf("标签（A）", "声优（B）", "社团（C）"))
        val m = o.message(0)
        listOf("A", "B", "C").forEach { assertTrue("$it 应当出现在文案里：$m", m.contains(it)) }
    }

    @Test
    fun `全失败时不把 succeeded 说成部分成功`() {
        // isPartial 的定义是"有成功也有失败"；一本都没成功时界面不该给出
        // "部分成功"这种半安慰的措辞
        assertFalse(outcome(0, emptyMap(), listOf("标签（x）")).isPartial)
    }
}