package com.asmrone.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 单曲缓存记账。
 *
 * ## 这里原本测的是一个复杂得多的东西
 *
 * 前后有两版更复杂的实现，都删掉了：
 *
 * 1. **`ReadAheadBudget`** —— 管"预读额度 2MB"与"单曲上限"两个量，
 *    于是要测两段分别记账、跨段头尾拆记、换曲重置额度……
 * 2. **`TrackCacheSession`** —— 去掉预读额度后，仍保留了"起始阶段块长截断"
 *    这个连带的产物。
 *
 * 用户的判断更直接：曲目之间的等待本来就只有 5 秒左右，
 * 为"下一首"预留数据没有意义，为"当前这首"多读一小段同样没有意义。
 *
 * ## 现在只剩两件事要守
 *
 * ```
 * ① 缓存由播放器驱动 —— 不需要任何额度去模拟"别一次拉整首"
 * ② 唯一封顶是用户可配的单曲上限
 * ```
 *
 * 注意"缓存由播放器驱动"这件事**测不到**（它在 `output.write` 的阻塞行为里，
 * 要真机才验证得了）。所以这里测的是上限语义，以及"改设置下一首生效"。
 */
class TrackCacheLedgerTest {

    private val mb = 1024L * 1024L

    private val defaultCap = AudioCachePolicy.DEFAULT_PER_TRACK_MB.toLong() * mb

    private fun capMb(n: Int): Long = n.toLong() * mb

    /**
     * ⚠️ 每个测试前必须清掉共享记账。
     *
     * [TrackCacheLedger] 是 object（同时只播一首，当前曲目是唯一上下文）。
     * 不清的话前一个测试写进去的字节会被后一个测试当成自己的起点，
     * 断言会差一个量级且**看起来像实现 bug**。
     * （这个坑我踩过一次，排查花了很多轮 —— 诊断值其实一直是自洽的。）
     */
    @Before
    fun resetLedger() {
        TrackCacheLedger.clear()
    }

    /** 把当前这一首能写的量跑满，返回累计写入 */
    private fun drain(cap: Long, total: Long = Long.MAX_VALUE): Long {
        TrackCacheLedger.startFor("t", cap)
        var written = 0L
        repeat(400_000) {
            val remaining = TrackCacheLedger.remaining()
            if (remaining <= 0) return@repeat
            // 代理按读缓冲（64KB）整块写，所以这里也整块推进
            val chunk = minOf(64L * 1024, total - written)
            if (chunk <= 0) return@repeat
            // 追加写不截断：最后一块可能略微超过上限（最多一个读缓冲）
            TrackCacheLedger.record(chunk)
            written += chunk
        }
        return written
    }

    // ═══════════════ ① 上限语义 ═══════════════

    @Test
    fun `★ 新的一首从完整的上限开始`() {
        TrackCacheLedger.startFor("t", capMb(64))
        assertEquals(64 * mb, TrackCacheLedger.remaining())
        assertEquals(0L, TrackCacheLedger.spentBytes())
        assertEquals(64 * mb, TrackCacheLedger.capBytes())
    }

    @Test
    fun `★ 已写量从剩余里扣减`() {
        TrackCacheLedger.startFor("t", capMb(64))
        TrackCacheLedger.record(10 * mb)
        assertEquals(54 * mb, TrackCacheLedger.remaining())

        TrackCacheLedger.record(4 * mb)
        assertEquals(50 * mb, TrackCacheLedger.remaining())
        assertEquals(14 * mb, TrackCacheLedger.spentBytes())
    }

    @Test
    fun `★ 达到上限后剩余归零（代理据此转为只转发）`() {
        TrackCacheLedger.startFor("t", capMb(4))
        TrackCacheLedger.record(4 * mb)
        assertEquals(0L, TrackCacheLedger.remaining())

        // 再记也不会变成负数
        TrackCacheLedger.record(1 * mb)
        assertEquals(0L, TrackCacheLedger.remaining())
    }

    @Test
    fun `★ 累计写入在整块推进下不会明显超过上限`() {
        // 追加写不截断，所以最多超出"最后一个读缓冲"（64KB）。
        // 这是刻意接受的：为这点溢出引入"按剩余量截断"那层复杂度不划算。
        val cap = 32 * mb
        val written = drain(cap)
        assertTrue(
            "实际写入 $written 不该超过上限 $cap 一个读缓冲以上",
            written <= cap + 64 * 1024
        )
        assertTrue("但也不该明显少于上限", written >= cap)
    }

    @Test
    fun `上限为 0 或负数时剩余恒为 0`() {
        TrackCacheLedger.startFor("t", 0)
        assertEquals(0L, TrackCacheLedger.remaining())

        TrackCacheLedger.startFor("t", -100)
        assertEquals(0L, TrackCacheLedger.remaining())
        assertEquals(0L, TrackCacheLedger.capBytes())
    }

    @Test
    fun `record 忽略非正数（不写就不该扣额度）`() {
        TrackCacheLedger.startFor("t", capMb(8))
        TrackCacheLedger.record(0)
        TrackCacheLedger.record(-5)
        assertEquals(0L, TrackCacheLedger.spentBytes())
        assertEquals(8 * mb, TrackCacheLedger.remaining())
    }

    // ═══════════════ ② claimFor：连接与曲目的关系 ═══════════════

    @Test
    fun `★ 同一个资源多次 claim 沿用同一份记账`() {
        // 播放器可能重连开多条连接。若每条连接都重置上限，
        // 上限就等于失效（每 2MB 重新开始算）
        TrackCacheLedger.startFor("same", capMb(32))
        TrackCacheLedger.record(20 * mb)

        TrackCacheLedger.claimFor("same", capMb(32))
        assertEquals("沿用已写量", 20 * mb, TrackCacheLedger.spentBytes())
        assertEquals("剩余应是扣减后的", 12 * mb, TrackCacheLedger.remaining())
    }

    @Test
    fun `★ 换了资源 claim 会新建记账`() {
        TrackCacheLedger.startFor("a", capMb(32))
        TrackCacheLedger.record(32 * mb)
        assertEquals(0L, TrackCacheLedger.remaining())

        TrackCacheLedger.claimFor("b", capMb(32))
        assertEquals("新资源必须重新拥有完整上限", 0L, TrackCacheLedger.spentBytes())
        assertEquals(32 * mb, TrackCacheLedger.remaining())
    }

    @Test
    fun `★ 从没 startFor 过的资源，claim 会补上一次`() {
        // 代理可能在 startFor 之前就收到请求（播放器抢跑），
        // 这时不能因为"没有记账"就完全不缓存
        TrackCacheLedger.clear()
        TrackCacheLedger.claimFor("fresh", capMb(16))
        assertEquals(16 * mb, TrackCacheLedger.remaining())
    }

    @Test
    fun `★ claim 会采用传入的上限（改设置下一首生效）`() {
        TrackCacheLedger.startFor("t1", capMb(32))
        // 用户在设置里调到 512MB，然后播下一首
        TrackCacheLedger.claimFor("t2", capMb(512))
        assertEquals(512 * mb, TrackCacheLedger.capBytes())
    }

    // ═══════════════ ③ 换曲重置 ═══════════════

    @Test
    fun `★ startFor 清掉上一首的记账`() {
        TrackCacheLedger.startFor("a", capMb(32))
        TrackCacheLedger.record(32 * mb)
        assertEquals(0L, TrackCacheLedger.remaining())

        TrackCacheLedger.startFor("b", capMb(32))
        assertEquals("第二首必须重新拥有完整上限", 32 * mb, TrackCacheLedger.remaining())
        assertEquals(0L, TrackCacheLedger.spentBytes())
    }

    @Test
    fun `★ 重播同一首也是全新的记账`() {
        TrackCacheLedger.startFor("a", capMb(32))
        TrackCacheLedger.record(32 * mb)

        TrackCacheLedger.startFor("a", capMb(32))
        assertEquals(32 * mb, TrackCacheLedger.remaining())
    }

    @Test
    fun `★ 连续 5 首连播，每首都各自缓存完整`() {
        val perTrack = 4_752_557L   // 实测那条三原実莉 file2 的体积

        repeat(5) { i ->
            val id = "track-$i"
            TrackCacheLedger.startFor(id, defaultCap)
            var cached = 0L
            while (cached < perTrack) {
                if (TrackCacheLedger.remaining() <= 0) break
                val chunk = minOf(64L * 1024, perTrack - cached)
                TrackCacheLedger.record(chunk)
                cached += chunk
            }
            assertEquals("第 ${i + 1} 首应能缓存完整", perTrack, cached)
        }
    }

    // ═══════════════ ④ 真实体积场景 ═══════════════

    @Test
    fun `★ 模拟一条 11_4MB 音频被正常听完 —— 缓存应当完整`() {
        // 这正是当初出问题的那条音频（日志里的 11444553 字节）
        val total = 11_444_553L
        val written = drain(defaultCap, total)
        assertEquals("正常听完应能缓存完整", total, written)
    }

    @Test
    fun `★ 92MB 的 WAV 在默认上限下能缓存完整`() {
        // 实测单轨最大 92MB。真机日志显示 MediaPlayer 常常
        // 只开一条连接从头读到尾，所以上限必须能装下它
        val total = 92L * 1024 * 1024
        assertEquals("92MB 的单轨在默认上限下必须能缓存完整", total, drain(defaultCap, total))
    }

    @Test
    fun `★ 上限设太小会让大曲子缓存不完整 —— 这正是要给用户的警告`() {
        val total = 92L * 1024 * 1024
        val written = drain(32 * mb, total)
        assertTrue("应当停在上限附近而不是走完全长", written < total)
        assertEquals(32 * mb, written)
    }

    // ═══════════════ ⑤ 配置项自身 ═══════════════

    @Test
    fun `★ 默认单曲上限覆盖实测最大单轨`() {
        val largestObserved = 92L * 1024 * 1024
        assertTrue(
            "默认上限(${AudioCachePolicy.DEFAULT_PER_TRACK_MB}MB) 必须能装下最大的单轨(92MB)",
            AudioCachePolicy.DEFAULT_PER_TRACK_MB.toLong() * mb > largestObserved
        )
    }

    @Test
    fun `★ 可选值与允许区间自洽`() {
        assertTrue(
            "默认值要落在允许区间内，否则用户一进设置就看到非法值",
            AudioCachePolicy.DEFAULT_PER_TRACK_MB in
                AudioCachePolicy.MIN_PER_TRACK_MB..AudioCachePolicy.MAX_PER_TRACK_MB
        )
        AudioCachePolicy.PER_TRACK_CHOICES_MB.forEach {
            assertTrue(
                "$it MB 这个可选项超出了允许区间",
                it in AudioCachePolicy.MIN_PER_TRACK_MB..AudioCachePolicy.MAX_PER_TRACK_MB
            )
        }
        assertTrue(
            "默认值应当是可选项之一，否则芯片里没有一个被选中",
            AudioCachePolicy.DEFAULT_PER_TRACK_MB in AudioCachePolicy.PER_TRACK_CHOICES_MB
        )
        assertTrue(AudioCachePolicy.DEFAULT_LIMIT_MB in AudioCachePolicy.TOTAL_LIMIT_CHOICES_MB)
    }

    // ═══════════════ ⑥ 源码守卫 ═══════════════

    private fun source(path: String) = java.io.File("src/main/java/com/asmrone/app/$path").readText()

    @Test
    fun `★ 播放器的换曲路径必须重置记账`() {
        // 这条不变量落在播放器的调用链里，纯单测覆盖不到，
        // 所以按本工程既有做法做源码级断言。
        val controller = source("player/AudioController.kt")

        assertTrue(
            "startCurrent() 里必须 startFor，否则第二首起会被判定已达上限而完全不再缓存",
            controller.contains("TrackCacheLedger.startFor(")
        )
        assertTrue(
            "必须传入用户配置的单曲上限",
            controller.contains("AppPrefs.perTrackCacheLimitMb.value")
        )
        assertTrue(
            "next() 的下标由播放模式决定，但必须落到 playTrackAt 上（否则不会重置记账）",
            controller.contains("PlayQueuePolicy.manualNextIndex(")
        )
        assertTrue(
            "prev() 同样要经 playTrackAt",
            controller.contains("PlayQueuePolicy.manualPrevIndex(")
        )
        assertTrue(
            "播完自动连播也要走 playTrackAt（下标可能指向任意一首，记账必须跟着换）",
            controller.contains("if (target >= 0) playTrackAt(target) else finishPlayback(cur)")
        )
    }

    @Test
    fun `★ 代理必须从用户设置读上限，而不是写死常量`() {
        assertTrue(
            "代理要从 AppPrefs 读单曲上限",
            source("data/audio/LocalAudioProxy.kt").contains("AppPrefs.perTrackCacheLimitMb.value")
        )
    }

    @Test
    fun `★ 预读那一层已经删掉 —— 防止被重新引入`() {
        // 用户明确要求删除：曲间等待只有 5 秒左右，为下一首预留数据没有意义，
        // 而为当前这首多读一小段同样没有意义。这条守卫防止它悄悄回来。
        val dir = java.io.File("src/main/java/com/asmrone/app/data/audio")
        listOf("ReadAheadBudget.kt", "TrackCacheSession.kt").forEach { gone ->
            assertTrue("$gone 应当已被删除", !java.io.File(dir, gone).exists())
        }

        val proxy = source("data/audio/LocalAudioProxy.kt")
        assertTrue("代理不该再引用它们", !proxy.contains("ReadAheadBudget"))
        assertTrue(!proxy.contains("TrackCacheSession"))
        assertTrue("不该再有预读语义的日志", !proxy.contains("代理预读"))
        assertTrue("不该再有「预读额度」字样", !proxy.contains("预读额度"))

        val ledger = source("data/audio/TrackCacheLedger.kt")
        assertTrue("记账里不该再有预读常量", !ledger.contains("PRELOAD_BYTES"))
        assertTrue("记账里不该再有起始阶段概念", !ledger.contains("isPreloading"))
    }
}
