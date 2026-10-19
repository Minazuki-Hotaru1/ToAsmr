package com.asmrone.app.data.cache

import android.content.Context
import com.asmrone.app.data.model.NamedItem
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.Work
import org.json.JSONArray
import org.json.JSONObject

/**
 * 缓存里的**精简作品**。
 *
 * 只保留收藏/标记列表要显示的字段。接口返回的完整 `Work` 带
 * `i18n` 标签翻译表（每部约 4.8KB），而这里每部约 250 字节 —— **小 20 倍**，
 * 这才是能放心把上千条收藏写进本地的原因。
 *
 * 站点的「我的收藏」（`/api/review`）实际能拿到的展示信息就是：
 * 社团名、声优名、我的评分、收藏状态，以及年龄分级（`nsfw` / `age_category_string`）。
 * 标签虽然也在返回里，但收藏页按需求**不显示**，所以不进缓存 ——
 * 每条最多 6 个声优名，仍然是小体积。
 *
 * 注意：这不是"另一个数据源"，只是**同一份云端数据的本地副本**，
 * 所以缺字段时一律留空由界面兜底，绝不去猜。
 */
data class CachedWork(
    val id: Int,
    val title: String,
    val circleName: String? = null,
    val durationSec: Int = 0,
    val sourceId: String? = null,
    val coverUrl: String? = null,
    val hasSubtitle: Boolean = false,
    /** 收藏状态（`marked` / `listening` / …），未标记为 null */
    val progress: String? = null,
    /** 我的评分 1~5，未评为 null */
    val userRating: Int? = null,
    /**
     * 18 禁标记（接口字段 `nsfw`）。
     *
     * ⚠️ 必须和 [ageCategory] 一起存：卡片左上角的「全年龄」角标由
     * `Work.isAdult = nsfw || ageCategory == "adult"` 决定。
     * 不存的话还原出来永远是 `false` —— 整个收藏夹都会被误标成「全年龄」。
     */
    val nsfw: Boolean = false,
    /** 年龄分级原文（`general` / `adult`） */
    val ageCategory: String? = null,
    /** 声优名（只存显示用的名字，上限 [MAX_CREDITS] 条） */
    val vas: List<String> = emptyList()
) {
    /**
     * 还原成 [Work] 以便直接喂给现有的卡片组件。
     *
     * 卡片真正读的是：标题 / 封面 / 社团 / 声优 / 年龄分级 / 收听进度 / 我的评分 ——
     * 这些都还原出来；标签留空，收藏页本来也不显示。
     */
    fun toWork(): Work = Work(
        id = id,
        title = title,
        circleName = circleName,
        durationSec = durationSec,
        sourceId = sourceId,
        mainCoverUrl = coverUrl,
        hasSubtitle = hasSubtitle,
        progress = progress,
        userRating = userRating,
        nsfw = nsfw,
        ageCategory = ageCategory,
        vas = vas.map { NamedItem(id = "", name = it) }
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("circle", circleName ?: "")
        put("duration", durationSec)
        put("sourceId", sourceId ?: "")
        put("cover", coverUrl ?: "")
        put("subtitle", hasSubtitle)
        put("progress", progress ?: "")
        put("rating", userRating ?: 0)
        put("nsfw", nsfw)
        put("age", ageCategory ?: "")
        if (vas.isNotEmpty()) put("vas", JSONArray().apply { vas.forEach { put(it) } })
    }

    companion object {
        /** 每部最多缓存多少声优名 */
        const val MAX_CREDITS = 6

        fun fromWork(work: Work): CachedWork = CachedWork(
            id = work.id,
            title = work.title,
            circleName = work.circleName,
            durationSec = work.durationSec,
            sourceId = work.sourceId,
            coverUrl = work.mainCoverUrl,
            hasSubtitle = work.hasSubtitle,
            progress = work.progress,
            userRating = work.userRating,
            nsfw = work.nsfw,
            ageCategory = work.ageCategory,
            vas = work.vas.take(MAX_CREDITS).map { it.name }
        )

        fun fromJson(o: JSONObject): CachedWork? {
            val id = o.optInt("id", 0)
            // 没有 id 的记录无法用于任何操作，直接丢掉
            if (id <= 0) return null
            return CachedWork(
                id = id,
                title = o.optString("title"),
                circleName = o.optString("circle", "").takeIf { it.isNotBlank() },
                durationSec = o.optInt("duration", 0),
                sourceId = o.optString("sourceId", "").takeIf { it.isNotBlank() },
                coverUrl = o.optString("cover", "").takeIf { it.isNotBlank() },
                hasSubtitle = o.optBoolean("subtitle", false),
                progress = o.optString("progress", "").takeIf { it.isNotBlank() },
                userRating = o.optInt("rating", 0).takeIf { it > 0 },
                nsfw = o.optBoolean("nsfw", false),
                ageCategory = o.optString("age", "").takeIf { it.isNotBlank() },
                vas = stringList(o.optJSONArray("vas"))
            )
        }

        private fun stringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optString(i, "").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        fun listToJson(items: List<CachedWork>): JSONArray =
            JSONArray().apply { items.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray?): List<CachedWork> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { o -> fromJson(o)?.let(::add) }
                }
            }
        }
    }
}

// ─────────────────────────── 各类缓存体的内容 ───────────────────────────

/** 作品列表类缓存（标记页、收藏夹内部的作品） */
data class CachedWorkPage(
    val items: List<CachedWork>,
    val totalCount: Int,
    /** 这一份缓存覆盖到第几页（用于「加载更多」续拉） */
    val loadedPages: Int = 1
)

/** 收藏夹列表缓存 */
data class CachedPlaylists(val items: List<Playlist>)

/** 单个收藏夹的元数据缓存 */
data class CachedPlaylistMeta(val playlist: Playlist)

// ─────────────────────────── 编解码 ───────────────────────────

/** 缓存体的编解码器 —— 让 [SyncCacheStore] 能泛型地读写不同形状的数据 */
interface CacheCodec<T> {
    fun encode(value: T): JSONObject
    fun decode(o: JSONObject): T?
}

object WorkPageCodec : CacheCodec<CachedWorkPage> {
    override fun encode(value: CachedWorkPage): JSONObject = JSONObject().apply {
        put("items", CachedWork.listToJson(value.items))
        put("totalCount", value.totalCount)
        put("loadedPages", value.loadedPages)
    }

    override fun decode(o: JSONObject): CachedWorkPage = CachedWorkPage(
        items = CachedWork.listFromJson(o.optJSONArray("items")),
        totalCount = o.optInt("totalCount", 0),
        loadedPages = o.optInt("loadedPages", 1)
    )
}

object PlaylistsCodec : CacheCodec<CachedPlaylists> {
    override fun encode(value: CachedPlaylists): JSONObject = JSONObject().apply {
        put("items", JSONArray().apply { value.items.forEach { put(it.toJson()) } })
    }

    override fun decode(o: JSONObject): CachedPlaylists {
        val array = o.optJSONArray("items") ?: JSONArray()
        return CachedPlaylists(Playlist.listFrom(array))
    }
}

object PlaylistMetaCodec : CacheCodec<CachedPlaylistMeta> {
    override fun encode(value: CachedPlaylistMeta): JSONObject =
        JSONObject().put("playlist", value.playlist.toJson())

    override fun decode(o: JSONObject): CachedPlaylistMeta? =
        o.optJSONObject("playlist")?.let { CachedPlaylistMeta(Playlist.fromJson(it)) }
}
