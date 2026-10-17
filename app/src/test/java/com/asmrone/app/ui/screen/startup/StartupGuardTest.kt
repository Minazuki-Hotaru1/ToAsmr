package com.asmrone.app.ui.screen.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「失败页面一闪而过」那次问题的**回归守卫**。
 *
 * ## 现象
 *
 * 飞行模式下首次进入，失败卡片刚出现就被首页顶掉了，用户既看不清原因、
 * 也来不及点「重试」。
 *
 * ## 为什么这类问题要用源码级守卫
 *
 * 它**没有编译错误、单测也测不到** —— 因为问题出在"哪个状态会触发自动跳转"
 * 这层组合逻辑上，而 ViewModel 依赖 `AppPrefs` / `AsmrRepository`（都需要
 * Android `Context`），在 JVM 单测里起不来。
 *
 * 所以这里直接断言源码里的**不变量**：
 *
 * 1. `readyOk` 只在成功时被置 true —— 失败路径不存在自动跳转
 * 2. `proceed()` 拒绝在拉取中放行 —— 否则任何误触都会跳过"准备中"
 * 3. 自动放行的 `LaunchedEffect` 必须以 `readyOk` 为条件
 * 4. 失败原因写进状态（`failureDetail`），不只靠 1.5 秒的 toast
 * 5. 慢网提示的阈值是 10 秒
 *
 * 与 `SearchDisplayNameGuardTest` / `CollectionSyncGuardTest` 同一套路数。
 */
class StartupGuardTest {

    private val sourceDir = File("src/main/java/com/asmrone/app")
    private val viewModel by lazy { File(sourceDir, "ui/screen/startup/StartupViewModel.kt").readText() }
    private val screen by lazy { File(sourceDir, "ui/screen/startup/StartupScreen.kt").readText() }

    /** 取出某个函数的函数体（大括号配对），避免匹配到别的函数 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val braceStart = source.indexOf('{', start)
        var depth = 0
        var i = braceStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(braceStart, i + 1)
                }
            }
            i++
        }
        error("大括号不配对：$signature")
    }

    // ═══════════════ 失败路径没有自动跳转 ═══════════════

    @Test
    fun `★ readyOk 只在成功时置为 true`() {
        val body = functionBody(viewModel, "private fun fetch()")
        assertTrue(
            "必须有 readyOk = ready 这一处，且 ready 来自 dictionariesReady()",
            body.contains("readyOk = ready")
        )
        assertTrue("ready 必须由 dictionariesReady() 得出", body.contains("AsmrRepository.dictionariesReady()"))
        // 不能出现无条件 readyOk = true
        assertFalse(
            "★ 无条件置 true 会让失败也被自动放行 —— 那正是「闪一下」的成因",
            body.contains("readyOk = true")
        )
    }

    @Test
    fun `★ 自动放行的效果以 readyOk 为条件`() {
        assertTrue(
            "LaunchedEffect 必须监听 readyOk",
            screen.contains("LaunchedEffect(state.readyOk)")
        )
        val effect = screen.substringAfter("LaunchedEffect(state.readyOk)")
        assertTrue("只在 readyOk 为真时才放行", effect.contains("if (state.readyOk)"))
        assertTrue("放行前要留出读提示的时间", effect.contains("delay("))
    }

    @Test
    fun `★ 拉取中也要给用户一条出路（慢网时不该被迫干等）`() {
        val body = functionBody(viewModel, "fun proceed()")
        assertFalse(
            "★ 不能再用 stage 拦它 —— 界面在拉取中「暂不更新」时调的就是这个",
            body.contains("StartupStage.FETCHING) return")
        )
        assertTrue("放行就是切到 DONE", body.contains("StartupStage.DONE"))
    }

    @Test
    fun `★ 界面在拉取中就提供「暂不更新」入口`() {
        val fetching = screen
            .substringAfter("StartupStage.FETCHING ->")
            .substringBefore("StartupStage.READY ->")
        assertTrue(
            "拉取中必须能看到离开的入口",
            fetching.contains("FOREGO_DICTIONARIES")
        )
    }

    @Test
    fun `失败卡片只在拉取结束且未成功时出现`() {
        assertTrue(
            "showFailure 必须同时看 stage 与 readyOk",
            viewModel.contains("stage == StartupStage.READY && !readyOk")
        )
        assertTrue("界面用 showFailure 判断", screen.contains("state.readyOk"))
    }

    // ═══════════════ 失败原因要常驻 ═══════════════

    @Test
    fun `★ 失败原因写进状态，不只靠 toast`() {
        assertTrue("状态里要有 failureDetail 字段", viewModel.contains("val failureDetail: String?"))
        val body = functionBody(viewModel, "private fun fetch()")
        assertTrue(
            "失败时要把原因写进 failureDetail",
            body.contains("failureDetail = if (ready) null else")
        )
        assertTrue(
            "界面要把 failureDetail 显示出来",
            screen.contains("state.failureDetail")
        )
    }

    @Test
    fun `静止的失败卡片上不再有自动跳转`() {
        // FailureContent 里只应有重试与暂不更新两个入口
        val body = functionBody(screen, "private fun FailureContent(")
        assertTrue(body.contains("onRetry"))
        assertTrue(body.contains("onSkip"))
        assertFalse(
            "★ 失败卡片里不该出现任何「自动」逻辑",
            body.contains("LaunchedEffect") || body.contains("delay(")
        )
    }

    // ═══════════════ 避开会触发广告跳过工具的文案 ═══════════════

    @Test
    fun `★ 界面文案里不能出现「跳过」两个字`() {
        // 用户装了跳过开屏广告的工具，那类工具用无障碍服务**按文本找按钮**
        // （找的就是「跳过」「跳过广告」），会在这一屏刚出现时自动点掉 ——
        // 现象就是"失败页面闪一下就进了首页"。
        //
        // 只检查**用户可见的部分**：注释里说明这个坑是应该的，所以要按 Kotlin
        // 的注释语法把它们去掉 —— 包括 `/** … */` 这种单行块注释
        // （第一版守卫就是漏了它，被自己的文档注释绊了一跤）。
        val userVisible = stripComments(screen)
        assertFalse(
            "★ 可见文案里出现「跳过」会被广告跳过工具点掉",
            userVisible.contains("跳过")
        )
    }

    /** 去掉 `//` 行注释与 `/* … */` 块注释（含单行的 `/** … */`） */
    private fun stripComments(source: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        return noBlock.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun `★ 离开入口的文案由常量统一提供`() {
        // 集中一处，避免以后有人在别处又写回「跳过」
        assertTrue(screen.contains("internal const val FOREGO_DICTIONARIES ="))
        assertTrue("按钮显示这个常量", screen.contains("Text(FOREGO_DICTIONARIES)"))
        val value = screen.substringAfter("internal const val FOREGO_DICTIONARIES =")
            .substringBefore("\n").trim()
        assertFalse("常量值本身也不能是「跳过」", value.contains("跳过"))
    }

    // ═══════════════ 慢网提示 ═══════════════

    @Test
    fun `慢网提示阈值为 10 秒`() {
        assertTrue(
            "常量应为 10_000L",
            viewModel.contains("const val SLOW_NETWORK_HINT_MS = 10_000L")
        )
        assertTrue("拉取时要起计时", viewModel.contains("delay(SLOW_NETWORK_HINT_MS)"))
    }

    @Test
    fun `慢网提示只解释不中断拉取`() {
        val body = functionBody(viewModel, "private fun fetch()")
        val hint = body.substringAfter("delay(SLOW_NETWORK_HINT_MS)").substringBefore("}")
        assertFalse("★ 慢网提示不该取消拉取 —— 移动网络慢但能完成的情况是存在的", hint.contains("cancel"))
    }

    @Test
    fun `慢网提示的文案给可执行的建议`() {
        val body = functionBody(screen, "private fun SlowNetworkHint(")
        assertTrue("要说网络环境较差", body.contains("网络环境较差"))
        assertTrue("要给出可执行的建议", body.contains("Wi-Fi") || body.contains("更换网络"))
    }

    // ═══════════════ 并发：旧的拉取不能覆盖新的 ═══════════════

    @Test
    fun `★ 重试时旧的那次拉取不能覆盖新结果`() {
        assertTrue(
            "要有拉取代数计数",
            viewModel.contains("private var fetchGeneration = 0")
        )
        assertTrue("每次拉取领一个号", viewModel.contains("val generation = ++fetchGeneration"))
        assertTrue(
            "★ 被取代的那次必须提前退出，否则它晚一步返回会把新结果顶掉",
            viewModel.contains("if (generation != fetchGeneration) return@launch")
        )
    }

    @Test
    fun `进度回调也只由当前那次写`() {
        val body = functionBody(viewModel, "private fun fetch()")
        assertTrue(
            "旧的那次不能写进度，否则「正在更新声优」会倒着跳",
            body.contains("if (generation == fetchGeneration)")
        )
    }

    // ═══════════════ 首次判断 ═══════════════

    @Test
    fun `已初始化且数据齐全时直接放行，不打扰用户`() {
        val body = functionBody(viewModel, "fun start()")
        assertTrue(
            "两个条件缺一不可：标记 + 数据",
            body.contains("AppPrefs.dictionariesInitialized.value && AsmrRepository.dictionariesReady()")
        )
        assertTrue("直接进 DONE", body.contains("StartupStage.DONE"))
    }
}