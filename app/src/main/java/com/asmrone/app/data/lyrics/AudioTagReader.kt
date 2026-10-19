package com.asmrone.app.data.lyrics

import java.util.Locale

/**
 * 内嵌歌词的两种形态。
 *
 * 区别只在**有没有时间轴**，这决定了界面怎么显示：
 *  - [Timed] 换算成 LRC 文本，交给 [LyricsLoader.parseLyrics] 走正常的跟随播放；
 *  - [Plain] 只能整段静态显示（不跟随、不可点行跳转）。
 */
internal sealed interface EmbeddedLyric {
    /** 带时间轴；[text] 一律已换算成 LRC 格式 */
    data class Timed(val text: String) : EmbeddedLyric

    /** 没有时间轴（ID3v2 `USLT`、FLAC 的 `LYRICS=整段话` 常见） */
    data class Plain(val text: String) : EmbeddedLyric
}

/**
 * 从音频容器的**头部字节**里读内嵌歌词。
 *
 * ## 为什么只读头部
 *
 * 一部作品里单首 wav 可能有几十上百 MB，而歌词标签只在文件开头：
 *  - MP3：ID3v2 头 + 帧，都在文件最前面（ID3v1 在末尾 128 字节，但没有歌词字段，
 *    所以不必读末尾）；
 *  - FLAC：`fLaC` + metadata block 区也在最前面。
 *
 * 因此只需取前 [HEAD_BYTES] 就够，**不必下载整首音频**。远程取这一段用
 * `Range: bytes=0-N`（服务端对音频是支持 Range 的，实测 206）。
 *
 * ## 支持范围
 *
 * | 容器 | 字段 |
 * |---|---|
 * | MP3 (ID3v2.3 / 2.4) | `USLT`（无时间轴）、`SYLT`（有时间轴，转 LRC） |
 * | FLAC (VORBIS_COMMENT) | `LYRICS` / `UNSYNCEDLYRICS` / `SYNCEDLYRICS` |
 *
 * M4A 的 `©lyr`、OGG/Opus 的注释头**暂不支持**：asmr 音声里几乎都是 mp3/wav/flac，
 * 先覆盖绝大多数；`wav` 本身没有标签容器，天然没有内嵌歌词。
 *
 * ## 容错
 *
 * 标签解析是"尽力而为"：任何字段异常都只代表这一首没有内嵌歌词，绝不能让
 * 歌词页报错。所以整条链路都包在 `runCatching` 里，失败返回 null。
 */
internal object AudioTagReader {

    /** 头部读取长度。ID3v2 的歌词帧基本都在最前面几十 KB 内，512KB 是宽松上限 */
    const val HEAD_BYTES = 512 * 1024

    /** FLAC / Vorbis comment 里可能装歌词的键（大写比较） */
    private val lyricKeys = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")

    /**
     * 这类文件值得去读头部吗。
     *
     * `wav` **没有标签容器**（asmr 音声里 wav 占比很高），`m4a`/`ogg` 本类还不支持 ——
     * 对它们先判掉，能省掉整整 512KB 的无用请求；只在真可能带歌词的格式上去读。
     */
    fun canContain(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext == "mp3" || ext == "flac"
    }

    /**
     * 判断这堆头部字节里有没有内嵌歌词。
     *
     * @param head 从文件开头取到的字节（可以比整个文件短）
     */
    fun read(head: ByteArray): EmbeddedLyric? {
        if (head.size < 12) return null
        return runCatching {
            when {
                head.ascii(0, 3) == "ID3" -> readId3v2(head)
                head.ascii(0, 4) == "fLaC" -> readFlac(head)
                else -> null
            }
        }.getOrNull()
    }

    // ─────────────────────────── ID3v2 ───────────────────────────

    private fun readId3v2(b: ByteArray): EmbeddedLyric? {
        val major = b.u8(3)
        // v2.2 的帧头是 3 字节、字段名 3 字符，现实里基本见不到，直接放弃
        if (major != 3 && major != 4) return null
        val flags = b.u8(5)
        val tagSize = b.syncSafe(6)
        var pos = 10
        // 扩展头：v2.4 是同步安全整数，v2.3 是普通整数且**包含自身 4 字节**
        if (flags and 0x40 != 0) {
            if (pos + 4 > b.size) return null
            val ext = if (major == 4) b.syncSafe(pos) else b.be32(pos) + 4
            pos += ext.coerceAtLeast(4)
        }
        val end = minOf(b.size, 10 + tagSize)

        while (pos + 10 <= end) {
            // 填充区：帧头已到末尾，后面全是 0
            if (b[pos] == 0.toByte()) break
            val id = b.ascii(pos, 4)
            val size = if (major == 4) b.syncSafe(pos + 4) else b.be32(pos + 4)
            val dataStart = pos + 10
            val dataEnd = dataStart + size
            if (size <= 0 || dataEnd > end) break

            when (id) {
                "USLT" -> readUslt(b, dataStart, dataEnd)?.let { text ->
                    if (text.isNotBlank()) return EmbeddedLyric.Plain(text)
                }

                "SYLT" -> readSylt(b, dataStart, dataEnd)?.let { lrc ->
                    if (lrc.isNotBlank()) return EmbeddedLyric.Timed(lrc)
                }
            }
            pos = dataEnd
        }
        return null
    }

    /**
     * `USLT`：encoding(1) + language(3) + 内容描述（以 NUL 结尾）+ 歌词正文（到帧尾）。
     */
    private fun readUslt(b: ByteArray, start: Int, end: Int): String? {
        if (start + 4 >= end) return null
        val enc = b.u8(start)
        val descStart = start + 4
        val descEnd = b.findTerminator(descStart, end, enc) ?: return null
        val textStart = descEnd + b.terminatorLength(enc)
        if (textStart >= end) return null
        return b.decode(textStart, end, enc).trimEnd('\u0000').trim()
    }

    /**
     * `SYLT`：encoding(1) + language(3) + 时间戳格式(1) + 内容类型(1) + 内容描述 + 若干
     * 「正文 + 时间戳(4 字节)」条目。这里换算成 LRC，好让解析与显示走同一条路。
     *
     * 时间戳格式 1 表示"MPEG 帧数"，没有帧率换算不出毫秒，遇到就放弃（现实里极少）。
     */
    private fun readSylt(b: ByteArray, start: Int, end: Int): String? {
        if (start + 6 >= end) return null
        val enc = b.u8(start)
        val timeFormat = b.u8(start + 4)
        if (timeFormat != 2) return null
        var pos = start + 6
        val descEnd = b.findTerminator(pos, end, enc) ?: return null
        pos = descEnd + b.terminatorLength(enc)

        val out = StringBuilder()
        while (pos < end) {
            val textEnd = b.findTerminator(pos, end, enc) ?: break
            val text = b.decode(pos, textEnd, enc)
            val stampStart = textEnd + b.terminatorLength(enc)
            if (stampStart + 4 > end) break
            val ms = b.be32(stampStart).toLong()
            pos = stampStart + 4
            if (text.isBlank()) continue
            out.append('[').append(formatLrcTime(ms)).append(']').append(text.trim()).append('\n')
        }
        return out.takeIf { it.isNotEmpty() }?.toString()
    }

    // ─────────────────────────── FLAC ───────────────────────────

    private fun readFlac(b: ByteArray): EmbeddedLyric? {
        var pos = 4
        while (pos + 4 <= b.size) {
            val header = b.u8(pos)
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val len = (b.u8(pos + 1) shl 16) or (b.u8(pos + 2) shl 8) or b.u8(pos + 3)
            val start = pos + 4
            val end = start + len
            if (end > b.size) break
            if (type == 4) readVorbisComment(b, start, end)?.let { return it }
            if (last) break
            pos = end
        }
        return null
    }

    /**
     * VORBIS_COMMENT（小端长度）：vendor 串 + 条目数 + 若干 `KEY=value`。
     */
    private fun readVorbisComment(b: ByteArray, start: Int, end: Int): EmbeddedLyric? {
        var pos = start
        if (pos + 4 > end) return null
        pos += 4 + b.le32(pos)
        if (pos + 4 > end) return null
        val count = b.le32(pos)
        pos += 4
        repeat(count.coerceIn(0, 4096)) {
            if (pos + 4 > end) return null
            val len = b.le32(pos)
            pos += 4
            if (len <= 0 || pos + len > end) return null
            val entry = String(b, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0 && entry.substring(0, eq).uppercase(Locale.ROOT) in lyricKeys) {
                val text = entry.substring(eq + 1)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
                if (text.isNotEmpty()) return classify(text)
            }
        }
        return null
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * FLAC 的歌词是一整段文本，可能带 LRC 时间轴也可能不带 —— 靠"有没有 `[mm:ss`"判断。
     */
    private fun classify(text: String): EmbeddedLyric {
        var i = 0
        while (i + 8 < text.length) {
            if (text[i] == '[' && text[i + 1].isDigit() && text[i + 2].isDigit() &&
                (text[i + 3] == ':' || text[i + 3] == '.')
            ) {
                return EmbeddedLyric.Timed(text)
            }
            i++
        }
        return EmbeddedLyric.Plain(text)
    }

    private fun formatLrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val hundredths = (safe % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, minOf(length, size - offset), Charsets.ISO_8859_1)

    private fun ByteArray.u8(offset: Int): Int =
        if (offset in indices) this[offset].toInt() and 0xFF else 0

    private fun ByteArray.be32(offset: Int): Int {
        if (offset + 4 > size) return 0
        return (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)
    }

    private fun ByteArray.le32(offset: Int): Int {
        if (offset + 4 > size) return 0
        return (u8(offset + 3) shl 24) or (u8(offset + 2) shl 16) or
            (u8(offset + 1) shl 8) or u8(offset)
    }

    /** ID3v2 的长度字段是 7 位一字节（最高位恒为 0） */
    private fun ByteArray.syncSafe(offset: Int): Int {
        if (offset + 4 > size) return 0
        return (u8(offset) shl 21) or (u8(offset + 1) shl 14) or
            (u8(offset + 2) shl 7) or u8(offset + 3)
    }

    /** 编码 1/2 是 UTF-16，终止符是两个 0 字节且按偶数边界对齐 */
    private fun isWide(encoding: Int) = encoding == 1 || encoding == 2

    private fun ByteArray.terminatorLength(encoding: Int) = if (isWide(encoding)) 2 else 1

    private fun ByteArray.findTerminator(start: Int, end: Int, encoding: Int): Int? {
        var i = start
        if (isWide(encoding)) {
            // 起点对齐到相对 start 的偶数位置，避免把 UTF-16 字符内部的 0 当成终止符
            while (i + 1 < end) {
                if (this[i] == 0.toByte() && this[i + 1] == 0.toByte()) return i
                i += 2
            }
            return null
        }
        while (i < end) {
            if (this[i] == 0.toByte()) return i
            i++
        }
        return null
    }

    private fun ByteArray.decode(start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        // UTF-16 带 BOM 时用符号名解码，Android 上 Charsets.UTF_16 已经会识别 BOM
        return String(this, start, end - start, charset)
    }
}
