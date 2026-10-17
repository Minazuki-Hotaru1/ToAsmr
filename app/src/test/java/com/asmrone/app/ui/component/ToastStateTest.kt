package com.asmrone.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻量提示的状态机测试。
 *
 * 这个组件是从 comicApp 的漫画详情页抽出来的（标签复制 / 更新提示），
 * 它的样式简单，但**状态推进有两个容易写错的点**，所以值得钉住：
 *
 *  1. 文字与"是否可见"必须分开 —— 淡出期间要继续显示文字，
 *     否则提示框会"空着"闪一下才消失；
 *  2. 自动消失靠自增序号重启计时 —— 连续触发两次，第二次也要能重新计时，
 *     否则第二次提示会立刻消失（沿用第一次的剩余时间）。
 */
class ToastStateTest {

    @Test
    fun `初始状态不显示`() {
        val toast = ToastState()
        assertFalse(toast.isVisible)
        assertEquals("", toast.text)
    }

    @Test
    fun `show 之后可见且带上文字`() {
        val toast = ToastState()
        toast.show("刷新失败")
        assertTrue(toast.isVisible)
        assertEquals("刷新失败", toast.text)
    }

    @Test
    fun `dismiss 只隐藏，文字保留到淡出结束`() {
        val toast = ToastState()
        toast.show("刷新失败")
        toast.dismiss()

        assertFalse("已经不可见", toast.isVisible)
        assertEquals(
            "★ 文字必须保留 —— 淡出动画还在用它，清空会让提示框空着闪一下",
            "刷新失败",
            toast.text
        )
    }

    @Test
    fun `每次 show 都推进序号（用来重启自动消失计时）`() {
        val toast = ToastState()
        val before = toast.seq
        toast.show("第一次")
        assertEquals(before + 1, toast.seq)
        toast.show("第二次")
        assertEquals(
            "★ 序号必须继续自增，否则第二次提示会沿用第一次的剩余时间而立刻消失",
            before + 2,
            toast.seq
        )
        assertEquals("文字被替换为最新一条", "第二次", toast.text)
    }

    @Test
    fun `重复 show 同一条内容也算一次新的触发`() {
        val toast = ToastState()
        toast.show("刷新失败")
        val first = toast.seq
        toast.show("刷新失败")
        assertEquals(
            "用户连点两次刷新，两次都该完整显示（而不是第二次被忽略）",
            first + 1,
            toast.seq
        )
    }

    @Test
    fun `空消息被忽略，不会显示一个空框`() {
        val toast = ToastState()
        toast.show("")
        assertFalse("空字符串不该触发提示", toast.isVisible)

        toast.show("   ")
        assertFalse("纯空白同样忽略", toast.isVisible)
        assertEquals("", toast.text)
    }

    @Test
    fun `隐藏之后仍可再次显示`() {
        val toast = ToastState()
        toast.show("第一次")
        toast.dismiss()
        toast.show("第二次")
        assertTrue(toast.isVisible)
        assertEquals("第二次", toast.text)
    }
}
