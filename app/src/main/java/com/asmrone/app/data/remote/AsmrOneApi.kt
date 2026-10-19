package com.asmrone.app.data.remote

import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.model.NamedEntry
import com.asmrone.app.data.model.NamedItem
import com.asmrone.app.data.model.SearchTag
import com.asmrone.app.data.model.PageResult
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.PlaylistPage
import com.asmrone.app.data.model.TagItem
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 连接配置：API 主机 + 可选登录 token + 可选服务端 Cookie。
 *
 * 说明：早期版本支持在 App 内配置 HTTP 代理，现已移除 —— 需要代理时请使用
 * 系统级方案（Android 上的 Clash / v2rayNG 等 VPN 模式），App 内只保留「切换主机」。
 *
 * [token] 来自登录（`POST /api/auth/me` 返回的 `token` 字段），
 * 会以 `Authorization: Bearer <token>` 的形式附加到**所有**请求上；
 * 未登录时为空串，请求退化为匿名访问（公开接口都能用）。
 */
data class ApiConfig(
    val host: String,
    val token: String = "",
    /** 自建服务端可能要求额外的 Cookie（如 Cloudflare 会话），默认为空 */
    val cookie: String = ""
)

/** 接口异常（带一个面向用户的中文说明） */
class AsmrApiException(message: String, val cause2: Throwable? = null) : Exception(message, cause2)

/**
 * 服务端明确拒绝了凭据（HTTP 401 / 403）。
 *
 * 与普通网络错误的区别很重要：拿到这个异常说明**令牌或密码确实不对**，
 * 应该清除本地会话；而超时 / 连不上只说明网络不可用，绝不能因此把用户登出。
 */
class AuthRejectedException(message: String, val statusCode: Int) :
    Exception(message)

/**
 * 收藏夹**不存在**（HTTP 404 且服务端回了 `playlistNotFound`）。
 *
 * ## 为什么要单独建模
 *
 * 这个错误和普通网络失败的处理方式**必须不同**：
 *
 * - 普通失败（超时 / 500）→ 值得重试，所以要记一条"待补写"等下次对齐再试
 * - 收藏夹不存在 → **重试永远不会成功**。再记"待补写"只会留下一条
 *   永久失败的记录，并且每次对齐都多打一次注定失败的请求
 *
 * 实测依据（2026-10，官方站）：
 * ```
 * POST /api/playlist/add-works-to-playlist  {id: <随机 UUID>, works: [...]}
 * → HTTP 404，37 字节，{"error":"playlist.playlistNotFound"}
 * ```
 *
 * 什么时候会遇到：收藏夹在**网页端/另一台设备**被删了，而本地因为
 * "每天只同步一次"仍显示着它，用户这时往里加作品。
 */
class PlaylistNotFoundException(message: String) : Exception(message)

/**
 * asmr.one / Kikoeru 接口客户端。
 *
 * 依赖为零：`HttpURLConnection` + 平台自带 `org.json`（不引入 OkHttp / Retrofit / 序列化库），
 * 这样整个工程可以离线构建，也省掉了额外的二进制体积。
 *
 * 端点（经实测确认）：
 *  - `GET /api/works?page&pageSize&order&sort&seed` → `{ works: [...], pagination: {...} }`
 *  - `GET /api/search/{keyword}?page&pageSize&order&sort` → 同上结构
 *  - `GET /api/work/{id}?v=2` → 作品**元数据**（不含曲目）
 *  - `GET /api/tracks/{id}` → **根节点数组**（folder / audio / image / text 组成的文件树）
 *  - `GET /api/tags/` `GET /api/vas/` `GET /api/circles/`
 *  - `GET /api/cover/{id}` → 封面兜底
 */
class AsmrOneApi(private val configProvider: () -> ApiConfig) {

    private val host: String get() = configProvider().host.trimEnd('/')

    // ─────────────────────────── 作品列表 ───────────────────────────

    /**
     * 作品列表 / 搜索。
     *
     * `order` 支持：`release`（发行日期，默认）/ `create_date` / `dl_count` /
     * `rate` / `review_count` / `id` / `nsfw` / `random`。
     *
     * ⚠️ `nsfw` 是**排序**维度（`asc` → 全年龄在前，`desc` → 18禁在前），
     * 不是过滤维度 —— 接口**没有**"排除 R18"的参数（实测 `nsfw=0`、
     * `age_category=adult` 等 8 个候选参数全都不生效，总数毫无变化）。
     */
    suspend fun getWorks(
        page: Int = 1,
        pageSize: Int = 20,
        order: String = "release",
        sort: String = "desc",
        keyword: String? = null,
        seed: Int = 21,
        tagId: Int? = null,
        vaId: String? = null,
        circleId: Int? = null
    ): PageResult<Work> {
        val query = buildString {
            append("page=").append(page)
            append("&pageSize=").append(pageSize)
            append("&order=").append(order)
            append("&sort=").append(sort)
            append("&seed=").append(seed)
        }
        val path = when {
            !keyword.isNullOrBlank() -> "/api/search/${encPathSegment(keyword)}?$query"
            tagId != null -> "/api/tags/$tagId/works?$query"
            vaId != null -> "/api/vas/$vaId/works?$query"
            circleId != null -> "/api/circles/$circleId/works?$query"
            else -> "/api/works?$query"
        }
        return parsePage(getJsonObject(path))
    }

    // ─────────────────────── 首页板块：热门 / 推荐 ───────────────────────
    //
    // 这两个板块走的是站点自己的 recommender 接口（POST + JSON body），
    // 请求体字段与网页版一致：keyword 传一个空格表示「不带关键词」。

    /**
     * 热门作品（对应 `asmr.one/popular`）。
     * 站点对它有**100 部**的上限：pageSize=20 时只有 5 页，第 6 页起返回空数组。
     */
    suspend fun getPopularWorks(
        page: Int = 1,
        pageSize: Int = 20,
        keyword: String = " ",
        subtitle: Int = 0
    ): PageResult<Work> {
        val body = JSONObject().apply {
            put("keyword", keyword)
            put("page", page)
            put("pageSize", pageSize)
            put("subtitle", subtitle)
            put("localSubtitledWorks", JSONArray())
            put("withPlaylistStatus", JSONArray())
        }
        return parsePage(postJsonObject("/api/recommender/popular", body))
    }

    /**
     * 推荐作品（对应 `asmr.one/recommend`）。
     *
     * [recommenderUuid] 是「这台设备」的匿名标识：接口**必须**带上它，否则返回 401。
     * 实测一个本机随机生成的 UUID 即可正常返回推荐结果，无需登录。
     */
    suspend fun getRecommendedWorks(
        recommenderUuid: String,
        page: Int = 1,
        pageSize: Int = 20,
        keyword: String = " ",
        subtitle: Int = 0
    ): PageResult<Work> {
        val body = JSONObject().apply {
            put("keyword", keyword)
            put("recommenderUuid", recommenderUuid)
            put("page", page)
            put("pageSize", pageSize)
            put("subtitle", subtitle)
            put("localSubtitledWorks", JSONArray())
            put("withPlaylistStatus", JSONArray())
        }
        return parsePage(postJsonObject("/api/recommender/recommend-for-user", body))
    }

    /** 作品元数据（不含曲目树） */
    suspend fun getWork(id: Int): Work = Work.fromJson(getWorkJson(id), host)

    /**
     * 作品元数据的**原始响应**。
     *
     * 单独开一个入口是给本地缓存用的：`WorkMetaStore` 存服务端原文，
     * 读的时候再走一遍 `Work.fromJson` —— 缓存版与网络版共用同一套解析规则。
     */
    suspend fun getWorkJson(id: Int): JSONObject = getJsonObject("/api/work/$id?v=2")

    /** 曲目 / 文件树：接口直接返回根节点数组 */
    suspend fun getTracks(id: Int): List<Track> = Track.listFrom(getJsonArray("/api/tracks/$id"))

    // ─────────────────────────── 标签 / 声优 / 社团 ───────────────────────────

    suspend fun getTags(): List<TagItem> {
        val json = getJsonObject("/api/tags/")
        val array = json.optJSONArray("tags") ?: JSONArray(json.toString())
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

    /**
     * 拉取声优 / 社团的**完整**字典（含 `count`）。
     *
     * 声优与社团的旧接口（只取 id + name、丢掉 `count`）已随字典化改造移除：
     * 候选列表要靠 `count` 排序，因为接口顺序**不是**按热度 ——
     *  - 声优按 id 排（UUID）→ 等同于随机序
     *  - 社团按名称字母序
     *
     * 两个端点结构相同（`{id, name, count, i18n}`，且 `i18n` 实测全空），
     * 所以共用一个实现，只差路径。
     *
     * ⚠️ 返回的是**顶层数组**（不是 `{vas:[...]}`）。实测 2026-10：
     * 声优 5552 项 / 480KB，社团 5882 项 / 350KB。
     *
     * 返回**原始 JSON 对象**而不是解析好的实体：声优与社团结构虽然一样，
     * 但由各自的字典负责解析 —— 这样"把社团当成声优"在类型上就不成立。
     */
    suspend fun getNamedEntries(path: String): List<JSONObject> {
        val array = getJsonArrayAnonymous(path)
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::add)
            }
        }
    }

    /**
     * 搜索标签字典（`GET /api/tags/`）。
     *
     * 与 [getTags] 的区别：这个保留**完整**的 i18n 结构
     * （`name` / `censored` / `history`，见 [SearchTag]）。
     * [getTags] 是给详情页标签墙用的，只取"原名 + 中文译名"两个字符串。
     *
     * ⚠️ `/api/tags/` 返回的是**顶层数组**（不是 `{tags:[...]}`），
     * 所以这里用数组解析。实测 2026-10：422 条、约 72KB。
     */
    suspend fun getSearchTags(): List<SearchTag> {
        val array = getJsonArrayAnonymous("/api/tags/")
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { o ->
                    SearchTag.fromJson(o)?.let(::add)
                }
            }
        }
    }

    // ─────────────────────────── 媒体地址 ───────────────────────────

    /**
     * 取一个**绝对地址**的原始字节（文件树里的文本 / PDF）。
     *
     * 与其它方法不同，这里**不拼 host**：服务端在 `track.mediaStreamUrl` 里
     * 直接给了完整地址，而且两种形态都出现过（实测）：
     *  - 图片 / PDF：`https://raw.kiko-play-niptan.one/media/...`（直连 CDN）
     *  - 文本：`$host/api/media/stream/{workId}/{id}`（API 代理）
     *
     * ⚠️ **不要用它取视频**：实测单个视频可达 1.8GB，必须由播放器流式拉取
     * （服务端支持 `Range`，实测返回 206）。
     */
    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        HttpLog.request("GET ${redactUrl(url)} | 取原始字节")
        val conn = open(url, cfg, "GET", null, null, null)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = readBody(conn).take(200)
                throw AsmrApiException("下载失败：HTTP $code $msg")
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: AsmrApiException) {
            throw e
        } catch (e: Exception) {
            throw AsmrApiException(friendlyMessage(e), e)
        } finally {
            conn.disconnect()
        }
    }

    /** 封面兜底地址（作品没有 *_cover_url 字段时使用） */
    fun coverUrl(workId: Int): String = "$host/api/cover/$workId"

    /** 音频流兜底地址（音频节点的 mediaStreamUrl 为空时构造） */
    fun streamUrl(hash: String, token: String = ""): String = buildString {
        append("$host/api/media/stream/$hash")
        if (token.isNotBlank()) append("?token=$token")
    }

    // ─────────────────────────── 登录 / 注册 ───────────────────────────
    //
    // 接口契约（对齐参考实现 KikoFlu 的 kikoeru_api_service.dart）：
    //  - `POST /api/auth/me`  body `{name, password}` → `{token, user?…, loggedIn, group…}`
    //  - `POST /api/auth/reg` body `{name, password, recommenderUuid?}` → 同结构
    //  - `GET  /api/auth/me`  header `Authorization: Bearer <token>` → 当前用户画像
    //
    // ⚠️ 登录/注册时**绝不能**带旧的 Authorization 头（会被服务端当成"查询信息"而不是登录），
    //    所以下面这两个方法都显式传 `token = ""`。

    /**
     * 登录。返回的 [JSONObject] 里 `token` 字段是后续所有请求的凭据。
     *
     * [hostOverride] 允许"用还没保存的主机登录"（登录页可以现场切换主机并直接登录）。
     */
    suspend fun login(
        username: String,
        password: String,
        hostOverride: String? = null,
        cookie: String = ""
    ): JSONObject {
        val body = JSONObject().apply {
            put("name", username)
            put("password", password)
        }
        return postJsonObject("/api/auth/me", body, hostOverride = hostOverride, cookie = cookie, withToken = false)
    }

    /**
     * 注册。
     *
     * [recommenderUuid] 是官方站要求的前置字段：先不带令牌请求一次
     * `POST /api/recommender/recommend-for-user` 拿到服务端下发的 uuid，再带着它注册。
     * 自建服务端可以传 null（会退回到本地已保存的 uuid）。
     */
    suspend fun register(
        username: String,
        password: String,
        recommenderUuid: String?,
        hostOverride: String? = null,
        cookie: String = ""
    ): JSONObject {
        val body = JSONObject().apply {
            put("name", username)
            put("password", password)
            if (!recommenderUuid.isNullOrBlank()) put("recommenderUuid", recommenderUuid)
        }
        return postJsonObject("/api/auth/reg", body, hostOverride = hostOverride, cookie = cookie, withToken = false)
    }

    /**
     * 取当前登录用户的画像（也用于**校验令牌是否仍然有效**）。
     *
     * 无效令牌会抛 [AuthRejectedException]，调用方据此决定是否清除会话。
     */
    suspend fun getCurrentUser(
        token: String,
        hostOverride: String? = null,
        cookie: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = (hostOverride ?: host).trimEnd('/') + "/api/auth/me"
        requestWithToken(url, token, null, cookie)
    }

    /**
     * 注册前先拿一个服务端下发的 `recommenderUuid`（对齐参考实现 `_registerOfficial` 的步骤 1）。
     *
     * 这个请求**必须不带令牌** —— 带令牌时该端点返回的是推荐结果而不是注册信息。
     * 拿不到就返回 null，由调用方回退到本地已保存的 uuid。
     */
    suspend fun fetchRecommenderUuid(hostOverride: String? = null): String? = runCatching {
        val body = JSONObject().apply {
            put("keyword", " ")
            put("page", 1)
            put("pageSize", 20)
        }
        val json = postJsonObject(
            "/api/recommender/recommend-for-user",
            body,
            hostOverride = hostOverride,
            withToken = false
        )
        json.optString("uuid", "").takeIf { it.isNotBlank() }
            ?: json.optString("recommenderUuid", "").takeIf { it.isNotBlank() }
    }.getOrNull()

    // ─────────────────────────── 我的收藏（/api/review）───────────────────────────
    //
    // ⚠️ 重要：站点的「我的收藏」**不是** `/api/favourites`（那个端点属于播放列表域，
    //    参考实现里只定义了 getFavorites/addToFavorites 却从未调用）。
    //    真正的收藏是**带状态标记的收藏列表**，走 `/api/review`：
    //      - `GET    /api/review?page&pageSize&order&sort&filter` → 我标记过的作品
    //      - `PUT    /api/review` body `{work_id, progress?, rating?, review_text?}` → 打标记
    //      - `DELETE /api/review?work_id={id}`                    → 移除标记
    //    `filter` 取值：marked / listening / listened / replay / postponed，为空即全部。

    /**
     * 我的收藏列表（服务端状态过滤 + 分页）。
     *
     * [filter] 传 null 表示「全部」。
     */
    suspend fun getMyCollection(
        page: Int = 1,
        pageSize: Int = 20,
        filter: String? = null,
        order: String = "updated_at",
        sort: String = "desc"
    ): PageResult<Work> {
        val query = buildString {
            append("page=").append(page)
            append("&pageSize=").append(pageSize)
            append("&order=").append(order)
            append("&sort=").append(sort)
            if (!filter.isNullOrBlank()) append("&filter=").append(filter)
        }
        return parseWorkListPage(getJsonObject("/api/review?$query"))
    }

    /**
     * 设置 / 更新收藏标记。
     *
     * [progress] 传 null 且 [rating] 传 null 时只提交评分或不提交任何字段，
     * 因此这里要求调用方至少给一个值。自建服务端还会认两个查询参数
     * `starOnly` / `progressOnly` 来区分"只打分"和"只改状态"——
     * 官方站不需要，所以只在两者缺一时附带。
     *
     * [clearProgress] 为 true 时**显式**把 `progress` 送成 JSON `null`，
     * 表示"把这个作品的状态清空"；[clearRating] 同理作用于 `rating`。
     * 详见 [buildReviewRequest]。
     */
    suspend fun updateCollection(
        workId: Int,
        progress: String?,
        rating: Int? = null,
        reviewText: String? = null,
        clearProgress: Boolean = false,
        clearRating: Boolean = false
    ): JSONObject {
        val req = buildReviewRequest(workId, progress, rating, reviewText, clearProgress, clearRating)
        return requestJson(
            url = host + "/api/review" + req.query,
            method = "PUT",
            jsonBody = req.body.toString()
        )
    }

    /**
     * 拼装 `PUT /api/review` 的请求体与查询串。
     *
     * 抽成**纯函数**是为了能单测 —— 这里出过真 bug：
     * 详情页把用户选的星级丢掉，导致"选了 5 星但请求里没有 rating"。
     * 所以「传进来的值必须真的出现在请求里」这件事值得钉住。
     *
     * ## 为什么不带任何查询参数
     *
     * 参考实现（KikoFlu）把官方站与自建站分成两个分支：
     * ```dart
     * // 官方站：_updateReviewProgressOfficial
     * final response = await _dio.put('/api/review', data: data);   // ← 无查询参数
     * // 自建站：_updateReviewProgressCustom
     * final response = await _dio.put('/api/review', data: data, queryParameters: queryParams);
     * ```
     * 早先这里照抄的是**自建站**那一支的 `starOnly` / `progressOnly`，
     * 用在了官方站上 —— 与参考实现的行为不一致，属于凭猜测引入的偏差。
     * 现在对齐官方站的写法：**一个查询参数都不带**，只靠 body 里的字段表达意图
     * （`progress` 不传就是不改状态，`rating` 不传就是不改评分）。
     *
     * ## `"progress": null` 与"不传 progress"不是一回事（实测确认）
     *
     * `PUT /api/review` 是**部分更新**：body 里没有的键表示"别动这个字段"。
     * 所以"取消状态"是表达不出来的 —— 不传 `progress` 只会让服务端原样保留。
     * 结果就是用户在 App 里把状态清掉，本地看着已经没有了，一刷新云端又回来了。
     *
     * 2026-10 对着 api.asmr-200.com 实测（作品 1681251，当时 状态=marked、评分=5）：
     * ```
     * PUT {"work_id":1681251,"rating":5,"progress":null}
     *   → GET /api/review 复查：progress=[] rating=[5]      ← 状态清空、评分保留
     * PUT {"work_id":1681251,"progress":null}               ← 不带 rating 键
     *   → GET /api/review 复查：progress=[] rating=[5]      ← 评分同样没被动
     * PUT {"work_id":1681251,"rating":null,"progress":"marked"}
     *   → GET /api/review 复查：progress=[marked] rating=[]  ← 评分清空、状态保留
     * DELETE /api/review?work_id=…                          ← 整条移除才用它
     * ```
     * 于是清空某个字段必须**显式**送 `null`，由 [clearProgress] / [clearRating] 控制。
     * 默认仍是 false：`null` 与"不传"语义完全不同，不能让调用方在不知情的情况下清掉用户的标记。
     *
     * @return [ReviewRequest.query] 恒为空串，保留字段是为了以后接自建站时不用改签名
     */
    internal fun buildReviewRequest(
        workId: Int,
        progress: String?,
        rating: Int? = null,
        reviewText: String? = null,
        clearProgress: Boolean = false,
        clearRating: Boolean = false
    ): ReviewRequest {
        val body = JSONObject().apply {
            put("work_id", workId)
            // 显式清空：JSONObject.NULL 会序列化成字面量 `null`。
            // 有值时以值为准 —— 一次请求不该同时"给值"和"清空"同一个字段。
            when {
                progress != null -> put("progress", progress)
                clearProgress -> put("progress", JSONObject.NULL)
            }
            when {
                rating != null -> put("rating", rating)
                clearRating -> put("rating", JSONObject.NULL)
            }
            if (reviewText != null) put("review_text", reviewText)
        }
        return ReviewRequest(body = body, query = "")
    }

    /** 移除收藏标记（`DELETE /api/review?work_id={id}`） */
    suspend fun deleteCollection(workId: Int) {
        requestBody(host + "/api/review?work_id=$workId", method = "DELETE")
    }

    // ─────────────────────────── 播放列表（收藏夹）───────────────────────────
    //
    // 端点与请求体现在都经过**实测确认**（2026-10 对着 api.asmr-200.com）：
    //  - `GET  /api/playlist/get-playlists?page&pageSize&filterBy`   → `{playlists[], pagination{page,pageSize,totalCount}}`
    //  - `GET  /api/playlist/get-playlist-works?id&page&pageSize`    → `{works[], pagination{…}}`
    //  - `GET  /api/playlist/get-playlist-metadata?id`               → 单个 playlist 对象
    //  - `POST /api/playlist/add-works-to-playlist`    body `{id, works:[String]}`
    //  - `POST /api/playlist/remove-works-from-playlist` body `{id, works:[Int]}`
    //  - `POST /api/playlist/create-playlist`          body `{name, privacy, locale, works:[]}`
    //  - `POST /api/playlist/edit-playlist-metadata`   body `{id, data:{name,privacy,description}}`
    //  - `POST /api/playlist/delete-playlist`          body `{id}`
    //
    // ⚠️ 参考实现里的 `GET /api/playlists`（返回裸数组）实测是 **404**，属于失效代码，这里不实现。

    /** 我的收藏夹列表（需要登录）。`filterBy` 固定传 `all`。 */
    suspend fun getPlaylists(
        page: Int = 1,
        pageSize: Int = 50,
        filterBy: String = "all"
    ): PlaylistPage {
        val json = getJsonObject("/api/playlist/get-playlists?page=$page&pageSize=$pageSize&filterBy=$filterBy")
        return parsePlaylistPage(json)
    }

    /** 单个收藏夹的元数据（含 `liked_count`） */
    suspend fun getPlaylistMetadata(playlistId: String): Playlist =
        Playlist.fromJson(getJsonObject("/api/playlist/get-playlist-metadata?id=$playlistId"))

    /** 收藏夹里的作品（分页） */
    suspend fun getPlaylistWorks(
        playlistId: String,
        page: Int = 1,
        pageSize: Int = 20
    ): PageResult<Work> {
        val json = getJsonObject(
            "/api/playlist/get-playlist-works?id=$playlistId&page=$page&pageSize=$pageSize"
        )
        return parseWorkListPage(json)
    }

    /** 把作品加入收藏夹（`works` 是**字符串** id 列表，注意与移除接口的类型差别） */
    suspend fun addWorksToPlaylist(playlistId: String, workIds: List<Int>): JSONObject {
        val body = JSONObject().apply {
            put("id", playlistId)
            put("works", JSONArray().apply { workIds.forEach { put(it.toString()) } })
        }
        return postJsonObject("/api/playlist/add-works-to-playlist", body)
    }

    /** 从收藏夹移除作品（这里 `works` 是**整数** id 列表） */
    suspend fun removeWorksFromPlaylist(playlistId: String, workIds: List<Int>): JSONObject {
        val body = JSONObject().apply {
            put("id", playlistId)
            put("works", JSONArray().apply { workIds.forEach { put(it) } })
        }
        return postJsonObject("/api/playlist/remove-works-from-playlist", body)
    }

    /** 新建收藏夹 */
    suspend fun createPlaylist(
        name: String,
        privacy: Int = 0,
        description: String? = null
    ): String? {
        val body = JSONObject().apply {
            put("name", name)
            put("privacy", privacy)
            put("locale", "zh-CN")
            put("works", JSONArray())
            if (!description.isNullOrBlank()) put("description", description)
        }
        val json = postJsonObject("/api/playlist/create-playlist", body)
        // 服务端返回的字段名没有文档，逐个兜底；取不到就返回 null，由调用方重新拉列表
        return json.optString("id", "").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("playlist")?.optString("id", "")?.takeIf { it.isNotBlank() }
    }

    /** 修改收藏夹信息（**系统收藏夹不允许改**，调用前请先用 `Playlist.isSystem` 判断） */
    suspend fun editPlaylistMetadata(
        playlistId: String,
        name: String,
        privacy: Int,
        description: String
    ) {
        val body = JSONObject().apply {
            put("id", playlistId)
            put("data", JSONObject().apply {
                put("name", name)
                put("privacy", privacy)
                put("description", description)
            })
        }
        postJsonObject("/api/playlist/edit-playlist-metadata", body)
    }

    /** 删除自己创建的收藏夹（**系统收藏夹删不掉**，服务端也会拒绝） */
    suspend fun deletePlaylist(playlistId: String) {
        postJsonObject("/api/playlist/delete-playlist", JSONObject().put("id", playlistId))
    }

    /**
     * 播放列表分页解析。
     *
     * ⚠️ 这个端点的分页键是 **`page`**，而作品列表接口用的是 `currentPage` ——
     * 所以不能复用 [parsePage] 的那套读取逻辑。
     */
    private fun parsePlaylistPage(json: JSONObject): PlaylistPage {
        val list = Playlist.listFrom(json.optJSONArray("playlists") ?: json.optJSONArray("data"))
        val p = json.optJSONObject("pagination")
        val pageSize = p?.optInt("pageSize", list.size.coerceAtLeast(1)) ?: list.size.coerceAtLeast(1)
        return PlaylistPage(
            playlists = list,
            page = p?.optInt("page", 1) ?: 1,
            pageSize = pageSize,
            totalCount = p?.optInt("totalCount", list.size) ?: list.size
        )
    }

    /**
     * `{works:[…], pagination:{currentPage,…}}` 形态的通用解析。
     *
     * `/api/review` 与 `/api/playlist/get-playlist-works` 都用这个形状，
     * 但字段名历史上出现过 works / reviews / data 三种，所以统一走这里。
     */
    private fun parseWorkListPage(json: JSONObject): PageResult<Work> {
        val array = json.optJSONArray("works")
            ?: json.optJSONArray("reviews")
            ?: json.optJSONArray("data")
            ?: JSONArray()
        val items = Work.listFrom(array, host)
        val p = json.optJSONObject("pagination")
        val pageSize = p?.optInt("pageSize", items.size.coerceAtLeast(1)) ?: items.size.coerceAtLeast(1)
        return PageResult(
            items = items,
            // 作品列表用 currentPage；万一服务端给了 page 也认
            currentPage = p?.optInt("currentPage", 0)?.takeIf { it > 0 }
                ?: p?.optInt("page", 1) ?: 1,
            pageSize = pageSize,
            totalCount = p?.optInt("totalCount", items.size) ?: items.size
        )
    }

    // ─────────────────────────── 连通性自检 ───────────────────────────

    /** 返回 null 表示连通，否则返回面向用户的错误说明 */
    suspend fun testConnection(hostOverride: String? = null): String? = try {
        val path = "/api/works?page=1&pageSize=1&order=release&sort=desc"
        val json = if (hostOverride != null) {
            requestJson(hostOverride.trimEnd('/') + path)
        } else {
            getJsonObject(path)
        }
        if (json.has("works") || json.has("pagination")) null
        else "响应格式不符合预期（未找到 works 字段）"
    } catch (e: Exception) {
        e.message ?: "未知错误"
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private fun parsePage(json: JSONObject): PageResult<Work> {
        val worksArray = json.optJSONArray("works")
            ?: json.optJSONArray("items")
            ?: JSONArray()
        val items = Work.listFrom(worksArray, host)
        val pagination = json.optJSONObject("pagination")
        val currentPage = pagination?.optInt("currentPage", 1) ?: 1
        val pageSize = pagination?.optInt("pageSize", items.size.coerceAtLeast(1))
            ?: items.size.coerceAtLeast(1)
        val totalCount = pagination?.optInt("totalCount", items.size) ?: items.size
        return PageResult(items, currentPage, pageSize, totalCount)
    }

    private suspend fun getJsonObject(path: String): JSONObject = withContext(Dispatchers.IO) {
        requestJson(host + path)
    }

    private suspend fun getJsonArray(path: String): JSONArray = withContext(Dispatchers.IO) {
        requestArray(host + path)
    }

    /**
     * **匿名**取一个 JSON 数组（公开端点用）。
     *
     * `tokenOverride = ""` 的语义见 [resolveToken]：明确不带令牌。
     * 为什么专门开一条而不是复用 [getJsonArray]：字典这三个端点都是公开的，
     * 带上令牌没有意义，而"到底带没带"在代码里应当一眼可见 ——
     * 见 [publicEndpointsThatMustNotSendToken] 的实测依据。
     */
    private suspend fun getJsonArrayAnonymous(path: String): JSONArray = withContext(Dispatchers.IO) {
        requestArray(host + path, tokenOverride = "")
    }

    /**
     * POST 一个 JSON body。
     *
     * ## ⚠️ 这里曾经有个严重的默认值错误
     *
     * 早期版本为了登录/注册"不带旧令牌"，把这个方法的 `tokenOverride` **写死成 `""`**，
     * 于是**所有** POST 都变成了"明确不带令牌" —— 加入收藏夹因此永远收到
     * `401 No authorization token was found`，而 GET 读取却完全正常。
     *
     * 现在改成显式开关 [withToken]，默认 **true（带令牌）**：
     * 需要匿名调用的那几个端点（登录/注册/取推荐 uuid）自己传 `withToken = false`。
     * 默认值选"带令牌"是刻意的 —— 漏掉的后果是"功能报 401"，
     * 能被立刻发现；反过来（默认不带）会让每个新加的写接口都静默失去鉴权。
     */
    private suspend fun postJsonObject(
        path: String,
        body: JSONObject,
        hostOverride: String? = null,
        cookie: String = "",
        withToken: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            url = (hostOverride?.trimEnd('/') ?: host) + path,
            method = "POST",
            jsonBody = body.toString(),
            // 明确不带令牌 → 传空串；否则传 null 表示"用配置里的令牌"
            tokenOverride = if (withToken) null else "",
            cookieOverride = cookie
        )
    }

    /** 服务端返回给我们的原始响应（带响应头），用于条件请求 */
    private data class RawResponse(
        val body: String,
        val statusCode: Int,
        val etag: String?
    )

    private suspend fun requestJson(
        url: String,
        method: String = "GET",
        jsonBody: String? = null,
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): JSONObject {
        val body = requestBody(url, method, jsonBody, tokenOverride, cookieOverride)
        // 动作型端点可能回空响应（204），此时当成"成功但没有内容"
        if (body.isBlank()) return JSONObject()
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AsmrApiException("解析响应失败：${e.message}", e)
        }
    }

    /**
     * 带 `If-None-Match` 的条件 GET。
     *
     * 返回 null 表示服务端回了 **304 Not Modified** —— 内容没变，
     * 调用方应当继续用本地缓存，并把同步时间往前推。
     *
     * 之所以把 304 单独建模而不是当成错误：实测 `/api/review` 的
     * 一页响应有 95,876 字节，而 304 是 0 字节。这正是"频繁校验一致性"
     * 不会增加服务器压力的原因，值得为它单独开一条路径。
     *
     * [ifNoneMatch] 为 null 时退化成普通 GET（服务端不给 ETag 时就是这样）。
     */
    private suspend fun getJsonConditional(path: String, ifNoneMatch: String?): RawResponse? =
        withContext(Dispatchers.IO) {
            val cfg = configProvider()
            val url = host + path
            val effectiveToken = cfg.token
            // 与 requestBody 同样记一条 —— 读取路径也要能看见"带到哪台主机、有没有令牌"
            HttpLog.request(
                "GET ${redactUrl(url)} | host=${cfg.host} | " +
                    "token=${if (effectiveToken.isBlank()) "无" else "有(${effectiveToken.length}字符)"} | " +
                    "ifNoneMatch=${if (ifNoneMatch.isNullOrBlank()) "-" else "有"}"
            )
            val conn = open(url, cfg, "GET", null, null, null)
            try {
                conn.instanceFollowRedirects = true
                if (!ifNoneMatch.isNullOrBlank()) {
                    conn.setRequestProperty("If-None-Match", ifNoneMatch)
                }
                val code = conn.responseCode
                if (code == 304) {
                    HttpLog.response("GET ${redactUrl(url)} -> HTTP 304（内容未变化）")
                    return@withContext null
                }
                val body = readBody(conn)
                HttpLog.response("GET ${redactUrl(url)} -> HTTP $code | ${body.take(160)}")
                if (code !in 200..299) {
                    if (code == 401 || code == 403) {
                        throw AuthRejectedException(
                            message = serverMessage(body) ?: authRejectionMessage(code),
                            statusCode = code
                        )
                    }
                    throw AsmrApiException("服务器返回 $code：${body.take(120)}")
                }
                RawResponse(
                    body = body,
                    statusCode = code,
                    // ETag 可能是弱校验符（W/"..."），原样保存、原样回传
                    etag = conn.getHeaderField("ETag")
                )
            } catch (e: AuthRejectedException) {
                HttpLog.error("GET ${redactUrl(url)} -> 被拒绝：${e.message}")
                throw e
            } catch (e: AsmrApiException) {
                HttpLog.error("GET ${redactUrl(url)} -> 失败：${e.message}")
                throw e
            } catch (e: Exception) {
                val friendly = friendlyMessage(e)
                HttpLog.error(
                    "GET ${redactUrl(url)} -> 异常 ${e.javaClass.simpleName}：" +
                        "${e.message ?: "(无消息)"} → 展示为「$friendly」"
                )
                throw AsmrApiException(friendly, e)
            } finally {
                conn.disconnect()
            }
        }

    // ─────────────────── 带条件请求 + ETag 的收藏类端点 ───────────────────
    //
    // 这几个端点会返回 ETag（/api/review 已实测；收藏夹几个按同样方式处理，
    // 没有 ETag 时会自动退化成普通请求，不会出错）。

    /** 我的收藏列表（条件请求版）。返回 null = 304，内容没变。 */
    suspend fun getMyCollectionConditional(
        page: Int = 1,
        pageSize: Int = 20,
        filter: String? = null,
        order: String = "updated_at",
        sort: String = "desc",
        ifNoneMatch: String? = null
    ): ConditionalPage? {
        val query = buildString {
            append("page=").append(page)
            append("&pageSize=").append(pageSize)
            append("&order=").append(order)
            append("&sort=").append(sort)
            if (!filter.isNullOrBlank()) append("&filter=").append(filter)
        }
        val raw = getJsonConditional("/api/review?$query", ifNoneMatch) ?: return null
        val json = parseJsonObject(raw.body)
        return ConditionalPage(parseWorkListPage(json), raw.etag)
    }

    /** 收藏夹列表（条件请求版） */
    suspend fun getPlaylistsConditional(
        ifNoneMatch: String? = null,
        page: Int = 1,
        pageSize: Int = 50,
        filterBy: String = "all"
    ): ConditionalPlaylists? {
        val path = "/api/playlist/get-playlists?page=$page&pageSize=$pageSize&filterBy=$filterBy"
        val raw = getJsonConditional(path, ifNoneMatch) ?: return null
        return ConditionalPlaylists(parsePlaylistPage(parseJsonObject(raw.body)), raw.etag)
    }

    /** 收藏夹内部的作品（条件请求版） */
    suspend fun getPlaylistWorksConditional(
        playlistId: String,
        page: Int = 1,
        pageSize: Int = 20,
        ifNoneMatch: String? = null
    ): ConditionalPage? {
        val path = "/api/playlist/get-playlist-works?id=$playlistId&page=$page&pageSize=$pageSize"
        val raw = getJsonConditional(path, ifNoneMatch) ?: return null
        return ConditionalPage(parseWorkListPage(parseJsonObject(raw.body)), raw.etag)
    }

    /** 收藏夹元数据（条件请求版） */
    suspend fun getPlaylistMetadataConditional(
        playlistId: String,
        ifNoneMatch: String? = null
    ): ConditionalPlaylistMeta? {
        val path = "/api/playlist/get-playlist-metadata?id=$playlistId"
        val raw = getJsonConditional(path, ifNoneMatch) ?: return null
        return ConditionalPlaylistMeta(
            Playlist.fromJson(parseJsonObject(raw.body)),
            raw.etag
        )
    }

    private fun parseJsonObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw AsmrApiException("解析响应失败：${e.message}", e)
    }

    /** `/api/tracks/{id}` 返回的是数组，不能按 JSONObject 解析 */
    private suspend fun requestArray(url: String, tokenOverride: String? = null): JSONArray {
        val body = requestBody(url, tokenOverride = tokenOverride)
        return try {
            JSONArray(body)
        } catch (e: Exception) {
            throw AsmrApiException("解析曲目列表失败：${e.message}", e)
        }
    }

    /** 带指定令牌请求一个 JSON 对象（用于 `/api/auth/me` 校验令牌） */
    private suspend fun requestWithToken(
        url: String,
        token: String,
        jsonBody: String?,
        cookie: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = requestBody(url, "GET", jsonBody, tokenOverride = token, cookieOverride = cookie)
        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AsmrApiException("解析响应失败：${e.message}", e)
        }
    }

    /**
     * 发送请求并拿到响应体。
     *
     * ## 为什么这里自己切 IO 线程
     *
     * 这个方法原来是**纯阻塞**的（`HttpURLConnection` 全程同步），
     * 却只靠着调用方各自记得包一层 `withContext(Dispatchers.IO)` ——
     * 一旦有人忘了（`PUT /api/review` 就是这样），Android 会直接抛
     * `NetworkOnMainThreadException`，而且异常**没有 message**，
     * 最终在界面上只显示成一句"网络请求失败"，完全看不出原因。
     *
     * 所以现在把 IO 切换收进这一层：**任何调用方都不可能再在主线程发请求**。
     * 已经包了 IO 的调用方重叠一次没有额外代价（`withContext` 对相同 dispatcher 是廉价的）。
     */
    private suspend fun requestBody(
        url: String,
        method: String = "GET",
        jsonBody: String? = null,
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        // 记录"即将发出的请求"。这一步是排查"没带 token"这类问题的关键：
        // 把**实际使用的主机**与**是否带令牌**写进日志，避免事后靠推演。
        val effectiveToken = tokenOverride ?: cfg.token
        HttpLog.request(
            "$method ${redactUrl(url)} | host=${cfg.host} | " +
                "token=${if (effectiveToken.isBlank()) "无" else "有(${effectiveToken.length}字符)"} | " +
                "body=${jsonBody?.take(200) ?: "-"}"
        )

        val conn = open(url, cfg, method, jsonBody, tokenOverride, cookieOverride)
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = readBody(conn)
            HttpLog.response("$method ${redactUrl(url)} -> HTTP $code | ${body.take(200)}")
            if (code !in 200..299) {
                // 401/403：凭据被服务端明确拒绝 —— 单独抛出，好让调用方区分于网络错误
                if (code == 401 || code == 403) {
                    throw AuthRejectedException(
                        message = serverMessage(body) ?: authRejectionMessage(code),
                        statusCode = code
                    )
                }
                // 404 + playlistNotFound：这个收藏夹已经不存在了。
                // 单独抛出是为了让调用方**不要**记"待补写"（那条永远补不上）。
                if (code == 404 && body.contains("playlistNotFound", ignoreCase = true)) {
                    throw PlaylistNotFoundException("收藏夹已不存在（可能已在网页端删除）")
                }
                throw AsmrApiException("服务器返回 $code：${body.take(120)}")
            }
            if (body.isBlank()) {
                // 收藏 / 播放列表这类"动作型"端点常返回 204 空响应，这是正常的
                // 这里用 return@withContext 而不是 return，避免非局部返回造成的歧义
                if (method == "PUT" || method == "DELETE" || method == "POST") return@withContext ""
                throw AsmrApiException("服务器返回空响应")
            }
            body
        } catch (e: AuthRejectedException) {
            HttpLog.error("$method ${redactUrl(url)} -> 被拒绝：${e.message}")
            throw e
        } catch (e: AsmrApiException) {
            HttpLog.error("$method ${redactUrl(url)} -> 失败：${e.message}")
            throw e
        } catch (e: Exception) {
            val friendly = friendlyMessage(e)
            HttpLog.error(
                "$method ${redactUrl(url)} -> 异常 ${e.javaClass.simpleName}：" +
                    "${e.message ?: "(无消息)"} → 展示为「$friendly」"
            )
            throw AsmrApiException(friendly, e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把日志里的完整 URL 收敛成"主机 + 路径"。
     *
     * 之所以还留主机名：排查"请求打到了哪台服务器"正是重点
     * （标记写到了别的服务器上，是这类问题的常见原因）。
     * 但去掉查询串，避免把 token、关键词之类的东西记进日志。
     */
    private fun redactUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        return noQuery.substringAfter("://", noQuery)
    }

    /** 服务端在错误响应体里给的说明（通常是 `{"error":"…"}`），没有就返回 null */
    private fun serverMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optString("error", "").takeIf { it.isNotBlank() }
                ?: json.optString("message", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun authRejectionMessage(code: Int): String = when (code) {
        401 -> "用户名或密码不正确（或登录状态已过期）"
        else -> "服务器拒绝了这次请求（HTTP $code），请检查账号权限"
    }

    /**
     * 决定这次请求实际使用哪个令牌。
     *
     * 抽成纯函数是为了能单测 —— 这里出过一个真实且静默的 bug：
     * `postJsonObject` 当初把 `tokenOverride` 写死成空串，
     * 于是所有 POST（加入收藏夹、移出收藏夹、新建收藏夹…）都**不带令牌**，
     * 服务端一律回 `401 No authorization token was found`，
     * 而 GET 读取正常，看起来像服务端的问题。
     *
     * 语义：
     *  - `null` → 用配置里的令牌（**默认、也是绝大多数请求该有的行为**）
     *  - `""`   → 明确不带令牌（公开端点，以及登录/注册/取推荐 uuid）
     */
    internal fun resolveToken(tokenOverride: String?, configured: String): String =
        tokenOverride ?: configured

    /**
     * **公开端点** —— 明确不带令牌。
     *
     * 这些都是匿名可取的公共数据，带上令牌没有意义。
     *
     * ## 实测依据（2026-10，对着 api.asmr-200.com）
     *
     * 同一时刻对照三组请求，**响应字节数完全相同**，说明令牌对结果毫无影响：
     *
     * | 端点 | 匿名 | 带假 token |
     * |---|---|---|
     * | `/api/tags/` | 200，65070 字节，422 条 | 200，**65070 字节**，422 条 |
     * | `/api/vas/` | 200，447362 字节，5552 条 | 200，**447362 字节**，5552 条 |
     * | `/api/circles/` | 200，302143 字节，5882 条 | 200，**302143 字节**，5882 条 |
     *
     * 对照组（确实要令牌的端点，同一时刻同一主机）：
     *
     * | 端点 | 匿名 | 假 token |
     * |---|---|---|
     * | `/api/review` | **401**，44 字节 | **401**，25 字节 |
     * | `/api/playlist/get-playlists` | **401**，44 字节 | **401**，25 字节 |
     *
     * 两个 401 的**长度不同**（44 = `No authorization token was found`，
     * 25 = `invalid token`）说明服务端确实在校验令牌 —— 所以"假 token 也返回 200"
     * 不是因为服务端不校验，而是这三个端点**根本不在受保护范围内**。
     *
     * 顺带一个副作用：不带令牌之后，**换账号不必重建索引**
     * （索引本来就按 host 分文件、与账号无关，这样只是让请求本身也保持一致）。
     */
    internal fun publicEndpointsThatMustNotSendToken(): Set<String> = setOf(
        "/api/tags/",
        "/api/vas/",
        "/api/circles/"
    )

    /** 供单测确认"哪些端点故意不带令牌"，避免以后有人把默认值改回去 */
    internal fun authEndpointsThatMustNotSendToken(): Set<String> = setOf(
        "/api/auth/me",
        "/api/auth/reg",
        "/api/recommender/recommend-for-user"
    ) + publicEndpointsThatMustNotSendToken()

    /**
     * 仅供单测：拿到"配置好请求头的连接对象"。
     *
     * ⚠️ 注意：普通的 JVM `HttpURLConnection` **不会**把 `Authorization` 这类
     * 受限头回写到 `getRequestProperty()`（Android 上才会），
     * 所以不要用它断言头部 —— 那条路子在单元测试里恒为 null。
     * 令牌相关的断言请用 [resolveToken] 与 HttpLog。
     */
    internal fun openForTest(
        url: String,
        cfg: ApiConfig,
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): HttpURLConnection = open(url, cfg, "GET", null, tokenOverride, cookieOverride)

    private fun open(
        url: String,
        cfg: ApiConfig,
        method: String = "GET",
        jsonBody: String? = null,
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        // tokenOverride 非 null 时以它为准（空串 = 明确不带令牌）
        val token = tokenOverride ?: cfg.token
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        val cookie = cookieOverride ?: cfg.cookie
        if (cookie.isNotBlank()) {
            conn.setRequestProperty("Cookie", cookie)
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.errorStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时：请检查网络，或在「设置 → 服务器」里切换 API 主机"
        is java.net.ConnectException -> "无法连接到服务器：请检查网络或更换 API 主机"
        is java.net.UnknownHostException -> "域名解析失败：请检查网络或更换 API 主机"
        is javax.net.ssl.SSLException -> "TLS 握手失败（连接可能被中断）：建议在系统层使用代理/VPN"
        else -> e.message ?: "网络请求失败"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 把关键词编码成**路径段**。
     *
     * ## ⚠️ 不能直接用 [URLEncoder] —— 这里踩过一个真实的坑
     *
     * `URLEncoder.encode` 是 **`application/x-www-form-urlencoded`** 的编码器，
     * 它把空格编成 `+`。而 `+` 只在 **query string** 里代表空格；
     * 在**路径段**里 `+` 就是一个字面的加号。
     *
     * 检索串是 `/api/search/{检索串}`，检索串在路径里，多个条件之间靠**空格**分隔，
     * 所以编成 `+` 之后服务端收到的是含 `+` 的检索串 —— 匹配不到任何东西。
     *
     * 实测（2026-10，`api.asmr-200.com`）：
     *
     * | 编码 | 请求 | 结果 |
     * |---|---|---|
     * | `%20` | `/api/search/%24tag%3AASMR%24%20%24tag%3A%E5%82%AC%E7%9C%A0%24` | **405** ✅ |
     * | `+`   | `/api/search/%24tag%3AASMR%24+%24tag%3A%E5%82%AC%E7%9C%A0%24`   | **0** ❌ |
     *
     * ## 为什么这个 bug 只影响多标签搜索
     *
     * 单标签走专用端点 `/api/tags/{id}/works`：`id` 是数字、页面参数在 query 里，
     * **完全不经过这里**。只有"多个条件"才必须拼检索串走 `/api/search/`，
     * 于是它是唯一会碰到这条路径的用法 —— 现象就是"单标签正常、多标签没结果"。
     */
    internal fun encPathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")


    companion object {
        /**
         * 推荐接口的兜底 UUID：本机生成的 UUID 万一被服务端拒绝（401）时用它重试一次。
         * 这是个已被服务端登记的匿名标识（公开客户端里通用）。
         */
        const val FALLBACK_RECOMMENDER_UUID = "766cc58d-7f1e-4958-9a93-913400f378dc"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
