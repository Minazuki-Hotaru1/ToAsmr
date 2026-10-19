package com.asmrone.app.data.download

import com.asmrone.app.data.cache.TrackJson
import com.asmrone.app.data.model.Track
import org.json.JSONObject

/**
 * 一个已经落盘的音频文件。
 *
 * [path] 是**树内路径**（与 `Track.path` 一致），它是树里的唯一键，
 * 也是"这份清单覆盖了哪些内容"的唯一依据 —— 详情页要靠它过滤出已下载的部分。
 */
data class DownloadedFile(
    val path: String,
    /** 作品目录内的文件名（不含目录）。由 [DownloadStore.fileNameFor] 确定性生成 */
    val fileName: String,
    val bytes: Long,
    val durationSec: Double? = null
)

/**
 * 一部作品的**下载清单**。
 *
 * ## 为什么要连完整文件树一起存
 *
 * 「只下载了一部分」是常态（一部作品里通常同时有 mp3 与 wav 两套）。
 * 如果清单里只记已下载的文件，下次打开选择界面就**没有树可选**了 ——
 * 要么重新联网拉 6MB 的树，要么没法再补下载。所以这里存整棵树：
 * 清单既是"下载状态"，也是这部作品的离线元数据。
 *
 * [workJson] 是 `/api/work/{id}?v=2` 的**原始响应**（不是序列化后的 `Work`）。
 * 理由与 [com.asmrone.app.data.cache.WorkMetaStore] 一致：`Work` 没有 `toJson`，
 * 解析规则只有 `Work.fromJson` 一处，存原文才不会出现"存进去的字段和读出来的对不上"。
 */
data class DownloadManifest(
    val workId: Int,
    val host: String,
    val savedAt: Long,
    val workJson: String?,
    /** 完整文件树（不是只有已下载的那些） */
    val tree: List<Track>,
    /** 已下载的文件，按树内路径索引 */
    val files: Map<String, DownloadedFile>
) {
    val fileCount: Int get() = files.size

    val totalBytes: Long get() = files.values.sumOf { it.bytes }

    val isEmpty: Boolean get() = files.isEmpty()

    /** 已下载的树内路径集合（详情页过滤用） */
    val paths: Set<String> get() = files.keys

    /** 新增/更新一个已下载文件（刷新 [savedAt]，让下载页按"最近下载"排序） */
    fun withFile(file: DownloadedFile): DownloadManifest = copy(
        files = files + (file.path to file),
        savedAt = System.currentTimeMillis()
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", SCHEMA)
        put("workId", workId)
        put("host", host)
        put("savedAt", savedAt)
        workJson?.takeIf { it.isNotBlank() }?.let { put("work", it) }
        put("tree", TrackJson.encodeTree(tree))
        put(
            "files",
            JSONObject().apply {
                files.forEach { (path, file) ->
                    put(
                        path,
                        JSONObject().apply {
                            put("n", file.fileName)
                            put("b", file.bytes)
                            file.durationSec?.let { put("d", it) }
                        }
                    )
                }
            }
        )
    }

    companion object {
        const val SCHEMA = 1

        fun fromJson(o: JSONObject): DownloadManifest? {
            if (o.optInt("v", 0) != SCHEMA) return null
            val workId = o.optInt("workId", 0)
            if (workId <= 0) return null
            val tree = runCatching { TrackJson.decodeTree(o.optJSONArray("tree")) }
                .getOrElse { emptyList() }
            val filesJson = o.optJSONObject("files")
            val files = LinkedHashMap<String, DownloadedFile>()
            if (filesJson != null) {
                val keys = filesJson.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val entry = filesJson.optJSONObject(path) ?: continue
                    val name = entry.optString("n")
                    if (name.isBlank()) continue
                    files[path] = DownloadedFile(
                        path = path,
                        fileName = name,
                        bytes = entry.optLong("b", 0L),
                        durationSec = if (entry.has("d") && !entry.isNull("d")) {
                            entry.optDouble("d")
                        } else {
                            null
                        }
                    )
                }
            }
            return DownloadManifest(
                workId = workId,
                host = o.optString("host"),
                savedAt = o.optLong("savedAt", 0L),
                workJson = o.optString("work").ifBlank { null },
                tree = tree,
                files = files
            )
        }

        /** 空清单（还没有任何文件落盘时的初始值） */
        fun empty(workId: Int, host: String, workJson: String?, tree: List<Track>) = DownloadManifest(
            workId = workId,
            host = host,
            savedAt = System.currentTimeMillis(),
            workJson = workJson,
            tree = tree,
            files = emptyMap()
        )
    }
}

/** 递归收集所有**叶子文件**的路径（文件夹本身不是可下载项） */
internal fun collectLeafPaths(nodes: List<Track>, out: MutableList<String> = ArrayList()): List<String> {
    nodes.forEach { node ->
        if (node.children.isEmpty()) {
            if (node.path.isNotBlank()) out.add(node.path)
        } else {
            collectLeafPaths(node.children, out)
        }
    }
    return out
}

/**
 * 把树裁剪成"只剩 [keep] 里的文件"。
 *
 * 文件夹**只要有一个后代被保留就保留**（并递归裁剪它的子节点）——
 * 否则详情页会出现"下载了 `mp3/01.mp3`，但 `mp3/` 这个文件夹不见了"的怪象。
 * 顺序保持原树的顺序，不做重排。
 */
internal fun filterTreeTo(nodes: List<Track>, keep: Set<String>): List<Track> = buildList {
    nodes.forEach { node ->
        if (node.children.isEmpty()) {
            if (node.path in keep) add(node)
        } else {
            val children = filterTreeTo(node.children, keep)
            if (children.isNotEmpty()) add(node.copy(children = children))
        }
    }
}
