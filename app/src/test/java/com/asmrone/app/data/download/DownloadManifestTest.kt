package com.asmrone.app.data.download

import com.asmrone.app.data.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载清单的两块纯逻辑：**树的裁剪**与**JSON 往返**。
 *
 * 这两块决定了"详情页只显示已下载内容"对不对 —— 裁剪错了，用户会看到
 * 自己没下载的文件（点了播不了），或者下载过的文件夹整个消失。
 */
class DownloadManifestTest {

    // ─────────────────────────── 构造 ───────────────────────────

    private fun audio(path: String) = Track(
        title = path.substringAfterLast('/'),
        type = "audio",
        hash = "h-$path",
        size = 1000,
        durationSec = 10.0,
        mediaStreamUrl = "https://cdn/$path",
        mediaDownloadUrl = null,
        streamLowQualityUrl = null,
        path = path
    )

    private fun folder(path: String, children: List<Track>) = Track(
        title = path.substringAfterLast('/'),
        type = "folder",
        children = children,
        path = path
    )

    private fun tree() = listOf(
        folder("mp3", listOf(audio("mp3/01.mp3"), audio("mp3/02.mp3"))),
        folder("wav", listOf(audio("wav/01.wav"))),
        audio("readme.txt")
    )

    // ─────────────────────────── collectLeafPaths ───────────────────────────

    @Test
    fun `叶子路径只收文件，不收文件夹`() {
        val paths = collectLeafPaths(tree())
        assertEquals(listOf("mp3/01.mp3", "mp3/02.mp3", "wav/01.wav", "readme.txt"), paths)
    }

    // ─────────────────────────── filterTreeTo ───────────────────────────

    @Test
    fun `只保留一个文件时，它的父文件夹仍然在`() {
        val result = filterTreeTo(tree(), setOf("mp3/02.mp3"))
        assertEquals(1, result.size)
        val mp3 = result.single()
        assertEquals("mp3", mp3.path)
        assertEquals(listOf("mp3/02.mp3"), mp3.children.map { it.path })
    }

    @Test
    fun `没有任何后代被保留的文件夹会被整枝丢掉`() {
        val result = filterTreeTo(tree(), setOf("mp3/01.mp3"))
        assertEquals(listOf("mp3"), result.map { it.path })
    }

    @Test
    fun `根级文件也能被单独保留`() {
        val result = filterTreeTo(tree(), setOf("readme.txt"))
        assertEquals(listOf("readme.txt"), result.map { it.path })
    }

    @Test
    fun `保持原树顺序，不按保留集合的顺序重排`() {
        val result = filterTreeTo(tree(), setOf("readme.txt", "wav/01.wav", "mp3/02.mp3"))
        assertEquals(listOf("mp3", "wav", "readme.txt"), result.map { it.path })
    }

    @Test
    fun `保留集合为空时结果为空`() {
        assertTrue(filterTreeTo(tree(), emptySet()).isEmpty())
    }

    // ─────────────────────────── 清单 JSON ───────────────────────────

    @Test
    fun `清单往返：树、文件、原始 JSON 都还在`() {
        val manifest = DownloadManifest.empty(
            workId = 1681251,
            host = "https://api.asmr-200.com",
            workJson = """{"id":1681251}""",
            tree = tree()
        ).withFile(
            DownloadedFile(path = "mp3/01.mp3", fileName = "abc.mp3", bytes = 1000, durationSec = 10.0)
        ).withFile(
            DownloadedFile(path = "readme.txt", fileName = "def.txt", bytes = 5)
        )

        val back = DownloadManifest.fromJson(JSONObject(manifest.toJson().toString()))
        assertNotNull("往返后不该变成 null", back)
        requireNotNull(back)

        assertEquals(1681251, back.workId)
        assertEquals("https://api.asmr-200.com", back.host)
        assertEquals("""{"id":1681251}""", back.workJson)
        assertEquals(2, back.fileCount)
        assertEquals(1005L, back.totalBytes)
        assertEquals(setOf("mp3/01.mp3", "readme.txt"), back.paths)
        // 树的层级与 path 必须原样回来，否则详情页会显示成扁平列表
        assertEquals(listOf("mp3", "wav", "readme.txt"), back.tree.map { it.path })
        assertEquals(listOf("mp3/01.mp3", "mp3/02.mp3"), back.tree.first().children.map { it.path })
        // durationSec 缺失时要回 null，不能变成 0.0（0.0 意味着"零时长"）
        assertNull(back.files.getValue("readme.txt").durationSec)
    }

    @Test
    fun `withFile 会刷新 savedAt`() {
        val manifest = DownloadManifest.empty(1, "h", null, tree()).copy(savedAt = 1L)
        val updated = manifest.withFile(DownloadedFile("mp3/01.mp3", "a.mp3", 1))
        assertTrue("下载页按最近下载排序，新增文件必须刷新时间", updated.savedAt > 1L)
    }

    @Test
    fun `schema 版本不符时视为无效清单`() {
        val json = JSONObject().put("v", 999).put("workId", 1)
        assertNull(DownloadManifest.fromJson(json))
    }

    @Test
    fun `没有 workId 的清单视为无效`() {
        assertNull(DownloadManifest.fromJson(JSONObject().put("v", DownloadManifest.SCHEMA)))
    }

    @Test
    fun `重名路径不会把清单撑成两条`() {
        val manifest = DownloadManifest.empty(1, "h", null, tree())
            .withFile(DownloadedFile("mp3/01.mp3", "a.mp3", 10))
            .withFile(DownloadedFile("mp3/01.mp3", "a.mp3", 20))
        assertEquals(1, manifest.fileCount)
        assertEquals(20L, manifest.totalBytes)
    }

    @Test
    fun `空清单的 isEmpty 为真`() {
        assertTrue(DownloadManifest.empty(1, "h", null, tree()).isEmpty)
        assertFalse(DownloadManifest.empty(1, "h", null, tree()).withFile(
            DownloadedFile("mp3/01.mp3", "a.mp3", 1)
        ).isEmpty)
    }
}
