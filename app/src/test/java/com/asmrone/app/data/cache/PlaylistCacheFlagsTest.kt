package com.asmrone.app.data.cache

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 每个收藏夹"内容已缓存"的标识 —— 最兜底那道判断的存储层。
 *
 * ## 为什么这个标识必须存在（而不是直接看缓存文件在不在）
 *
 * 文件系统只能回答"磁盘上有没有东西"，回答不了这三个问题：
 *
 * | 问题 | 只有文件时 |
 * |---|---|
 * | 这份内容是不是**完整**的？ | 只有第 1 页的文件也在磁盘上 |
 * | 缓存曾经被清掉过吗？ | 清掉之后与"从没缓存过"无法区分 |
 * | 一个**确定为空**的收藏夹（新建的）缓存了吗？ | 空列表与"没缓存"都是 0 条 |
 *
 * 所以用一个显式标识记录"内容已经缓存好了"，由所有改变缓存的操作维护。
 * 「加入收藏夹」在动手前先看它：说没缓存就**先缓存、再收藏**。
 */
class PlaylistCacheFlagsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val host = "https://api.example.com"
    private val user = "tester"

    @Before
    fun setUp() {
        PlaylistCacheFlags.initForTest(tmp.newFolder("flags"))
    }

    @After
    fun tearDown() {
        PlaylistCacheFlags.initForTest(null)
    }

    // ═══════════════ 基本读写 ═══════════════

    @Test
    fun `默认是没缓存`() {
        assertFalse(PlaylistCacheFlags.isCached(host, user, "pl-1"))
    }

    @Test
    fun `标记后就是已缓存`() {
        PlaylistCacheFlags.markCached(host, user, "pl-1")
        assertTrue(PlaylistCacheFlags.isCached(host, user, "pl-1"))
        assertFalse("不该影响别的收藏夹", PlaylistCacheFlags.isCached(host, user, "pl-2"))
    }

    @Test
    fun `★ 作废后回到没缓存`() {
        // 这是兜底路径的触发条件：内容缓存被删掉时必须降级，
        // 否则下次会拿不存在的内容当基线去插入作品。
        PlaylistCacheFlags.markCached(host, user, "pl-1")
        PlaylistCacheFlags.markNotCached(host, user, "pl-1")
        assertFalse(PlaylistCacheFlags.isCached(host, user, "pl-1"))
    }

    @Test
    fun `重复标记与重复作废都幂等`() {
        repeat(3) { PlaylistCacheFlags.markCached(host, user, "pl-1") }
        assertEquals(setOf("pl-1"), PlaylistCacheFlags.cachedIds(host, user))
        repeat(3) { PlaylistCacheFlags.markNotCached(host, user, "pl-1") }
        assertTrue(PlaylistCacheFlags.cachedIds(host, user).isEmpty())
    }

    @Test
    fun `★ 换账号互不影响`() {
        PlaylistCacheFlags.markCached(host, "alice", "pl-1")
        assertTrue(PlaylistCacheFlags.isCached(host, "alice", "pl-1"))
        assertFalse(
            "上一个人缓存过，不代表这个账号也缓存过",
            PlaylistCacheFlags.isCached(host, "bob", "pl-1")
        )
    }

    @Test
    fun `★ 换服务器互不影响`() {
        PlaylistCacheFlags.markCached("https://a.com", user, "pl-1")
        assertFalse(
            "不同站点的收藏夹 id 可能相同，但内容无关",
            PlaylistCacheFlags.isCached("https://b.com", user, "pl-1")
        )
    }

    // ═══════════════ 持久化（跨进程必须存活）═══════════════

    @Test
    fun `★ 落盘内容跨实例可读（真正的重开）`() {
        val dir = tmp.newFolder("persist")
        PlaylistCacheFlags.initForTest(dir)
        PlaylistCacheFlags.markCached(host, user, "pl-a")
        PlaylistCacheFlags.markCached(host, user, "pl-b")

        // 模拟进程重启：只保留目录，清空内存
        PlaylistCacheFlags.initForTest(dir)

        assertEquals(setOf("pl-a", "pl-b"), PlaylistCacheFlags.cachedIds(host, user))
    }

    @Test
    fun `★ 作废状态也会落盘`() {
        // 只持久化"已缓存"的集合，所以作废就是从集合里移除 ——
        // 重开后必须仍然是"没缓存"，否则兜底判断会失效
        val dir = tmp.newFolder("persist-notcached")
        PlaylistCacheFlags.initForTest(dir)
        PlaylistCacheFlags.markCached(host, user, "pl-a")
        PlaylistCacheFlags.markNotCached(host, user, "pl-a")

        PlaylistCacheFlags.initForTest(dir)
        assertFalse(PlaylistCacheFlags.isCached(host, user, "pl-a"))
    }

    @Test
    fun `忘记（收藏夹被删）之后不再报告已缓存`() {
        PlaylistCacheFlags.markCached(host, user, "pl-1")
        PlaylistCacheFlags.forget(host, user, "pl-1")
        assertFalse(PlaylistCacheFlags.isCached(host, user, "pl-1"))
    }

    // ═══════════════ 健壮性 ═══════════════

    @Test
    fun `空参数一律当作没缓存，不抛异常`() {
        assertFalse(PlaylistCacheFlags.isCached("", user, "pl-1"))
        assertFalse(PlaylistCacheFlags.isCached(host, "", "pl-1"))
        assertFalse(PlaylistCacheFlags.isCached(host, user, ""))
        // 写入空参数也只是无操作
        PlaylistCacheFlags.markCached("", "", "")
        PlaylistCacheFlags.markNotCached("", "", "")
        assertTrue(PlaylistCacheFlags.cachedIds("", "").isEmpty())
    }

    @Test
    fun `未初始化（无目录）时退化为内存态，不崩`() {
        PlaylistCacheFlags.initForTest(null)
        PlaylistCacheFlags.markCached(host, user, "pl-1")
        assertTrue(
            "没有目录也要能工作（只是不持久化）—— 不能让收藏功能因为忘了 init 就崩",
            PlaylistCacheFlags.isCached(host, user, "pl-1")
        )
    }

    @Test
    fun `文件损坏时当作没缓存，不抛异常`() {
        val dir = tmp.newFolder("broken")
        PlaylistCacheFlags.initForTest(dir)
        PlaylistCacheFlags.markCached(host, user, "pl-1")

        // 把文件写坏
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach {
            it.writeText("{ this is not json", Charsets.UTF_8)
        }
        PlaylistCacheFlags.initForTest(dir)

        assertFalse(
            "坏数据必须当成没缓存 —— 宁可多拉一次，也不能拿坏状态去改数据",
            PlaylistCacheFlags.isCached(host, user, "pl-1")
        )
    }

    @Test
    fun `clearAll 清空全部账号`() {
        PlaylistCacheFlags.markCached(host, "alice", "pl-1")
        PlaylistCacheFlags.markCached(host, "bob", "pl-2")
        PlaylistCacheFlags.clearAll()
        assertTrue(PlaylistCacheFlags.cachedIds(host, "alice").isEmpty())
        assertTrue(PlaylistCacheFlags.cachedIds(host, "bob").isEmpty())
    }

    @Test
    fun `大量收藏夹不会互相干扰`() {
        val ids = (1..50).map { "pl-$it" }
        ids.forEach { PlaylistCacheFlags.markCached(host, user, it) }
        ids.forEach { assertTrue(it, PlaylistCacheFlags.isCached(host, user, it)) }

        PlaylistCacheFlags.markNotCached(host, user, "pl-25")
        assertEquals(49, PlaylistCacheFlags.cachedIds(host, user).size)
        assertFalse(PlaylistCacheFlags.isCached(host, user, "pl-25"))
    }

    /** TemporaryFolder 在极少数环境下不可用，此时跳过而不是假失败 */
    @Test
    fun `临时目录可用性自检`() {
        assumeTrue(tmp.root.exists())
        assertTrue(tmp.root.isDirectory)
    }
}
