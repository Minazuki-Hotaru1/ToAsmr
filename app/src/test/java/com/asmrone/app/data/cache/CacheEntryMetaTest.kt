package com.asmrone.app.data.cache

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 缓存条目的元信息：`dirty` 标记、ETag、读写往返与兼容性。
 *
 * ## 这个文件的前身是 `CacheCompletenessTest`
 *
 * 它原来测的是 `CacheEntry.complete`（"这份内容是否完整"）。
 * 那个标记已经删掉了 —— 它存在的唯一理由是"本地写入前必须先有完整基线"，
 * 而这条前提随收藏夹兜底链一起被去掉了
 * （见 `AsmrRepository.patchPlaylistWorksCache` 与 `PlaylistPatchOps`）。
 *
 * "完整性"的意义只剩分页：`loadedPages` 已经能完整表达这件事，
 * 再单独记一个布尔就成了只写不读的死状态 —— 而**死状态最危险的地方
 * 是它看起来还像个约束**，下一个人会以为有东西在依赖它。
 *
 * 保留下来的 `dirty` 仍然是活的：它决定下次核对要不要跳过 `If-None-Match`。
 */
class CacheEntryMetaTest {

    @Before
    fun setUp() {
        SyncCacheStore.installForTest()
    }

    @After
    fun tearDown() {
        SyncCacheStore.uninstallForTest()
    }

    private fun page(vararg ids: Int) = CachedWorkPage(
        items = ids.map { CachedWork(id = it, title = "作品 $it") },
        totalCount = ids.size,
        loadedPages = 1
    )

    @Test
    fun `写进去的元信息能原样读回`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1, 2), etag = "W/\"x\"", syncedAt = 5L)
        )
        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertEquals(2, back?.value?.items?.size)
        assertEquals("W/\"x\"", back?.etag)
        assertEquals(5L, back?.syncedAt)
    }

    @Test
    fun `dirty 为 true 时能读回 —— 它决定下次跳过 ETag`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = "e", syncedAt = 1L, dirty = true)
        )
        assertTrue(SyncCacheStore.read("k", WorkPageCodec)?.dirty == true)
    }

    @Test
    fun `dirty 默认是 false`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 5L)
        )
        assertFalse(SyncCacheStore.read("k", WorkPageCodec)?.dirty == true)
    }

    @Test
    fun `touch 只清 dirty，内容不动`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1, 2), etag = "old", syncedAt = 1L, dirty = true)
        )
        SyncCacheStore.touch("k", WorkPageCodec, dirty = false)

        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertEquals("内容不能变", 2, back?.value?.items?.size)
        assertFalse("dirty 要清掉", back?.dirty == true)
        assertEquals("etag 没传就保持原样", "old", back?.etag)
    }

    @Test
    fun `旧版本写的缓存（缺 optional 字段）仍能读出内容`() {
        // 升级兼容：老缓存里没有 dirty 字段，按 false 处理，内容照样可用
        val legacy = JSONObject().apply {
            put("v", CacheFiles.SCHEMA_VERSION)
            put("key", "k")
            put("etag", "")
            put("syncedAt", 1L)
            put("body", WorkPageCodec.encode(page(1, 2, 3)))
        }.toString()
        SyncCacheStore.testPut("k", legacy)

        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertEquals("内容仍要能读出来", 3, back?.value?.items?.size)
        assertFalse(back?.dirty == true)
        assertNull("没写过 etag 就是 null", back?.etag)
    }

    @Test
    fun `删除会同时移除条目与元信息`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 1L)
        )
        SyncCacheStore.delete("k")
        assertNull(SyncCacheStore.read("k", WorkPageCodec))
        assertTrue("键也要真的没了", SyncCacheStore.testKeys().isEmpty())
    }

    @Test
    fun `每个缓存键各自持有自己的元信息`() {
        // 「全部」与「在听」是两份缓存：一份 dirty、一份干净，必须能分别判断
        SyncCacheStore.write(
            "all",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1, 2), etag = null, syncedAt = 1L, dirty = true)
        )
        SyncCacheStore.write(
            "listening",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 1L)
        )

        assertTrue(SyncCacheStore.read("all", WorkPageCodec)?.dirty == true)
        assertFalse(SyncCacheStore.read("listening", WorkPageCodec)?.dirty == true)
    }
}
