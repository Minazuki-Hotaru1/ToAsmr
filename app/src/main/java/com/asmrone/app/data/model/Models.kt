package com.asmrone.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * asmr.one（Kikoeru v2）数据模型 —— 字段以**真实接口实测结果**为准。
 *
 * 实测结论（2026-10，经代理直连 api.asmr.one）：
 *  - `/api/works`、`/api/search/{kw}` → `{ works: [...], pagination: {...} }`
 *  - `/api/work/{id}?v=2` → **只返回元数据，不含 children**；
 *    评分字段是 `rate_average_2dp`，声优 `vas[].id` 是 UUID **字符串**，
 *    标签 `tags[].i18n["zh-cn"].name` 带官方中文名。
 *  - `/api/tracks/{id}` → **根节点数组**，节点类型 `folder` / `audio` / `image` / `text`；
 *    `folder` 只有 `title` + `children`，音频叶子带 `hash`、`size`、`duration`（秒，浮点）、
 *    `mediaStreamUrl`（绝对地址，指向 CDN）、`mediaDownloadUrl`、`streamLowQualityUrl`。
 *  - 所有节点都**没有 id**，因此树里用「路径」作为唯一键（与 KikoFlu 的做法一致）。
 */

/** 读取可空字符串：JSON 里为 null / 缺失 / 空串时统一返回 null */
private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** 读取可空 Long */
private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

/** 读取可空 Double */
private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

/**
 * 标签（带官方 i18n 名称，优先显示中文）。
 *
 * ⚠️ 这是**详情页标签墙**用的轻量形态：只有"原名 + 中文译名"两个字符串。
 * 搜索需要更完整的结构（含和谐名与旧名），那是 [SearchTag] +
 * [TagNameResolver]。两处的显示规则一致（译名优先、缺失则回落原名），
 * 但这里没有和谐名分支 —— 详情页不做那个设置。
 */
data class TagItem(
    val id: Int,
    val name: String,
    val zhName: String? = null,
    val count: Int? = null
) {
    /** 展示名：有官方中文就用中文，否则用原名 */
    val displayName: String get() = zhName?.takeIf { it.isNotBlank() } ?: name
}

/** 通用「id + 名称」条目；注意声优 id 是 UUID 字符串 */
data class NamedItem(
    val id: String,
    val name: String
)

/** 评分分布里的一档（5★ / 4★ …） */
data class RateCountDetail(
    val reviewPoint: Int,
    val count: Int,
    val ratio: Int
) {
    companion object {
        fun listFrom(array: JSONArray?): List<RateCountDetail> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        RateCountDetail(
                            reviewPoint = o.optInt("review_point", 0),
                            count = o.optInt("count", 0),
                            ratio = o.optInt("ratio", 0)
                        )
                    )
                }
            }
        }
    }
}

/**
 * 曲目 / 文件树节点。
 *
 * asmr.one 的作品就是一个**压缩包目录树**：根节点通常是 `mp3` / `wav` 这类音质文件夹，
 * 里面可能再套 `01_wav`、`03_omake/02_mp3（音声のみ）` 之类的子目录，
 * 最内层才是 `audio`；同级还会有 `image` / `text` 文件。
 *
 * [path] 由父路径 + 标题逐级拼接而成，是树里的唯一键（接口不提供 id）。
 */
data class Track(
    val title: String,
    val type: String? = null,
    val hash: String? = null,
    val size: Long? = null,
    val durationSec: Double? = null,
    val mediaStreamUrl: String? = null,
    val mediaDownloadUrl: String? = null,
    val streamLowQualityUrl: String? = null,
    val children: List<Track> = emptyList(),
    /** 树内唯一路径，如 `03_omake/02_mp3（音声のみ）/01_xxx.mp3` */
    val path: String = ""
) {
    val isFolder: Boolean get() = type == "folder" || (type == null && children.isNotEmpty())
    val isAudio: Boolean get() = type == "audio"
    val isImage: Boolean get() = type == "image"
    val isText: Boolean get() = type == "text"
    val isVideo: Boolean get() = type == "video"

    /** 可以点开播放（有音频地址） */
    val isPlayable: Boolean
        get() = (isAudio || (!isFolder && mediaStreamUrl != null)) && !mediaStreamUrl.isNullOrBlank()

    /** 当前节点之下的音频数量（文件夹折叠时显示） */
    fun audioCount(): Int =
        if (isAudio) 1 else children.sumOf { it.audioCount() }

    /** 当前节点之下的文件总数 */
    fun fileCount(): Int =
        if (children.isEmpty()) 1 else children.sumOf { it.fileCount() }

    /** 子树总大小（字节） */
    fun subtreeSize(): Long =
        if (children.isEmpty()) (size ?: 0L) else children.sumOf { it.subtreeSize() }

    /** 按树的原有顺序展开出所有音频 */
    fun collectAudio(): List<Track> {
        val out = mutableListOf<Track>()
        fun walk(node: Track) {
            if (node.isAudio) out.add(node)
            node.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    val durationText: String
        get() = durationSec?.let { Work.formatDuration(it.toLong()) } ?: ""

    val sizeText: String
        get() = size?.let { Work.formatBytes(it) } ?: ""

    companion object {
        fun listFrom(array: JSONArray?, parentPath: String = ""): List<Track> {
            if (array == null) return emptyList()
            // 同级重名 / 空名去重：保证整棵树里 path 唯一
            // （path 同时被用作 LazyColumn 的 key，见 ui/component/FileTree.kt）
            val usedSegments = HashMap<String, Int>()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val title = (o.str("title") ?: o.str("name") ?: "").ifBlank { "未命名" }
                    val seen = usedSegments.getOrDefault(title, 0)
                    usedSegments[title] = seen + 1
                    val segment = if (seen == 0) title else "$title ($seen)"
                    add(fromJson(o, parentPath, title, segment))
                }
            }
        }

        /**
         * @param title 已解析好的显示名（[listFrom] 传入，避免重复解析）
         * @param segment 已去重的路径片段；为空时用 [title]
         */
        fun fromJson(
            o: JSONObject,
            parentPath: String = "",
            title: String? = null,
            segment: String? = null
        ): Track {
            // 接口的 title 在个别自建服务端上叫 name
            val resolvedTitle = title
                ?: (o.str("title") ?: o.str("name") ?: "").ifBlank { "未命名" }
            val seg = segment ?: resolvedTitle
            val path = if (parentPath.isEmpty()) seg else "$parentPath/$seg"
            return Track(
                title = resolvedTitle,
                type = o.str("type"),
                hash = o.str("hash"),
                size = o.longOrNull("size"),
                durationSec = o.doubleOrNull("duration"),
                mediaStreamUrl = o.str("mediaStreamUrl"),
                mediaDownloadUrl = o.str("mediaDownloadUrl"),
                streamLowQualityUrl = o.str("streamLowQualityUrl"),
                children = listFrom(o.optJSONArray("children"), path),
                // ⚠️ 修复：这里原本漏传 path，导致所有节点 path 都是默认的 ""，
                //    文件树用它当 LazyColumn key → 崩溃 "Key \"\" was already used"
                path = path
            )
        }
    }
}

/**
 * 站内**实际收录**的另一个语言版本（接口字段 `other_language_editions_in_db` 的一条），例如：
 * ```json
 * {"id":416817,"lang":"简体中文","title":"…","source_id":"RJ416817","source_type":"DLsite"}
 * ```
 *
 * ⚠️ 注意与 DLsite 的 `language_editions` 的区别：后者描述的是**DLsite 商品家族**
 * （可能包含站内并不存在的版本，例如某作品的中文版只列在家族里、但站内没有收录）。
 * 网页端的「语言版本」链接用的是**站内版本**，所以这里只认
 * `other_language_editions_in_db` —— 与参考项目 KikoFlu 的做法一致。
 */
data class LanguageEdition(
    /** 站内作品 id：可直接跳转到该语言版本的详情页 */
    val workId: Int,
    /** DLsite 作品号，如 RJ416817 */
    val sourceId: String,
    /** 展示名（接口的 `lang` 字段本身就是中文名，如「简体中文」「繁體中文」「English」） */
    val label: String,
    /** 该版本的标题 */
    val title: String? = null
)

/** 作品（音声） */
data class Work(
    val id: Int,
    val title: String,
    /** DLsite 作品号，如 RJ243448（详情页顶栏显示它） */
    val sourceId: String? = null,
    val circleId: Int? = null,
    val circleName: String? = null,
    val vas: List<NamedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val release: String? = null,
    val createDate: String? = null,
    val dlCount: Int = 0,
    val price: Int = 0,
    val reviewCount: Int = 0,
    /** 平均评分（接口字段 rate_average_2dp） */
    val rate: Double = 0.0,
    val rateCount: Int = 0,
    val rateCountDetail: List<RateCountDetail> = emptyList(),
    val hasSubtitle: Boolean = false,
    val durationSec: Int = 0,
    val nsfw: Boolean = false,
    /** "general" / "adult" */
    val ageCategory: String? = null,
    val sourceUrl: String? = null,
    val description: String? = null,
    val samCoverUrl: String? = null,
    val thumbnailCoverUrl: String? = null,
    val mainCoverUrl: String? = null,
    /** 来自 /api/tracks/{id}，与元数据分开获取 */
    val tracks: List<Track> = emptyList(),
    /** 站内**实际收录**的其它语言版本（`other_language_editions_in_db`） */
    val languageEditions: List<LanguageEdition> = emptyList(),
    /**
     * 当前登录用户对这个作品的收藏状态（接口字段 `progress`）。
     *
     * 取值 `marked` / `listening` / `listened` / `replay` / `postponed`，
     * 未登录或未标记时为 null。解析成枚举见 [CollectionStatus.fromApi]。
     */
    val progress: String? = null,
    /** 当前登录用户给出的评分（接口字段 `userRating`，1~5 星），未评分为 null */
    val userRating: Int? = null
) {
    /** 收藏状态枚举；无法识别时为 null（界面按"未标记"处理） */
    val collectionStatus: CollectionStatus? get() = CollectionStatus.fromApi(progress)
    /**
     * 是否显示「多语种」：站内确实存在这部作品的其它语言版本时才算。
     *
     * 只看 `other_language_editions_in_db`，不看 DLsite 的 `language_editions` ——
     * 后者会把「站内没有的版本」也算进来（网页端同样不显示）。
     */
    val hasMultipleLanguages: Boolean get() = languageEditions.isNotEmpty()
    /**
     * 封面地址（永远给一个 Coil 能直接取的**绝对**地址）。
     *
     * 服务端各端点回给我们的是三种形态混着来：绝对的 `https://…/media/…`、
     * 网页端用的绝对地址 `https://asmr.one/media/…`、以及相对路径 `/media/…`。
     * 后两种交给 Coil 只会得到"图片永远加载不出来"（相对 Uri 无法请求；
     * 网页域名在很多网络下压根连不上，而 API 主机是通的）。
     * 所以在取值这一处统一收敛（见 [resolveMediaUrl]），调用方只管拿 URL。
     */
    fun coverUrl(host: String): String {
        val base = host.trimEnd('/')
        val explicit = mainCoverUrl?.takeIf { it.isNotBlank() }
            ?: samCoverUrl?.takeIf { it.isNotBlank() }
            ?: thumbnailCoverUrl?.takeIf { it.isNotBlank() }
            ?: return "$base/api/cover/$id"
        return resolveMediaUrl(explicit, base)
    }

    val durationText: String get() = formatDuration(durationSec.toLong())
    val vaText: String get() = vas.joinToString(" / ") { it.name }
    val isAdult: Boolean get() = nsfw || ageCategory == "adult"

    /** 全部音频（按原树顺序） */
    fun allAudio(): List<Track> = tracks.flatMap { it.collectAudio() }

    companion object {
        fun formatDuration(totalSeconds: Long): String {
            if (totalSeconds <= 0) return "--:--"
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1 -> "%.2f GB".format(gb)
                mb >= 1 -> "%.1f MB".format(mb)
                else -> "%.0f KB".format(kb)
            }
        }

        fun listFrom(array: JSONArray?, host: String = ""): List<Work> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(fromJson(it, host)) }
                }
            }
        }

        fun fromJson(o: JSONObject, host: String = ""): Work {
            // 收藏列表（/api/review）的条目可能是「评论对象」而不是作品本身：
            //   { "work_id": 123, "progress": "marked", "work": { …作品字段… } }
            // 有嵌套的 work 就用它，外层只用来取收藏状态。作品详情/列表接口没有这一层，不受影响。
            val nested = o.optJSONObject("work")
            if (nested != null) {
                return fromJson(nested, host).copy(
                    progress = o.str("progress") ?: nested.str("progress"),
                    userRating = o.optInt("userRating", 0).takeIf { it > 0 }
                        ?: o.optInt("user_rating", 0).takeIf { it > 0 }
                        ?: nested.optInt("userRating", 0).takeIf { it > 0 }
                )
            }
            val sourceId = o.str("source_id")
            val circleObj = o.optJSONObject("circle")
            val detail = RateCountDetail.listFrom(o.optJSONArray("rate_count_detail"))
            val derivedRate = if (detail.isNotEmpty()) {
                val total = detail.sumOf { it.count }
                if (total > 0) detail.sumOf { it.reviewPoint * it.count }.toDouble() / total else 0.0
            } else 0.0
            // 接口给的是 rate_average_2dp；自建服务端可能只给 rate
            val rate = o.optDouble("rate_average_2dp", 0.0).takeIf { it > 0 }
                ?: o.optDouble("rate", 0.0).takeIf { it > 0 }
                ?: derivedRate

            return Work(
                // 收藏条目可能只给 work_id 而不给 id
                id = o.optInt("id", 0).takeIf { it > 0 } ?: o.optInt("work_id", 0),
                title = o.optString("title", "").ifBlank { o.optString("name", "未命名作品") },
                sourceId = sourceId,
                circleId = circleObj?.optInt("id") ?: if (o.has("circle_id")) o.optInt("circle_id") else null,
                circleName = circleObj?.let { c -> if (c.isNull("name")) null else c.optString("name") }
                    ?.takeIf { it.isNotBlank() }
                    ?: o.str("circle_name")
                    ?: o.str("name"),
                vas = namedList(o.optJSONArray("vas")),
                tags = tagList(o.optJSONArray("tags")),
                release = o.str("release"),
                createDate = o.str("create_date"),
                dlCount = o.optInt("dl_count", 0),
                price = o.optInt("price", 0),
                reviewCount = o.optInt("review_count", 0),
                rate = rate,
                rateCount = o.optInt("rate_count", 0).takeIf { it > 0 } ?: detail.sumOf { it.count },
                rateCountDetail = detail,
                hasSubtitle = o.optBoolean("has_subtitle", false),
                durationSec = o.optInt("duration", 0),
                nsfw = o.optBoolean("nsfw", false),
                ageCategory = o.str("age_category_string"),
                sourceUrl = o.str("source_url"),
                description = o.str("description"),
                samCoverUrl = o.str("samCoverUrl") ?: o.str("sam_cover_url"),
                thumbnailCoverUrl = o.str("thumbnailCoverUrl") ?: o.str("thumbnail_cover_url"),
                mainCoverUrl = o.str("mainCoverUrl") ?: o.str("main_cover_url"),
                languageEditions = languageEditionList(o.optJSONArray("other_language_editions_in_db"), o.optInt("id", 0)),
                // 收藏状态：只有登录后带令牌请求时服务端才会返回这两个字段
                progress = o.str("progress"),
                userRating = o.optInt("userRating", 0).takeIf { it > 0 }
                    ?: o.optInt("user_rating", 0).takeIf { it > 0 }
            )
        }

        /**
         * 解析**站内实际收录**的其它语言版本（`other_language_editions_in_db`）。
         *
         * 只认这个字段：DLsite 的 `language_editions` 描述的是商品家族，
         * 会把站内并不存在的版本也列进来（网页端同样不显示那些）。
         *
         * @param currentWorkId 当前作品 id，用于过滤掉万一出现的"自己"
         */
        private fun languageEditionList(
            array: JSONArray?,
            currentWorkId: Int
        ): List<LanguageEdition> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val workId = o.optInt("id", 0)
                    if (workId <= 0 || workId == currentWorkId) continue
                    val sourceId = o.optString("source_id", "")
                    add(
                        LanguageEdition(
                            workId = workId,
                            sourceId = sourceId,
                            // 接口的 lang 本身就是展示名（简体中文 / 繁體中文 / English …）
                            label = o.optString("lang", "").ifBlank { sourceId },
                            title = o.optString("title", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        private fun namedList(array: JSONArray?): List<NamedItem> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val name = o.optString("name", "")
                    if (name.isNotBlank()) add(NamedItem(o.opt("id")?.toString() ?: "", name))
                }
            }
        }

        private fun tagList(array: JSONArray?): List<TagItem> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val name = o.optString("name", "")
                    if (name.isNotBlank()) {
                        val zh = o.optJSONObject("i18n")
                            ?.optJSONObject("zh-cn")
                            ?.let { cn -> if (cn.isNull("name")) null else cn.optString("name") }
                            ?.takeIf { it.isNotBlank() }
                        add(TagItem(o.optInt("id", i), name, zh, o.optInt("count", 0)))
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 播放队列与音质分支 ───────────────────────────

/**
 * 一个可播放的「音质分支」。
 *
 * 真实作品里同一个音声往往同时提供 `mp3` 与 `wav` 两套目录（甚至
 * `03_omake/01_wav（音声のみ）` 这种再套一层的备份），因此播放前需要选一条分支，
 * 而不是把所有音频混在一起播。
 */
data class AudioFormatOption(
    val label: String,
    val tracks: List<Track>
) {
    val totalSize: Long get() = tracks.sumOf { it.size ?: 0L }
    val totalDurationSec: Double get() = tracks.sumOf { it.durationSec ?: 0.0 }
}

/** 音质偏好（持久化在设置里） */
enum class AudioFormatPreference(val value: String, val label: String) {
    MP3("mp3", "MP3（省流量）"),
    M4A("m4a", "M4A"),
    WAV("wav", "WAV（无损）"),
    FLAC("flac", "FLAC"),
    ALL("", "全部");

    companion object {
        fun fromValue(value: String?): AudioFormatPreference =
            entries.firstOrNull { it.value == value } ?: MP3
    }
}

/**
 * 把文件树拆成若干「音质分支」，供详情页切换与播放器使用。
 *
 * 规则（对齐原站/客户端的习惯用法）：
 *  1. 逐个顶层节点收集其子树里的音频；
 *  2. 顶层节点本身就是音频时，归入「根目录」分支；
 *  3. 只有存在多个含音频的分支时才提供切换（否则直接给单一分支）。
 */
fun List<Track>.audioFormatOptions(): List<AudioFormatOption> {
    val options = mutableListOf<AudioFormatOption>()
    val looseAudio = mutableListOf<Track>()

    this.forEach { root ->
        val audio = root.collectAudio()
        if (audio.isEmpty()) return@forEach
        if (root.isAudio) looseAudio.addAll(audio) else options.add(AudioFormatOption(root.title, audio))
    }
    if (looseAudio.isNotEmpty()) options.add(0, AudioFormatOption("根目录", looseAudio))

    // 补一个「全部」选项，方便整部连播（多分支时才需要）
    if (options.size > 1) {
        return options + AudioFormatOption("全部", options.flatMap { it.tracks })
    }
    return options
}

/**
 * 按偏好挑出默认播放分支：优先匹配格式名（mp3/m4a/wav/flac），
 * 匹配不到就挑体积最小的分支（最省流量），最后兜底用第一个。
 */
fun List<AudioFormatOption>.pickPreferred(preference: AudioFormatPreference): AudioFormatOption? {
    if (isEmpty()) return null
    if (preference == AudioFormatPreference.ALL) {
        return firstOrNull { it.label == "全部" } ?: first()
    }
    val key = preference.value
    return firstOrNull { it.label.lowercase().contains(key) }
        ?: firstOrNull { it.label == "全部" }
        ?: minByOrNull { it.totalSize }
        ?: first()
}

// ─────────────────────────── 列表分页与首页板块 ───────────────────────────

/** 分页结果（`pagination` 字段） */
data class PageResult<T>(
    val items: List<T>,
    val currentPage: Int,
    val pageSize: Int,
    val totalCount: Int
) {
    val hasMore: Boolean
        get() = currentPage * pageSize < totalCount

    /**
     * 换一份 items，其余分页信息保留。
     *
     * 主要给"从本地缓存还原"用：分页由缓存记着，
     * 但 items 要换成界面能直接消费的形态（见 CachedWork.toWork）。
     */
    fun withItems(newItems: List<T>): PageResult<T> = copy(items = newItems)
}

/**
 * 首页的三个板块 —— 与网站的入口一一对应：
 *
 * | 板块 | 网站 | 接口 |
 * |---|---|---|
 * | [ALL] | `asmr.one/works?page=N` | `GET  /api/works?order=create_date&sort=desc` |
 * | [POPULAR] | `asmr.one/popular?page=N` | `POST /api/recommender/popular`（站点上限 100 部） |
 * | [RECOMMEND] | `asmr.one/recommend?page=N` | `POST /api/recommender/recommend-for-user`（需 recommenderUuid） |
 *
 * 三者都**不需要登录**即可访问（实测：推荐接口带一个 UUID 就返回结果，
 * 不带才会 401）。
 */
enum class HomeBoard(
    val id: String,
    val label: String,
    /** 板块说明，显示在列表首行 —— **保持简短**（四字左右即可） */
    val description: String
) {
    ALL("all", "所有内容", "所有作品"),
    POPULAR("popular", "热门作品", "热门作品"),
    RECOMMEND("recommend", "推荐作品", "推荐作品");

    companion object {
        fun fromId(id: String?): HomeBoard = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * 作品列表可用的排序方式（`/api/works` 的 `order` 参数）。
 *
 * ⚠️ 目前**没有任何 UI 在用**（搜索页的排序选项已按需求移除，首页板块固定用
 * `create_date`）。保留这组常量是为了让"接口支持哪些 order"这件事有据可查 ——
 * 尤其是 `NSFW`：它是**排序**（全年龄 ↔ 18禁），不是过滤。
 *
 * 曾经这里还列了 `price` 与 `duration`，但实测网站前端只支持
 * `release / create_date / dl_count / rate / review_count / id / nsfw / random`，
 * 那两个大概率是无效参数，已移除。
 */
enum class WorkOrder(val apiValue: String, val label: String) {
    RELEASE("release", "发行日期"),
    CREATE_DATE("create_date", "收录日期"),
    DL_COUNT("dl_count", "销量"),
    RATE("rate", "评分"),
    REVIEW_COUNT("review_count", "评论数"),
    ID("id", "收录顺序"),
    /** 按年龄分级排序：`sort=asc` → 全年龄在前，`desc` → 18禁在前 */
    NSFW("nsfw", "年龄分级"),
    RANDOM("random", "随机");

    companion object {
        fun fromApi(value: String?): WorkOrder =
            entries.firstOrNull { it.apiValue == value } ?: RELEASE
    }
}

/** 网页端的域名：这些 host 上的 `/media/`、`/api/` 与 API 主机同一套资源 */
private val WEB_MEDIA_HOSTS = setOf("asmr.one", "www.asmr.one", "beta.asmr.one")

/**
 * 把任意形态的媒体地址收敛成**当前主机上能取到**的绝对地址。
 *
 * 接口给我们的地址三种形态混着来，直接丢给 Coil 会出现"首页有图、搜索结果全白"这种事：
 *
 *  1. 已经是绝对地址且指向别处 → 原样保留；
 *  2. 相对路径 `/media/xxx/main` → 拼当前主机。相对 Uri 在 Coil 里压根没法请求；
 *  3. 指向网页域名（`https://asmr.one/media/…`）→ 把 origin 换成 API 主机。
 *     网页域名在不少网络环境下连不上，而 API 主机是通的；且这两类路径本就由 API 主机提供。
 *
 * 协议相对的 `//host/path` 按 https 补齐后走同一套判断；非 http(s) 的 scheme（`data:`、
 * `file:` 之类）一律原样返回，不能乱拼。
 */
internal fun resolveMediaUrl(value: String, base: String): String {
    val trimmed = value.trim()
    val root = base.trim()
    if (trimmed.isEmpty()) return root
    val absolute = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.contains("://") -> trimmed
        // 相对地址：以 / 开头直接拼主机，否则补一个分隔符
        trimmed.startsWith("/") -> return root + trimmed
        else -> return "$root/$trimmed"
    }
    val schemeEnd = absolute.indexOf("://")
    val scheme = absolute.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return absolute
    val rest = absolute.substring(schemeEnd + 3)
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "" else rest.substring(slash)
    val hostName = authority.substringBefore(':').lowercase()
    val needsApiHost = hostName in WEB_MEDIA_HOSTS &&
        (path.startsWith("/media/") || path.startsWith("/api/"))
    return if (needsApiHost) root + path else absolute
}
