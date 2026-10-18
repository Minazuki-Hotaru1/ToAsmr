package com.asmrone.app.data.lyrics

import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.text.TextDecoder
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 歌词加载结果 */
sealed interface LyricsLoad {
    data class Ready(val lines: List<LyricLine>, val sourceTitle: String) : LyricsLoad
    data object None : LyricsLoad
    data class Failed(val message: String) : LyricsLoad
}

/**
 * 曲目 ↔ 歌词文件匹配与解析。
 *
 * 匹配规则（用户的文件组织习惯）：
 *  - 优先在**音频所在的同一个文件夹**里找；
 *  - 文件名去掉扩展名后与音频同名；
 *  - 兼容 `01_foo.mp3 + 01_foo.lrc` 与 `01_foo.mp3 + 01_foo.mp3.lrc` 两种写法；
 *  - 支持 `.lrc` / `.vtt` / `.srt` / `.ass` / `.ssa`，按这个顺序取第一个匹配。
 *
 * 不做“全作品同名文件兜底”：不同文件夹里的同名文件很可能是不同章节，
 * 跨目录匹配比“找不到”更容易出错。
 */
object LyricsLoader {

    private val lyricExts = listOf("lrc", "vtt", "srt", "ass", "ssa")

    /** 歌词文件名里可能残留的音频扩展名（`track.mp3.vtt`） */
    private val audioExts = setOf(
        "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac", "wma"
    )

    private val lrcTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val lrcWordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val vttTag = Regex("""<[^>]+>""")

    /**
     * ⚠️ 这里**没有** ASS 的 `\{[^}]*}` 正则。
     *
     * 那条正则在桌面 JVM 上完全正常，但在 Android 的 ICU 正则引擎上会抛
     * `PatternSyntaxException`（末尾未转义的 `}` 非法）——而它写在 object 的
     * 静态初始化里，于是变成 `ExceptionInInitializerError`，一进播放器就闪退，
     * 且类初始化失败后无法再次初始化（再点继续崩）。
     *
     * 教训：**别让正则出现在 `object` 的静态字段里**，也别依赖桌面 JVM 的宽容度。
     * ASS 的 `{...}` 覆盖标签改成手写扫描（见 [stripAssTags]），既不吃正则方言的亏，
     * 也更快。
     */

    suspend fun load(workId: Int, trackPath: String, trackTitle: String): LyricsLoad {
        if (workId <= 0 || trackPath.isBlank()) return LyricsLoad.None

        // 详情页播放时文件树已经缓存，直接用；冷启动恢复时再拉。
        val tree = AsmrRepository.peekTracks(workId)?.takeIf { it.isNotEmpty() }
            ?: runCatching { AsmrRepository.getTracks(workId) }.getOrElse {
                if (it is CancellationException) throw it
                return LyricsLoad.Failed(it.message ?: "无法获取作品文件列表")
            }
        val lyric = findLyricTrack(tree, trackPath, trackTitle) ?: return LyricsLoad.None
        val url = lyric.mediaStreamUrl?.takeIf { it.isNotBlank() } ?: return LyricsLoad.None
        val lyricName = lyric.path.substringAfterLast('/').ifBlank { lyric.title }

        return runCatching {
            val decoded = TextDecoder.decode(
                bytes = AsmrRepository.fetchBytes(url),
                fileName = lyricName
            )
            val lines = parseLyrics(decoded.text, lyricName)
            if (lines.isEmpty()) {
                LyricsLoad.Failed("歌词文件里没有可识别的时间轴（支持 LRC / VTT / SRT / ASS）")
            } else {
                LyricsLoad.Ready(lines = lines, sourceTitle = lyricName)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            LyricsLoad.Failed(it.message ?: "歌词下载失败")
        }
    }

    // ─────────────────────────── 匹配 ───────────────────────────

    private fun findLyricTrack(
        tree: List<Track>,
        audioPath: String,
        audioTitle: String
    ): Track? {
        val parent = audioPath.substringBeforeLast('/', "")
        val audioStem = normalizeStem(
            audioPath.substringAfterLast('/').ifBlank { audioTitle }
        )
        if (audioStem.isEmpty()) return null

        val leaves = mutableListOf<Track>()
        fun walk(nodes: List<Track>) {
            nodes.forEach { node ->
                if (node.children.isEmpty()) {
                    leaves += node
                } else {
                    walk(node.children)
                }
            }
        }
        walk(tree)

        return leaves
            .asSequence()
            .filter { TrackKind.of(it) == TrackKind.SUBTITLE }
            .filter { it.path.substringBeforeLast('/', "") == parent }
            .filter {
                normalizeStem(it.path.substringAfterLast('/').ifBlank { it.title }) == audioStem
            }
            .minByOrNull {
                lyricPriority(it.path.substringAfterLast('/').ifBlank { it.title })
            }
    }

    /**
     * 去掉扩展名并归一化。
     *
     * 例：
     *  - `01_foo.mp3`        → `01_foo`
     *  - `01_foo.lrc`        → `01_foo`
     *  - `01_foo.mp3.lrc`    → `01_foo`
     */
    private fun normalizeStem(name: String): String {
        var stem = name.substringBeforeLast('.', name).trim()
        val possibleAudioExt = stem.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (possibleAudioExt in audioExts) {
            stem = stem.substringBeforeLast('.', stem)
        }
        return stem.trim().lowercase(Locale.ROOT)
    }

    private fun lyricPriority(title: String): Int {
        val ext = title.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val index = lyricExts.indexOf(ext)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    // ─────────────────────────── 解析入口 ───────────────────────────

    /**
     * 仅供单测：把一段歌词文本按文件名后缀解析。
     *
     * 不联网、不碰文件树，所以覆盖各格式的解析器**可以**在纯 JVM 上验证。
     * ⚠️ 但它验不出正则方言差异（ICU vs JVM）——那条只能靠不去写奇怪的正则，
     * 见文件顶部关于 ASS 正则的说明。
     */
    internal fun parseForTest(text: String, fileName: String): List<LyricLine> =
        parseLyrics(text, fileName)

    private fun parseLyrics(text: String, fileName: String): List<LyricLine> {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val raw = when (ext) {
            "vtt", "srt" -> parseCueFormat(text)
            "ass", "ssa" -> parseAss(text)
            else -> parseLrc(text)
        }
        return raw
            .filter { it.original.isNotBlank() }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs to it.original }
    }

    // ─────────────────────────── LRC ───────────────────────────

    private fun parseLrc(text: String): List<LyricLine> {
        data class Entry(val timeMs: Long, val text: String)

        val entries = mutableListOf<Entry>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val tags = lrcTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            val content = line.substring(tags.last().range.last + 1)
                .replace(lrcWordTag, "")
                .trim()
            if (content.isEmpty()) return@forEach

            for (tag in tags) {
                val time = lrcTimeOf(tag) ?: continue
                entries += Entry(time, content)
            }
        }

        // 同一时间戳下的第二行按“翻译”处理，这是双语 LRC 的常见写法。
        return entries
            .groupBy { it.timeMs }
            .toSortedMap()
            .map { (time, group) ->
                val texts = group.map { it.text }.distinct()
                LyricLine(
                    original = texts.first(),
                    translation = texts.drop(1).joinToString(" / "),
                    startMs = time
                )
            }
    }

    private fun lrcTimeOf(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val millis = match.groupValues[3]
            .takeIf { it.isNotEmpty() }
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L
        return ((minutes * 60L + seconds) * 1000L + millis)
    }

    // ─────────────────────────── VTT / SRT ───────────────────────────

    private fun parseCueFormat(text: String): List<LyricLine> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<LyricLine>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i].trim()
            if (!header.contains("-->")) {
                i++
                continue
            }

            val startToken = header.substringBefore("-->").trim().substringBefore(' ')
            val startMs = parseClock(startToken)
            if (startMs == null) {
                i++
                continue
            }

            i++
            val content = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                val clean = cleanCueLine(lines[i])
                if (clean.isNotEmpty()) content += clean
                i++
            }
            if (content.isNotEmpty()) {
                out += lineOf(content, startMs)
            }
        }
        return out
    }

    private fun cleanCueLine(raw: String): String =
        vttTag.replace(raw, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()

    // ─────────────────────────── ASS / SSA ───────────────────────────

    private fun parseAss(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimStart()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) return@forEach

            // limit = 10：最后一段是正文，正文里可以继续有逗号。
            val fields = line.substringAfter(':').split(',', limit = 10)
            if (fields.size < 10) return@forEach

            val startMs = fields.drop(1).firstNotNullOfOrNull { parseClock(it.trim()) }
                ?: return@forEach
            val content = stripAssTags(fields.last())
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (content.isNotEmpty()) out += lineOf(content, startMs)
        }
        return out
    }

    /**
     * 去掉 ASS/SSA 的覆盖标签 `{...}`。
     *
     * 用扫描而不是正则，理由见文件顶部没有 ASS 正则的那段说明。
     * 花括号按层级配对，未闭合的 `{` 之后的内容全部丢弃（与原正则行为一致：
     * 原正则匹配不到就没有可删的东西，这里保守地当作文本结束）。
     */
    private fun stripAssTags(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (c in text) {
            when (c) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.toString()
    }

    // ─────────────────────────── 通用 ───────────────────────────

    private fun lineOf(content: List<String>, startMs: Long): LyricLine {
        val texts = content.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return LyricLine(
            original = texts.first(),
            translation = texts.drop(1).joinToString(" / "),
            startMs = startMs
        )
    }

    /**
     * 解析 `HH:MM:SS.mmm`、`MM:SS.mmm`、ASS 的 `H:MM:SS.cc` 以及逗号毫秒。
     */
    private fun parseClock(raw: String): Long? {
        val value = raw.trim().replace(',', '.')
        val parts = value.split(':')
        if (parts.size !in 2..3) return null
        val secondsPart = parts.last()
        val secondPieces = secondsPart.split('.')
        val seconds = secondPieces[0].toLongOrNull() ?: return null
        val millis = secondPieces.getOrNull(1)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L

        val minutes: Long
        val hours: Long
        if (parts.size == 3) {
            hours = parts[0].toLongOrNull() ?: return null
            minutes = parts[1].toLongOrNull() ?: return null
        } else {
            hours = 0L
            minutes = parts[0].toLongOrNull() ?: return null
        }
        if (seconds !in 0..59 || minutes !in 0..59) return null
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
    }
}
