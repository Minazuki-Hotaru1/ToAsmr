package com.asmrone.app.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 作品详情页元数据的本地持久化。
 *
 * ## 为什么需要它
 *
 * 详情页的封面、标题、社团、标签、简介、评分、收藏状态全在
 * `/api/work/{id}?v=2` 这一个响应里。之前它只有内存缓存
 * （`AsmrRepository.workCache`），杀掉进程就没了 —— 从收藏页点进任何一部
 * 作品都要等一次网络往返才有内容可看，网络差时就是一直转圈。
 *
 * 现在这份响应会落盘：再进同一部作品直接本地出图出字，**0 请求**。
 *
 * ## 为什么存服务端原文
 *
 * 不另写一套 `Work` 序列化（`Work` 上没有 `toJson`，也不想加）：
 *
 * - 服务端加了字段不用改这里，也不会在缓存里丢掉；
 * - 解析规则只有一处 —— 读的时候仍然走 `Work.fromJson(json, host)`，
 *   缓存版与网络版不会出现"字段对不上"。
 *
 * ## 为什么按 workId 命名
 *
 * 详情页永远先拿到 workId，RJ 号要等元数据解析完才知道。按 workId 命名后
 * 读写只有一条路径，[forget] 也只删一个文件，不必像文件树那样同时维护
 * `RJxxx.json` 与 `w<id>.json` 两套候选。
 *
 * 站点换主机时 workId 可能指向另一部作品，由信封里的 `host` 拦住：
 * 主机不符一律视为没缓存（见 [load]）。
 *
 * ## 为什么放在 cacheDir
 *
 * 元数据是**可再生的**：清掉只会慢一次。与音频、文件树同一个定位，
 * 系统空间紧张时允许被清。
 */
object WorkMetaStore {

    private const val SCHEMA = 1
    private const val DIR = "work_meta"

    /**
     * 总量上限（16MB）。
     *
     * 单部作品的详情响应通常 8–30KB（简介长、标签多的大作也就 60KB），
     * 16MB 大约能放 500–1500 部，足够覆盖"常看的收藏"。
     */
    private const val MAX_BYTES = 16L * 1024 * 1024

    /** 实际上限：生产用 [MAX_BYTES]，单测可以调小以便验证淘汰 */
    private var limitBytes = MAX_BYTES

    private var rootDir: File? = null

    /** 一份缓存：服务端原文 + 写入时间 */
    data class Entry(val json: JSONObject, val savedAt: Long)

    fun init(context: Context) {
        if (rootDir != null) return
        setRoot(File(context.applicationContext.cacheDir, DIR))
    }

    /** 仅供测试：指定（或清空）存储目录，并可临时调小总量上限 */
    internal fun initForTest(dir: File?, maxBytes: Long = MAX_BYTES) {
        limitBytes = if (dir == null) MAX_BYTES else maxBytes
        setRoot(dir)
    }

    private fun setRoot(dir: File?) {
        rootDir = dir
        dir?.let { runCatching { it.mkdirs() } }
    }

    private fun fileFor(workId: Int): File? {
        if (workId <= 0) return null
        val root = rootDir ?: return null
        return File(root, "w$workId.json")
    }

    /**
     * 读一份元数据。
     *
     * 命中时顺手 `setLastModified(now)` —— 这就是 LRU 的"最近使用"记号。
     *
     * @param host 当前 API 主机；与落盘时不一致视为没缓存
     * @return null = 没有缓存（或文件损坏 / 换了主机），调用方自己决定要不要联网
     */
    suspend fun load(workId: Int, host: String): Entry? = withContext(Dispatchers.IO) {
        val file = fileFor(workId)?.takeIf { it.exists() } ?: return@withContext null
        val entry = runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("v", 0) != SCHEMA) return@runCatching null
            if (root.optString("host") != host) return@runCatching null
            val work = root.optJSONObject("work") ?: return@runCatching null
            Entry(work, root.optLong("savedAt", 0L))
        }.getOrNull()

        if (entry != null) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        } else {
            // 坏文件（或换了主机留下的旧文件）直接删掉，否则每次读都要再解析一遍
            runCatching { file.delete() }
        }
        entry
    }

    /**
     * 写一份元数据（原子写：先写 `.tmp` 再改名）。
     *
     * @param work 服务端原文，即 `/api/work/{id}?v=2` 解析出的 `JSONObject`
     */
    suspend fun save(workId: Int, host: String, work: JSONObject) = withContext(Dispatchers.IO) {
        val target = fileFor(workId) ?: return@withContext
        val json = JSONObject().apply {
            put("v", SCHEMA)
            put("workId", workId)
            put("host", host)
            put("savedAt", System.currentTimeMillis())
            put("work", work)
        }.toString()

        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            target.setLastModified(System.currentTimeMillis())
        }
        prune()
    }

    /** 删除某部作品的缓存（强制刷新、或收藏状态变了以后用） */
    fun forget(workId: Int) {
        fileFor(workId)?.let { runCatching { it.delete() } }
    }

    fun has(workId: Int): Boolean = fileFor(workId)?.exists() == true

    fun sizeBytes(): Long {
        val root = rootDir ?: return 0L
        return runCatching {
            root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    fun count(): Int = rootDir?.listFiles()?.count { it.isFile } ?: 0

    fun clearAll() {
        runCatching { rootDir?.listFiles()?.forEach { it.delete() } }
    }

    /**
     * 超出上限就按最后修改时间从旧到新删，直到回到 80% 以下。
     *
     * 一次删到 80% 而不是"删到刚好达标"：避免每次写入都触发一次淘汰。
     */
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
