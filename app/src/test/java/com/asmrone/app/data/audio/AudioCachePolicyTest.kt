package com.asmrone.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频缓存的淘汰策略。
 *
 * 这些规则直接决定"用户的手机被占多少"和"缓存到底有没有用"，
 * 而且三条规则的**顺序**是有意的（见 [AudioCachePolicy] 的文档），
 * 所以每条都单独钉住。
 */
class AudioCachePolicyTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val mb = 1024L * 1024L

    private fun entry(
        id: String,
        bytes: Long,
        complete: Boolean,
        lastModifiedDaysAgo: Long = 0,
        lastAccessedDaysAgo: Long = 0,
        total: Long = bytes
    ) = AudioCacheEntry(
        id = id,
        cachedBytes = bytes,
        totalBytes = total,
        complete = complete,
        lastModifiedMs = now - lastModifiedDaysAgo * day,
        lastAccessedMs = now - lastAccessedDaysAgo * day
    )

    private fun plan(entries: List<AudioCacheEntry>, limitMb: Long = 1024) =
        AudioCachePolicy.plan(
            entries = entries,
            limitBytes = limitMb * mb,
            nowMs = now,
            usedBytes = entries.sumOf { it.cachedBytes }
        )

    // ═══════════════ ① 过期未完成的 .part ═══════════════

    @Test
    fun `★ 超过 7 天没增长的未完成缓存会被删`() {
        // 典型来源：试听 5 秒就划走。这种文件永远不会完成，
        // 也不会自己消失，是最该优先回收的垃圾
        val stale = entry("a", 500_000, complete = false, lastModifiedDaysAgo = 8)
        val p = plan(listOf(stale))
        assertEquals(1, p.delete.size)
        assertEquals("a", p.delete.single().id)
    }

    @Test
    fun `★ 7 天之内没增长的未完成缓存要留着`() {
        // 正在被慢慢听的长音频（分几天听）其 .part 会持续增长，
        // 刚缓存的不能删
        val fresh = entry("a", 500_000, complete = false, lastModifiedDaysAgo = 2)
        assertTrue(plan(listOf(fresh)).isEmpty)
    }

    @Test
    fun `★ 未完成但很旧的也要按「未增长时长」判，而不是「创建时长」`() {
        // 这条钉住一个容易写错的点：判据必须是 lastModified（还在长吗），
        // 不是创建时间。一个 20 天前创建、但昨天还在增长的 .part 不能删。
        val growing = entry("a", 1_000_000, complete = false, lastModifiedDaysAgo = 1)
        val p = plan(listOf(growing))
        assertTrue("还在增长的部分缓存不该被回收", p.isEmpty)
    }

    @Test
    fun `临界值：正好 7 天不算过期（用严格小于）`() {
        val exactly7 = entry("a", 100, complete = false, lastModifiedDaysAgo = 7)
        assertEquals(
            "边界必须明确：实现用 < cutoff，所以正好 7 天不删",
            0,
            plan(listOf(exactly7)).delete.size
        )
        val justOver = entry("b", 100, complete = false, lastModifiedDaysAgo = 7, total = 100)
            .copy(lastModifiedMs = now - 7 * day - 1)
        assertEquals(1, plan(listOf(justOver)).delete.size)
    }

    // ═══════════════ ② 已完成的 TTL ═══════════════

    @Test
    fun `★ 超过 30 天的完整缓存会过期`() {
        // 服务端没有 ETag / Last-Modified（实测），TTL 是唯一的失效兜底
        val old = entry("a", 10 * mb, complete = true, lastModifiedDaysAgo = 31)
        val p = plan(listOf(old))
        assertEquals(1, p.delete.size)
    }

    @Test
    fun `30 天之内的完整缓存留着`() {
        val recent = entry("a", 10 * mb, complete = true, lastModifiedDaysAgo = 10)
        assertTrue(plan(listOf(recent)).isEmpty)
    }

    // ═══════════════ ③ 超上限 → LRU ═══════════════

    @Test
    fun `★ 超上限时按最久未播放淘汰`() {
        // 3 个各 400MB，上限 1024MB → 需要淘汰到 ≤1024，即至少腾 176MB
        val oldest = entry("old", 400 * mb, complete = true, lastAccessedDaysAgo = 30)
        val middle = entry("mid", 400 * mb, complete = true, lastAccessedDaysAgo = 10)
        val newest = entry("new", 400 * mb, complete = true, lastAccessedDaysAgo = 1)

        val p = plan(listOf(oldest, middle, newest), limitMb = 1024)

        assertTrue("应淘汰最久未播放的那个", p.delete.any { it.id == "old" })
        assertFalse("刚播过的不该被淘汰", p.delete.any { it.id == "new" })
    }

    @Test
    fun `★ 未超上限时不动任何东西`() {
        val a = entry("a", 100 * mb, complete = true)
        val b = entry("b", 100 * mb, complete = true)
        assertTrue(plan(listOf(a, b), limitMb = 1024).isEmpty)
    }

    @Test
    fun `★ LRU 只淘汰已完成的，不碰正在增长的部分缓存`() {
        // 部分缓存留着：下次顺序播到那里就能命中，删了就白缓存了
        val growing = entry("growing", 900 * mb, complete = false, lastModifiedDaysAgo = 1)
        val done1 = entry("d1", 400 * mb, complete = true, lastAccessedDaysAgo = 20)
        val done2 = entry("d2", 400 * mb, complete = true, lastAccessedDaysAgo = 5)

        val p = plan(listOf(growing, done1, done2), limitMb = 1024)

        assertFalse("正在增长的部分缓存不该被 LRU 淘汰", p.delete.any { it.id == "growing" })
        assertTrue("应该淘汰已完成的", p.delete.any { it.id == "d1" })
    }

    @Test
    fun `超上限很多时会连续淘汰多个`() {
        val entries = (1..6).map {
            entry("e$it", 300 * mb, complete = true, lastAccessedDaysAgo = it.toLong())
        }
        // 合计 1800MB，上限 512MB
        val p = plan(entries, limitMb = 512)
        val freed = p.freedBytes
        assertTrue("必须腾到 512MB 以下", 1800 * mb - freed <= 512 * mb)
        assertTrue("应从最旧开始", p.delete.first().id == "e6")
    }

    @Test
    fun `上限小于单个体积时也要能收敛`() {
        // 用户把上限调到 256MB，而缓存里有一个 400MB 的
        val big = entry("big", 400 * mb, complete = true)
        val p = plan(listOf(big), limitMb = 256)
        assertEquals(1, p.delete.size)
        assertEquals("big", p.delete.single().id)
    }

    // ═══════════════ 三者的交互 ═══════════════

    @Test
    fun `★ 先清过期再考虑上限 —— 过期清完就不必动 LRU`() {
        val expired = entry("expired", 400 * mb, complete = true, lastModifiedDaysAgo = 40)
        val recent = entry("recent", 400 * mb, complete = true, lastModifiedDaysAgo = 1)

        val p = plan(listOf(expired, recent), limitMb = 500)

        assertTrue(p.delete.any { it.id == "expired" })
        assertFalse(
            "清掉过期后总占用已降到 400MB < 500MB，不该再淘汰 recent",
            p.delete.any { it.id == "recent" }
        )
    }

    @Test
    fun `释放字节数等于被删条目之和`() {
        val stale = entry("s", 123_456, complete = false, lastModifiedDaysAgo = 10)
        val expired = entry("e", 654_321, complete = true, lastModifiedDaysAgo = 40)
        val p = plan(listOf(stale, expired))
        assertEquals(123_456L + 654_321L, p.freedBytes)
    }

    @Test
    fun `空缓存不产生任何计划`() {
        val p = plan(emptyList())
        assertTrue(p.isEmpty)
        assertEquals(0L, p.freedBytes)
    }

    @Test
    fun `计划里带可读原因`() {
        val stale = entry("s", 100, complete = false, lastModifiedDaysAgo = 10)
        val p = plan(listOf(stale))
        assertTrue("原因要能说清为什么清", p.reasons.isNotEmpty())
        assertTrue(p.reasons.any { it.contains("7") })
    }

    // ═══════════════ 常量与口径 ═══════════════

    @Test
    fun `★ 两个时间口径与需求一致`() {
        // 用户明确要求：未增长的 .part 7 天即删
        assertEquals(7L, AudioCachePolicy.STALE_PARTIAL_DAYS)
        assertEquals(30L, AudioCachePolicy.COMPLETE_TTL_DAYS)
    }

    @Test
    fun `默认上限与可选区间合理`() {
        assertEquals(1024, AudioCachePolicy.DEFAULT_LIMIT_MB)
        assertTrue(AudioCachePolicy.MIN_LIMIT_MB > 0)
        assertTrue(AudioCachePolicy.MAX_LIMIT_MB > AudioCachePolicy.DEFAULT_LIMIT_MB)
        assertTrue(
            "默认值必须落在允许区间内，否则用户一进设置就看到一个非法值",
            AudioCachePolicy.DEFAULT_LIMIT_MB in
                AudioCachePolicy.MIN_LIMIT_MB..AudioCachePolicy.MAX_LIMIT_MB
        )
    }

    @Test
    fun `cutoff 换算正确`() {
        assertEquals(now - 7 * day, AudioCachePolicy.stalePartialCutoffMs(now))
        assertEquals(now - 30 * day, AudioCachePolicy.completeTtlCutoffMs(now))
    }
}
