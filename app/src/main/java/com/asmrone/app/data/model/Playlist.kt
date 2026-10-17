package com.asmrone.app.data.model

import org.json.JSONObject

/**
 * 播放列表（收藏夹）。
 *
 * 字段与实测的 `/api/playlist/get-playlists` 响应一一对应
 * （2026-10 对着 api.asmr-200.com 实测）：
 * ```jsonc
 * { "id":"b9692823-…", "user_name":"xyz18767", "privacy":0, "locale":"",
 *   "playback_count":0, "name":"__SYS_PLAYLIST_MARKED", "description":"",
 *   "created_at":"2026-04-16 18:19:20", "updated_at":"2026-10-15 14:59:26",
 *   "works_count":0, "latestWorkID":null, "mainCoverUrl":"/statics/no-image.jpg" }
 * ```
 *
 * ⚠️ [id] 是 **UUID 字符串**，不是整数。
 */
data class Playlist(
    val id: String,
    val name: String,
    val userName: String? = null,
    /** 0 = 私享，1 = 不公开，2 = 公开 */
    val privacy: Int = 0,
    val description: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** 收藏夹里的作品数 */
    val worksCount: Int = 0,
    /** 最新加入的作品 id（列表封面用它拼图） */
    val latestWorkId: Int? = null,
    /** 封面相对路径（如 `/statics/no-image.jpg`）或完整 URL */
    val mainCoverUrl: String? = null,
    /** 只有 get-playlist-metadata 才返回：被多少人收藏 */
    val likedCount: Int = 0
) {
    /**
     * 是否为**系统收藏夹**。
     *
     * 服务端用 `__SYS_PLAYLIST_` 前缀标记两个内置收藏夹，它们**不允许删除**，
     * 也不允许改名 —— 与参考实现 `Playlist.isSystemPlaylist` 的判定一致。
     */
    val isSystem: Boolean get() = name.startsWith(SYS_PREFIX)

    /** 界面显示名：系统收藏夹要翻译成中文，普通收藏夹用原名 */
    val displayName: String
        get() = when (name) {
            SYS_MARKED -> "我标记的"
            SYS_LIKED -> "我喜欢的"
            else -> name
        }

    /** 系统收藏夹的说明文案 */
    val descriptionText: String
        get() = when (name) {
            SYS_MARKED -> "默认收藏夹：点收藏按钮时默认加入这里"
            SYS_LIKED -> "我喜欢的内容"
            else -> description.orEmpty()
        }

    /** 隐私设置的展示名 */
    val privacyLabel: String
        get() = when (privacy) {
            0 -> "私享"
            1 -> "不公开"
            else -> "公开"
        }

    /**
     * 封面完整地址。
     *
     * 服务端返回的可能是 `/statics/no-image.jpg` 这种相对路径，
     * 需要拼上主机；已经是 http(s) 开的原样返回。
     */
    fun coverUrl(host: String): String {
        val raw = mainCoverUrl.orEmpty()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.isBlank()) return "$host/api/cover/$latestWorkId"
        return host.trimEnd('/') + raw
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("user_name", userName ?: "")
        put("privacy", privacy)
        put("description", description ?: "")
        put("created_at", createdAt ?: "")
        put("updated_at", updatedAt ?: "")
        put("works_count", worksCount)
        put("latestWorkID", latestWorkId ?: 0)
        put("mainCoverUrl", mainCoverUrl ?: "")
    }

    companion object {
        const val SYS_PREFIX = "__SYS_PLAYLIST_"
        /** 系统收藏夹：我标记的 */
        const val SYS_MARKED = "__SYS_PLAYLIST_MARKED"
        /** 系统收藏夹：我喜欢的 */
        const val SYS_LIKED = "__SYS_PLAYLIST_LIKED"

        fun fromJson(o: JSONObject): Playlist = Playlist(
            id = o.optString("id"),
            name = o.optString("name"),
            userName = o.optString("user_name", "").takeIf { it.isNotBlank() },
            privacy = o.optInt("privacy", 0),
            description = o.optString("description", "").takeIf { it.isNotBlank() },
            createdAt = o.optString("created_at", "").takeIf { it.isNotBlank() },
            updatedAt = o.optString("updated_at", "").takeIf { it.isNotBlank() },
            worksCount = o.optInt("works_count", 0),
            latestWorkId = o.optInt("latestWorkID", 0).takeIf { it > 0 },
            mainCoverUrl = o.optString("mainCoverUrl", "").takeIf { it.isNotBlank() },
            likedCount = o.optInt("liked_count", 0)
        )

        fun listFrom(array: org.json.JSONArray?): List<Playlist> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val p = fromJson(o)
                    // 丢掉没有 id 的损坏记录，避免后续操作全部失败
                    if (p.id.isNotBlank()) add(p)
                }
            }
        }
    }
}

/** 播放列表的分页信息（`{page, pageSize, totalCount}`） */
data class PlaylistPage(
    val playlists: List<Playlist>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int
) {
    val hasMore: Boolean get() = page * pageSize < totalCount
}
