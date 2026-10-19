package com.asmrone.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 内嵌歌词解析。
 *
 * 这里**不依赖真实音频文件**：ID3v2 / FLAC 的标签结构都很小，直接按规范拼几段字节
 * 就能覆盖"读得到 / 读不到 / 读坏了"三类情况，跑得比下载真文件快得多。
 */
class AudioTagReaderTest {

    // ─────────────────────────── ID3v2 ───────────────────────────

    @Test
    fun `USLT 的 UTF-8 歌词被读成无时间轴`() {
        val head = id3v2(frame("USLT", uslt(3, "第一行\n第二行")))
        val result = AudioTagReader.read(head)
        assertTrue("应当是 Plain，实际 $result", result is EmbeddedLyric.Plain)
        assertEquals("第一行\n第二行", (result as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `USLT 的 Latin-1 歌词也能读`() {
        val payload = ByteArrayOutputStream().apply {
            write(0)
            write("eng".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write("café au lait".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        val result = AudioTagReader.read(id3v2(frame("USLT", payload)))
        assertEquals("café au lait", (result as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `USLT 的 UTF-16 带 BOM 歌词能读`() {
        val text = "こんばんは"
        val payload = ByteArrayOutputStream().apply {
            write(1)
            write("jpn".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(0, 0))
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(text.toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        val result = AudioTagReader.read(id3v2(frame("USLT", payload)))
        assertEquals(text, (result as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `SYLT 的毫秒时间戳被换算成 LRC`() {
        val entries = ByteArrayOutputStream().apply {
            write("はじめに".toByteArray(Charsets.UTF_8))
            write(0)
            write(be32(1_500))
            write("つぎ".toByteArray(Charsets.UTF_8))
            write(0)
            write(be32(62_400))
        }.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            write(3)
            write("jpn".toByteArray(Charsets.ISO_8859_1))
            write(2) // 时间戳格式：毫秒
            write(1) // 内容类型：歌词
            write(0) // 描述为空
            write(entries)
        }.toByteArray()

        val result = AudioTagReader.read(id3v2(frame("SYLT", payload)))
        assertTrue("应当是 Timed，实际 $result", result is EmbeddedLyric.Timed)
        val lrc = (result as EmbeddedLyric.Timed).text
        assertTrue(lrc, lrc.contains("[00:01.50]はじめに"))
        assertTrue(lrc, lrc.contains("[01:02.40]つぎ"))
    }

    @Test
    fun `ID3v2 4 的帧长度是同步安全整数`() {
        // 长度要大于 127 才能真正区分「同步安全整数」和「普通 32 位整数」
        val text = "这是一段很长很长的歌词".repeat(12)
        val payload = uslt(3, text)
        assertTrue("测试数据要超过 127 字节，实际 ${payload.size}", payload.size > 127)
        val head = id3v2(frame("USLT", payload, major = 4), major = 4)
        val result = AudioTagReader.read(head)
        assertEquals(text, (result as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `ID3v2 只有别的帧时没有歌词`() {
        val head = id3v2(frame("TIT2", byteArrayOf(3) + "标题".toByteArray(Charsets.UTF_8)))
        assertNull(AudioTagReader.read(head))
    }

    @Test
    fun `ID3v2 2 直接放弃而不是读出乱码`() {
        val head = id3v2(frame("USLT", uslt(3, "v2.2 无帧名")), major = 2)
        assertNull(AudioTagReader.read(head))
    }

    // ─────────────────────────── FLAC ───────────────────────────

    @Test
    fun `FLAC 的 LYRICS 纯文本被读成无时间轴`() {
        val head = flac(
            flacBlock(type = 0, body = ByteArray(34)), // STREAMINFO，只为验证会跳过
            flacBlock(type = 4, body = vorbis("LYRICS=一行\n二行"), last = true)
        )
        val result = AudioTagReader.read(head)
        assertTrue("应当是 Plain，实际 $result", result is EmbeddedLyric.Plain)
        assertEquals("一行\n二行", (result as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `FLAC 的 UNSYNCEDLYRICS 大小写不敏感`() {
        val head = flac(flacBlock(4, vorbis("unsyncedlyrics=内容"), last = true))
        assertEquals("内容", (AudioTagReader.read(head) as EmbeddedLyric.Plain).text)
    }

    @Test
    fun `FLAC 里本身就是 LRC 的话按有时间轴处理`() {
        val head = flac(flacBlock(4, vorbis("LYRICS=[00:01.00]一\n[00:05.00]二"), last = true))
        assertTrue(AudioTagReader.read(head) is EmbeddedLyric.Timed)
    }

    @Test
    fun `FLAC 没有歌词字段时返回 null`() {
        val head = flac(flacBlock(4, vorbis("ARTIST=someone", "TITLE=x"), last = true))
        assertNull(AudioTagReader.read(head))
    }

    @Test
    fun `ALBUM 里含 LYRICS 字样也不算`() {
        val head = flac(flacBlock(4, vorbis("LYRICS_NOTE=不是歌词字段"), last = true))
        assertNull(AudioTagReader.read(head))
    }

    // ─────────────────────────── 是否需要读头部 ───────────────────────────

    @Test
    fun `只有 mp3 与 flac 值得读头部`() {
        assertTrue(AudioTagReader.canContain("01.mp3"))
        assertTrue(AudioTagReader.canContain("整部.FLAC"))
        assertTrue(AudioTagReader.canContain("a/b/c.mp3"))
        assertTrue(!AudioTagReader.canContain("01.wav"))
        assertTrue(!AudioTagReader.canContain("01.m4a"))
        assertTrue(!AudioTagReader.canContain("没有扩展名"))
    }

    // ─────────────────────────── 容错 ───────────────────────────

    @Test
    fun `头部被截断时不抛异常`() {
        val full = id3v2(frame("USLT", uslt(3, "这一行足够长 ".repeat(40))))
        // 只给前 40 字节：帧数据不完整
        assertNull(AudioTagReader.read(full.copyOf(40)))
    }

    @Test
    fun `乱码字节不抛异常`() {
        val junk = ByteArray(512) { (it * 37 % 251).toByte() }
        assertNull(AudioTagReader.read(junk))
    }

    @Test
    fun `太短的字节直接返回 null`() {
        assertNull(AudioTagReader.read(ByteArray(0)))
        assertNull(AudioTagReader.read("ID3".toByteArray()))
    }

    @Test
    fun `wav 这类没有标签容器的文件返回 null`() {
        val wav = "RIFF".toByteArray() + ByteArray(64) + "WAVEfmt ".toByteArray()
        assertNull(AudioTagReader.read(wav))
    }

    // ─────────────────────────── 拼字节的小工具 ───────────────────────────

    private fun uslt(encoding: Int, text: String): ByteArray = ByteArrayOutputStream().apply {
        write(encoding)
        write("eng".toByteArray(Charsets.ISO_8859_1))
        write(0)
        write(text.toByteArray(Charsets.UTF_8))
    }.toByteArray()

    private fun id3v2(vararg frames: ByteArray, major: Int = 3): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(major)
            write(0)
            write(0)
            write(syncSafe(body.size()))
            write(body.toByteArray())
        }.toByteArray()
    }

    private fun frame(id: String, payload: ByteArray, major: Int = 3): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(if (major == 4) syncSafe(payload.size) else be32(payload.size))
            write(byteArrayOf(0, 0))
            write(payload)
        }.toByteArray()

    private fun flac(vararg blocks: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            blocks.forEach { write(it) }
        }.toByteArray()

    private fun flacBlock(type: Int, body: ByteArray, last: Boolean = false): ByteArray =
        ByteArrayOutputStream().apply {
            write((if (last) 0x80 else 0) or type)
            write((body.size shr 16) and 0xFF)
            write((body.size shr 8) and 0xFF)
            write(body.size and 0xFF)
            write(body)
        }.toByteArray()

    private fun vorbis(vararg entries: String): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(le32(vendor.size))
            write(vendor)
            write(le32(entries.size))
            entries.forEach { entry ->
                val bytes = entry.toByteArray(Charsets.UTF_8)
                write(le32(bytes.size))
                write(bytes)
            }
        }.toByteArray()
    }

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte()
    )
}
