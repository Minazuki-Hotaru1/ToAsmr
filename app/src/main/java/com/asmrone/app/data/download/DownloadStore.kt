package com.asmrone.app.data.download

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 下载内容的**本地存储**。
 *
 * ## 目录结构
 * ```
 * filesDir/downloads/
 *   └─ 1681251/                  ← 每部作品一个目录，删除 = 删目录（一次 IO）
 *        ├─ manifest.json        ← 清单：完整树 + 已下载文件 + 原始作品 JSON
 *        └─ 3f9a…c1.mp3          ← 音频文件本身
 * ```
 *
 * ## 为什么文件名是哈希而不是原文件名
 *
 * 树内路径可以是 `03_omake/02_mp3（音声のみ）/01_xxx.mp3`：中文、全角括号、
 * 斜杠、长度都可能出问题（移动端文件系统对大写/特殊字符的处理并不统一）。
 * 文件名改成"路径的 sha1 前 20 位 + 扩展名"之后：
 *  - 与路径**一一对应**（确定性生成，不需要查清单就能算出本地文件在哪）；
 *  - 不含任何特殊字符，也不会因为路径过长失败。
 *
 * 展示给用户的名字来自清单里的树（[DownloadManifest.tree]），与文件名无关，
 * 所以哈希命名对界面没有任何可见影响。
 *
 * ## 播放时怎么用
 *
 * [localFileFor] 是**同步**的纯路径计算 + 一次 `isFile` 判断，不做 JSON 解析 ——
 * 播放器在主线程调它，不能因为读清单而卡一下。
 */
object DownloadStore {

    private const val DIR = "downloads"
    private const val MANIFEST_NAME = "manifest.json"
    private const val PART_SUFFIX = ".part"
    private val HEX = "0123456789abcdef".toCharArray()

    private val lock = Any()

    @Volatile
    private var root: File? = null

    /** 目录里有哪些作品（启动时扫一遍目录名，避免为"是否存在"去解析清单） */
    private var workIds: MutableSet<Int> = mutableSetOf()

    /** 本次会话已解析过的清单（含"确认不存在"的 null，避免反复 stat） */
    private val cache = HashMap<Int, DownloadManifest?>()

    fun init(context: Context) {
        initForTest(File(context.filesDir, DIR))
    }

    /** 测试用：换一个临时目录（不依赖 Android Context）；传 null = 复位 */
    fun initForTest(dir: File?) {
        synchronized(lock) {
            if (dir == null) {
                root = null
                workIds = mutableSetOf()
                cache.clear()
                return
            }
            if (!dir.exists()) dir.mkdirs()
            root = dir
            workIds = dir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { it.name.toIntOrNull() }
                ?.toMutableSet()
                ?: mutableSetOf()
            cache.clear()
        }
    }

    private fun requireRoot(): File = root ?: error("DownloadStore 未初始化")

    fun workDir(workId: Int): File = File(requireRoot(), workId.toString())

    fun manifestFile(workId: Int): File = File(workDir(workId), MANIFEST_NAME)

    /** 有没有下载过这部作品（**目录存在**即算，哪怕清单还没写） */
    fun isDownloaded(workId: Int): Boolean = synchronized(lock) { workId in workIds }

    /**
     * 树内路径 → 本地文件名。
     *
     * 用 SHA-1 前 20 位（80 bit）足够避免碰撞，同时比 MD5 更容易通过静态检查工具。
     */
    fun fileNameFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        val safeExt = ext.filter { it.isLetterOrDigit() }.take(8)
        return if (safeExt.isEmpty()) hex.take(20) else "${hex.take(20)}.$safeExt"
    }

    /** 下载中的临时文件（与正式文件同目录，完成后 rename） */
    fun partFile(workId: Int, path: String): File =
        File(workDir(workId), fileNameFor(path) + PART_SUFFIX)

    /** 正式文件（不判断是否存在） */
    fun fileFor(workId: Int, path: String): File = File(workDir(workId), fileNameFor(path))

    /**
     * 播放用：这个文件已经下载好了吗？是则返回本地文件。
     *
     * **不读清单**：文件名是确定性的，`isFile` 就是最终答案。
     * 这样即使清单损坏 / 还没写，已落盘的音频照样能播。
     */
    fun localFileFor(workId: Int, path: String): File? {
        if (workId <= 0 || path.isBlank() || root == null) return null
        val file = fileFor(workId, path)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /** 已解析过的清单（可能为 null = 确认没有）；未加载过时返回 null 且不触发 IO */
    fun cached(workId: Int): DownloadManifest? = synchronized(lock) { cache[workId] }

    fun isCached(workId: Int): Boolean = synchronized(lock) { cache.containsKey(workId) }

    suspend fun load(workId: Int): DownloadManifest? {
        synchronized(lock) {
            if (cache.containsKey(workId)) return cache[workId]
        }
        val manifest = withContext(Dispatchers.IO) { readManifest(workId) }
        synchronized(lock) { cache[workId] = manifest }
        return manifest
    }

    /** 一次读完目录下所有清单（下载页 / 启动预热用） */
    suspend fun loadAll(): List<DownloadManifest> = withContext(Dispatchers.IO) {
        val ids = synchronized(lock) { workIds.toList() }
        ids.mapNotNull { id ->
            val manifest = load(id)
            if (manifest != null) manifest else null
        }
    }

    /** 预先读进内存：播放器、详情页要用时就不必再碰磁盘 */
    suspend fun prewarm(workId: Int) {
        load(workId)
    }

    suspend fun save(manifest: DownloadManifest) {
        withContext(Dispatchers.IO) { writeManifest(manifest) }
        synchronized(lock) {
            cache[manifest.workId] = manifest
            workIds.add(manifest.workId)
        }
    }

    /** 删掉一部作品的整个目录（含音频文件） */
    suspend fun remove(workId: Int): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val dir = workDir(workId)
            val ok = if (dir.exists()) dir.deleteRecursively() else true
            ok
        }
        synchronized(lock) {
            cache.remove(workId)
            workIds.remove(workId)
        }
        return deleted
    }

    /** 已下载总量（供设置页显示；只数音频文件，不含清单） */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val ids = synchronized(lock) { workIds.toList() }
        ids.sumOf { id ->
            workDir(id).listFiles()
                ?.filter { it.name != MANIFEST_NAME && !it.name.endsWith(".tmp") }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    // ─────────────────────────── 磁盘 IO ───────────────────────────

    private fun readManifest(workId: Int): DownloadManifest? {
        val file = manifestFile(workId)
        if (!file.isFile) return null
        val manifest = runCatching {
            DownloadManifest.fromJson(JSONObject(file.readText()))
        }.getOrNull()
        if (manifest == null) {
            // 结构升级或文件损坏：删掉，让下载页把它当成"没下载过"
            runCatching { file.delete() }
        }
        return manifest
    }

    private fun writeManifest(manifest: DownloadManifest) {
        val dir = workDir(manifest.workId)
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, MANIFEST_NAME)
        val tmp = File(dir, "$MANIFEST_NAME.tmp")
        tmp.writeText(manifest.toJson().toString())
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 在同一个目录里几乎不会失败；失败时退化成直接写，
            // 总比丢掉这份清单（= 用户下载的内容从界面上消失）好
            target.writeText(manifest.toJson().toString())
            tmp.delete()
        }
    }
}
