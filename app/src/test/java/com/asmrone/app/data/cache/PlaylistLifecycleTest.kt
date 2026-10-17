package com.asmrone.app.data.cache

import com.asmrone.app.data.model.CollectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 收藏夹的**生命周期一致性**：创建 / 删除 / 加内容 三种操作不能留下"半截状态"。
 *
 * 这组测试对应三个真实缺陷（都由"每天只同步一次"这个前提放大）：
 *
 * | 缺陷 | 机制 | 后果 |
 * |---|---|---|
 * | 新收藏夹没有内容缓存 | 加内容时走到了"作废缓存"分支，而它**连列表缓存一起删** | 重启后「列表」页整页空白，要到明天才恢复 |
 * | 收藏夹已在别处删除 | 加内容时服务端回 `playlistNotFound`，却被当成普通网络失败 | 留下**永远失败**的待补写记录，每次对齐多打一次注定失败的请求 |
 * | 用户删除收藏夹 | 没清掉它的待补写操作 | 同上，且违背"用户已经不要这个收藏夹了"的语义 |
 */
class PlaylistLifecycleTest {

    private val cacheDir: File by lazy { File("src/main/java/com/asmrone/app/data/cache") }
    private val repoSource: String by lazy {
        File("src/main/java/com/asmrone/app/data/repo/AsmrRepository.kt").readText()
    }
    private val apiSource: String by lazy {
        File("src/main/java/com/asmrone/app/data/remote/AsmrOneApi.kt").readText()
    }

    // ═══════════════ ① 按收藏夹清理待补写（纯函数） ═══════════════

    private fun add(playlistId: String, workId: Int) =
        PendingWrite.PlaylistAdd(playlistId = playlistId, workId = workId, createdAt = 0L)

    private fun remove(playlistId: String, workId: Int) =
        PendingWrite.PlaylistRemove(playlistId = playlistId, workId = workId, createdAt = 0L)

    private fun mark(workId: Int) =
        PendingWrite.CollectionMark(
            workId = workId,
            status = CollectionStatus.LISTENED,
            rating = null,
            createdAt = 0L
        )

    @Test
    fun `★ 删掉某个收藏夹的加入意图`() {
        val items = listOf(add("A", 1), add("A", 2), add("B", 3))
        val kept = withoutPlaylist(items, "A")
        assertEquals("只该剩下 B 的那条", 1, kept.size)
        assertEquals("B", (kept.single() as PendingWrite.PlaylistAdd).playlistId)
    }

    @Test
    fun `★ 删掉某个收藏夹的移出意图`() {
        val items = listOf(remove("A", 1), remove("B", 2))
        val kept = withoutPlaylist(items, "A")
        assertEquals(1, kept.size)
        assertEquals("B", (kept.single() as PendingWrite.PlaylistRemove).playlistId)
    }

    @Test
    fun `★ 标记类的待补写不受影响`() {
        // 删一个收藏夹不该影响「标记」的未送达意图 —— 那是另一套数据
        val items = listOf(mark(99), add("A", 1))
        val kept = withoutPlaylist(items, "A")
        assertEquals(1, kept.size)
        assertTrue("标记必须保留", kept.single() is PendingWrite.CollectionMark)
    }

    @Test
    fun `★ 别的收藏夹完全不受影响`() {
        val items = (1..3).map { add("B", it) } + (1..2).map { remove("C", it) }
        assertEquals("删 A 不该动 B/C", items.size, withoutPlaylist(items, "A").size)
    }

    @Test
    fun `空列表与不存在的 id 都安全`() {
        assertTrue(withoutPlaylist(emptyList(), "A").isEmpty())
        assertEquals(2, withoutPlaylist(listOf(add("A", 1), add("B", 2)), "Z").size)
    }

    // ═══════════════ ② 创建与内容缓存原子建立 ═══════════════

    @Test
    fun `★ 新建收藏夹时同时建立内容缓存（同一流程里）`() {
        val body = functionBody(repoSource, "suspend fun createPlaylist(")
        assertTrue("createPlaylist 应当存在", body.isNotEmpty())
        assertTrue(
            "★ 创建后必须立刻建立它的内容缓存 —— 否则存在" +
                "「收藏夹已存在、但它的内容缓存还不存在」的窗口",
            body.contains("seedEmptyPlaylistContent(")
        )
        assertTrue(
            "而且要在同一个 runCatching 流程里（原子，不是两个独立操作）",
            body.indexOf("seedEmptyPlaylistContent(") < body.indexOf("currentPlaylistsKey()")
        )
    }

    @Test
    fun `★ 预置的空内容缓存是「完整」的`() {
        // complete = true 才成立：空列表就是它的全部内容。
        // 若标成不完整，加第一部作品时又会走到"作废"那条分支。
        val body = functionBody(repoSource, "private fun seedEmptyPlaylistContent(")
        assertTrue(body.isNotEmpty())
        assertTrue("必须标 complete = true", body.contains("complete = true"))
        assertTrue("必须是零请求（本地写，不联网）", !body.contains("api."))
        assertTrue(
            "已有内容时不能覆盖成空",
            body.contains("if (existing != null)") && body.contains("return")
        )
    }

    @Test
    fun `★ 每日扫描时给缺内容缓存的收藏夹补上`() {
        assertTrue(
            "列表同步后要检查缺内容缓存的收藏夹",
            repoSource.contains("seedMissingPlaylistContent(")
        )
        val body = functionBody(repoSource, "private suspend fun seedMissingPlaylistContent(")
        assertTrue(body.isNotEmpty())

        // ① 空收藏夹：零请求
        assertTrue(
            "worksCount == 0 时应当本地写空缓存（零请求）",
            body.contains("playlist.worksCount == 0") && body.contains("seedEmptyPlaylistContent(")
        )

        // ② 有内容的收藏夹：真的拉一次（用户明确认可这一份额外请求）
        assertTrue(
            "★ 有内容的收藏夹必须真的去拉一次 —— 用户认可「多一个请求也可以接受」",
            body.contains("loadPlaylistWorks(playlist.id")
        )
        assertFalse("★ 不能再因为 worksCount > 0 就跳过", body.contains("playlist.worksCount != 0) return"))

        // ③ 幂等：判定是「没有缓存」，不是「本次新出现」
        assertTrue(
            "判定条件是「本地没有内容缓存」",
            body.contains("SyncCacheStore.read(key, WorkPageCodec) != null")
        )
        assertFalse(
            "★ 不能再用「本次新出现」判定 —— 那会让限额跳过的收藏夹永久漏掉",
            body.contains("previousIds")
        )

        // ④ 有上限，避免新设备首次同步一次性打出 N 个请求
        assertTrue("应当有每次同步的请求上限", body.contains("MAX_PLAYLIST_CONTENT_SEEDS"))
    }

    // ═══════════════ ③ 不再连带删除列表缓存 ═══════════════

    @Test
    fun `★ 内容缓存作废时不能把收藏夹列表缓存也删掉`() {
        val body = functionBody(repoSource, "private fun patchPlaylistWorksCache(")
        assertTrue(body.isNotEmpty())
        assertTrue("应当作废内容缓存", body.contains("SyncCacheStore.delete(worksKey)"))

        // 找出"删列表缓存"的那些行，确认没有出现在这个函数里
        assertFalse(
            "★ 这个函数里不能出现 `delete(currentPlaylistsKey())` —— 那会让" +
                "「列表」页在重启后空白（重拉被每日闸门挡着）",
            body.contains("SyncCacheStore.delete(it)") ||
                body.contains("currentPlaylistsKey()?.let { SyncCacheStore.delete")
        )
        assertTrue(
            "改成只改件数",
            body.contains("patchPlaylistsWorksCount(")
        )
    }

    // ═══════════════ ④ 收藏夹不存在时直接失败 ═══════════════

    @Test
    fun `★ API 层单独识别 playlistNotFound`() {
        assertTrue(
            "应当有一个专门的异常类型（不要靠解析错误字符串）",
            apiSource.contains("class PlaylistNotFoundException")
        )
        assertTrue(
            "实测服务端回 404 + playlistNotFound",
            apiSource.contains("playlistNotFound")
        )
    }

    @Test
    fun `★ 加入失败且收藏夹不存在时：不记待补写 + 撤销本地改动 + 给出理由`() {
        val body = functionBody(repoSource, "suspend fun addWorkToPlaylist(")
        assertTrue(body.isNotEmpty())

        // 精确取出 `if (e is PlaylistNotFoundException) { … }` 这一块。
        // 不能用"截到下一个 return"那种切片写法：它会被后面的
        // `recordPending(...)` 干扰，我第一次就因此误报成失败。
        val block = bracedBlockAfter(body, "is PlaylistNotFoundException")
        assertTrue("必须识别这个异常并单独处理，实际函数体：\n$body", block.isNotEmpty())

        assertFalse(
            "★ 收藏夹不存在时绝不能记待补写 —— 那条记录永远补不上，" +
                "而且每次对齐都会多打一次注定失败的请求。实际块：\n$block",
            block.contains("recordPending(")
        )
        assertTrue(
            "要撤销本地那次改动（否则界面显示「失败了却多了一部作品」）",
            block.contains("revertPlaylistPatch(")
        )
        assertTrue(
            "要告诉用户原因",
            block.contains("PlaylistWriteResult(error =")
        )
    }

    @Test
    fun `★ 移出失败且收藏夹不存在时同样不记待补写`() {
        val body = functionBody(repoSource, "suspend fun removeWorkFromPlaylist(")
        assertTrue(body.isNotEmpty())
        val block = bracedBlockAfter(body, "is PlaylistNotFoundException")
        assertTrue("必须识别这个异常，实际函数体：\n$body", block.isNotEmpty())
        assertFalse("同样不能记待补写", block.contains("recordPending("))
        assertTrue("要撤销本地那次移除", block.contains("revertPlaylistPatch("))
    }

    // ═══════════════ ⑤ 删除收藏夹时清理待补写 ═══════════════

    @Test
    fun `★ 删收藏夹时丢掉它的待补写，不再为它同步`() {
        val body = functionBody(repoSource, "suspend fun deletePlaylist(")
        assertTrue(body.isNotEmpty())
        assertTrue(
            "★ 用户删掉收藏夹 = 不要里面的内容了 → 必须清掉它的待补写记录",
            body.contains("PendingWriteStore.removeAllForPlaylist(")
        )
    }

    // ═══════════════ ⑥ 最兜底：收藏前先确认已缓存 ═══════════════

    @Test
    fun `★ 加入收藏夹前必须先检查标识并确保已缓存`() {
        val body = functionBody(repoSource, "suspend fun addWorkToPlaylist(")
        assertTrue(body.isNotEmpty())

        val ensureIdx = body.indexOf("ensurePlaylistContentCached(")
        val patchIdx = body.indexOf("patchPlaylistWorksCache(")

        assertTrue(
            "★ 必须调用 ensurePlaylistContentCached —— 这是绕过前面所有保证时的最后一道",
            ensureIdx >= 0
        )
        assertTrue(
            "★ 顺序必须是「先确保缓存，再改本地」：否则会拿残缺基线插入作品。" +
                "实际顺序 ensure=$ensureIdx patch=$patchIdx",
            ensureIdx in 0 until patchIdx
        )
    }

    @Test
    fun `★ 移出收藏夹前同样先确保已缓存`() {
        val body = functionBody(repoSource, "suspend fun removeWorkFromPlaylist(")
        assertTrue(body.isNotEmpty())
        val ensureIdx = body.indexOf("ensurePlaylistContentCached(")
        val patchIdx = body.indexOf("patchPlaylistWorksCache(")
        assertTrue("必须调用", ensureIdx >= 0)
        assertTrue("必须先确保缓存", ensureIdx in 0 until patchIdx)
    }

    @Test
    fun `★ 兜底检查本身：没缓存才拉，拉完记掉当日机会`() {
        val body = functionBody(repoSource, "private suspend fun ensurePlaylistContentCached(")
        assertTrue(body.isNotEmpty())

        assertTrue(
            "先看标识，已缓存就直接返回（正常情况下零请求）",
            body.contains("PlaylistCacheFlags.isCached(") &&
                body.contains("return")
        )
        assertTrue(
            "没缓存就真的拉一次",
            body.contains("loadPlaylistWorks(playlistId")
        )
        assertTrue(
            "拉完要记掉它当天的自动机会，避免用户点开时又拉一次",
            body.contains("SyncGate.markSyncedToday(")
        )
    }

    @Test
    fun `★ 所有会改变内容缓存的地方都维护了标识`() {
        // 有写必置位、有删必降级 —— 否则兜底判断会读到过时的"已缓存"
        val writer = functionBody(repoSource, "private fun writePlaylistWorksCache(")
        assertTrue(
            "写入完整内容后要标为已缓存",
            writer.contains("markPlaylistContentCached(") && writer.contains("if (complete)")
        )

        val seed = functionBody(repoSource, "private fun seedEmptyPlaylistContent(")
        assertTrue(
            "预置空缓存后也要标为已缓存",
            seed.contains("PlaylistCacheFlags.markCached(")
        )

        val patch = functionBody(repoSource, "private fun patchPlaylistWorksCache(")
        assertTrue(
            "作废内容缓存时要降级标识",
            patch.contains("markPlaylistContentNotCached(")
        )

        val invalidate = functionBody(repoSource, "private fun invalidatePlaylistContent(")
        assertTrue(
            "作废相关缓存时也要降级",
            invalidate.contains("markPlaylistContentNotCached(")
        )

        val delete = functionBody(repoSource, "suspend fun deletePlaylist(")
        assertTrue(
            "删收藏夹时要忘掉标识",
            delete.contains("PlaylistCacheFlags.forget(")
        )
    }

    @Test
    fun `★ 30 收藏夹上限的旧行为已不再出现`() {
        // 防御性：确认没有为了"省请求"又把 worksCount > 0 的情况跳过
        val body = functionBody(repoSource, "private suspend fun seedMissingPlaylistContent(")
        assertTrue("有内容的要真的拉", body.contains("loadPlaylistWorks(playlist.id"))
    }

    /** 按大括号配对取函数体（从签名处开始找第一个 `{`） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) return ""
        val open = source.indexOf('{', start)
        if (open < 0) return ""
        return bracedFrom(source, open)
    }

    /** 取出 `marker` 之后紧跟的那个 `{ … }` 块的内容 */
    private fun bracedBlockAfter(source: String, marker: String): String {
        val at = source.indexOf(marker)
        if (at < 0) return ""
        val open = source.indexOf('{', at)
        if (open < 0) return ""
        return bracedFrom(source, open)
    }

    private fun bracedFrom(source: String, openBrace: Int): String {
        var depth = 0
        for (i in openBrace until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBrace + 1, i)
                }
            }
        }
        return ""
    }
}
