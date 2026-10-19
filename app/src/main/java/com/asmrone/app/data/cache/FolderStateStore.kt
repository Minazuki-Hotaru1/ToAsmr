package com.asmrone.app.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件树「哪些文件夹是展开的」的持久化 —— 按作品记。
 *
 * ## 为什么需要它
 *
 * 详情页的文件树展开态原来只活在 `DetailViewModel` 的内存里：退出再进来
 * 全部收起，用户要为同一部作品一遍遍点开同一串文件夹。
 *
 * 现在每次展开/收起都会记下当前展开的路径集合，下次进同一部作品时直接回填。
 *
 * ## 存的是路径，不是"第几个文件夹"
 *
 * 文件树是服务端给的递归结构，节点增删随时可能发生。按路径存，重新读出来时
 * 再用 `collectFolderPaths(树)` 取交集：已经不存在、或已变成文件的路径自动
 * 丢弃（见 `DetailViewModel`）。这样即使作者改了目录结构也不会展开错东西。
 *
 * ## 为什么放在 filesDir
 *
 * 它是**不可再生的用户状态**：几百字节，但丢了就是用户要重新点一遍。
 * 放在 cacheDir 里，"清缓存"顺手就把它删掉了 —— 那不是用户想清的东西。
 * 总量上也有硬上限（[MAX_WORKS] 部），不会无限涨。
 */
object FolderStateStore {

    private const val SCHEMA = 1
    private const val FILE_NAME = "folder_state.json"

    /** 最多记多少部作品；超出按最后写入时间淘汰（一部作品几十到几百字符） */
    private const val MAX_WORKS = 200

    /** 单部作品最多记多少条路径：`collectFolderPaths` 的上限是 400，留点余量 */
    private const val MAX_PATHS = 600

    private var file: File? = null

    /** workId → 展开的路径 */
    private val entries = LinkedHashMap<Int, Entry>()

    private var loaded = false

    /** 加锁保护 [entries] 与落盘：读写都发生在 IO 线程，但可能来自不同协程 */
    private val lock = Any()

    private data class Entry(val paths: Set<String>, val savedAt: Long)

    fun init(context: Context) {
        if (file != null) return
        setFile(File(context.applicationContext.filesDir, FILE_NAME))
    }

    /** 仅供测试：指定（或清空）存储目录 */
    internal fun initForTest(dir: File?) {
        setFile(dir?.let { File(it, FILE_NAME) })
    }

    private fun setFile(target: File?) {
        synchronized(lock) {
            file = target
            entries.clear()
            loaded = false
        }
    }

    /**
     * 读一部作品的展开态。
     *
     * @return 上次展开的路径；没有记录时为空集（= 全部收起）
     */
    suspend fun load(workId: Int): Set<String> = withContext(Dispatchers.IO) {
        if (workId <= 0) return@withContext emptySet()
        synchronized(lock) {
            ensureLoaded()
            entries[workId]?.paths ?: emptySet()
        }
    }

    /**
     * 记下一部作品的展开态。
     *
     * 传空集等于"全部收起"，直接把这条记录删掉 —— 收起态本来就是默认值，
     * 没必要占配额。
     */
    suspend fun save(workId: Int, paths: Set<String>) = withContext(Dispatchers.IO) {
        if (workId <= 0) return@withContext
        synchronized(lock) {
            ensureLoaded()
            if (paths.isEmpty()) {
                if (entries.remove(workId) == null) return@synchronized
            } else {
                entries[workId] = Entry(paths.take(MAX_PATHS).toSet(), System.currentTimeMillis())
            }
            flush()
        }
    }

    fun sizeBytes(): Long = file?.takeIf { it.exists() }?.length() ?: 0L

    fun clearAll() {
        synchronized(lock) {
            entries.clear()
            loaded = true
            runCatching { file?.delete() }
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /** 懒加载：应用启动时不读盘，第一次真正用到才解析 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val target = file?.takeIf { it.exists() } ?: return
        val ok = runCatching {
            val root = JSONObject(target.readText(Charsets.UTF_8))
            if (root.optInt("v", 0) != SCHEMA) return@runCatching
            val works = root.optJSONObject("works") ?: return@runCatching
            works.keys().forEach { key ->
                val id = key.toIntOrNull() ?: return@forEach
                val node = works.optJSONObject(key) ?: return@forEach
                val arr = node.optJSONArray("p") ?: return@forEach
                val paths = buildSet {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (paths.isNotEmpty()) entries[id] = Entry(paths, node.optLong("t", 0L))
            }
        }.isSuccess

        // 坏文件直接删掉，否则每次启动都要再解析一遍坏 JSON
        if (!ok) runCatching { target.delete() }
    }

    /** 淘汰超限条目后整文件原子重写 */
    private fun flush() {
        val target = file ?: return
        while (entries.size > MAX_WORKS) {
            val oldest = entries.entries.minByOrNull { it.value.savedAt }?.key ?: break
            entries.remove(oldest)
        }

        val works = JSONObject().apply {
            entries.forEach { (id, entry) ->
                put(id.toString(), JSONObject().apply {
                    put("t", entry.savedAt)
                    put("p", JSONArray(entry.paths.toList()))
                })
            }
        }
        val json = JSONObject().apply {
            put("v", SCHEMA)
            put("works", works)
        }.toString()

        runCatching {
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }
}
