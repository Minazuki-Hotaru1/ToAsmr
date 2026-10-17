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
 * 缓存条目的 `complete`（完整性）标记测试。
 *
 * ## 为什么需要这个字段
 *
 * "标记成功后立刻在收藏显示"要求把新条目**就地插进缓存列表**。
 * 但只有手里已经有一份**完整基线**时才敢这么做 ——
 * 如果本地压根没有这份列表（用户从没进过收藏），凭空插一条会造出
 * "我的收藏里只有这一部作品"的假象。而写入后紧接着的自动核对已被抑制
 * （见 `SyncPolicy.noteLocalWrite`），所以那个假象会一直挂着。
 *
 * 另外分页（「加载更多」）手里只有前几页，也绝不能当地基。
 * 所以标记默认 false（保守）：宁可少插一条、等下次核对补上，
 * 也不要伪造一份残缺的列表。
 */
class CacheCompletenessTest {

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
    fun `complete 为 true 时能原样读回`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1, 2), etag = "W/\"x\"", syncedAt = 5L, complete = true)
        )
        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertTrue("完整标记必须能读回——它是能否就地插条目的依据", back?.complete == true)
        assertEquals(2, back?.value?.items?.size)
    }

    @Test
    fun `complete 为 false 时也能原样读回`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 5L, complete = false)
        )
        assertFalse(SyncCacheStore.read("k", WorkPageCodec)?.complete == true)
    }

    @Test
    fun `默认值是 false —— 保守优先`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 5L)
        )
        assertFalse(
            "没显式声明完整就不许当地基，否则会伪造出残缺列表",
            SyncCacheStore.read("k", WorkPageCodec)?.complete == true
        )
    }

    @Test
    fun `旧版本写的缓存没有 complete 字段时按 false 处理`() {
        // 升级兼容：老缓存里没有这个字段，必须当成"不完整"，
        // 不能因为默认 true 就把老缓存当成完整基线
        val legacy = JSONObject().apply {
            put("v", CacheFiles.SCHEMA_VERSION)
            put("key", "k")
            put("etag", "")
            put("syncedAt", 1L)
            put("dirty", false)
            put("body", WorkPageCodec.encode(page(1, 2, 3)))
        }.toString()
        SyncCacheStore.testPut("k", legacy)

        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertEquals("内容仍要能读出来", 3, back?.value?.items?.size)
        assertFalse("但绝不能当成完整基线", back?.complete == true)
    }

    @Test
    fun `dirty 与 complete 互不干扰`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = "e", syncedAt = 1L, dirty = true, complete = true)
        )
        val back = SyncCacheStore.read("k", WorkPageCodec)
        assertTrue(back?.dirty == true)
        assertTrue(back?.complete == true)
    }

    @Test
    fun `删除会同时移除条目与它的完整性标记`() {
        SyncCacheStore.write(
            "k",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 1L, complete = true)
        )
        SyncCacheStore.delete("k")
        assertNull(SyncCacheStore.read("k", WorkPageCodec))
        assertTrue("键也要真的没了", SyncCacheStore.testKeys().isEmpty())
    }

    @Test
    fun `不同筛选各自持有自己的完整性标记`() {
        // 「全部」有完整基线，「在听」没有 —— 必须能分别判断
        SyncCacheStore.write(
            "all",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1, 2), etag = null, syncedAt = 1L, complete = true)
        )
        SyncCacheStore.write(
            "listening",
            WorkPageCodec,
            SyncCacheStore.CacheEntry(value = page(1), etag = null, syncedAt = 1L, complete = false)
        )

        assertTrue(SyncCacheStore.read("all", WorkPageCodec)?.complete == true)
        assertFalse(SyncCacheStore.read("listening", WorkPageCodec)?.complete == true)
    }
}
