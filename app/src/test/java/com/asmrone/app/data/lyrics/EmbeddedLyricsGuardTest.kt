package com.asmrone.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内嵌歌词的**接线**守卫。
 *
 * 解析本身由 [AudioTagReaderTest] 覆盖；这里守的是几件容易在后续改动中被悄悄破坏的事：
 *
 *  - 内嵌只能是**兜底**（排在同名歌词文件之后），不能被提到前面顶替人工整理过的 `.lrc`；
 *  - 读音频头必须**只读头部**，不能退化成把整首音频读进内存；
 *  - 无时间轴的内嵌歌词必须关掉"点行跳转"（那些行时间全是 0，点了会把播放拉回开头）；
 *  - 无时间轴这个标记要能落盘，否则重启后会被当成"时间轴全 0 的正常歌词"。
 */
class EmbeddedLyricsGuardTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/asmrone/app/$relative")
        assertTrue("找不到源文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    // ─────────────────────────── 兜底顺序 ───────────────────────────

    @Test
    fun `内嵌歌词排在同名文件之后`() {
        val src = source("data/lyrics/LyricsLoader.kt")
        val byFile = src.indexOf("val lyric = findLyricTrack(")
        val embedded = src.indexOf("val embedded = loadEmbedded(")
        assertTrue("找不到 findLyricTrack 调用点", byFile >= 0)
        assertTrue("找不到 loadEmbedded 调用点", embedded >= 0)
        assertTrue("内嵌歌词不能排在同名文件之前", byFile < embedded)
    }

    @Test
    fun `内嵌歌词只读音频头部`() {
        val loader = source("data/lyrics/LyricsLoader.kt")
        assertTrue(loader.contains("AsmrRepository.readAudioHead("))
        assertTrue(
            "wav 等没有标签容器的格式应当先判掉，别白读 512KB",
            loader.indexOf("AudioTagReader.canContain(") < loader.indexOf("AsmrRepository.readAudioHead(")
        )

        val repo = source("data/repo/AsmrRepository.kt")
        assertTrue("已下载的文件应当直接读本地", repo.contains("DownloadStore.localFileFor("))
        assertTrue(repo.contains("api.readPrefix("))

        val api = source("data/remote/AsmrOneApi.kt")
        val start = api.indexOf("suspend fun readPrefix(")
        assertTrue("找不到 readPrefix", start >= 0)
        val body = api.substring(start, api.indexOf("\n    /** 封面兜底地址", start))
        assertTrue("必须用 Range 只取前一段", body.contains("\"Range\""))
        assertTrue("不能退化成整文件读取", !body.contains("readBytes()"))
    }

    // ─────────────────────────── 界面与落盘 ───────────────────────────

    @Test
    fun `无时间轴时不响应点行跳转`() {
        val src = source("ui/screen/lyrics/LyricsPreviewScreen.kt")
        assertTrue(src.contains("seekable = !untimed"))
        assertTrue(src.contains("highlightCurrent = !untimed"))
        assertTrue("点击行跳转要用 seekable 控制", src.contains("enabled = seekable"))
    }

    @Test
    fun `无时间轴标记会落盘`() {
        val store = source("data/lyrics/LyricsStore.kt")
        assertTrue(store.contains("put(\"untimed\", true)"))
        assertTrue(store.contains("optBoolean(\"untimed\""))
    }

    // ─────────────────────────── 解析器的硬约束 ───────────────────────────

    @Test
    fun `解析器里不放正则`() {
        // 教训：ASS 那条正则在 Android 的 ICU 引擎上非法，写在 object 静态字段里
        // 变成 ExceptionInInitializerError，一进播放器就闪退。这里干脆一条正则都不用。
        val src = source("data/lyrics/AudioTagReader.kt")
        assertTrue("AudioTagReader 不应依赖正则", !src.contains("Regex("))
    }

    @Test
    fun `解析器覆盖三类标签`() {
        val src = source("data/lyrics/AudioTagReader.kt")
        assertTrue(src.contains("\"USLT\""))
        assertTrue(src.contains("\"SYLT\""))
        assertTrue(src.contains("LYRICS"))
        assertTrue("FLAC 走 fLaC 头", src.contains("fLaC"))
    }

    // ─────────────────────────── 无时间轴歌词的拆行 ───────────────────────────

    @Test
    fun `无时间轴歌词按行拆开且丢掉空行`() {
        val lines = LyricsLoader.plainLyricLines("一\n\n  二  \n\n三\n")
        assertEquals(3, lines.size)
        assertEquals("一", lines[0].original)
        assertEquals("二", lines[1].original)
        assertEquals("三", lines[2].original)
    }

    @Test
    fun `无时间轴歌词的时间统一是 0 且没有译文`() {
        val lines = LyricsLoader.plainLyricLines("一\n二")
        assertTrue(lines.all { it.startMs == 0L })
        assertTrue(lines.all { it.translation.isEmpty() })
    }

    @Test
    fun `CRLF 也按行拆`() {
        val lines = LyricsLoader.plainLyricLines("一\r\n二\r\n三")
        assertEquals(listOf("一", "二", "三"), lines.map { it.original })
    }

    @Test
    fun `行数有上限`() {
        val many = (1..900).joinToString("\n") { "第 $it 行" }
        assertEquals(500, LyricsLoader.plainLyricLines(many).size)
    }

    @Test
    fun `空白内容没有行`() {
        assertTrue(LyricsLoader.plainLyricLines(" \n\n\t\n ").isEmpty())
    }
}
