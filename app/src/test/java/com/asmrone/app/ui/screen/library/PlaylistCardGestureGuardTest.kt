package com.asmrone.app.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 收藏夹卡片的**手势**守卫。
 *
 * ## 这条守卫对应的真实 bug
 *
 * 收藏夹卡片上叠了两层手势：
 *
 * ```kotlin
 * .clickable(onClick = onClick)                              // 外层：真正要执行的
 * .then(if (system) Modifier else
 *       Modifier.combinedClickable(onClick = {}, …))         // 内层：空的 onClick
 * ```
 *
 * 同一节点上后声明（更内层）的手势会先拿到并消费事件，于是**点击被内层那个空
 * onClick 吃掉**：非系统收藏夹点了有涟漪、但什么都不发生，永远进不去。
 * 系统收藏夹反而正常 —— 它不挂长按，链上只有外层那一个 clickable。
 *
 * 症状是"我在网页端新建了一个收藏夹，往里加了作品，却点不开它"。
 * 这类问题不能靠数据层单测发现，也不值得为它引入 Compose UI 测试依赖
 * （会破坏本工程"离线可构建、依赖全部命中本地缓存"的约束），
 * 所以按仓库里既有做法（`DetailLinkGuardTest` / `SearchDisplayNameGuardTest`）
 * 用源码级断言把它钉住。
 *
 * ⚠️ 断言前必须先**去掉注释**：解释这个 bug 的注释里本身就写着那段坏代码，
 * 否则守卫会被自己的说明文字绊倒（第一版就踩了这个坑）。
 */
class PlaylistCardGestureGuardTest {

    private val rawSource: String by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/library/LibraryScreen.kt").readText()
    }

    /** 去掉行注释与块注释后的源码 —— 断言只看真正的代码 */
    private val source: String by lazy { stripComments(rawSource) }

    @Test
    fun `★ 手势修饰符不能带空 onClick`() {
        // 空 onClick 正是当初吞掉点击的那一层。它出现在任何地方都说明
        // "又有人想用两个修饰符拼一次点击 + 长按"。
        assertFalse(
            "★ 不该出现 `onClick = {}` —— 那会消费掉事件却不做任何事，" +
                "外层的 clickable 永远收不到点击",
            source.contains("onClick = {}")
        )
    }

    @Test
    fun `★ 收藏夹卡片只挂一个手势修饰符`() {
        val body = functionBody(source, "private fun PlaylistCard(")
        assertTrue("PlaylistCard 应当存在", body.isNotEmpty())

        assertTrue(
            "点击与长按要由 combinedClickableCompat 一次承担",
            body.contains("combinedClickableCompat(")
        )
        assertFalse(
            "★ 不该再叠一层 .clickable —— 两个手势修饰符同时存在时，" +
                "更内层那个会先消费事件。实际函数体：\n$body",
            body.contains(".clickable(")
        )
    }

    @Test
    fun `★ 卡片的点击真的接到了 onClick`() {
        val body = functionBody(source, "private fun PlaylistCard(")
        assertTrue(
            "★ 必须把 onClick 原样传下去，而不是传空实现",
            body.contains("onClick = onClick")
        )
    }

    @Test
    fun `★ 系统收藏夹不挂长按（不允许删除）`() {
        val body = functionBody(source, "private fun PlaylistCard(")
        assertTrue(
            "系统收藏夹应当传 null 长按回调",
            body.contains("if (playlist.isSystem) null else onLongClick")
        )
    }

    @Test
    fun `★ combinedClickableCompat 同时承担点击与长按`() {
        // 它是表达式函数体（`: Modifier = this.combinedClickable(…)`），没有大括号，
        // 所以取签名之后的一段窗口来判断，而不是做大括号配对。
        val at = source.indexOf("private fun Modifier.combinedClickableCompat(")
        assertTrue("combinedClickableCompat 应当存在", at >= 0)
        val window = source.substring(at, (at + 600).coerceAtMost(source.length))

        assertTrue("必须接点击：\n$window", window.contains("onClick = onClick"))
        assertTrue("必须接长按（可为 null）：\n$window", window.contains("onLongClick = onLongClick"))
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 去掉 `//` 行注释与 `/* … */` 块注释（够用即可：本项目没有字符串里带注释符号的行） */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }

    /** 按大括号配对取函数体（从签名处开始找第一个 `{`） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) return ""
        val open = source.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        return ""
    }
}
