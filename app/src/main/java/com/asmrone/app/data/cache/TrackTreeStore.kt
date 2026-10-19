package com.asmrone.app.data.cache

import android.content.Context
import com.asmrone.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 文件树的本地持久化 —— **按 RJ 号存**。
 *
 * ## 为什么需要它
 *
 * 之前文件树只有内存缓存（`AsmrRepository.trackCache`）：杀掉进程就没了。
 * 于是冷启动后每开一次播放器，歌词页都要重新拉一次 `/api/tracks/{id}`
 * （实测大作品 6MB、1450+ 节点），既慢又费流量，还会在播放器展开动画里卡住。
 *
 * 现在**进入作品时**就把树落盘（`AsmrRepository.getWorkDetail` 成功之后），
 * 之后播放器/歌词页只读本地。
 *
 * ## 为什么用 RJ 号做键
 *
 * RJ 号（`Work.sourceId`，如 `RJ243448`）是作品在 DLsite 上的唯一号，
 * 与站点数据库 id、当前 API 主机都无关 —— 换主机、换账号仍然是同一部作品，
 * 所以换主机后这份缓存依然可用（树里的音频地址是 CDN 绝对地址）。
 *
 * 拿不到 RJ 号的调用方非常多（详情页永远是"我只有 workId"），所以除了树本身，
 * 还会写一张几十字节的**指针** `w<workId>.rj`（内容就是 RJ 号）：
 * 读的时候先问指针，再按 RJ 去开那份树。没有指针时退回历史遗留的
 * `w<workId>.json`（早期版本在拿到 RJ 之前存的那份整树）。
 *
 * ## 为什么放在 cacheDir
 *
 * 树是**可再生的**（重新拉一次就有），与音频缓存同一个定位：
 * 系统空间紧张时允许清掉，清掉只会慢一次。放在 filesDir 会让"清缓存"
 * 这个动作对它们失效，反而更容易把用户存储吃满。
 *
 * 单份最大可达 6MB，所以有总量上限 + 按最后访问时间淘汰（见 [MAX_BYTES]）。
 */
object TrackTreeStore {

    private const val SCHEMA = 1
    private const val DIR = "track_tree"

    /** 指针文件后缀：`w<workId>.rj`，只记 RJ 号 */
    private const val POINTER_SUFFIX = ".rj"

    /** 迁移时每个文件只读这么多字节（信封里 workId 在最前面） */
    private const val HEAD_BYTES = 256

    private val WORK_ID_RE = Regex("\"workId\"\\s*:\\s*(\\d+)")

    /**
     * 总量上限（48MB）。
     *
     * 普通作品一棵树 20–200KB，48MB 大约能放几百部；只有 RJ250820 那种
     * 6MB 的极端作品才会明显占用配额。超了就删最久没碰过的。
     */
    private const val MAX_BYTES = 48L * 1024 * 1024

    /** 实际上限：生产用 [MAX_BYTES]，单测可以调小以便验证淘汰 */
    private var limitBytes = MAX_BYTES

    private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir != null) return
        setRoot(File(context.applicationContext.cacheDir, DIR))
    }

    /**
     * 仅供测试：指定（或清空）存储目录，并可临时调小总量上限。
     *
     * 调小上限是必要的：真实上限 48MB，测试里要造出 48MB 数据才能验证淘汰，
     * 那既慢又占地。
     */
    internal fun initForTest(dir: File?, maxBytes: Long = MAX_BYTES) {
        limitBytes = if (dir == null) MAX_BYTES else maxBytes
        setRoot(dir)
    }

    private fun setRoot(dir: File?) {
        rootDir = dir
        dir?.let { runCatching { it.mkdirs() } }
    }

    // ─────────────────────────── 键 ───────────────────────────

    /** RJ 号 → 安全的文件名片段（只留字母数字与 `-` `_`） */
    private fun sanitize(sourceId: String): String =
        sourceId.trim()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(40)

    /**
     * 这份树可能落在哪几个文件里（按优先级）。
     *
     * 三套命名都试：给了 RJ 就用 RJ；只给 workId 就先问指针文件；
     * 最后才是历史遗留的 `w<workId>.json`。
     */
    private fun candidates(sourceId: String?, workId: Int): List<File> {
        val root = rootDir ?: return emptyList()
        val names = buildList {
            sourceId?.let(::sanitize)?.takeIf { it.isNotEmpty() }?.let { add("$it.json") }
            if (workId > 0) {
                if (sourceId.isNullOrBlank()) {
                    readPointer(workId)
                        ?.let(::sanitize)?.takeIf { it.isNotEmpty() }
                        ?.let { add("$it.json") }
                }
                add("w$workId.json")
            }
        }.distinct()
        return names.map { File(root, it) }
    }

    private fun pointerFile(workId: Int): File? {
        if (workId <= 0) return null
        return rootDir?.let { File(it, "w$workId$POINTER_SUFFIX") }
    }

    /**
     * 记下 `workId → RJ`。
     *
     * 指针只有几十字节，却决定了"只拿到 workId 的调用方能不能读到这份树" ——
     * 详情页正是这种调用方（元数据与树并发获取，树这一路根本没有 RJ）。
     */
    private fun writePointer(workId: Int, sourceId: String) {
        val file = pointerFile(workId) ?: return
        runCatching {
            val json = JSONObject().apply {
                put("v", SCHEMA)
                put("rj", sourceId)
            }.toString()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    /** 读指针；坏指针直接删掉（它只是索引，丢了最多多拉一次树） */
    private fun readPointer(workId: Int): String? {
        val file = pointerFile(workId) ?: return null
        if (!file.exists()) return null
        val rj = runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).optString("rj").trim()
        }.getOrNull()
        if (rj.isNullOrEmpty()) {
            runCatching { file.delete() }
            return null
        }
        return rj
    }

    /**
     * 给"升级前就存在"的树补指针（一次性迁移，幂等）。
     *
     * 旧版本只写 `RJxxx.json` 并把 `w<id>.json` 删掉，所以只拿到 workId 的调用方
     * 读不到 —— 修好命名规则之后，这些**已经缓存过**的作品仍然要再拉一次树。
     * 这里从文件头里读出 workId 补出指针，省掉这一趟。
     *
     * 只读每个文件的前 [HEAD_BYTES] 字节（信封里 `workId` 在最前面），
     * 不解析整棵树 —— 否则几百份缓存会在冷启动时读掉几百 MB。
     *
     * @return 补出来的指针数量
     */
    suspend fun backfillPointers(): Int = withContext(Dispatchers.IO) {
        val root = rootDir ?: return@withContext 0
        val files = runCatching {
            root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
        }.getOrNull() ?: return@withContext 0

        var created = 0
        // 先把已有的指针读成一个集合：否则每个树文件都要遍历一遍指针文件
        val existing = HashSet<String>()
        runCatching {
            root.listFiles()?.forEach { file ->
                if (!file.name.endsWith(POINTER_SUFFIX)) return@forEach
                val rj = runCatching {
                    JSONObject(file.readText(Charsets.UTF_8)).optString("rj").trim()
                }.getOrNull()
                if (!rj.isNullOrEmpty()) existing.add(rj)
            }
        }

        for (file in files) {
            val rj = file.nameWithoutExtension
            // `w<id>.json` 这种本来就能只靠 workId 读到，不需要指针
            if (rj.startsWith("w") || rj.isBlank()) continue
            if (rj in existing) continue

            val workId = runCatching {
                val head = file.inputStream().use { input ->
                    val buf = ByteArray(HEAD_BYTES)
                    val read = input.read(buf)
                    if (read <= 0) "" else String(buf, 0, read, Charsets.UTF_8)
                }
                WORK_ID_RE.find(head)?.groupValues?.get(1)?.toIntOrNull()
            }.getOrNull() ?: continue
            if (workId <= 0) continue
            if (pointerFile(workId)?.exists() == true) continue

            writePointer(workId, rj)
            existing.add(rj)
            created++
        }
        created
    }

    // ─────────────────────────── 读写 ───────────────────────────

    /**
     * 读一份树。
     *
     * 逐个候选试，直到读出非空树：某个候选是坏文件时删掉它并继续 ——
     * 不能像以前那样"第一个存在的文件解析失败就当整体没缓存"。
     *
     * 命中时顺手 `setLastModified(now)` —— 这就是 LRU 的"最近使用"记号，
     * 淘汰只按它排序，不额外维护索引文件。
     *
     * @return null = 没有缓存（或文件损坏），调用方自己决定要不要联网
     */
    suspend fun load(sourceId: String?, workId: Int): List<Track>? = withContext(Dispatchers.IO) {
        for (file in candidates(sourceId, workId)) {
            if (!file.exists()) continue
            val tracks = runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optInt("v", 0) != SCHEMA) return@runCatching null
                TrackJson.decodeTree(root.optJSONArray("tracks")).takeIf { it.isNotEmpty() }
            }.getOrNull()

            if (tracks != null) {
                runCatching { file.setLastModified(System.currentTimeMillis()) }
                // 手里有 RJ 却没指针时补一张：下次只拿到 workId 也能命中
                if (!sourceId.isNullOrBlank() && pointerFile(workId)?.exists() != true) {
                    writePointer(workId, sourceId)
                }
                return@withContext tracks
            }
            // 坏文件直接删掉，否则每次读都要再解析一遍坏 JSON
            runCatching { file.delete() }
        }
        null
    }

    /**
     * 写一份树（原子写：先写 `.tmp` 再改名）。
     *
     * @param sourceId RJ 号；为 null 时按 `w<workId>.json` 存
     */
    suspend fun save(
        sourceId: String?,
        workId: Int,
        host: String,
        tracks: List<Track>
    ) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        val target = candidates(sourceId, workId).firstOrNull() ?: return@withContext
        val json = JSONObject().apply {
            put("v", SCHEMA)
            put("workId", workId)
            put("sourceId", sourceId ?: "")
            put("host", host)
            put("savedAt", System.currentTimeMillis())
            put("tracks", TrackJson.encodeTree(tracks))
        }.toString()

        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            target.setLastModified(System.currentTimeMillis())
        }
        // 树按 RJ 命名时，顺手记一张 workId → RJ 的指针
        if (!sourceId.isNullOrBlank()) writePointer(workId, sourceId)
        prune()
    }

    /** 是否存在一份缓存（不解析内容，只判文件） */
    fun has(sourceId: String?, workId: Int): Boolean =
        candidates(sourceId, workId).any { it.exists() }

    fun sizeBytes(): Long {
        val root = rootDir ?: return 0L
        return runCatching {
            root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    fun count(): Int =
        rootDir?.listFiles()?.count { it.isFile && !it.name.endsWith(POINTER_SUFFIX) } ?: 0

    fun clearAll() {
        runCatching { rootDir?.listFiles()?.forEach { it.delete() } }
    }

    /** 删除某部作品的缓存（详情页强制刷新时用）；指针一并删掉 */
    fun forget(sourceId: String?, workId: Int) {
        candidates(sourceId, workId).forEach { runCatching { it.delete() } }
        runCatching { pointerFile(workId)?.delete() }
    }

    /**
     * 只删"只知道 workId"的那份 `w<id>.json`。
     *
     * 用在"先按 workId 存了一份，随后才拿到 RJ 号又按 RJ 存了一份"之后：
     * 不删的话同一棵树会占两份空间。读取时 RJ 优先，所以删掉不影响命中。
     * 指针 `w<id>.rj` 必须留着 —— 否则下次只拿到 workId 又读不到 RJ 那份。
     */
    fun forgetByIdOnly(workId: Int) {
        if (workId <= 0) return
        val root = rootDir ?: return
        runCatching { File(root, "w$workId.json").delete() }
    }

    /**
     * 超出上限就按最后修改时间从旧到新删，直到回到 80% 以下。
     *
     * 一次删到 80% 而不是"删到刚好达标"：避免每次写入都触发一次淘汰。
     * 只删树（`.json`），指针永不参与淘汰（否则会把成片的树变成"读不到"）；
     * 指针指向的树没了就顺手清掉指针。
     */
    private fun prune() {
        val root = rootDir ?: return
        val files = runCatching { root.listFiles()?.filter { it.isFile } }.getOrNull() ?: return

        files.filter { it.name.endsWith(POINTER_SUFFIX) }.forEach { pointer ->
            val rj = runCatching {
                JSONObject(pointer.readText(Charsets.UTF_8)).optString("rj").trim()
            }.getOrNull()
            val target = rj?.takeIf { it.isNotEmpty() }?.let { File(root, "${sanitize(it)}.json") }
            if (target == null || !target.exists()) runCatching { pointer.delete() }
        }

        val trees = files.filter { !it.name.endsWith(POINTER_SUFFIX) }
        var total = files.sumOf { it.length() }
        val limit = limitBytes
        if (total <= limit) return

        val target = (limit * 8 / 10)
        trees.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) return
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= len
        }
    }
}
