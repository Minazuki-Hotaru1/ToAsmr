package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 文件树本地持久化（按 RJ 号）的行为测试。
 *
 * 用真实临时目录 + 真实文件，因为这里要验证的正是"落盘 / 读回 / 淘汰"这些
 * 只在磁盘上才成立的事情（与 `AudioCacheStoreTest` 同一个思路）。
 */
class TrackTreeStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("track-tree").toFile()
        TrackTreeStore.initForTest(dir)
    }

    @After
    fun tearDown() {
        TrackTreeStore.initForTest(null)
        dir.deleteRecursively()
    }

    // ─────────────────────────── 构造 ───────────────────────────

    private fun audio(path: String, title: String = path.substringAfterLast('/')) = Track(
        title = title,
        type = "audio",
        hash = "h-$path",
        size = 1024,
        durationSec = 12.5,
        mediaStreamUrl = "https://cdn/$path",
        mediaDownloadUrl = "https://cdn/dl/$path",
        streamLowQualityUrl = "https://cdn/low/$path",
        path = path
    )

    private fun folder(path: String, children: List<Track>) = Track(
        title = path.substringAfterLast('/'),
        type = "folder",
        children = children,
        path = path
    )

    private fun sampleTree(): List<Track> = listOf(
        folder("mp3", listOf(audio("mp3/Tr1.mp3"), audio("mp3/Tr2.mp3"))),
        folder("wav", listOf(audio("wav/Tr1.wav"))),
        Track(title = "readme.txt", type = "text", path = "readme.txt", mediaStreamUrl = "https://cdn/r")
    )

    // ─────────────────────────── 往返 ───────────────────────────

    @Test
    fun `★ 按 RJ 号存，能按 RJ 号读回`() = runBlocking {
        TrackTreeStore.save("RJ243448", 243448, "https://api.example", sampleTree())
        assertTrue("应当按 RJ 命名", File(dir, "RJ243448.json").exists())

        val back = TrackTreeStore.load("RJ243448", 243448)
        assertNotNull(back)
        assertEquals(3, back!!.size)
        assertEquals(listOf("mp3", "wav", "readme.txt"), back.map { it.title })
    }

    @Test
    fun `★ 整棵树的层级、path 与所有字段都保真`() = runBlocking {
        TrackTreeStore.save("RJ243448", 243448, "h", sampleTree())
        val back = TrackTreeStore.load("RJ243448", 243448)!!

        val mp3 = back.first { it.title == "mp3" }
        assertEquals("mp3", mp3.path)
        assertTrue(mp3.isFolder)
        assertEquals(listOf("mp3/Tr1.mp3", "mp3/Tr2.mp3"), mp3.children.map { it.path })

        val tr1 = mp3.children.first()
        assertEquals("audio", tr1.type)
        assertEquals("h-mp3/Tr1.mp3", tr1.hash)
        assertEquals(1024L, tr1.size)
        assertEquals(12.5, tr1.durationSec!!, 0.0001)
        assertEquals("https://cdn/mp3/Tr1.mp3", tr1.mediaStreamUrl)
        assertEquals("https://cdn/dl/mp3/Tr1.mp3", tr1.mediaDownloadUrl)
        assertEquals("https://cdn/low/mp3/Tr1.mp3", tr1.streamLowQualityUrl)
    }

    @Test
    fun `没有 RJ 号时按 workId 存，之后仍能读到`() = runBlocking {
        TrackTreeStore.save(null, 999, "h", sampleTree())
        assertTrue(File(dir, "w999.json").exists())

        // 之后即使调用方知道了 RJ 号，也要能命中那份 w 文件
        assertNotNull(TrackTreeStore.load("RJ999", 999))
    }

    @Test
    fun `RJ 优先于 workId 命中`() = runBlocking {
        TrackTreeStore.save("RJ1", 1, "h", listOf(audio("a/only.mp3")))
        val viaRj = TrackTreeStore.load("RJ1", 1)
        assertEquals("a/only.mp3", viaRj!!.single().path)
    }

    @Test
    fun `没存过时返回 null`() = runBlocking {
        assertNull(TrackTreeStore.load("RJ404", 404))
    }

    @Test
    fun `空树不写盘`() = runBlocking {
        TrackTreeStore.save("RJ1", 1, "h", emptyList())
        assertTrue("空树没有意义，不该产生文件", dir.listFiles().isNullOrEmpty())
    }

    // ─────────────────────────── 清理 ───────────────────────────

    @Test
    fun `按 RJ 存了之后可以只删掉 workId 那份`() = runBlocking {
        TrackTreeStore.save(null, 7, "h", sampleTree())
        assertTrue(File(dir, "w7.json").exists())

        TrackTreeStore.save("RJ7", 7, "h", sampleTree())
        TrackTreeStore.forgetByIdOnly(7)

        assertTrue("RJ 那份要留着", File(dir, "RJ7.json").exists())
        assertTrue("w<id> 那份要删掉，避免同一棵树占两份", !File(dir, "w7.json").exists())
        assertNotNull(TrackTreeStore.load("RJ7", 7))
    }

    @Test
    fun `forget 把两份都删掉`() = runBlocking {
        TrackTreeStore.save(null, 7, "h", sampleTree())
        TrackTreeStore.save("RJ7", 7, "h", sampleTree())
        TrackTreeStore.forget("RJ7", 7)
        assertNull(TrackTreeStore.load("RJ7", 7))
    }

    @Test
    fun `clearAll 清空全部`() = runBlocking {
        TrackTreeStore.save("RJ1", 1, "h", sampleTree())
        TrackTreeStore.save("RJ2", 2, "h", sampleTree())
        assertTrue(TrackTreeStore.count() >= 2)
        TrackTreeStore.clearAll()
        assertEquals(0, TrackTreeStore.count())
        assertNull(TrackTreeStore.load("RJ1", 1))
    }

    // ─────────────────────────── 健壮性 ───────────────────────────

    @Test
    fun `★ 文件损坏时当作没有缓存，并把坏文件删掉`() = runBlocking {
        File(dir, "RJ1.json").writeText("{ 这不是 JSON")
        assertNull(TrackTreeStore.load("RJ1", 1))
        assertTrue("坏文件应当被删掉，否则每次读都要再解析一遍", !File(dir, "RJ1.json").exists())
    }

    @Test
    fun `版本号不匹配时当作没有缓存`() = runBlocking {
        File(dir, "RJ1.json").writeText("""{"v":999,"tracks":[]}""")
        assertNull(TrackTreeStore.load("RJ1", 1))
    }

    @Test
    fun `RJ 号里的非法字符被清掉，不会跑出目录`() = runBlocking {
        TrackTreeStore.save("../../etc/passwd", 1, "h", sampleTree())
        val files = dir.listFiles()!!.map { it.name }
        assertEquals("只该在缓存目录里留一个文件", 1, files.size)
        assertTrue("文件名不能含斜杠或点：${files.single()}", !files.single().contains('/'))
        assertNotNull(TrackTreeStore.load("../../etc/passwd", 1))
    }

    @Test
    fun `未初始化时读不崩、写不崩`() = runBlocking {
        TrackTreeStore.initForTest(null)
        assertNull(TrackTreeStore.load("RJ1", 1))
        TrackTreeStore.save("RJ1", 1, "h", sampleTree())
        assertTrue(!TrackTreeStore.has("RJ1", 1))
        assertEquals(0L, TrackTreeStore.sizeBytes())
    }

    @Test
    fun `has 只判文件存在，不解析内容`() = runBlocking {
        assertTrue(!TrackTreeStore.has("RJ1", 1))
        TrackTreeStore.save("RJ1", 1, "h", sampleTree())
        assertTrue(TrackTreeStore.has("RJ1", 1))
    }

    // ─────────────────────────── 容量 ───────────────────────────

    @Test
    fun `总量统计包含所有文件`() = runBlocking {
        TrackTreeStore.save("RJ1", 1, "h", sampleTree())
        val size = TrackTreeStore.sizeBytes()
        assertTrue("应当统计到实际字节数，实际 $size", size > 0)
        assertEquals(File(dir, "RJ1.json").length(), size)
    }

    @Test
    fun `★ 超出上限时按最后修改时间淘汰最旧的`() = runBlocking {
        // ① 先用一个很大的上限把三份写进去（否则写到第三份时就会触发淘汰）
        TrackTreeStore.initForTest(dir, maxBytes = 10_000_000)
        TrackTreeStore.save("RJ-a", 1, "h", sampleTree())
        File(dir, "RJ-a.json").setLastModified(1_000_000L)
        TrackTreeStore.save("RJ-b", 2, "h", sampleTree())
        File(dir, "RJ-b.json").setLastModified(2_000_000L)
        TrackTreeStore.save("RJ-c", 3, "h", sampleTree())
        File(dir, "RJ-c.json").setLastModified(3_000_000L)

        val total3 = TrackTreeStore.sizeBytes()
        assertTrue("前置条件：三份都要在（$total3 字节）", total3 > 0 && TrackTreeStore.count() == 3)

        // 读到 b 会刷新它的时间戳 —— 于是 a 变成最旧的
        TrackTreeStore.load("RJ-b", 2)

        // ② 把上限压到比现有总量还小，再写一份 → 触发淘汰
        TrackTreeStore.initForTest(dir, maxBytes = total3 - 1)
        TrackTreeStore.save("RJ-d", 4, "h", sampleTree())

        assertTrue("刚写的那份必须留着", File(dir, "RJ-d.json").exists())
        assertTrue("刚刚读过的那份也不该被淘汰", File(dir, "RJ-b.json").exists())
        assertTrue("最久没碰过的要先被删掉", !File(dir, "RJ-a.json").exists())
        assertTrue(
            "总量应当被压回上限以内，实际 ${TrackTreeStore.sizeBytes()}",
            TrackTreeStore.sizeBytes() <= total3 - 1
        )
    }

    @Test
    fun `读取会刷新最近使用时间（LRU 依据）`() = runBlocking {
        TrackTreeStore.save("RJ-a", 1, "h", sampleTree())
        File(dir, "RJ-a.json").setLastModified(1_000_000L)
        TrackTreeStore.load("RJ-a", 1)
        assertTrue(
            "读一次之后时间戳应当变新",
            File(dir, "RJ-a.json").lastModified() > 1_000_000L
        )
    }
}
