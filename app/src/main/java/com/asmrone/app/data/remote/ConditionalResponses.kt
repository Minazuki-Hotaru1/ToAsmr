package com.asmrone.app.data.remote

import com.asmrone.app.data.model.PageResult
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.PlaylistPage

/**
 * 条件请求的返回体。
 *
 * 这三个类型只做一件事：把**服务端给的 ETag** 和解析好的数据一起带出来，
 * 好让缓存层把它存下来、下次用 `If-None-Match` 发回去。
 *
 * 之所以不为它们建模 `statusCode`：能构造出来就说明是 200（有内容），
 * 304 的语义用 `null` 表达（见 [AsmrOneApi.getMyCollectionConditional]）。
 */

/** 一页作品 + 它的 ETag */
data class ConditionalPage(
    val page: PageResult<com.asmrone.app.data.model.Work>,
    val etag: String?
)

/** 收藏夹列表 + 它的 ETag */
data class ConditionalPlaylists(
    val page: PlaylistPage,
    val etag: String?
)

/** 单个收藏夹元数据 + 它的 ETag */
data class ConditionalPlaylistMeta(
    val playlist: Playlist,
    val etag: String?
)

/**
 * 一次 `PUT /api/review` 的请求内容（体 + 查询串）。
 *
 * 单独建模是为了能在纯 JVM 上断言"传进来的值真的进了请求"，
 * 见 [AsmrOneApi.buildReviewRequest] 的说明。
 */
data class ReviewRequest(
    val body: org.json.JSONObject,
    /** 形如 `?starOnly=true`，不需要时为**空串** */
    val query: String
)
