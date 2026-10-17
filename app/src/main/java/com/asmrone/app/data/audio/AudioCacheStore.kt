package com.asmrone.app.data.audio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 音频流缓存的**文件与元数据管理**。
 *
 * ## 磁盘布局
 *
 * ```
 * <root>/audio_cache/
 *   <id>.part      正在追加的缓存（永远是从头开始的一段**连续前缀**）
 *   <id>.audio     已完整的缓存（字节数与上游总长精确相等后改名而来）
 *   index.json     元数据：上游 URL / 总长 / 最后访问时间
 * ```
 *
 * `<id>` 由**上游 URL** 派生（SHA-256 前 16 字节），不是 track id ——
 * 同一部作品的同一音轨可能有多个 URL（不同质量分支、不同 CDN 域名），
 * 用 URL 才能把"同一份字节"正确归并，也不会把不同质量混成一个缓存。
 *
 * ## 为什么 `.part` 与 `.audio` 分成两个文件
 *
 * `.audio` 的存在本身就代表"**完整且校验过**"。播放器与清理逻辑只要看到
 * `.audio` 就可以完全信任它；而 `.part` 一律当作"可能只有前半段"。
 * 这样就不需要一个额外的 `complete` 字段去描述文件自身 ——
 * 文件名即状态，不会出现"元数据说完整但文件其实不完整"的矛盾。
 *
 * ## 为什么只依赖 [File] 而不是 Android 的 Context
 *
 * 这样整个类可以用临时目录做**真正的单元测试**（写入 / 追加 / 校验 / 改名 /
 * 快照 / 清理都能跑到），而不只是测几个纯函数。调用方传入
 * `context.cacheDir` 即可。
 */
class AudioCacheStore {

    @Volatile
    private var root: File? = null

    /** 元数据：资源 id → 条目。内存里保存一份，避免每次读 JSON */
    private val meta = LinkedHashMap<String, Meta>()

    @Synchronized
    fun init(rootDir: File) {
        if (root != null && root?.absolutePath == rootDir.absolutePath) return
        val dir = File(rootDir, DIR).apply { if (!exists()) mkdirs() }
        root = dir
        loadMeta(dir)
    }

    /** 是否已初始化（未初始化时所有操作都安全地返回"没有缓存"） */
    val isReady: Boolean get() = root != null

    private fun dir(): File? = root

    // ─────────────────────────── 查询 ───────────────────────────

    /** 是否已有**完整**缓存 */
    fun isComplete(id: String): Boolean = completeFile(id)?.exists() == true

    /**
     * 已缓存的字节数。
     *
     * `.audio`（完整）优先 —— 它一定比 `.part` 可信，而且两者不会同时存在。
     * 用普通 `if` 而不是 `?.let { return }`：后者在这里会被推断成 `Long?`，
     * 因为 lambda 里的非局部返回与表达式的最后一行类型混在一起。
     */
    fun cachedBytes(id: String): Long {
        val complete = completeFile(id)
        if (complete != null && complete.exists()) return complete.length()
        val part = partFile(id)
        if (part != null && part.exists()) return part.length()
        return 0L
    }

    /** 完整缓存的文件；没有则 null */
    fun completeFile(id: String): File? = dir()?.let { File(it, "$id.audio") }

    /** 正在追加的缓存文件；没有则 null */
    fun partFile(id: String): File? = dir()?.let { File(it, "$id.part") }

    fun lastAccessedAt(id: String): Long = synchronized(this) { meta[id]?.lastAccessedMs ?: 0L }

    // ─────────────────────────── 写入 ───────────────────────────

    /**
     * 追加一段字节到 `.part`。
     *
     * ## ⚠️ 调用方必须先确认这是**顺序**写入
     *
     * 这个方法不做连续性检查 —— 它假设调用方已经保证"这段字节紧接在
     * 当前 `.part` 末尾之后"。这是刻意的：连续性判断需要知道请求的起点，
     * 而那信息在代理层（见 `LocalAudioProxy`）。
     *
     * 如果在这里放了空洞，"文件长度"就再也反映不出真实完整性，
     * 后续的长度校验会误判为完整 —— 那是**静默的数据损坏**，比不做缓存糟得多。
     *
     * @return 写入后的总长度；未初始化时返回 0（等于"没有缓存"），
     *   写入本身失败也返回 0 —— 缓存失败不该被当成需要处理的错误，
     *   它只是这次没缓存上，播放本身不受影响
     */
    @Synchronized
    fun appendPart(id: String, bytes: ByteArray, offset: Int, length: Int): Long {
        val d = dir() ?: return 0L
        if (length <= 0) return cachedBytes(id)
        return runCatching {
            val f = File(d, "$id.part")
            val len = RandomAccessFile(f, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write(bytes, offset, length)
                raf.length()
            }
            // 每次落盘后同步刷新网络日志（调试期有效，release 下是空操作）。
            // 放在这里而不是代理层：缓存写入是"边播边缓存真的生效了"的
            // 直接证据，日志必须跟上，否则出问题时无从判断走到了哪一步。
            com.asmrone.app.data.diag.HttpLog.flushToFile()
            len
        }.getOrDefault(0L)
    }

    /**
     * 数据下完后校验并改名成 `.audio`。
     *
     * **只有字节数精确等于 [expectedTotal] 才算成功** ——
     * 服务端没有 `ETag`/`Last-Modified`（实测），长度是唯一可用的完整性判据。
     * 校验失败就把 `.part` 删掉（留着也没有意义：它不可能自己补完）。
     */
    @Synchronized
    fun finalizeIfComplete(id: String, expectedTotal: Long, nowMs: Long): Boolean {
        val d = dir() ?: return false
        if (expectedTotal <= 0) return false

        val part = File(d, "$id.part")
        val complete = File(d, "$id.audio")
        if (complete.exists()) return true
        if (!part.exists()) return false

        val size = part.length()
        if (size != expectedTotal) {
            // 不完整：删掉，下次从头来。
            // 保留一个"差一点就完整"的文件只会让下次仍然不完整。
            part.delete()
            return false
        }

        if (complete.exists()) complete.delete()
        if (!part.renameTo(complete)) return false

        meta[id] = (meta[id] ?: Meta(id, "", expectedTotal, nowMs)).copy(
            totalBytes = expectedTotal,
            lastAccessedMs = nowMs
        )
        saveMeta(d)
        return true
    }

    /** 记录一次访问（LRU 与 TTL 的依据） */
    @Synchronized
    fun touch(id: String, upstreamUrl: String, totalBytes: Long, nowMs: Long) {
        val d = dir() ?: return
        val old = meta[id]
        meta[id] = Meta(
            id = id,
            upstreamUrl = upstreamUrl.ifBlank { old?.upstreamUrl.orEmpty() },
            totalBytes = if (totalBytes > 0) totalBytes else (old?.totalBytes ?: 0L),
            lastAccessedMs = nowMs
        )
        saveMeta(d)
    }

    /** 删掉某个资源的缓存（完整与部分都删） */
    @Synchronized
    fun delete(id: String): Long {
        val d = dir() ?: return 0L
        var freed = 0L
        listOf(File(d, "$id.audio"), File(d, "$id.part")).forEach {
            if (it.exists()) {
                freed += it.length()
                it.delete()
            }
        }
        meta.remove(id)
        saveMeta(d)
        return freed
    }

    // ─────────────────────────── 统计与清理 ───────────────────────────

    /**
     * 当前状态快照（设置页展示 + 淘汰决策都用它）。
     *
     * 以**磁盘上的文件**为准，而不是元数据 —— 元数据可能因为崩溃而落后，
     * 而"占了多少空间"这个问题只有文件本身答得准。
     */
    @Synchronized
    fun snapshot(nowMs: Long): AudioCacheStatus {
        val d = dir() ?: return AudioCacheStatus()
        val files = d.listFiles().orEmpty()

        val entries = files.mapNotNull { f ->
            val name = f.name
            when {
                name.endsWith(".audio") -> {
                    val id = name.removeSuffix(".audio")
                    AudioCacheEntry(
                        id = id,
                        cachedBytes = f.length(),
                        totalBytes = meta[id]?.totalBytes ?: f.length(),
                        complete = true,
                        lastModifiedMs = f.lastModified(),
                        lastAccessedMs = meta[id]?.lastAccessedMs ?: f.lastModified()
                    )
                }

                name.endsWith(".part") -> {
                    val id = name.removeSuffix(".part")
                    AudioCacheEntry(
                        id = id,
                        cachedBytes = f.length(),
                        totalBytes = meta[id]?.totalBytes ?: 0L,
                        complete = false,
                        lastModifiedMs = f.lastModified(),
                        lastAccessedMs = meta[id]?.lastAccessedMs ?: f.lastModified()
                    )
                }

                else -> null
            }
        }

        val used = entries.sumOf { it.cachedBytes }
        val completeCount = entries.count { it.complete }
        val partialCount = entries.size - completeCount
        // "可回收" = 过期未完成的 + 过期已完成的（与淘汰策略同一口径）
        val stalePartial = entries.count {
            it.isStalePartial && it.lastModifiedMs < AudioCachePolicy.stalePartialCutoffMs(nowMs)
        }

        return AudioCacheStatus(
            usedBytes = used,
            completeCount = completeCount,
            partialCount = partialCount,
            stalePartialCount = stalePartial,
            updatedAt = nowMs
        )
    }

    /** 生成淘汰计划（不执行）；[limitBytes] 来自用户设置 */
    fun cleanupPlan(limitBytes: Long, nowMs: Long): CacheCleanupPlan {
        val status = snapshot(nowMs)
        return AudioCachePolicy.plan(
            entries = entriesForPolicy(nowMs),
            limitBytes = limitBytes,
            nowMs = nowMs,
            usedBytes = status.usedBytes
        )
    }

    /** 执行淘汰计划，返回实际释放的字节数 */
    @Synchronized
    fun executePlan(plan: CacheCleanupPlan): Long {
        var freed = 0L
        plan.delete.forEach { freed += delete(it.id) }
        return freed
    }

    /** 清空全部音频缓存 */
    @Synchronized
    fun clearAll(): Long {
        val d = dir() ?: return 0L
        var freed = 0L
        d.listFiles().orEmpty().forEach {
            if (it.isFile && it.name != META_FILE) {
                freed += it.length()
                it.delete()
            }
        }
        meta.clear()
        saveMeta(d)
        return freed
    }

    @Synchronized
    private fun entriesForPolicy(nowMs: Long): List<AudioCacheEntry> {
        val d = dir() ?: return emptyList()
        return d.listFiles().orEmpty().mapNotNull { f ->
            when {
                f.name.endsWith(".audio") -> {
                    val id = f.name.removeSuffix(".audio")
                    AudioCacheEntry(
                        id, f.length(), meta[id]?.totalBytes ?: f.length(),
                        complete = true, f.lastModified(),
                        meta[id]?.lastAccessedMs ?: f.lastModified()
                    )
                }

                f.name.endsWith(".part") -> {
                    val id = f.name.removeSuffix(".part")
                    AudioCacheEntry(
                        id, f.length(), meta[id]?.totalBytes ?: 0L,
                        complete = false, f.lastModified(),
                        meta[id]?.lastAccessedMs ?: f.lastModified()
                    )
                }

                else -> null
            }
        }
    }

    // ─────────────────────────── 元数据持久化 ───────────────────────────

    private fun metaFile(d: File) = File(d, META_FILE)

    private fun loadMeta(d: File) {
        meta.clear()
        runCatching {
            val f = metaFile(d)
            if (!f.exists()) return
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                meta[id] = Meta(
                    id = id,
                    upstreamUrl = o.optString("url"),
                    totalBytes = o.optLong("total"),
                    lastAccessedMs = o.optLong("at")
                )
            }
        }
    }

    private fun saveMeta(d: File) {
        runCatching {
            val arr = JSONArray()
            meta.values.forEach {
                arr.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("url", it.upstreamUrl)
                        put("total", it.totalBytes)
                        put("at", it.lastAccessedMs)
                    }
                )
            }
            // 原子写：先写临时文件再改名，避免写一半掉电留下坏 JSON
            val target = metaFile(d)
            val tmp = File(d, "$META_FILE.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private data class Meta(
        val id: String,
        val upstreamUrl: String,
        val totalBytes: Long,
        val lastAccessedMs: Long
    )

    companion object {
        private const val DIR = "audio_cache"
        private const val META_FILE = "index.json"

        /**
         * 由上游 URL 派生资源 id。
         *
         * 用 SHA-256 前 16 字节而不是 URL 本身：URL 里含 `%E3%81%82...`
         * 这类百分号编码的日文，做文件名在多数文件系统上能用但容易踩编码坑
         * （参考项目的做法是把 `/` 换成 `_` 直接用 server hash，我们这里
         * 统一用哈希，两类来源都安全）。
         */
        fun idFor(upstreamUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(upstreamUrl.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 16) sb.append("%02x".format(digest[i]))
            return sb.toString()
        }
    }
}

/**
 * 音频缓存的状态快照 —— 设置页展示用。
 *
 * 刻意把"总数"和"其中过期可回收的"分开：[stalePartialCount] 是
 * "试听几秒就划走"留下的残渣，用户看到"有 37 个未完成缓存"时才明白
 * 空间被什么占了。只报一个总占用数字是说不清的。
 */
data class AudioCacheStatus(
    val usedBytes: Long = 0L,
    val completeCount: Int = 0,
    val partialCount: Int = 0,
    /** 其中已超过 7 天未增长、可以回收的 */
    val stalePartialCount: Int = 0,
    val updatedAt: Long = 0L
) {
    val totalCount: Int get() = completeCount + partialCount

    val usedText: String get() = formatBytes(usedBytes)

    /** 一句话描述占用构成；没有任何缓存时返回空串 */
    val compositionText: String
        get() = when {
            totalCount == 0 -> ""
            partialCount == 0 -> "$completeCount 首已缓存"
            completeCount == 0 -> "$partialCount 首未缓存完"
            else -> "$completeCount 首已缓存 · $partialCount 首未缓存完"
        }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
