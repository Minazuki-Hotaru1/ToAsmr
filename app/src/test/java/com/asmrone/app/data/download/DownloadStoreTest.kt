package com.asmrone.app.data.download

import com.asmrone.app.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 下载存储的**磁盘行为**测试。
 *
 * 用真实临时目录 + 真实文件（与 `TrackTreeStoreTest`、`AudioCacheStoreTest` 同一做法），
 * 因为这里要验证的正是"文件名怎么算、文件在不在、删目录删干净了没有"这些
 * 只在磁盘上才成立的事情。
 */
class DownloadStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("downloads").toFile()
        DownloadStore.initForTest(dir)
    }

    @After
    fun tearDown() {
        DownloadStore.initForTest(null)
        dir.deleteRecursively()
    }

    private fun workId() = 1681251

    private fun manifest(
        files: List<Pair<String, Long>> = emptyList(),
        id: Int = workId()
    ) =
        DownloadManifest.empty(
            workId = id,
            host = "https://api.asmr-200.com",
            workJson = """{"id":1681251,"title":"测试"}""",
            tree = listOf(
                Track(
                    title = "mp3",
                    type = "folder",
                    children = listOf(
                        Track(title = "01.mp3", type = "audio", path = "mp3/01.mp3", hash = "h1"),
                        Track(title = "02.mp3", type = "audio", path = "mp3/02.mp3", hash = "h2")
                    ),
                    path = "mp3"
                )
            )
        ).let { base ->
            files.fold(base) { acc, (path, bytes) ->
                acc.withFile(
                    DownloadedFile(path = path, fileName = DownloadStore.fileNameFor(path), bytes = bytes)
                )
            }
        }

    // ─────────────────────────── 文件名 ───────────────────────────

    @Test
    fun `文件名是确定性的：同一路径永远算出同一个名字`() {
        assertEquals(
            DownloadStore.fileNameFor("03_omake/02_mp3（音声のみ）/01_あ.mp3"),
            DownloadStore.fileNameFor("03_omake/02_mp3（音声のみ）/01_あ.mp3")
        )
    }

    @Test
    fun `不同路径得到不同文件名`() {
        val a = DownloadStore.fileNameFor("mp3/01.mp3")
        val b = DownloadStore.fileNameFor("wav/01.wav")
        assertFalse(a == b)
    }

    @Test
    fun `文件名带上小写扩展名，且不含路径分隔符与特殊字符`() {
        val name = DownloadStore.fileNameFor("mp3/sub/01.MP3")
        assertTrue("扩展名要保留成小写，播放内核靠它挑解码器：$name", name.endsWith(".mp3"))
        assertFalse("不能含斜杠", name.contains('/'))
        assertFalse("不能含反斜杠", name.contains('\\'))
        // 只允许 0-9a-f 与一个点
        assertTrue("意外字符：$name", name.dropLast(4).all { it in "0123456789abcdef" })
    }

    @Test
    fun `没有扩展名的路径也能算出文件名`() {
        val name = DownloadStore.fileNameFor("readme")
        assertEquals(20, name.length)
    }

    // ─────────────────────────── localFileFor ───────────────────────────

    @Test
    fun `文件真的落盘后 localFileFor 才返回它`() {
        val path = "mp3/01.mp3"
        assertNull("还没下载时不能返回 File", DownloadStore.localFileFor(workId(), path))

        val file = DownloadStore.fileFor(workId(), path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(128) { 1 })

        val found = DownloadStore.localFileFor(workId(), path)
        assertNotNull(found)
        assertEquals(file.absolutePath, found?.absolutePath)
    }

    @Test
    fun `零字节文件不算已下载`() {
        val path = "mp3/02.mp3"
        val file = DownloadStore.fileFor(workId(), path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        assertNull("空的半成品文件不能拿去播放", DownloadStore.localFileFor(workId(), path))
    }

    @Test
    fun `localFileFor 不需要读清单`() {
        // 直接把文件放到位，一个字节的清单都不写
        val path = "mp3/01.mp3"
        val file = DownloadStore.fileFor(workId(), path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(32) { 7 })
        assertFalse("这部作品还没写过清单", DownloadStore.isDownloaded(workId()))
        assertNotNull(DownloadStore.localFileFor(workId(), path))
    }

    // ─────────────────────────── 清单读写 ───────────────────────────

    @Test
    fun `清单存下去能原样读回来`() = runBlocking {
        DownloadStore.save(manifest(listOf("mp3/01.mp3" to 1000L)))

        // 新实例（清掉内存缓存）模拟冷启动
        DownloadStore.initForTest(dir)
        val loaded = DownloadStore.load(workId())
        assertNotNull(loaded)
        assertEquals(1, loaded?.fileCount)
        assertEquals(1000L, loaded?.totalBytes)
        assertEquals("""{"id":1681251,"title":"测试"}""", loaded?.workJson)
        assertTrue("目录存在即算已下载", DownloadStore.isDownloaded(workId()))
    }

    @Test
    fun `save 之后内存缓存立刻可用，不必再读盘`() = runBlocking {
        val m = manifest(listOf("mp3/01.mp3" to 1L))
        DownloadStore.save(m)
        assertTrue(DownloadStore.isCached(workId()))
        assertEquals(1, DownloadStore.cached(workId())?.fileCount)
        assertEquals(1, DownloadStore.load(workId())?.fileCount)
    }

    @Test
    fun `清单文件损坏时视为没下载过，并把它删掉`() = runBlocking {
        val file = DownloadStore.manifestFile(workId())
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 json")

        assertNull(DownloadStore.load(workId()))
        assertFalse("坏清单要清掉，否则每次都白解析一遍", file.exists())
    }

    @Test
    fun `loadAll 读回所有作品的清单`() = runBlocking {
        DownloadStore.save(manifest(listOf("mp3/01.mp3" to 1L)))
        DownloadStore.save(
            DownloadManifest.empty(2, "h", """{"id":2}""", emptyList())
        )
        val all = DownloadStore.loadAll()
        assertEquals(setOf(workId(), 2), all.map { it.workId }.toSet())
    }

    // ─────────────────────────── 删除与统计 ───────────────────────────

    @Test
    fun `remove 把整个作品目录连音频一起删掉`() = runBlocking {
        DownloadStore.save(manifest(listOf("mp3/01.mp3" to 1000L)))
        val audio = DownloadStore.fileFor(workId(), "mp3/01.mp3")
        audio.parentFile?.mkdirs()
        audio.writeBytes(ByteArray(1000))

        assertTrue(DownloadStore.remove(workId()))
        assertFalse("目录要整个消失", DownloadStore.workDir(workId()).exists())
        assertFalse(DownloadStore.isDownloaded(workId()))
        assertNull(DownloadStore.cached(workId()))
    }

    @Test
    fun `totalBytes 不把清单本身算进去`() = runBlocking {
        DownloadStore.save(manifest(listOf("mp3/01.mp3" to 1000L)))
        val audio = DownloadStore.fileFor(workId(), "mp3/01.mp3")
        audio.parentFile?.mkdirs()
        audio.writeBytes(ByteArray(1000))

        assertEquals(1000L, DownloadStore.totalBytes())
    }

    @Test
    fun `part 文件与正式文件同名不同后缀，且同目录`() {
        val path = "mp3/01.mp3"
        val part = DownloadStore.partFile(workId(), path)
        val full = DownloadStore.fileFor(workId(), path)
        assertEquals(full.parent, part.parent)
        assertEquals(full.name + ".part", part.name)
    }
}
