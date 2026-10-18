package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Track] 的 JSON 编解码 —— 给"要落盘的曲目列表"共用。
 *
 * 目前有两处要把曲目写进本地：
 *  - [com.asmrone.app.data.prefs.PlaybackSessionStore]（播放会话快照）
 *  - [TrackTreeStore]（文件树缓存，按 RJ 号）
 *
 * 两处用的是**同一份**字段映射，抽出来避免"存进去的字段和读出来的字段不一致"
 * 这类只在真机上才发现的错位。
 *
 * 只存**播放与匹配歌词需要**的字段：标题、类型、hash、大小、时长、三种地址、路径。
 * 不存 `children` —— 这里存的一律是已拍平的叶子节点列表（播放队列 / 歌词匹配用）。
 */
internal object TrackJson {

    fun encode(tracks: List<Track>): JSONArray = JSONArray().apply {
        tracks.forEach { track ->
            put(
                JSONObject().apply {
                    put("title", track.title)
                    put("type", track.type ?: "audio")
                    track.hash?.takeIf { it.isNotBlank() }?.let { put("hash", it) }
                    track.size?.let { put("size", it) }
                    track.durationSec?.let { put("durationSec", it) }
                    put("mediaStreamUrl", track.mediaStreamUrl ?: "")
                    put("mediaDownloadUrl", track.mediaDownloadUrl ?: "")
                    put("streamLowQualityUrl", track.streamLowQualityUrl ?: "")
                    put("path", track.path)
                }
            )
        }
    }

    fun decode(array: JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                add(trackFrom(o, fallbackPath = "track-$i", defaultType = "audio"))
            }
        }
    }

    // ─────────────────────── 整棵树（文件树缓存用）───────────────────────

    /**
     * 递归编码整棵树，**保留 `path`**。
     *
     * `path` 是树里的唯一键（接口不给节点 id），播放队列与歌词匹配全靠它，
     * 所以它必须原样落盘；读回来时也**不能**用标题重新拼 —— 重拼会改变
     * 历史上已经写进播放队列/会话快照里的键，导致"同一个文件对不上"。
     */
    fun encodeTree(nodes: List<Track>): JSONArray = JSONArray().apply {
        nodes.forEach { node ->
            put(
                JSONObject().apply {
                    put("title", node.title)
                    node.type?.let { put("type", it) }
                    node.hash?.takeIf { it.isNotBlank() }?.let { put("hash", it) }
                    node.size?.let { put("size", it) }
                    node.durationSec?.let { put("durationSec", it) }
                    node.mediaStreamUrl?.takeIf { it.isNotBlank() }?.let { put("mediaStreamUrl", it) }
                    node.mediaDownloadUrl?.takeIf { it.isNotBlank() }?.let { put("mediaDownloadUrl", it) }
                    node.streamLowQualityUrl?.takeIf { it.isNotBlank() }?.let { put("streamLowQualityUrl", it) }
                    put("path", node.path)
                    if (node.children.isNotEmpty()) put("children", encodeTree(node.children))
                }
            )
        }
    }

    fun decodeTree(array: JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                add(
                    trackFrom(o, fallbackPath = "").copy(
                        children = decodeTree(o.optJSONArray("children"))
                    )
                )
            }
        }
    }

    private fun trackFrom(o: JSONObject, fallbackPath: String, defaultType: String? = null): Track = Track(
        title = o.optString("title").trim(),
        type = o.optString("type").ifBlank { defaultType },
        hash = o.optString("hash").ifBlank { null },
        size = if (o.has("size") && !o.isNull("size")) o.optLong("size") else null,
        durationSec = if (o.has("durationSec") && !o.isNull("durationSec")) {
            o.optDouble("durationSec")
        } else {
            null
        },
        mediaStreamUrl = o.optString("mediaStreamUrl").ifBlank { null },
        mediaDownloadUrl = o.optString("mediaDownloadUrl").ifBlank { null },
        streamLowQualityUrl = o.optString("streamLowQualityUrl").ifBlank { null },
        // path 是曲目在树里的唯一键（也是播放队列与歌词匹配的键），
        // 缺了它就没法与音频对上，给一个保底值而不是空串（空串会撞 key）
        path = o.optString("path").ifBlank { fallbackPath }
    )
}
