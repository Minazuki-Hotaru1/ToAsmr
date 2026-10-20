package com.asmrone.app.data.lyrics

import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.text.TextDecoder
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 歌词加载结果 */
sealed interface LyricsLoad {
    /**
     * @param untimed 没有时间轴（内嵌的 `USLT`、FLAC 的一整段 `LYRICS=` 常见）——
     *   界面应当只把文字排出来，不跟随播放、不响应点行跳转
     */
    data class Ready(
        val lines: List<LyricLine>,
        val sourceTitle: String,
        val untimed: Boolean = false
    ) : LyricsLoad

    data object None : LyricsLoad
    data class Failed(val message: String) : LyricsLoad
}

/**
 * 曲目 ↔ 歌词文件匹配与解析。
 *
 * ## 匹配规则
 *
 * 1. 文件名去掉扩展名后与音频**同名**（兼容 `01_foo.mp3 + 01_foo.lrc` 与
 *    `01_foo.mp3 + 01_foo.mp3.lrc` 两种写法）；
 * 2. 同名候选**不止一个**时，按下面的优先级挑：
 *    - **音频所在的同一个文件夹**（最贴近"同名同目录"这个最常见习惯）；
 *    - 目录名像歌词目录的（`lyric` / `歌詞` / `歌词` / `字幕` / `subtitle` / `lrc`…）；
 *    - 扩展名优先级 `.lrc` > `.vtt` > `.srt` > `.ass` > `.ssa`。
 *
 * ## 为什么不再限制"必须在同一个文件夹"
 *
 * 早先只认同目录，理由是"不同文件夹里的同名文件可能是不同章节"。
 * 但实测有相当一部分作品把整套歌词单独放在 `lyrics/`、`歌詞/`、`字幕/`
 * 这样的目录里，音频在 `mp3/`、`wav/` 下 —— 那条限制会让这些作品**一首歌词都找不到**。
 *
 * 现在改成"同目录优先，但不排斥跨目录"：同目录命中仍然排在第一位，
 * 只有在同目录找不到时才会去别处找同名文件，所以常见情况的行为没有变，
 * 只是把"找不到"变成了"能找到"。
 *
 * ## 内存缓存
 *
 * 解析结果按 `(workId, trackPath)` 缓存在内存里（[CACHE_MAX] 条 LRU），
 * 并由 [prefetch] 在**开始播放时**就预热。这样播放器的歌词页打开时
 * 通常已经是"读缓存"，不再在展开动画里做网络与解析。
 */
object LyricsLoader {

    private val lyricExts = listOf("lrc", "vtt", "srt", "ass", "ssa")

    /** 歌词文件名里可能残留的音频扩展名（`track.mp3.vtt`） */
    private val audioExts = setOf(
        "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac", "wma"
    )

    /** 目录名里出现这些词就认为"这是个歌词/字幕目录" */
    private val lyricDirHints = listOf(
        "lyric", "lyrics", "歌詞", "歌词", "字幕", "subtitle", "subtitles", "lrc", "vtt", "srt"
    )

    /** 内嵌歌词的来源标题（界面上显示这段文字来自哪里） */
    private const val EMBEDDED_TITLE = "内嵌歌词"
    private const val EMBEDDED_UNTIMED_TITLE = "内嵌歌词（无时间轴）"

    /** 无时间轴的内嵌歌词最多收多少行，防止个别文件塞进整本书 */
    private const val MAX_PLAIN_LINES = 500

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

    // ─────────────────────────── 内存缓存 ───────────────────────────

    /**
     * 内存里最多留多少首的解析结果。
     *
     * 取 64：连续收听时，听完一部作品（10–40 首）的解析结果都能同时留在内存，
     * 于是切歌、收起再展开、来回滑页都是零等待。
     * 单首约几十 KB，64 首最多几 MB，换来的收益远大于这点内存。
     */
    private const val CACHE_MAX = 64

    private val cache = object : LinkedHashMap<String, LyricsLoad>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsLoad>?): Boolean =
            size > CACHE_MAX
    }

    private fun cacheKey(workId: Int, trackPath: String) = "$workId|$trackPath"

    /** 同步读缓存：给界面的首帧用（命中就不用等任何异步） */
    fun cached(workId: Int, trackPath: String): LyricsLoad? =
        synchronized(cache) { cache[cacheKey(workId, trackPath)] }

    private fun remember(workId: Int, trackPath: String, result: LyricsLoad) {
        // 只缓存"有结论"的结果：Failed 多半是网络问题，下次要重试
        if (result is LyricsLoad.Failed) return
        synchronized(cache) { cache[cacheKey(workId, trackPath)] = result }
    }

    /** 清掉某部作品的缓存（重新拉歌词时用） */
    fun forget(workId: Int) {
        synchronized(cache) {
            cache.keys.filter { it.startsWith("$workId|") }.forEach { cache.remove(it) }
        }
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 预热：**开始播放这一首时**就把歌词准备好，并落盘。
     *
     * 调用方不应等待它 —— 它是纯后台动作，失败也不影响播放。
     * 命中内存/磁盘缓存时零请求。
     *
     * ## 只缓存"当前这一首"
     *
     * 这里刻意**不做**整部作品预取。缓存时机严格等于"这首歌被播放"：
     * 听到哪首缓存哪首，没听到过的一律不替用户提前下载。
     * 成本上也不亏 —— 每首只在第一次播放时取一次，之后都是本地读取；
     * 而歌词本身很小（几十行），按需缓存比预取更符合"我听了才存"的直觉。
     */
    suspend fun prefetch(
        workId: Int,
        sourceId: String?,
        trackPath: String,
        trackTitle: String
    ) {
        if (workId <= 0 || trackPath.isBlank()) return
        if (cached(workId, trackPath) != null) return
        load(workId, sourceId, trackPath, trackTitle)
    }

    /**
     * 取一首曲目的歌词。
     *
     * 来源顺序：**内存缓存 → 本地磁盘 → 文件树 + 下载**。
     * 中间那一层（[LyricsStore]）只是省掉重复下载 —— 加载时机与"打开就有"的
     * 体验完全不变：只要播放器一显示，没落过盘的歌词就会立刻开始取。
     *
     * @param sourceId RJ 号；有它才能命中按 RJ 落盘的文件树/歌词缓存
     */
    suspend fun load(
        workId: Int,
        sourceId: String?,
        trackPath: String,
        trackTitle: String
    ): LyricsLoad {
        if (workId <= 0 || trackPath.isBlank()) return LyricsLoad.None
        cached(workId, trackPath)?.let { return it }

        // ② 本地磁盘：听过一次的歌词重启后直接命中，零请求
        LyricsStore.load(sourceId, workId, trackPath)?.let { ready ->
            remember(workId, trackPath, ready)
            return ready
        }

        // ③ 没落过盘 → 取文件树并下载（与原先一致）
        val tree = AsmrRepository.peekTracks(workId)?.takeIf { it.isNotEmpty() }
            ?: runCatching { AsmrRepository.getTracks(workId, sourceId) }.getOrElse {
                if (it is CancellationException) throw it
                return LyricsLoad.Failed(it.message ?: "无法获取作品文件列表")
            }
        val lyric = findLyricTrack(tree, trackPath, trackTitle)
        if (lyric == null) {
            // ④ 兜底：作品里没有独立歌词文件 → 看音频容器自己有没有内嵌歌词
            val embedded = loadEmbedded(tree, workId, trackPath)
            if (embedded != null) {
                remember(workId, trackPath, embedded)
                if (embedded is LyricsLoad.Ready) {
                    LyricsStore.save(sourceId, workId, trackPath, embedded)
                }
                return embedded
            }
            // "这部作品里没有与它同名的歌词"是个稳定结论，缓存下来避免每次重算
            remember(workId, trackPath, LyricsLoad.None)
            return LyricsLoad.None
        }
        val lyricName = lyric.path.substringAfterLast('/').ifBlank { lyric.title }

        // ③' 歌词文件本身也下载过 → 直接读本地，断网可用（与音频同理）
        val localLyric = runCatching {
            com.asmrone.app.data.download.DownloadSources.localFileFor(workId, lyric.path)
        }.getOrNull()
        val url = lyric.mediaStreamUrl?.takeIf { it.isNotBlank() }
        if (localLyric == null && url == null) {
            remember(workId, trackPath, LyricsLoad.None)
            return LyricsLoad.None
        }

        val result = runCatching {
            val bytes = localLyric?.readBytes() ?: AsmrRepository.fetchBytes(url.orEmpty())
            val decoded = TextDecoder.decode(bytes = bytes, fileName = lyricName)
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
        remember(workId, trackPath, result)
        // 解析成功才落盘：失败是暂时性的（网络/超时），下次要能重试
        if (result is LyricsLoad.Ready) {
            LyricsStore.save(sourceId, workId, trackPath, result)
        }
        return result
    }

    // ─────────────────────────── 匹配 ───────────────────────────

    /**
     * 在同名候选里挑最合适的那一个。
     *
     * `internal` 是为了能在纯 JVM 上测匹配规则（构造几棵树即可，不联网）。
     */
    internal fun findLyricTrack(
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
            // 同名是硬条件：跨目录可以，但"名字对不上"绝不能拿
            .filter { stemOf(it) == audioStem }
            // 同目录优先，其次看目录名像不像歌词目录，最后比扩展名
            .minByOrNull { matchScore(it, parent) }
    }

    /** 叶子节点的文件名（`path` 的最后一段，退回落 `title`） */
    private fun stemOf(node: Track): String =
        normalizeStem(node.path.substringAfterLast('/').ifBlank { node.title })

    // ─────────────────────────── 内嵌歌词 ───────────────────────────

    /**
     * 从音频容器里读内嵌歌词（MP3 的 `USLT`/`SYLT`、FLAC 的 `LYRICS`）。
     *
     * 只在「作品里找不到同名歌词文件」时才走到这里 —— 排在同名文件之后是刻意的：
     * 独立 `.lrc` 通常是人整理过的（带翻译、时间轴准），内嵌标签经常只是整段纯文本，
     * 让它顶替会把本来好用的歌词变差。它的价值只在于填补"一首歌词都拿不到"。
     *
     * 只读文件头部（[AsmrRepository.readAudioHead]，远程走 `Range`），不下载整首。
     */
    private suspend fun loadEmbedded(
        tree: List<Track>,
        workId: Int,
        trackPath: String
    ): LyricsLoad? {
        val audio = findAudioNode(tree, trackPath) ?: return null
        // wav 没有标签容器、m4a/ogg 还不支持：先判掉，别白读 512KB
        val name = audio.path.substringAfterLast('/').ifBlank { audio.title }
        if (!AudioTagReader.canContain(name)) return null
        val head = AsmrRepository.readAudioHead(audio, workId) ?: return null
        return when (val embedded = AudioTagReader.read(head)) {
            null -> null

            is EmbeddedLyric.Timed -> {
                val lines = parseLyrics(embedded.text, "内嵌歌词.lrc")
                if (lines.isEmpty()) null else LyricsLoad.Ready(lines, EMBEDDED_TITLE)
            }

            is EmbeddedLyric.Plain -> {
                val lines = plainLyricLines(embedded.text)
                if (lines.isEmpty()) {
                    null
                } else {
                    LyricsLoad.Ready(lines, EMBEDDED_UNTIMED_TITLE, untimed = true)
                }
            }
        }
    }

    /** 没有时间轴的内嵌歌词：按行拆开，全部标在 0 时刻（界面不跟随播放，所以时间无意义） */
    internal fun plainLyricLines(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (out.size >= MAX_PLAIN_LINES) return@forEach
            out += LyricLine(original = line, translation = "", startMs = 0L)
        }
        return out
    }

    /** 在树里找指定路径的叶子节点（内嵌歌词要读它对应的音频文件） */
    private fun findAudioNode(nodes: List<Track>, path: String): Track? {
        nodes.forEach { node ->
            if (node.children.isEmpty()) {
                if (node.path == path) return node
            } else {
                findAudioNode(node.children, path)?.let { return it }
            }
        }
        return null
    }

    /** 节点的文件名（带扩展名） */
    private fun fileNameOf(node: Track): String =
        node.path.substringAfterLast('/').ifBlank { node.title }

    /**
     * 同名候选的优先级分（越小越优先）。
     *
     * 分段权重保证了三层判断互不干扰：
     *  - 同目录 = 0 分档，跨目录 = 1000 分档（同目录**永远**赢）；
     *  - 歌词目录再省 10 分（同样是跨目录时，`lyrics/` 比 `omake/` 更像）；
     *  - 扩展名 0..4 分（`.lrc` 最优先）。
     */
    private fun matchScore(node: Track, audioParent: String): Int {
        val dir = node.path.substringBeforeLast('/', "")
        val sameFolder = dir == audioParent
        val folderScore = when {
            sameFolder -> 0
            looksLikeLyricsDir(dir) -> 10
            else -> 20
        }
        return (if (sameFolder) 0 else 1000) + folderScore + lyricPriority(fileNameOf(node))
    }

    /** 目录名里是否出现"歌词/字幕"这类词（任意一层命中即可） */
    private fun looksLikeLyricsDir(dir: String): Boolean {
        if (dir.isBlank()) return false
        return dir.split('/').any { segment ->
            lyricDirHints.any { hint -> segment.contains(hint, ignoreCase = true) }
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
