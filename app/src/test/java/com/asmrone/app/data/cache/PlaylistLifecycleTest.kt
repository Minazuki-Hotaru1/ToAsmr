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
 * 这组测试最初对应三个真实缺陷（都由"每天只同步一次"这个前提放大）：
 *
 * | 缺陷 | 机制 | 后果 |
 * |---|---|---|
 * | 新收藏夹没有内容缓存 | 加内容时走到了"作废缓存"分支，而它**连列表缓存一起删** | 重启后「列表」页整页空白，要到明天才恢复 |
 * | 收藏夹已在别处删除 | 加内容时服务端回 `playlistNotFound`，却被当成普通网络失败 | 留下**永远失败**的待补写记录，每次对齐多打一次注定失败的请求 |
 * | 用户删除收藏夹 | 没清掉它的待补写操作 | 同上，且违背"用户已经不要这个收藏夹了"的语义 |
 *
 * ## 本轮的改动：兜底链已整条删除
 *
 * 上面第一条缺陷的**根因**是"本地这份内容必须完整才敢就地改"。那个前提取消了，
 * 为它服务的一整套脚手架（`PlaylistCacheFlags`、`ensurePlaylistContentCached`、
 * `seedEmpty/seedMissingPlaylistContent`、`CacheEntry.complete` 门槛）也跟着删掉。
 *
 * 所以这里不再断言"某个兜底方法必须存在"——那类断言锁定的是实现方式。
 * 真正需要判断的东西移到了 [PlaylistPatchOps.compute]（纯函数），
 * 由 [PlaylistPatchOpsTest] 逐条验证行为；
 * 本文件只保留**仍然成立的不变量**与**防回归**断言。
 */
class PlaylistLifecycleTest {

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

    // ═══════════════ ② 本地写入不得破坏收藏夹列表（历史的"整页空白"缺陷） ═══════════════

    @Test
    fun `★ 本地写入既不删内容缓存也不删列表缓存`() {
        val body = functionBody(repoSource, "private fun patchPlaylistWorksCache(")
        assertTrue("patchPlaylistWorksCache 应当存在", body.isNotEmpty())

        // 以前"没有完整基线"时会 delete 内容缓存；而更早的版本连列表缓存一起删，
        // 于是重启 App 后「列表」页整页空白（重拉被每日闸门挡着）。
        // 现在两条删除都不该再出现：写入就是把结果写进去，不是作废。
        assertFalse(
            "★ 本地写入不该删除任何缓存 —— 删了就等于把界面上的内容抹掉。实际函数体：\n$body",
            body.contains("SyncCacheStore.delete(")
        )
        assertTrue(
            "要就地改件数，而不是重建列表数组（重建会丢 latestWorkId）",
            body.contains("patchPlaylistsWorksCount(")
        )
    }

    @Test
    fun `★ 确认成功后只清 dirty，不再重复改内容与件数`() {
        // 旧实现的成功路径又调了一次 patchPlaylistWorksCache，而那个函数会
        // 无条件把件数 ±1 —— 于是"成功收藏一次，列表里显示 +2"。
        val body = functionBody(repoSource, "suspend fun addWorkToPlaylist(")
        assertTrue(body.isNotEmpty())
        // 只取 `runCatching { … }` 成功分支 —— 它前面的"本地先行"patch 是要保留的
        val successRegion = body.substringAfter("return runCatching {").substringBefore("getOrElse")
        assertTrue(
            "成功路径应当只清 dirty",
            successRegion.contains("clearPlaylistCacheDirty(")
        )
        assertFalse(
            "★ 成功路径不能再 patch 一次内容缓存 —— 那会重复增删件数",
            successRegion.contains("patchPlaylistWorksCache(")
        )

        val dirtyBody = functionBody(repoSource, "private fun clearPlaylistCacheDirty(")
        assertTrue("clearPlaylistCacheDirty 应当存在", dirtyBody.isNotEmpty())
        assertFalse(
            "clearPlaylistCacheDirty 只清标记，不该动件数",
            dirtyBody.contains("patchPlaylistsWorksCount(")
        )
    }

    // ═══════════════ ③ 收藏夹不存在时直接失败 ═══════════════

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

    // ═══════════════ ④ 删除收藏夹时清理待补写 ═══════════════

    @Test
    fun `★ 删收藏夹时丢掉它的待补写，不再为它同步`() {
        val body = functionBody(repoSource, "suspend fun deletePlaylist(")
        assertTrue(body.isNotEmpty())
        assertTrue(
            "★ 用户删掉收藏夹 = 不要里面的内容了 → 必须清掉它的待补写记录",
            body.contains("PendingWriteStore.removeAllForPlaylist(")
        )
    }

    // ═══════════════ ⑤ 防回归：兜底链不许被重新引入 ═══════════════

    @Test
    fun `★ 完整性门槛与它的脚手架已彻底移除`() {
        // 这一条与仓库里既有的"额度式旧机制已删掉"同类：防止有人
        // 为了修一个偶发问题，把整套"必须先有完整基线"又加回来。
        listOf(
            "PlaylistCacheFlags",
            "ensurePlaylistContentCached",
            "seedMissingPlaylistContent",
            "seedEmptyPlaylistContent",
            "markPlaylistContentCached",
            "markPlaylistContentNotCached",
            "MAX_PLAYLIST_CONTENT_SEEDS",
            "!entry.complete",
            "!existing?.complete",
            "markSyncedToday"
        ).forEach { gone ->
            assertFalse(
                "★ 「$gone」属于已删除的兜底链，不该再出现 —— " +
                    "本地是不是「完整的一份」不再需要任何前提判断",
                repoSource.contains(gone)
            )
        }
    }

    @Test
    fun `★ 收藏夹写入路径不再为了拿基线而多发一个读请求`() {
        // 旧实现：标识说"没缓存" → 收藏前先 loadPlaylistWorks(USER_REFRESH)。
        // 也就是说"收藏一次"最坏是 1 读 + 1 写。现在稳定 1 写。
        listOf("suspend fun addWorkToPlaylist(", "suspend fun removeWorkFromPlaylist(")
            .forEach { signature ->
                val body = functionBody(repoSource, signature)
                assertTrue("$signature 应当存在", body.isNotEmpty())
                val beforeWrite = body.substringBefore("api.")
                assertFalse(
                    "★ $signature 在写请求之前不该再拉列表（那正是被删掉的兜底）",
                    beforeWrite.contains("loadPlaylistWorks(")
                )
            }
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
