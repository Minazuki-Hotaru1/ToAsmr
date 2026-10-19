package com.asmrone.app.data.cache

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * 详情页元数据落盘的行为测试。
 *
 * 用真实临时目录 + 真实文件：这里要验证的正是"落盘 / 读回 / 换主机失效 / 淘汰"
 * 这些只在磁盘上才成立的事。
 */
class WorkMetaStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("work-meta").toFile()
        WorkMetaStore.initForTest(dir)
    }

    @After
    fun tearDown() {
        WorkMetaStore.initForTest(null)
        dir.deleteRecursively()
    }

    private fun meta(id: Int, title: String = "测试作品 $id") = JSONObject(
        """{"id":$id,"title":"$title","rate":4.5}"""
    )

    private fun file(workId: Int) = File(dir, "w$workId.json")

    @Test
    fun `落盘后能原样读回`() = runBlocking {
        WorkMetaStore.save(42, "https://asmr.one", meta(42, "安静的一夜"))

        val entry = WorkMetaStore.load(42, "https://asmr.one")

        assertNotNull(entry)
        assertEquals("安静的一夜", entry!!.json.optString("title"))
        assertEquals(42, entry.json.optInt("id"))
        assertTrue("savedAt 必须是真实时间戳", entry.savedAt > 0L)
    }

    @Test
    fun `换主机视为没缓存并删掉旧文件`() = runBlocking {
        WorkMetaStore.save(42, "https://a.example", meta(42))

        val entry = WorkMetaStore.load(42, "https://b.example")

        assertNull(entry)
        assertTrue("主机不符的旧文件应当被清掉", !file(42).exists())
    }

    @Test
    fun `forget 之后不再命中`() = runBlocking {
        WorkMetaStore.save(42, "h", meta(42))
        WorkMetaStore.forget(42)

        assertNull(WorkMetaStore.load(42, "h"))
        assertEquals(0, WorkMetaStore.count())
    }

    @Test
    fun `坏文件读不出来且被删除`() = runBlocking {
        file(7).writeText("{ 这不是 JSON", Charsets.UTF_8)

        assertNull(WorkMetaStore.load(7, "h"))
        assertTrue(!file(7).exists())
    }

    @Test
    fun `版本不符视为没缓存`() = runBlocking {
        file(7).writeText("""{"v":999,"host":"h","work":{"id":7}}""", Charsets.UTF_8)

        assertNull(WorkMetaStore.load(7, "h"))
    }

    @Test
    fun `非法 workId 不写也不读`() = runBlocking {
        WorkMetaStore.save(0, "h", meta(0))
        WorkMetaStore.save(-3, "h", meta(-3))

        assertEquals(0, WorkMetaStore.count())
        assertNull(WorkMetaStore.load(0, "h"))
    }

    @Test
    fun `超出上限按最久未用淘汰`() = runBlocking {
        // 四份等大（同一段长标题），先写三份并把它们的"最近使用"时间写死成 1/2/3 秒，
        // 这样淘汰顺序只由 lastModified 决定，与文件系统写入顺序无关
        val bulky = "x".repeat(400)
        listOf(1, 2, 3).forEach { id ->
            WorkMetaStore.save(id, "h", meta(id, bulky))
            file(id).setLastModified(id * 1000L)
        }

        // 收紧上限后再写一份：总量超限 → 从最旧的开始删
        WorkMetaStore.initForTest(dir, maxBytes = 1000L)
        WorkMetaStore.save(4, "h", meta(4, bulky))

        assertTrue("最新的一份必须留下", WorkMetaStore.has(4))
        assertTrue("最旧的一份必须被删", !WorkMetaStore.has(1))
        assertTrue("总量要压回上限以内", WorkMetaStore.sizeBytes() <= 1000L)
    }

    @Test
    fun `clearAll 清空整个目录`() = runBlocking {
        WorkMetaStore.save(1, "h", meta(1))
        WorkMetaStore.save(2, "h", meta(2))

        WorkMetaStore.clearAll()

        assertEquals(0, WorkMetaStore.count())
        assertEquals(0L, WorkMetaStore.sizeBytes())
    }
}
