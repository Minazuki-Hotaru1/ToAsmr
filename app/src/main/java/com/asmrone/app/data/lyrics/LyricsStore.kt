package com.asmrone.app.data.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 已解析歌词的本地持久化。
 *
 * ## 为什么需要
 *
 * [LyricsLoader] 只有**内存**缓存：杀掉进程就没了。于是每次冷启动、
 * 只要播放会话被恢复，为了把歌词准备好又会去 `/api/media/stream/...` 取一次
 * 那个歌词文件 —— 网络日志里"关掉首页加载后仍然有一条 media 请求"就是这么来的。
 *
 * 歌词解析结果很小（一首几十行、几 KB），把它和文件树一样落到本地之后：
 *  - 冷启动零请求即可还原歌词页；
 *  - 反复听同一部作品不再重复下载同一个歌词文件。
 *
 * ## 存哪儿、怎么命名
 *
 * 与 `TrackTreeStore` 同一个思路：`cacheDir`（可再生数据，允许系统清理）、
 * 文件名 = `<RJ 号或 w<id>>__<trackPath 的短哈希>.json`，不需要维护索引。
 * 单部作品的所有歌词共用同一个前缀，删起来也是一条 `delete`。
 */
internal object LyricsStore {

    private const val SCHEMA = 1
    private const val DIR = "lyrics"

    /** 总容量上限：歌词极小，16MB 能放非常非常多首 */
    private const val MAX_BYTES = 16L * 1024 * 1024

    private var limitBytes = MAX_BYTES
    private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir != null) return
        setRoot(File(context.applicationContext.cacheDir, DIR))
    }

    /** 仅供测试：指定目录与上限 */
    internal fun initForTest(dir: File?, maxBytes: Long = MAX_BYTES) {
        limitBytes = if (dir == null) MAX_BYTES else maxBytes
        setRoot(dir)
    }

    private fun setRoot(dir: File?) {
        rootDir = dir
        dir?.let { runCatching { it.mkdirs() } }
    }

    // ─────────────────────────── 键 ───────────────────────────

    private fun sanitize(value: String): String =
        value.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)

    /** 作品维度的前缀：优先 RJ 号，拿不到才退回 `w<workId>` */
    private fun workKey(sourceId: String?, workId: Int): String? {
        sanitize(sourceId.orEmpty()).takeIf { it.isNotEmpty() }?.let { return it }
        return if (workId > 0) "w$workId" else null
    }

    private fun fileFor(sourceId: String?, workId: Int, trackPath: String): File? {
        val root = rootDir ?: return null
        val key = workKey(sourceId, workId) ?: return null
        if (trackPath.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trackPath.toByteArray(Charsets.UTF_8))
        val hex = buildString { for (i in 0 until 8) append("%02x".format(digest[i])) }
        return File(root, "${key}__$hex.json")
    }

    // ─────────────────────────── 读写 ───────────────────────────

    suspend fun load(sourceId: String?, workId: Int, trackPath: String): LyricsLoad.Ready? =
        withContext(Dispatchers.IO) {
            val file = fileFor(sourceId, workId, trackPath) ?: return@withContext null
            if (!file.exists()) return@withContext null

            val parsed = runCatching {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                if (o.optInt("v", 0) != SCHEMA) return@runCatching null
                val title = o.optString("title")
                val lines = readLines(o.optJSONArray("lines"))
                if (lines.isEmpty()) null else LyricsLoad.Ready(lines = lines, sourceTitle = title)
            }.getOrNull()

            if (parsed != null) {
                runCatching { file.setLastModified(System.currentTimeMillis()) }
            } else {
                // 坏文件直接删，否则每次都要再解析一遍坏 JSON
                runCatching { file.delete() }
            }
            parsed
        }

    suspend fun save(
        sourceId: String?,
        workId: Int,
        trackPath: String,
        ready: LyricsLoad.Ready
    ) = withContext(Dispatchers.IO) {
        val file = fileFor(sourceId, workId, trackPath) ?: return@withContext
        runCatching {
            val json = JSONObject().apply {
                put("v", SCHEMA)
                put("title", ready.sourceTitle)
                put(
                    "lines",
                    JSONArray().apply {
                        ready.lines.forEach { line ->
                            put(
                                JSONObject().apply {
                                    put("ms", line.startMs)
                                    put("o", line.original)
                                    // 译文大部分为空，不写就不会让文件白白变大
                                    if (line.translation.isNotBlank()) put("t", line.translation)
                                }
                            )
                        }
                    }
                )
            }.toString()

            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            file.setLastModified(System.currentTimeMillis())
        }
        prune()
    }

    private fun readLines(array: JSONArray?): List<LyricLine> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val original = o.optString("o")
                if (original.isEmpty()) continue
                add(
                    LyricLine(
                        original = original,
                        translation = o.optString("t"),
                        startMs = o.optLong("ms", 0L)
                    )
                )
            }
        }
    }

    fun sizeBytes(): Long {
        val root = rootDir ?: return 0L
        return runCatching {
            root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    fun clearAll() {
        runCatching { rootDir?.listFiles()?.forEach { it.delete() } }
    }

    /** 删掉某部作品的全部歌词缓存 */
    fun forget(sourceId: String?, workId: Int) {
        val root = rootDir ?: return
        val key = workKey(sourceId, workId) ?: return
        runCatching { root.listFiles()?.filter { it.name.startsWith("${key}__") }?.forEach { it.delete() } }
    }

    /** 超出上限时按最后使用时间从旧到新删，一次删到 80% */
    private fun prune() {
        val root = rootDir ?: return
        val files = runCatching { root.listFiles()?.filter { it.isFile } }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        val limit = limitBytes
        if (total <= limit) return

        val target = limit * 8 / 10
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) return
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= len
        }
    }
}
