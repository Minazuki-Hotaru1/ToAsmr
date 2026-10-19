package com.asmrone.app.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 文件树展开态的持久化测试。
 *
 * 重点不在"能存能读"，而在**重启后还在**与**不会无限涨**这两件事：
 * 两者都只有在真实文件上才成立。
 */
class FolderStateStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("folder-state").toFile()
        FolderStateStore.initForTest(dir)
    }

    @After
    fun tearDown() {
        FolderStateStore.initForTest(null)
        dir.deleteRecursively()
    }

    private fun stateFile() = File(dir, "folder_state.json")

    @Test
    fun `存取往返`() = runBlocking {
        val paths = setOf("RJ1/audio", "RJ1/audio/mp3")

        FolderStateStore.save(1, paths)

        assertEquals(paths, FolderStateStore.load(1))
    }

    @Test
    fun `没记过的作品是空集`() = runBlocking {
        assertEquals(emptySet<String>(), FolderStateStore.load(999))
    }

    @Test
    fun `重启后仍然记得`() = runBlocking {
        FolderStateStore.save(1, setOf("a/b"))

        // 模拟进程重启：丢掉内存里的那份，只留下磁盘文件
        FolderStateStore.initForTest(dir)

        assertEquals(setOf("a/b"), FolderStateStore.load(1))
    }

    @Test
    fun `全部收起等于删掉记录`() = runBlocking {
        FolderStateStore.save(1, setOf("a/b"))

        FolderStateStore.save(1, emptySet())

        assertEquals(emptySet<String>(), FolderStateStore.load(1))
        val works = org.json.JSONObject(stateFile().readText()).getJSONObject("works")
        assertEquals("没有记录时不该留下空节点", 0, works.length())
    }

    @Test
    fun `不同作品互不影响`() = runBlocking {
        FolderStateStore.save(1, setOf("a"))
        FolderStateStore.save(2, setOf("b"))

        assertEquals(setOf("a"), FolderStateStore.load(1))
        assertEquals(setOf("b"), FolderStateStore.load(2))
    }

    @Test
    fun `坏文件当作没有记录且被删除`() = runBlocking {
        stateFile().writeText("{ 坏掉了", Charsets.UTF_8)

        assertEquals(emptySet<String>(), FolderStateStore.load(1))
        assertTrue("坏文件要删掉，否则每次启动都要再解析一遍", !stateFile().exists())
    }

    @Test
    fun `版本不符当作没有记录`() = runBlocking {
        stateFile().writeText("""{"v":999,"works":{"1":{"t":1,"p":["a"]}}}""", Charsets.UTF_8)

        assertEquals(emptySet<String>(), FolderStateStore.load(1))
    }

    @Test
    fun `超过上限按最旧淘汰`() = runBlocking {
        // 上限是 200 部；写 201 部，最早那部必须被挤掉
        (1..201).forEach { id ->
            FolderStateStore.save(id, setOf("RJ$id/audio"))
        }

        assertEquals(emptySet<String>(), FolderStateStore.load(1))
        assertEquals(setOf("RJ201/audio"), FolderStateStore.load(201))
    }

    @Test
    fun `clearAll 之后什么都不记得`() = runBlocking {
        FolderStateStore.save(1, setOf("a"))

        FolderStateStore.clearAll()

        assertEquals(emptySet<String>(), FolderStateStore.load(1))
        assertTrue(!stateFile().exists())
    }
}
