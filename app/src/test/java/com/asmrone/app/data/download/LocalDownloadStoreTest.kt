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
 * 「保存到本地」这套存储的磁盘行为测试。
 *
 * 与 [DownloadStoreTest] 同一做法：真实临时目录 + 真实文件。这里要钉住的是
 * **和 App 内那份不同的地方** —— 文件名原样保留、目录带 RJ 号、清单里的 workId
 * 才是身份（目录名里的号码只是给人看的）。
 */
class LocalDownloadStoreTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("ToAsmr").toFile()
        LocalDownloadStore.initForTest(base)
    }

    @After
    fun tearDown() {
        LocalDownloadStore.initForTest(null)
        base.deleteRecursively()
    }

    private fun workId() = 1681251

    private fun workJson(sourceId: String = "RJ243448", title: String = "作品:测试?") =
        """{"id":${workId()},"title":"$title","source_id":"$sourceId"}"""

    private fun manifest(
        files: List<Pair<String, Long>> = emptyList(),
        storage: DownloadStorage = DownloadStorage.LOCAL,
        sourceId: String = "RJ243448",
        title: String = "作品:测试?"
    ) = DownloadManifest.empty(
        workId = workId(),
        host = "https://api.asmr-200.com",
        workJson = workJson(sourceId, title),
        tree = listOf(
            Track(
                title = "mp3",
                type = "folder",
                children = listOf(
                    Track(title = "01 本編.mp3", type = "audio", path = "mp3/01 本編.mp3", hash = "h1")
                ),
                path = "mp3"
            )
        )
    ).copy(storage = storage).let { base ->
        files.fold(base) { acc, (path, bytes) ->
            acc.withFile(
                DownloadedFile(
                    path = path,
                    // 本地这一档的文件名 = 相对作品的路径（保留层级与原文件名）
                    fileName = path,
                    bytes = bytes
                )
            )
        }
    }

    // ─────────────────────── 目录名与路径 ───────────────────────

    @Test
    fun `目录名带作品名与 RJ 号`() {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")

        // 非法字符被换成下划线，RJ 号原样保留在方括号里（给人在文件管理器里认）
        assertEquals("作品_测试_ [RJ243448]", dir.name)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `拿不到 RJ 号时退回 w 加 workId`() {
        val dir = LocalDownloadStore.workDir(workId(), "无 RJ 的作品", null)

        assertEquals("无 RJ 的作品 [w${workId()}]", dir.name)
    }

    @Test
    fun `同一个作品永远拿到同一个目录`() {
        val first = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        // 标题在服务端改了、或者这次没传 RJ —— 都不该再建一个目录
        val second = LocalDownloadStore.workDir(workId(), "改过的标题", null)

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, base.listFiles()?.count { it.isDirectory })
    }

    @Test
    fun `目标文件保留原来的目录层级与文件名`() {
        val file = LocalDownloadStore.fileFor(workId(), "作品:测试?", "01_本編/02 トラック.mp3")

        assertEquals("02 トラック.mp3", file.name)
        assertEquals("01_本編", file.parentFile?.name)
        assertTrue(file.parentFile!!.isDirectory)
    }

    @Test
    fun `路径里的非法字符被清洗掉`() {
        val file = LocalDownloadStore.fileFor(workId(), "作品", "a:b/c*d?.mp3")

        // 冒号、星号、问号都不能出现在公共存储的文件名里（只看名字，
        // 绝对路径里的盘符冒号是 Windows 的，不算）
        assertEquals("c_d_.mp3", file.name)
        assertEquals("a_b", file.parentFile?.name)
    }

    @Test
    fun `超长文件名截断但保留扩展名`() {
        val long = "あ".repeat(80) + ".mp3"
        val file = LocalDownloadStore.fileFor(workId(), "作品", long)

        assertTrue("名字长度 ${file.name.length}", file.name.length <= 60)
        assertTrue(file.name.endsWith(".mp3"))
    }

    @Test
    fun `不同文件夹里的同名文件不互相覆盖`() {
        val a = LocalDownloadStore.fileFor(workId(), "作品", "a/01.mp3")
        a.parentFile?.mkdirs()
        a.writeText("a")
        val b = LocalDownloadStore.fileFor(workId(), "作品", "b/01.mp3")

        assertEquals("a", a.parentFile?.name)
        assertEquals("b", b.parentFile?.name)
        assertTrue(a.absolutePath != b.absolutePath)
    }

    // ─────────────────────── 清单与索引 ───────────────────────

    @Test
    fun `保存的清单一定是本地那一档`() = runBlocking {
        // 传进去的是 App 内那份的清单（storage=APP），落盘时必须改成本地，
        // 否则下载页会把同一份内容列成两张卡
        LocalDownloadStore.save(manifest(storage = DownloadStorage.APP), "作品:测试?")

        assertEquals(DownloadStorage.LOCAL, LocalDownloadStore.load(workId())?.storage)
    }

    @Test
    fun `清单往返保住文件映射`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")

        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")

        val loaded = LocalDownloadStore.load(workId())
        assertNotNull(loaded)
        assertEquals("mp3/01 本編.mp3", loaded?.files?.get("mp3/01 本編.mp3")?.fileName)
        assertEquals(1, loaded?.fileCount)
    }

    @Test
    fun `冷启动只靠清单就能找回目录`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")

        // 模拟杀进程重进：索引清空，只剩磁盘上的目录与清单
        LocalDownloadStore.initForTest(base)
        LocalDownloadStore.rescan()

        // 目录名里是 RJ243448，workId 是 1681251 —— 只能靠清单里的 workId 认出来
        assertEquals(dir.absolutePath, LocalDownloadStore.workDir(workId(), "作品:测试?").absolutePath)
        assertNotNull(LocalDownloadStore.localFileFor(workId(), "mp3/01 本編.mp3"))
    }

    @Test
    fun `播放查询在索引没预热时返回 null`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")

        LocalDownloadStore.initForTest(base) // 清索引，不 rescan

        assertNull(LocalDownloadStore.localFileFor(workId(), "mp3/01 本編.mp3"))
    }

    @Test
    fun `零字节的残留文件不算已下载`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").createNewFile()
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 0L)), "作品:测试?")
        LocalDownloadStore.rescan()

        assertNull(LocalDownloadStore.localFileFor(workId(), "mp3/01 本編.mp3"))
    }

    @Test
    fun `没有清单的目录不算下载`() = runBlocking {
        File(base, "顺手丢进来的 [RJ1]").mkdirs()
        LocalDownloadStore.rescan()

        assertTrue(LocalDownloadStore.loadAll().isEmpty())
    }

    @Test
    fun `删除会连目录一起清掉`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")

        assertTrue(LocalDownloadStore.remove(workId()))

        assertFalse(dir.exists())
        assertNull(LocalDownloadStore.cached(workId()))
    }

    // ─────────────────────── 要不要再下一遍 ───────────────────────

    @Test
    fun `本地已有同一路径的文件就不再下载`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")
        LocalDownloadStore.rescan()

        assertNotNull(LocalDownloadStore.existingFileAt(workId(), "mp3/01 本編.mp3"))
        // 树里没列出的路径当然也查不到
        assertNull(LocalDownloadStore.existingFileAt(workId(), "mp3/99.mp3"))
    }

    @Test
    fun `清单被删掉后也能认出已经下过的文件`() = runBlocking {
        val dir = LocalDownloadStore.workDir(workId(), "作品:测试?", "RJ243448")
        File(dir, "mp3").mkdirs()
        File(dir, "mp3/01 本編.mp3").writeText("x")
        LocalDownloadStore.save(manifest(files = listOf("mp3/01 本編.mp3" to 1L)), "作品:测试?")
        File(dir, ".toasmr.json").delete()
        LocalDownloadStore.initForTest(base) // 索引与目录表全清，且不 rescan

        // 索引与清单都没了，但文件还在目录里 —— 仍然算「已经下过」：
        // 目录名里的 [RJ243448] 不是 workId，只能靠"标题 + RJ"推出目录名
        val found = LocalDownloadStore.existingFileAt(
            workId = workId(),
            path = "mp3/01 本編.mp3",
            title = "作品:测试?",
            sourceId = "RJ243448"
        )

        assertNotNull(found)
        assertEquals("x", found?.readText())
    }

    @Test
    fun `没有这个作品的目录时不做任何创建`() = runBlocking {
        assertNull(LocalDownloadStore.existingFileAt(workId(), "mp3/01 本編.mp3"))
        assertEquals(0, base.listFiles()?.size)
    }

    // ─────────────────────── 另存到本地 ───────────────────────

    @Test
    fun `把 App 内那份另存到本地时保留层级与文件名`() = runBlocking {
        val appDir = Files.createTempDirectory("downloads").toFile()
        try {
            DownloadStore.initForTest(appDir)
            val fileName = DownloadStore.fileNameFor("mp3/01 本編.mp3")
            DownloadStore.fileFor(workId(), "mp3/01 本編.mp3").apply {
                parentFile?.mkdirs()
                writeText("audio")
            }
            DownloadStore.save(
                DownloadManifest.empty(
                    workId = workId(),
                    host = "https://api.asmr-200.com",
                    workJson = workJson(),
                    tree = emptyList()
                ).withFile(DownloadedFile(path = "mp3/01 本編.mp3", fileName = fileName, bytes = 5L))
            )

            assertTrue(LocalDownloadStore.copyFromApp(workId()))

            val copied = LocalDownloadStore.localFileFor(workId(), "mp3/01 本編.mp3")
            assertNotNull(copied)
            assertEquals("audio", copied?.readText())
            assertEquals("01 本編.mp3", copied?.name)
            assertEquals(DownloadStorage.LOCAL, LocalDownloadStore.load(workId())?.storage)
        } finally {
            DownloadStore.initForTest(null)
            appDir.deleteRecursively()
        }
    }
}
