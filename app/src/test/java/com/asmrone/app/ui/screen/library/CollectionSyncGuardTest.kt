package com.asmrone.app.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「什么时候允许与云端交互」的守卫（源码级断言）。
 *
 * ## 设计原则：把机制编在代码里，不靠"额度"
 *
 * 这里原先守的是一个 `SyncScope`（DAILY / MANUAL）+ 存在 SharedPreferences 里的
 * "今天已核对"日期标记。那套设计的根本问题是**判断散落**：
 * 门禁在一处，调用方各自传 `daily` 布尔值，于是任何一处写错就能静默绕过，
 * 而且**编译通过、单测全绿** —— 只有真机抓包才看得出来。
 *
 * 这个错**真的犯了两次**：
 *  1. 仓库层把 `SyncScope` 写死成 `MANUAL`
 *  2. UI 层用 `remember` 记"账号是否变化过"，而切页签会重建 composable，
 *     于是每次进收藏都被当成"账号刚变化"，走了一条刻意绕过门槛的路径
 *
 * 现在改成：请求只能带着**三个封闭的理由**（见 SyncTrigger）从
 * `SyncGate.sync` 发出，而"本次会话只自动一次"由闸门统一保证。
 * 所以守卫的目标也变了：不再是"确保大家记得传 daily"，而是
 * **确保没有第二套判断、没有绕过闸门的入口**。
 */
class CollectionSyncGuardTest {

    private val repoSource: String by lazy {
        File("src/main/java/com/asmrone/app/data/repo/AsmrRepository.kt").readText()
    }

    private val collectionVm: String by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/library/CollectionViewModel.kt").readText()
    }

    private val playlistsVm: String by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/library/PlaylistsViewModel.kt").readText()
    }

    private val libraryScreen: String by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/library/LibraryScreen.kt").readText()
    }

    // ═══════════ 请求只能从闸门发出 ═══════════

    @Test
    fun `仓库层每个列表加载都通过闸门`() {
        listOf("loadCollection", "loadPlaylists", "loadPlaylistWorks", "getPlaylistMetadata")
            .forEach { name ->
                val idx = repoSource.indexOf("suspend fun $name(")
                assertTrue("$name 应当存在", idx >= 0)
                val signature = repoSource.substring(idx, (idx + 400).coerceAtMost(repoSource.length))
                assertTrue(
                    "★ $name 必须接受 SyncTrigger —— 请求必须带着一个明确的理由",
                    signature.contains("trigger: SyncTrigger?")
                )
            }
    }

    @Test
    fun `★ 仓库层所有网络对齐都走 SyncGate`() {
        assertTrue("应当存在 SyncGate.sync 调用", repoSource.contains("SyncGate.sync("))
        assertFalse(
            "★ 不该再有 validate/daily 这对布尔参数 —— 它们正是「谁都能绕过」的来源",
            repoSource.contains("daily: Boolean") || repoSource.contains("validate: Boolean")
        )
    }

    @Test
    fun `★ 自动路径只允许一个：DAILY_AUTO`() {
        val sessionFirst = Regex("SyncTrigger\\.DAILY_AUTO").findAll(repoSource).count()
        assertTrue("仓库层的自动路径应当是 DAILY_AUTO（目前 $sessionFirst 处）", sessionFirst >= 1)
        assertTrue(
            "翻页应当用 USER_REFRESH（它本来只在用户滚到底时触发）",
            repoSource.contains("SyncTrigger.USER_REFRESH)")
        )
    }

    // ═══════════ UI 层不该再有任何门槛判断 ═══════════

    @Test
    fun `★ UI 层不再判断「该不该发请求」`() {
        // 这是本轮重构的核心：判断只在 SyncGate 一处。
        listOf(collectionVm to "CollectionViewModel", playlistsVm to "PlaylistsViewModel")
            .forEach { (src, name) ->
                assertFalse(
                    "★ $name 不该再自己判断门禁 —— 交给 SyncGate",
                    src.contains("shouldAutoSync") || src.contains("hasCache") ||
                        src.contains("shouldSync")
                )
                assertFalse(
                    "★ $name 不该再传 validate/daily 布尔值",
                    src.contains("daily =") || src.contains("validate =")
                )
            }
    }

    @Test
    fun `★ LibraryScreen 不该再有账号跟踪状态`() {
        // ⚠️ 这一条是本轮真机 bug 的直接守卫：
        // 以前这里用 remember 记"账号是否变化过"，而切页签会重建 composable，
        // 于是每次进收藏都被当成"账号刚变化"，走了一条绕过门槛的路径，
        // 表现为每切一次页签就打两个接口。
        assertFalse(
            "★ 不该再有账号跟踪状态 —— 归口判断已按 (数据类别, host, 账号) 记在 SyncGate 里",
            libraryScreen.contains("lastAccountKey") || libraryScreen.contains("accountKey")
        )
        assertFalse(
            "★ 不该在 UI 层用 reinitialize/onAccountChanged 强制核对",
            libraryScreen.contains("reinitialize = true") ||
                libraryScreen.contains("ViewModel.onAccountChanged()")
        )
    }

    @Test
    fun `★ 两个收藏 ViewModel 的进入与刷新各用一个理由`() {
        listOf(collectionVm to "CollectionViewModel", playlistsVm to "PlaylistsViewModel")
            .forEach { (src, name) ->
                assertTrue("$name.onEnter 应当存在", src.contains("fun onEnter("))
                assertTrue(
                    "$name 进入页面时用 DAILY_AUTO",
                    src.contains("SyncTrigger.DAILY_AUTO")
                )
                assertTrue(
                    "$name 用户刷新时用 USER_REFRESH",
                    src.contains("SyncTrigger.USER_REFRESH")
                )
            }
    }

    // ═══════════ 状态筛选必须是本地派生的 ═══════════
    //
    // 实测确认：`GET /api/review` 不带 filter 时每条都带 `progress`，
    // 所以「全部、想听…」共用一份本地 JSON，各筛选在读取时按 progress 派生。

    @Test
    fun `收藏列表的请求一律不带 filter`() {
        val hardcoded = Regex(
            """getMyCollectionConditional\([^)]*filter\s*=\s*status\?\.apiValue""",
            RegexOption.DOT_MATCHES_ALL
        )
        assertFalse(
            "★ 收藏列表请求不能带 filter —— 筛选是本地派生出来的",
            hardcoded.containsMatchIn(repoSource)
        )
        assertTrue(repoSource.contains("filter = null,"))
    }

    @Test
    fun `收藏只维护一份缓存键`() {
        val statusKeyed = Regex("""SyncKeys\.collection\([^)]*,\s*[^)]*\.apiValue\s*\)""")
        assertFalse("★ 不应再按状态分别建缓存键", statusKeyed.containsMatchIn(repoSource))
    }

    @Test
    fun `★ 切换筛选一个请求都不发`() {
        val body = functionBody(collectionVm, "fun selectFilter(")
            ?: error("selectFilter 应当存在且是块体函数")

        assertFalse(
            "★ 切筛选不能发请求 —— 筛选是本地派生的。实际函数体：\n$body",
            body.contains("validate = true") || body.contains("SyncTrigger.DAILY_AUTO") ||
                body.contains("SyncTrigger.USER_REFRESH")
        )
        assertTrue("应当显式传 trigger = null（只读本地）", body.contains("trigger = null"))
    }

    @Test
    fun `用户手动刷新一律不受限制`() {
        listOf(collectionVm, playlistsVm).forEach { src ->
            assertTrue("应当存在 USER_REFRESH 的手动路径", src.contains("SyncTrigger.USER_REFRESH"))
        }
    }

    // ═══════════ 旧设计不该复活 ═══════════

    @Test
    fun `★ 额度式的旧机制已经删掉 —— 防止被重新引入`() {
        val syncPolicy = File("src/main/java/com/asmrone/app/data/cache/SyncPolicy.kt").readText()

        assertFalse(
            "★ 不该再有 SyncScope（DAILY/MANUAL）—— 它让每个调用方都能传错",
            syncPolicy.contains("enum class SyncScope")
        )
        assertFalse(
            "★ 不该再持久化「今天已核对」的日期标记 —— 那是额度式设计的核心",
            syncPolicy.contains("PolicyDayState") || syncPolicy.contains("lastAttemptDay")
        )
        assertFalse(
            "★ 不该再有 shouldAutoSyncForEntry ——「本地有没有缓存」这个判断本身就是多余的复杂来源",
            syncPolicy.contains("shouldAutoSyncForEntry")
        )
        assertTrue(
            "闸门应当把「今天已对齐」落盘，并按 (数据类别, host, 账号) 分开记",
            syncPolicy.contains("AppPrefs.autoSyncDay(") && syncPolicy.contains("AppPrefs.setAutoSyncDay(")
        )
    }

    /**
     * 按大括号配对取出一个函数的函数体。
     *
     * 不能用"截到下一个 `private fun`"这种取巧办法 —— 紧接着的 `refresh()` /
     * `pullRefresh()` 是 public，会被漏掉，于是窗口滑进后面的函数里造成误报
     * （第一版就这么错过一次）。
     */
    private fun functionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null
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
        return null
    }
}
