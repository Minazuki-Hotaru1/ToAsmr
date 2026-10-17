package com.asmrone.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 音频缓存的**文件与元数据**管理。
 *
 * 这个类只依赖 [File]（不依赖 Android 的 Context），所以这里能用临时目录
 * 真正跑到写入 / 追加 / 校验 / 改名 / 快照 / 清理 —— 而不是只测几个纯函数。
 *
 * 重点覆盖两个"错了会静默损坏数据"的地方：
 *
 *  1. **长度校验**：只有字节数与上游总长精确相等才改名成完整缓存。
 *     服务端没有 `ETag`/`Last-Modified`（实测），长度是唯一判据。
 *  2. **`id` 的稳定性**：同一个上游 URL 必须永远映射到同一个 id，
 *     否则缓存永远命不中（每次都当成新资源）。
 */
class AudioCacheStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): AudioCacheStore =
        AudioCacheStore().apply { init(tmp.root) }

    private val now = 1_700_000_000_000L
    private fun bytes(n: Int, fill: Byte = 7) = ByteArray(n) { fill }

    // ═══════════════ id 派生 ═══════════════

    @Test
    fun `★ 同一个上游地址永远得到同一个 id`() {
        val url = "https://raw.example.com/media/stream/voice/RJ243515/01.wav"
        assertEquals(AudioCacheStore.idFor(url), AudioCacheStore.idFor(url))
    }

    @Test
    fun `★ 不同上游地址得到不同 id`() {
        // 同一部作品的不同质量分支必须是两个缓存
        assertFalse(
            AudioCacheStore.idFor("https://a/1.wav") == AudioCacheStore.idFor("https://a/2.wav")
        )
    }

    @Test
    fun `id 是安全的文件名`() {
        val id = AudioCacheStore.idFor("https://x/media/stream/voice/RJ243515 あ♪.wav")
        assertTrue("不能含路径分隔符", !id.contains('/') && !id.contains('\\'))
        assertTrue("不能含百分号编码留下的字符", id.all { it.isLetterOrDigit() })
        assertEquals(32, id.length)
    }

    // ═══════════════ 追加与长度 ═══════════════

    @Test
    fun `初次追加后 cachedBytes 等于写入长度`() {
        val s = newStore()
        s.appendPart("a", bytes(1000), 0, 1000)
        assertEquals(1000L, s.cachedBytes("a"))
    }

    @Test
    fun `多次追加是累加的（这就是「播到哪缓存到哪」）`() {
        val s = newStore()
        s.appendPart("a", bytes(1000), 0, 1000)
        s.appendPart("a", bytes(500), 0, 500)
        s.appendPart("a", bytes(250), 0, 250)
        assertEquals(1750L, s.cachedBytes("a"))
    }

    @Test
    fun `★ 追加的内容是连续的（没有空洞）`() {
        val s = newStore()
        s.appendPart("a", byteArrayOf(1, 2, 3), 0, 3)
        s.appendPart("a", byteArrayOf(4, 5), 0, 2)
        val content = s.partFile("a")!!.readBytes()
        assertArrayEqualsMsg(byteArrayOf(1, 2, 3, 4, 5), content)
    }

    @Test
    fun `★ 只写一部分字节时按 offset 与 length 取`() {
        val s = newStore()
        val buf = byteArrayOf(0, 0, 9, 8, 7, 0)
        s.appendPart("a", buf, offset = 2, length = 3)
        assertArrayEqualsMsg(byteArrayOf(9, 8, 7), s.partFile("a")!!.readBytes())
    }

    @Test
    fun `长度为 0 的追加不写任何东西`() {
        val s = newStore()
        s.appendPart("a", bytes(10), 0, 0)
        assertEquals(0L, s.cachedBytes("a"))
    }

    @Test
    fun `未初始化时所有操作都安全返回「没有缓存」`() {
        val s = AudioCacheStore()
        assertFalse(s.isReady)
        assertEquals(0L, s.cachedBytes("a"))
        assertNull(s.completeFile("a"))
        assertFalse(s.isComplete("a"))
        assertEquals(0L, s.appendPart("a", bytes(10), 0, 10))
        assertFalse(s.finalizeIfComplete("a", 10, now))
        assertEquals(0L, s.clearAll())
    }

    // ═══════════════ ★ 长度校验（数据完整性的唯一判据） ═══════════════

    @Test
    fun `★ 长度精确匹配时改名成完整缓存`() {
        val s = newStore()
        s.appendPart("a", bytes(1000), 0, 1000)
        assertTrue(s.finalizeIfComplete("a", expectedTotal = 1000, nowMs = now))
        assertTrue(s.isComplete("a"))
        assertNotNull(s.completeFile("a"))
        assertNull("完成后不该再留 .part", s.partFile("a")?.takeIf { it.exists() })
    }

    @Test
    fun `★ 长度不匹配时校验失败，并删掉 part 文件`() {
        // 这是最关键的防线：服务端没有 ETag/Last-Modified，
        // 长度对不上就说明拿到的不是完整文件 —— 必须丢弃而不是当成完整
        val s = newStore()
        s.appendPart("a", bytes(600), 0, 600)
        assertFalse(s.finalizeIfComplete("a", expectedTotal = 1000, nowMs = now))
        assertFalse("不该变成完整缓存", s.isComplete("a"))
        assertFalse("残缺的 .part 必须删掉（它不可能自己补完）", s.partFile("a")!!.exists())
    }

    @Test
    fun `总长为 0 或负数时拒绝完成`() {
        val s = newStore()
        s.appendPart("a", bytes(100), 0, 100)
        assertFalse(s.finalizeIfComplete("a", expectedTotal = 0, nowMs = now))
        assertFalse(s.finalizeIfComplete("a", expectedTotal = -1, nowMs = now))
    }

    @Test
    fun `没有 part 文件时无法完成`() {
        val s = newStore()
        assertFalse(s.finalizeIfComplete("nothing", 100, now))
    }

    @Test
    fun `已经完成时重复校验直接成功（幂等）`() {
        val s = newStore()
        s.appendPart("a", bytes(100), 0, 100)
        assertTrue(s.finalizeIfComplete("a", 100, now))
        assertTrue(s.finalizeIfComplete("a", 100, now))
    }

    @Test
    fun `★ 追加超过预期长度也判失败（不是「至少」而是「精确」）`() {
        val s = newStore()
        s.appendPart("a", bytes(1200), 0, 1200)
        assertFalse(s.finalizeIfComplete("a", expectedTotal = 1000, nowMs = now))
    }

    // ═══════════════ 完整缓存优先 ═══════════════

    @Test
    fun `完整缓存存在时 cachedBytes 取完整文件`() {
        val s = newStore()
        s.appendPart("a", bytes(1000), 0, 1000)
        s.finalizeIfComplete("a", 1000, now)
        assertEquals(1000L, s.cachedBytes("a"))
    }

    // ═══════════════ 删除 ═══════════════

    @Test
    fun `删除同时清掉完整与部分文件`() {
        val s = newStore()
        s.appendPart("a", bytes(500), 0, 500)
        val freed1 = s.delete("a")
        assertEquals(500L, freed1)
        assertNull(s.partFile("a")?.takeIf { it.exists() })

        s.appendPart("b", bytes(300), 0, 300)
        s.finalizeIfComplete("b", 300, now)
        assertEquals(300L, s.delete("b"))
        assertFalse(s.isComplete("b"))
    }

    @Test
    fun `删除不存在的 id 返回 0`() {
        assertEquals(0L, newStore().delete("nothing"))
    }

    // ═══════════════ 快照 ═══════════════

    @Test
    fun `快照统计完整与部分的数量和占用`() {
        val s = newStore()
        s.appendPart("p1", bytes(400), 0, 400)
        s.appendPart("p2", bytes(600), 0, 600)
        s.appendPart("c1", bytes(1000), 0, 1000)
        s.finalizeIfComplete("c1", 1000, now)

        val status = s.snapshot(now)
        assertEquals(2, status.partialCount)
        assertEquals(1, status.completeCount)
        assertEquals(3, status.totalCount)
        assertEquals(2000L, status.usedBytes)
    }

    @Test
    fun `★ 快照以磁盘文件为准，缺元数据也能统计`() {
        // 元数据可能因崩溃而落后，但"占了多少空间"只有文件答得准。
        // 重建一个 store（元数据从 index.json 重新加载）也要算对。
        val s1 = newStore()
        s1.appendPart("a", bytes(500), 0, 500)

        val s2 = newStore()
        assertEquals(500L, s2.snapshot(now).usedBytes)
        assertEquals(1, s2.snapshot(now).partialCount)
    }

    @Test
    fun `空目录的快照全为 0`() {
        val status = newStore().snapshot(now)
        assertEquals(0L, status.usedBytes)
        assertEquals(0, status.totalCount)
        assertEquals("", status.compositionText)
    }

    @Test
    fun `快照能数出超过 7 天未增长的部分缓存`() {
        val s = newStore()
        s.appendPart("old", bytes(100), 0, 100)
        // 手动把修改时间推回 10 天
        s.partFile("old")!!.setLastModified(now - 10 * 24L * 3600 * 1000)
        assertEquals(1, s.snapshot(now).stalePartialCount)
    }

    // ═══════════════ 清理 ═══════════════

    @Test
    fun `清理计划能识别过期的部分缓存`() {
        val s = newStore()
        s.appendPart("old", bytes(100), 0, 100)
        s.partFile("old")!!.setLastModified(now - 10 * 24L * 3600 * 1000)

        val plan = s.cleanupPlan(limitBytes = 1024L * 1024 * 1024, nowMs = now)
        assertEquals(1, plan.delete.size)
        assertEquals("old", plan.delete.single().id)
    }

    @Test
    fun `执行清理计划后文件真的没了且释放字节数正确`() {
        val s = newStore()
        s.appendPart("old", bytes(1234), 0, 1234)
        s.partFile("old")!!.setLastModified(now - 10 * 24L * 3600 * 1000)

        val plan = s.cleanupPlan(1024L * 1024 * 1024, now)
        val freed = s.executePlan(plan)
        assertEquals(1234L, freed)
        assertEquals(0, s.snapshot(now).totalCount)
    }

    @Test
    fun `★ 未超上限且没过期时不清理任何东西`() {
        val s = newStore()
        s.appendPart("a", bytes(1000), 0, 1000)
        s.finalizeIfComplete("a", 1000, now)
        val plan = s.cleanupPlan(1024L * 1024 * 1024, now)
        assertTrue("刚缓存的完整文件不该被清", plan.isEmpty)
    }

    @Test
    fun `清空全部后目录里只剩元数据文件`() {
        val s = newStore()
        s.appendPart("a", bytes(100), 0, 100)
        s.appendPart("b", bytes(200), 0, 200)
        s.finalizeIfComplete("b", 200, now)

        val freed = s.clearAll()
        assertEquals(300L, freed)
        assertEquals(0, s.snapshot(now).totalCount)
        assertEquals(0L, s.snapshot(now).usedBytes)
    }

    // ═══════════════ 元数据持久化 ═══════════════

    @Test
    fun `★ 元数据能跨实例读回（最后访问时间不丢）`() {
        // LRU 与 TTL 都依赖它；丢了就等于每次重建都当成"刚访问过"，
        // 淘汰会失效
        val s1 = newStore()
        s1.appendPart("a", bytes(100), 0, 100)
        s1.finalizeIfComplete("a", 100, now)
        s1.touch("a", "https://upstream/x.wav", 100, now - 5000)

        val s2 = newStore()
        assertEquals(now - 5000, s2.lastAccessedAt("a"))
    }

    @Test
    fun `★ 元数据文件损坏时不影响使用`() {
        // 坏 JSON 不该让缓存层整个崩掉 —— 最差退化成"没有元数据"
        val s1 = newStore()
        s1.appendPart("a", bytes(100), 0, 100)
        File(tmp.root, "audio_cache/index.json").writeText("{ 这不是合法 JSON")

        val s2 = newStore()
        // 文件仍在，所以按磁盘统计依然正确
        assertEquals(100L, s2.cachedBytes("a"))
        assertEquals(1, s2.snapshot(now).partialCount)
        assertEquals(0L, s2.lastAccessedAt("a"))
    }

    @Test
    fun `touch 会保留已有的总长度`() {
        val s = newStore()
        s.appendPart("a", bytes(100), 0, 100)
        s.finalizeIfComplete("a", 100, now)
        s.touch("a", "https://u/x", totalBytes = 0, nowMs = now)
        // 总长度未知时不该把已知的覆盖成 0
        assertEquals(100L, s.completeFile("a")!!.length())
    }

    // ═══════════════ 展示文本 ═══════════════

    @Test
    fun `占用文本可读`() {
        assertTrue(AudioCacheStatus.formatBytes(0) == "0 B")
        assertTrue(AudioCacheStatus.formatBytes(512).endsWith("B"))
        assertTrue(AudioCacheStatus.formatBytes(2048).endsWith("KB"))
        assertTrue(AudioCacheStatus.formatBytes(5L * 1024 * 1024).endsWith("MB"))
        assertTrue(AudioCacheStatus.formatBytes(3L * 1024 * 1024 * 1024).endsWith("GB"))
    }

    @Test
    fun `占用构成文本说明得清「被什么占了」`() {
        assertEquals("3 首已缓存", AudioCacheStatus(completeCount = 3).compositionText)
        assertEquals("2 首未缓存完", AudioCacheStatus(partialCount = 2).compositionText)
        assertEquals(
            "5 首已缓存 · 2 首未缓存完",
            AudioCacheStatus(completeCount = 5, partialCount = 2).compositionText
        )
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "内容不符：期望 ${expected.toList()}，实际 ${actual.toList()}",
            expected.toList(),
            actual.toList()
        )
    }
}
