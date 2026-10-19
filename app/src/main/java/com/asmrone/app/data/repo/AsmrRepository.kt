package com.asmrone.app.data.repo

import com.asmrone.app.data.remote.PlaylistNotFoundException
import com.asmrone.app.data.auth.AuthState
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.HomeBoard
import com.asmrone.app.data.model.NamedItem
import com.asmrone.app.data.model.PageResult
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.TagItem
import com.asmrone.app.data.model.Circle
import com.asmrone.app.data.model.Tag
import com.asmrone.app.data.model.TagCatalog
import com.asmrone.app.data.model.VoiceActor
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.model.WorkFilter
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.cache.CachedWork
import com.asmrone.app.data.cache.CachedWorkPage
import com.asmrone.app.data.cache.CachedPlaylists
import com.asmrone.app.data.cache.CachedPlaylistMeta
import com.asmrone.app.data.cache.PlaylistMetaCodec
import com.asmrone.app.data.cache.PlaylistsCodec
import com.asmrone.app.data.cache.CollectionCacheOps
import com.asmrone.app.data.cache.PendingWrite
import com.asmrone.app.data.cache.PendingWriteStore
import com.asmrone.app.data.cache.PlaylistPatchOps
import com.asmrone.app.data.cache.CircleDictionary
import com.asmrone.app.data.cache.TagDictionary
import com.asmrone.app.data.cache.SyncCacheStore
import com.asmrone.app.data.cache.VoiceActorDictionary
import com.asmrone.app.data.cache.SyncGate
import com.asmrone.app.data.cache.SyncTrigger
import com.asmrone.app.data.cache.TrackTreeStore
import com.asmrone.app.data.cache.WorkMetaStore
import com.asmrone.app.data.cache.WorkPageCodec
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.remote.ApiConfig
import com.asmrone.app.data.remote.AsmrOneApi
import com.asmrone.app.data.remote.AuthRejectedException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * 仓储层：把「配置 → API → 内存缓存」串起来。
 *
 * 缓存策略：
 *  - 列表页见过的作品元数据留在 [workCache]，点进详情页可以立刻铺出标题/封面/评分；
 *  - 文件树（`/api/tracks/{id}`）单独缓存 —— 它可能很大（实测热门作品 6MB、1450+ 节点），
 *    同一个作品来回进出不应重复下载；
 *  - 标签/声优/社团字典一次取回后基本不变。
 *
 * 两个缓存都设了**容量上限**：以前 [workCache] 虽然建成了 `LinkedHashMap(accessOrder=true)`
 * 但没有覆写 `removeEldestEntry`，等于没有淘汰；[trackCache] 更是裸 `HashMap`。
 * 连续浏览十几个大作品后，仅文件树就能占掉几十 MB。现在两者都会按上限淘汰最久未用的条目。
 */
object AsmrRepository {

    /** 元数据缓存上限（条） */
    private const val WORK_CACHE_MAX = 120

    /** 文件树缓存上限（部）—— 树的体积远大于元数据，所以条数要小得多 */
    private const val TRACK_CACHE_MAX = 8

    /**
     * 落盘元数据的保鲜期（7 天）。
     *
     * 7 天内进同一部作品不再发请求 —— 评分、评论数这些会变，但变化对用户
     * 来说不值得每次都等一个网络往返；过期后照常联网，失败还能拿它兜底。
     */
    private const val META_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 每次请求都**实时**取主机、令牌与 Cookie（而不是构造时快照一次）。
     *
     * 这一点很关键：登录、退出登录、切换账号、切换服务器之后，
     * 后续所有请求都必须自动带上新的 `Authorization` 头，无需重建 Api 实例。
     * 令牌由 [AuthManager] 持有，[AppPrefs] 只负责主机，
     * 自建服务端所需的 Cookie 则随账号一起存在 [com.asmrone.app.data.prefs.AccountStore] 里。
     */
    private val api = AsmrOneApi {
        ApiConfig(
            host = AppPrefs.host.value,
            token = AuthManager.state.value.token,
            cookie = AuthManager.activeCookie
        )
    }

    /**
     * 首页板块的数据入口 —— 三个板块对应网站的三个页面：
     *  - [HomeBoard.ALL]       → `GET  /api/works`（全站，按收录时间倒序）
     *  - [HomeBoard.POPULAR]   → `POST /api/recommender/popular`（站点上限 100 部）
     *  - [HomeBoard.RECOMMEND] → `POST /api/recommender/recommend-for-user`（带本机 UUID）
     */
    suspend fun getBoard(
        board: HomeBoard,
        page: Int = 1,
        pageSize: Int = 20
    ): PageResult<Work> {
        val result = when (board) {
            HomeBoard.ALL -> api.getWorks(
                page = page,
                pageSize = pageSize,
                order = "create_date",
                sort = "desc"
            )

            HomeBoard.POPULAR -> api.getPopularWorks(page = page, pageSize = pageSize)

            HomeBoard.RECOMMEND -> {
                val uuid = AppPrefs.recommenderUuid.value
                    .ifBlank { AsmrOneApi.FALLBACK_RECOMMENDER_UUID }
                runCatching { api.getRecommendedWorks(recommenderUuid = uuid, page = page, pageSize = pageSize) }
                    .getOrElse {
                        // 本机 UUID 被拒（401）时，用服务端已登记的兜底 UUID 再试一次
                        api.getRecommendedWorks(
                            recommenderUuid = AsmrOneApi.FALLBACK_RECOMMENDER_UUID,
                            page = page,
                            pageSize = pageSize
                        )
                    }
            }
        }
        result.items.forEach { workCache[it.id] = it }
        return result
    }

    /**
     * 作品元数据缓存：`accessOrder = true` 的 LinkedHashMap + 按上限淘汰最久未用的条目。
     * 必须覆写 [LinkedHashMap.removeEldestEntry]，否则 accessOrder 只是个排序标记，不会有淘汰。
     */
    private val workCache = object : LinkedHashMap<Int, Work>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Work>?): Boolean =
            size > WORK_CACHE_MAX
    }

    /** 文件树缓存：同上，但条数上限小得多（每棵树可能上千节点、几 MB） */
    private val trackCache = object : LinkedHashMap<Int, List<Track>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Track>>?): Boolean =
            size > TRACK_CACHE_MAX
    }

    private var tagCache: List<TagItem>? = null
    /** 标签 / 声优 / 社团三本字典的进程内快照由各自的 Dictionary 自己持有，这里不再重复缓存 */

    val host: String get() = AppPrefs.host.value

    /**
     * 搜索 / 列表查询。
     *
     * 不暴露 `order` / `sort` / `subtitle`：搜索页已按需求移除全部排序与筛选 chip，
     * 一律用服务端默认（**发行日期倒序**）。首页「全部」板块要别的排序时
     * 直接走 [AsmrOneApi.getWorks]，不经过这里。
     *
     * @param filter 标签 / 声优 / 社团三者之一；见 [WorkFilter]
     */
    suspend fun getWorks(
        page: Int = 1,
        pageSize: Int = 20,
        keyword: String? = null,
        filter: WorkFilter? = null
    ): PageResult<Work> {
        val result = api.getWorks(
            page = page,
            pageSize = pageSize,
            order = "release",
            sort = "desc",
            keyword = keyword,
            tagId = filter?.tagId,
            vaId = filter?.vaId,
            circleId = filter?.circleId
        )
        result.items.forEach { workCache[it.id] = it }
        return result
    }

    /**
     * 作品元数据（不含文件树）。
     *
     * 三级来源，与 [getTracks] 对称：
     *  1. 内存缓存（本次会话已经拿过）
     *  2. **落盘的元数据**（[WorkMetaStore]）；[META_TTL_MS] 之内直接返回，**0 请求**
     *  3. 联网拉 `/api/work/{id}?v=2`，成功后写盘
     *
     * 第 2 级让"从收藏页点进一部作品"在冷启动后也能立刻出图出字，
     * 而不是先转圈等一个来回。过期（超过 TTL）的副本仍然保留：
     * 网络失败时用它兜底，总比让用户看错误页强。
     *
     * @param forceRefresh 手动刷新时才为 true —— 它同时跳过内存与磁盘
     */
    suspend fun getWorkMeta(id: Int, forceRefresh: Boolean = false): Work {
        var stale: Work? = null
        if (!forceRefresh) {
            workCache[id]?.let { return it }
            WorkMetaStore.load(id, host)?.let { cached ->
                val work = runCatching { Work.fromJson(cached.json, host) }.getOrNull()
                if (work != null) {
                    val merged = work.copy(tracks = trackCache[id] ?: emptyList())
                    if (System.currentTimeMillis() - cached.savedAt < META_TTL_MS) {
                        workCache[id] = merged
                        return merged
                    }
                    stale = merged
                }
            }
        }

        val fetched = runCatching { api.getWorkJson(id) }
        val json = fetched.getOrNull()
        if (json == null) {
            // 离线 / 服务器异常：过期的那份照样能用（收藏状态之类的字段由本地缓存主导）
            stale?.let {
                workCache[id] = it
                return it
            }
            throw fetched.exceptionOrNull() ?: IllegalStateException("作品信息加载失败")
        }
        val work = Work.fromJson(json, host)
        // 保留已缓存的树，避免刷新元数据时把树丢掉
        val merged = work.copy(tracks = trackCache[id] ?: emptyList())
        workCache[id] = merged
        WorkMetaStore.save(id, host, json)
        return merged
    }

    /**
     * 丢掉某部作品的元数据（内存 + 磁盘）。
     *
     * 收藏状态写入成功后必须调一次：`/api/work` 的响应里带着
     * `progress` / `userRating`，留着旧副本会让详情页显示**过期的收藏标记**。
     */
    private fun invalidateWorkMeta(workId: Int) {
        workCache.remove(workId)
        WorkMetaStore.forget(workId)
    }

    /**
     * 文件树（folders + audio + image + text）。
     *
     * 三级来源，越靠前越便宜：
     *  1. 内存缓存（本次会话已经拿过）
     *  2. **按 RJ 号落盘的本地树**（[TrackTreeStore]，进入作品时写入）
     *  3. 联网拉 `/api/tracks/{id}`
     *
     * 第 2 级是这次新增的关键：冷启动后开播放器/看歌词只读本地，
     * 不会再去拉那棵最大 6MB 的树。
     *
     * @param sourceId RJ 号；**能传就传**，它是本地缓存的键，
     *   也决定这份树能不能被"只拿到 workId 的调用方"读到
     */
    suspend fun getTracks(
        id: Int,
        sourceId: String? = null,
        forceRefresh: Boolean = false
    ): List<Track> {
        val knownSourceId = sourceId ?: workCache[id]?.sourceId
        if (!forceRefresh) {
            trackCache[id]?.let { return it }
            TrackTreeStore.load(knownSourceId, id)?.let { cached ->
                trackCache[id] = cached
                workCache[id]?.let { workCache[id] = it.copy(tracks = cached) }
                return cached
            }
        }
        val tracks = api.getTracks(id)
        trackCache[id] = tracks
        workCache[id]?.let { workCache[id] = it.copy(tracks = tracks) }
        TrackTreeStore.save(knownSourceId, id, host, tracks)
        return tracks
    }

    /**
     * 详情页用：元数据与文件树**并发**获取。
     * 任一部分失败时，另一部分仍可用（详情页会分别展示错误/重试）。
     */
    suspend fun getWorkDetail(id: Int, forceRefresh: Boolean = false): DetailLoad = coroutineScope {
        val metaDeferred = async {
            runCatching { getWorkMeta(id, forceRefresh) }
        }
        val tracksDeferred = async {
            runCatching { getTracks(id, forceRefresh = forceRefresh) }
        }
        val meta = metaDeferred.await()
        val tracks = tracksDeferred.await()

        val work = meta.getOrNull()
        val tree = tracks.getOrNull() ?: trackCache[id] ?: emptyList()
        // ① 拿到元数据才知道 RJ 号 → 补写一次"按 RJ 命名"的树，
        //    并清掉可能先落下的 `w<id>.json`，避免同一棵树存两份
        work?.sourceId?.takeIf { it.isNotBlank() }?.let { rj ->
            if (tree.isNotEmpty()) {
                TrackTreeStore.save(rj, id, host, tree)
                TrackTreeStore.forgetByIdOnly(id)
            }
        }
        DetailLoad(
            work = work?.copy(tracks = tree) ?: workCache[id]?.copy(tracks = tree),
            metaError = meta.exceptionOrNull()?.message,
            tracksError = tracks.exceptionOrNull()?.message
        )
    }

    /** 列表页之间传引用用（避免详情页白屏） */
    fun peekWork(id: Int): Work? = workCache[id]

    fun peekTracks(id: Int): List<Track>? = trackCache[id]

    /**
     * 只读**本地磁盘**的元数据，**绝不发请求**。
     *
     * 给详情页冷启动第一帧用：内存里没有列表页留下的快照时，先拿这份铺界面，
     * 避免先闪一下骨架屏再出内容。真正要不要联网仍由 [getWorkMeta] 的保鲜期决定，
     * 所以这里**不写进** [workCache] —— 否则一份过期副本会被当成"刚取回来的"，
     * 本次会话内再也不会刷新。
     */
    suspend fun cachedWorkMeta(id: Int): Work? {
        if (id <= 0) return null
        val entry = WorkMetaStore.load(id, host) ?: return null
        val work = runCatching { Work.fromJson(entry.json, host) }.getOrNull() ?: return null
        return work.copy(tracks = trackCache[id] ?: emptyList())
    }

    suspend fun getTags(forceRefresh: Boolean = false): List<TagItem> {
        tagCache?.let { if (!forceRefresh) return it }
        return api.getTags().also { tagCache = it }
    }

    // ───────────────── 搜索字典：标签 / 声优 / 社团（本地优先）─────────────────
    //
    // 三者都是搜索用的"具名资源"，策略一致：**取一次长期用**，
    // 由用户在设置里手动触发更新 —— 搜索路径**永不发请求**。
    //
    // 体量差别很大（实测 2026-10 对着 api.asmr-200.com）：
    //   标签   422 条 /  72KB   —— 更新频率极低
    //   声优  5552 条 / 480KB   —— 更新相对频繁
    //   社团  5882 条 / 350KB
    // 用户明确接受"声优/社团可能不够新"，因为它们只用在搜索里。

    // ─────────────────── 文件树里的非音频内容 ───────────────────
    //
    // 详情页的文件树里除了音频，还有图片、视频、PDF、文本。
    // 它们取内容的方式不同（见 TrackKind 的说明），但**下载/读取**这一步是共用的：
    //  - 图片 / 视频：直接交给 Coil / Media3 流式读取，不经过这里
    //  - 文本：要拿到字节自己判编码，走这里
    //  - PDF：要落到本地文件再交给外部阅读器，走这里

    /**
     * 取一个媒体地址的原始字节。
     *
     * ⚠️ **不要用它去取视频** —— 实测单个视频可达 1.8GB，
     * 那必须由播放器流式拉取（`Range` 请求，实测服务端支持 206）。
     * 这个方法只适合小文件（文本几百 KB、PDF 十几 MB）。
     *
     * URL 是服务端直接给的绝对地址（CDN 或 API 代理），所以**不拼 host**。
     */
    suspend fun fetchBytes(url: String): ByteArray = api.fetchBytes(url)

    /** 本地已有的标签（只读内存/磁盘，不碰网络）；没有则空列表 */
    fun cachedSearchTags(): List<Tag> = TagDictionary.entries(AppPrefs.host.value)

    /** 标签字典的上次更新时间；从未更新过则 null */
    fun searchTagsUpdatedAt(): Long? = TagDictionary.lastUpdatedAt(AppPrefs.host.value)

    /** 本地已有的声优；没有则空列表 */
    fun cachedVoiceActors(): List<VoiceActor> = VoiceActorDictionary.entries(AppPrefs.host.value)

    fun voiceActorsUpdatedAt(): Long? = VoiceActorDictionary.lastUpdatedAt(AppPrefs.host.value)

    /** 本地已有的社团；没有则空列表 */
    fun cachedCircles(): List<Circle> = CircleDictionary.entries(AppPrefs.host.value)

    fun circlesUpdatedAt(): Long? = CircleDictionary.lastUpdatedAt(AppPrefs.host.value)

    /**
     * 三本搜索字典是否都已就绪（都非空）。
     *
     * 首次进入 App 时用它判断拉取是否完整 —— 三本都要，
     * 少一本就会出现"标签能搜到、社团搜不到"这种费解情况。
     */
    fun dictionariesReady(): Boolean =
        cachedSearchTags().isNotEmpty() &&
            cachedVoiceActors().isNotEmpty() &&
            cachedCircles().isNotEmpty()

    /**
     * 从服务端更新标签索引（设置页的「更新搜索字典」入口）。
     *
     * 只有这里会真的发请求 —— 搜索过程一律读本地。
     *
     * ## 这里是**唯一**一次名字解析
     *
     * 拉到的原始条目带着整张 `i18n` 表，[TagCatalog.build] 把"该显示哪个名字、
     * 该用什么检索、能用哪些别名匹配"一次性定下来，然后落盘。
     * 之后运行时只读 [Tag] 的字段，不再解析 —— 所以不可能出现
     * "同一标签在两处显示成两个名字"。
     *
     * @return 更新后的条数；0 表示服务端没返回内容（此时**不覆盖**本地）
     */
    suspend fun refreshSearchTags(): Int {
        val fetched = api.getSearchTags()
        if (fetched.isEmpty()) return 0
        TagDictionary.save(AppPrefs.host.value, TagCatalog.build(fetched))
        return fetched.size
    }

    /**
     * 从服务端更新声优字典。
     *
     * @return 更新后的条数；0 表示服务端没返回内容（此时**不覆盖**本地）
     */
    suspend fun refreshVoiceActors(): Int {
        val fetched = api.getNamedEntries("/api/vas/")
        if (fetched.isEmpty()) return 0
        VoiceActorDictionary.save(AppPrefs.host.value, fetched.mapNotNull { VoiceActor.fromJson(it) })
        return fetched.size
    }

    /**
     * 从服务端更新社团字典。
     *
     * @return 更新后的条数；0 表示服务端没返回内容（此时**不覆盖**本地）
     */
    suspend fun refreshCircles(): Int {
        val fetched = api.getNamedEntries("/api/circles/")
        if (fetched.isEmpty()) return 0
        CircleDictionary.save(AppPrefs.host.value, fetched.mapNotNull { Circle.fromJson(it) })
        return fetched.size
    }

    fun coverUrl(workId: Int): String = api.coverUrl(workId)

    suspend fun testConnection(hostOverride: String? = null): String? = api.testConnection(hostOverride)

    // ─────────────────── 我的收藏（带本地缓存 + 条件请求）───────────────────
    //
    // 站点的「我的收藏」是**带收听状态的收藏列表**（想听/在听/听过/重听/搁置），
    // 走 `/api/review`，不是 `/api/favourites`。
    //
    // 读取策略（缓存键含 host + 账号 + 筛选，见 SyncCacheStore.SyncKeys）：
    //  · 除了冷启动首次、用户显式刷新、本地改动待确认这几种情况，**一律只读本地**；
    //  · 真要访问网络时发**条件请求**（If-None-Match）：
    //      304 → 内容没变，只把同步时间往前推（**响应体 0 字节**）
    //      200 → 全量替换本地缓存
    //  · 单飞由 [SyncGate] 收口，重复操作不会放大成多次请求。

    /** 内存快照，避免每次写盘后再从 JSON 反序列化一遍 */
    private var collectionSnapshot: List<Work>? = null
    private var collectionSnapshotKey: String? = null

    // ─────────────────────── 「本地已改，界面请刷新」信号 ───────────────────────
    //
    // 为什么需要它：收藏的 ViewModel 挂在导航层、**不会**随页面进出而重建，
    // 所以"进详情页标记 → 返回收藏"时 `onEnter()` 不会重跑，
    // 光把新内容写进本地缓存是**看不见**的。
    //
    // 这里广播一个"本地收藏数据变了"的信号，收藏两个页签收到后就地重读本地缓存 ——
    // **不发任何请求**。replay = 1 是为了让"信号发出时还没开始收集"的
    // 订阅者（刚创建、还没走到 collect 的 ViewModel）也能收到最新一次。

    private val _libraryChanges = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 8
    )

    /** 本地「标记 / 收藏夹」数据发生变化的信号（只代表本地写入，不代表已同步云端） */
    val libraryChanges: SharedFlow<Unit> = _libraryChanges.asSharedFlow()

    /**
     * 通知界面"本地收藏数据变了"。
     *
     * 刻意**不用挂起**（`tryEmit` 而非 `emit`）：调用它的地方都是写路径的收尾，
     * 不该因为没人订阅或缓冲满而阻塞；缓冲满时丢掉一次通知也只是
     * "界面晚一拍刷新"，下次任何写操作都会再发一次，不会永久不一致。
     */
    private fun notifyLibraryChanged() {
        _libraryChanges.tryEmit(Unit)
    }

    /**
     * 本地收藏数据的缓存键 —— **只有一份**。
     *
     * 状态筛选（全部 / 想听 / …）不再是独立的缓存维度，
     * 它们是读取时按 `progress` 派生的视图，所以这里不带 status 参数。
     * 早期为每个筛选各存一份，导致切筛选要单独请求、多份缓存还会互相不一致。
     */
    private fun collectionKey(): String? {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return null
        return SyncCacheStore.SyncKeys.collection(state.host, state.displayName, null)
    }

    /**
     * 只读本地：有缓存就立刻给，**完全不碰网络**。
     *
     * ## 状态筛选是**本地派生**的，不再单独缓存
     *
     * 「全部、想听、在听…」共用同一份本地 JSON（「全部」那份），
     * 切换筛选时按每条作品的 `progress` **在内存里筛**。
     *
     * 这么做的依据是实测确认的：`GET /api/review`（**不带** `filter`）
     * 返回的每条作品都带 `progress` 字段，而 `?filter=listened` 的结果
     * 与本地按 `progress` 筛出来的完全一致。所以：
     *
     *  - 切换筛选**不发任何请求** —— 它只是换个视图看同一份本地数据；
     *  - 也不会出现"切到某个筛选看到空列表"的假象 ——
     *    只要「全部」对齐过，每个状态各自的条数就是确定的。
     *
     * 早期版本给每个筛选单独存了一份 JSON（各自带 ETag），
     * 于是切筛选要发一次请求、还要维护 6 份互相独立的缓存 —— 属于冗余。
     */
    fun cachedCollection(status: CollectionStatus?): CachedCollection? {
        val key = collectionKey() ?: return null
        val entry = SyncCacheStore.read(key, WorkPageCodec) ?: return null
        val all = entry.value.items.map { it.toWork() }
        if (collectionSnapshotKey == key) collectionSnapshot = all

        val works = if (status == null) all else all.filter { it.collectionStatus == status }
        return CachedCollection(
            works = works,
            // 总数跟着筛选后的结果走，这样「加载更多」的判断才正确
            totalCount = if (status == null) entry.value.totalCount else works.size,
            loadedPages = entry.value.loadedPages,
            syncedAt = entry.syncedAt
        )
    }

    /**
     * 与云端**对齐**一次（只在每天首次进入 + 用户手动刷新时调用）。
     *
     * ## 只对齐**一份**列表，筛选在本地做
     *
     * 无论要哪个筛选（全部 / 想听 / …），网络请求都是同一个：
     * `GET /api/review`（**不带** `filter`），拿到完整列表后按 `progress` 本地筛。
     *
     * 依据：实测确认不带 `filter` 时每条作品**都带 `progress`**，
     * 而且 `?filter=listened` 的结果与本地按 `progress` 筛出的完全一致。
     * 所以没必要为每个筛选各存一份缓存、各发一次请求。
     *
     * ## 顺序（按要求）
     *
     * 1. 拉云端列表；
     * 2. 合并时**跳过**待同步项 —— 待上传的保留本地那份、待删除的直接不出现
     *    （见 [CollectionCacheOps.resolveAgainstCloud]）；
     * 3. 合并完成**之后**，再把待同步项重试到云端（成功即出队）。
     *
     * 这样即便对齐时用户刚加的东西网络还没成功，它也不会被云端的旧数据抹掉。
     *
     * @param status 要返回哪个筛选；**只影响返回结果，不影响请求**
     */
    suspend fun loadCollection(
        status: CollectionStatus? = null,
        trigger: SyncTrigger? = SyncTrigger.DAILY_AUTO
    ): CachedCollection? {
        // 始终针对「全部」那份缓存（它才是主数据源）
        val key = collectionKey() ?: return null
        val existing = SyncCacheStore.read(key, WorkPageCodec)

        // 待上传（本地有、云端还没收到）与待删除（本地删了、云端还没删）
        val pendingAdds = pendingAddIds()
        val pendingRemoves = pendingRemoveIds()

        if (trigger != null) {
            // 该不该发请求完全交给 SyncGate（见 SyncTrigger）：
            // 被挡下时它直接返回，这里一个请求都不发。
            SyncGate.sync(key, trigger) {
                val result = api.getMyCollectionConditional(
                    page = 1,
                    pageSize = COLLECTION_PAGE_SIZE,
                    // 刻意**不带 filter**：一次拿到全部，各筛选本地派生
                    filter = null,
                    // 本地有未确认的改动 → 不做条件请求，强制拿一次云端真实内容，
                    // 否则会错误地收到 304、永远对齐不上
                    ifNoneMatch = existing?.etag.takeIf { existing?.dirty != true }
                )
                if (result == null) {
                    // 304：云端内容与本地一致。只把时间推新、清掉 dirty
                    SyncCacheStore.touch(key, WorkPageCodec, dirty = false)
                    // 304 时云端没给出新列表，但本地可能还留着已被删掉的条目
                    // （用户删了、云端也删了，只是这次是 304）→ 清一遍
                    purgeRemovedFromCache(pendingRemoves)
                } else {
                    writeCollectionCache(
                        key,
                        result.page,
                        result.etag,
                        pendingAdds = pendingAdds,
                        pendingRemoves = pendingRemoves
                    )
                }
            }

            // 对齐完成之后，才重试待同步项
            if (pendingAdds.isNotEmpty() || pendingRemoves.isNotEmpty()) {
                replayPendingWrites()
            }
        }

        return cachedCollection(status)
    }

    /**
     * 把"已被云端删掉"的作品从本地列表里清掉。
     *
     * 用在 304 分支上：那次没拿到新列表，但待删除项已经在本地删了，
     * 云端也可能是同样状态，本地不该继续留着。
     *
     * 只需要清**一份**列表 —— 状态筛选是读取时按 `progress` 派生的
     * （见 [cachedCollection]），所以删掉一条之后它自然不会再出现在任何筛选里。
     */
    private fun purgeRemovedFromCache(removedIds: Set<Int>) {
        if (removedIds.isEmpty()) return
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return

        val key = SyncCacheStore.SyncKeys.collection(state.host, state.displayName, null)
        val entry = SyncCacheStore.read(key, WorkPageCodec) ?: return
        val filtered = entry.value.items.filterNot { it.id in removedIds }
        if (filtered.size == entry.value.items.size) return

        SyncCacheStore.write(
            key,
            WorkPageCodec,
            entry.copy(
                value = entry.value.copy(
                    items = filtered,
                    totalCount = (entry.value.totalCount - (entry.value.items.size - filtered.size))
                        .coerceAtLeast(filtered.size)
                )
            )
        )
        if (collectionSnapshotKey == key) {
            collectionSnapshot = filtered.map { it.toWork() }
        }
    }

    /** 「加载更多」：拉下一页并**追加**（不重新校验已有页） */
    suspend fun loadMoreCollection(
        status: CollectionStatus? = null,
        nextPage: Int
    ): CachedCollection? {
        // 追加的永远是「全部」那份列表 —— 各筛选是它的派生视图，
        // 所以多加载一页会让**所有**筛选都看到更多内容，不需要各拉一份
        val key = collectionKey() ?: return null
        val existing = SyncCacheStore.read(key, WorkPageCodec)

        // 已经有这一页了就直接复用，不发请求
        if (existing != null && nextPage <= existing.value.loadedPages) {
            return cachedCollection(status)
        }

        SyncGate.sync("$key|page$nextPage", SyncTrigger.USER_REFRESH) {
            val result = api.getMyCollectionConditional(
                page = nextPage,
                pageSize = COLLECTION_PAGE_SIZE,
                // 与首页对齐一致：**不带 filter**，追加的是同一份「全部」列表的第 N 页。
                // 如果这里按 status 过滤，就会把"想听的第 2 页"追加到"全部的第 1 页"
                // 后面 —— 两份不同的列表混进同一个缓存，数据直接错乱。
                filter = null,
                // 追加页不做条件请求：ETag 是第 1 页的，用它校验第 3 页是错的
                ifNoneMatch = null
            ) ?: return@sync
            appendCollectionCache(key, result.page, nextPage)
        }
        return cachedCollection(status)
    }

    /**
     * 把云端返回的内容与**本地待同步状态**合并后写入缓存。
     *
     * 注意这里不是"云端覆盖本地"：本地才是主数据源，云端用来对齐。
     * 三条规则见 [CollectionCacheOps.resolveAgainstCloud]：
     * 待删除的一律不出现、待上传的保留本地那份、其余以云端为准。
     *
     * @param pendingAdds 本地已改、云端还没收到的作品
     * @param pendingRemoves 本地已删、云端还没删掉的作品
     */
    private fun writeCollectionCache(
        key: String,
        page: PageResult<Work>,
        etag: String?,
        pendingAdds: Set<Int> = emptySet(),
        pendingRemoves: Set<Int> = emptySet()
    ) {
        val remoteItems = page.items.map { CachedWork.fromWork(it) }
        val localItems = SyncCacheStore.read(key, WorkPageCodec)?.value?.items.orEmpty()

        val merged = if (pendingAdds.isEmpty() && pendingRemoves.isEmpty()) {
            // 没有待同步项 → 云端是唯一依据
            remoteItems
        } else {
            CollectionCacheOps.resolveAgainstCloud(
                remote = remoteItems.map { it.toWork() },
                local = localItems.map { it.toWork() },
                pendingAdds = pendingAdds,
                pendingRemoves = pendingRemoves
            ).map { CachedWork.fromWork(it) }
        }

        SyncCacheStore.write(
            key,
            WorkPageCodec,
            SyncCacheStore.CacheEntry(
                value = CachedWorkPage(
                    items = merged,
                    // 总数以服务端为准，但本地可能因待上传条目而多出几条
                    totalCount = maxOf(page.totalCount, merged.size),
                    loadedPages = page.currentPage
                ),
                etag = etag,
                syncedAt = System.currentTimeMillis()
            )
        )
        collectionSnapshotKey = key
        collectionSnapshot = merged.map { it.toWork() }
    }

    private fun appendCollectionCache(key: String, page: PageResult<Work>, pageNo: Int) {
        val existing = SyncCacheStore.read(key, WorkPageCodec)
        val merged = (existing?.value?.items.orEmpty() + page.items.map { CachedWork.fromWork(it) })
            // 分页边界上偶发重复，按 id 去重
            .distinctBy { it.id }
        SyncCacheStore.write(
            key,
            WorkPageCodec,
            SyncCacheStore.CacheEntry(
                value = CachedWorkPage(merged, page.totalCount, loadedPages = pageNo),
                etag = existing?.etag,
                syncedAt = existing?.syncedAt ?: System.currentTimeMillis(),
                dirty = existing?.dirty ?: false
            )
        )
        collectionSnapshotKey = key
        collectionSnapshot = null
    }

    /** 当前账号的待补写记录（本地已改、云端还没收到） */
    private fun pendingWrites(): List<PendingWrite> {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return emptyList()
        return PendingWriteStore.read(state.host, state.displayName)
    }

    /**
     * **待上传**的作品 id 集合：本地已加/已改，云端还没收到。
     *
     * 这些条目在对齐时要**保留本地那份**，并在对齐完成后重试上传。
     * 界面据此在卡片上显示「待同步」标识。
     */
    fun pendingAddIds(): Set<Int> = pendingWrites()
        .filterIsInstance<PendingWrite.CollectionMark>()
        .filter { it.status != null }
        .map { it.workId }
        .toSet()

    /**
     * **待删除**的作品 id 集合：本地已删，云端还留着。
     *
     * 这些条目在对齐时一律**不出现**（用户已经删了），
     * 并在对齐完成后重试云端删除。
     * 界面上不会显示它们，所以也没有对应的卡片标识。
     */
    fun pendingRemoveIds(): Set<Int> = pendingWrites()
        .filterIsInstance<PendingWrite.CollectionMark>()
        .filter { it.status == null }
        .map { it.workId }
        .toSet()

    /**
     * 需要在卡片上标「待同步」的作品。
     *
     * **只含待上传**：待删除的作品已经不在列表里了，用户看不到，
     * 也就无所谓标识（它的重试由每日对齐自动完成）。
     */
    fun pendingWorkIds(): Set<Int> = pendingAddIds()

    /**
     * 某个收藏夹里处于「待同步」状态的作品（本地已加、云端还没收到）。
     *
     * 界面据此在作品卡片上显示标识，点它就能重试。
     */
    fun pendingWorkIdsInPlaylist(playlistId: String): Set<Int> = pendingWrites()
        .filterIsInstance<PendingWrite.PlaylistAdd>()
        .filter { it.playlistId == playlistId }
        .map { it.workId }
        .toSet()

    private fun recordPending(write: PendingWrite) {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return
        PendingWriteStore.add(state.host, state.displayName, write)
    }

    private fun clearPending(write: PendingWrite) {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return
        PendingWriteStore.remove(state.host, state.displayName, write)
    }

    /**
     * 设置 / 更新某个作品的收藏状态与评分（**写穿**：本地先改，再打服务端）。
     *
     * [status] 为 null 时表示"只评分、不打状态"；[remove] 为 true 时才真的移除整条标记。
     * 详见 [updateCollectionOrThrow] —— 这里只是把它转成"错误文案或 null"的老签名，
     * 供不关心失败原因的调用方（列表页改状态）使用。
     */
    suspend fun updateCollection(
        workId: Int,
        status: CollectionStatus?,
        rating: Int? = null,
        remove: Boolean = false,
        work: Work? = null
    ): String? {
        val result: Result<Unit> = runCatching {
            updateCollectionOrThrow(workId, status, rating, remove, work)
        }
        return result.exceptionOrNull()?.let { e -> e.message ?: "收藏操作失败" }
    }

    /**
     * 同 [updateCollection]，但**失败时抛出原始异常**。
     *
     * 详情页需要它：只有拿到原始异常，才能把失败按类型分类成
     * "登录已失效 / 网络超时 / 服务端 5xx …" 并显示给用户
     * （见 `classifyMarkFailure`）。用一个拼好的字符串是做不到这件事的。
     *
     * ## 语义（重构后：本地优先，网络尽力而为）
     *
     * 1. **先写本地** —— 无条件、立即生效，然后通知界面重读。
     *    用户的视角是即时的：加了就能看到，删了就消失。
     * 2. 再发一次网络请求尝试同步到云端。
     * 3. 请求失败时：本地**保留**这次改动（不回滚），并在本地打标。
     *    - 网络/超时/5xx 等暂时性失败 → 记入待同步队列，卡片显示「待同步」，
     *      可点击重试，也会在下一次对齐时自动重试；
     *    - **凭据被拒（401/403）** → 不记队列（重试同样会被拒，挂着标识只会误导），
     *      只把异常抛给界面，让用户去重新登录。
     *
     * 本地写入是**先行且独立**的：网络成功与否都不影响界面已经显示的内容。
     */
    suspend fun updateCollectionOrThrow(
        workId: Int,
        status: CollectionStatus?,
        rating: Int? = null,
        remove: Boolean = false,
        work: Work? = null
    ) {
        if (!AuthManager.state.value.isLoggedIn) {
            throw AuthRejectedException("尚未登录", 401)
        }

        val targetStatus = if (remove) null else status
        // 本地写入需要作品的标题/封面等字段（新加时要插进列表）。
        // 调用方通常会传；没传就退回缓存里那份，再退回一个只带 id 的最小对象。
        val targetWork = work
            ?: workCache[workId]
            ?: Work(id = workId, title = "作品 $workId")

        // ① 本地先行 —— 网络成败都不影响它
        patchCollectionCache(targetWork, targetStatus, rating, remove, markDirty = true)

        try {
            if (remove) {
                api.deleteCollection(workId)
            } else {
                api.updateCollection(
                    workId = workId,
                    // 只有真的要设状态时才带 progress
                    progress = targetStatus?.apiValue,
                    rating = rating
                )
            }
        } catch (e: Exception) {
            if (e !is AuthRejectedException) {
                // 暂时性失败 → 打标等待重试。
                // 一次操作只留一条记录：先清掉两个方向上的旧意图，
                // 保证"待上传"与"待删除"互斥（同一作品不能既待加又待删）
                clearPending(PendingWrite.CollectionMark(workId, null, null, 0L))
                clearPending(PendingWrite.CollectionMark(workId, null, rating, 0L))
                recordPending(
                    PendingWrite.CollectionMark(
                        workId = workId,
                        status = targetStatus,
                        rating = rating,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            // 抛给界面做分类报错；本地内容与标识已经落好了
            throw e
        }

        // ② 网络成功
        clearPending(PendingWrite.CollectionMark(workId, targetStatus, rating, 0L))
        clearPending(PendingWrite.CollectionMark(workId, null, rating, 0L))
        clearPending(PendingWrite.CollectionMark(workId, null, null, 0L))
        // 记下"刚和服务端写过"：紧接着的自动对齐是多余的
        // 云端已确认 → 清掉 dirty（本地内容此刻与云端一致）
        patchCollectionCache(targetWork, targetStatus, rating, remove, markDirty = false)
        invalidateWorkMeta(workId)
    }

    /**
     * 只重试**某一个作品**的待同步记录。
     *
     * 用途：用户点卡片上的「待同步」标识 —— 不必等下一次每日对齐，
     * 立刻把这一条再送一次。成功即出队，失败就**保留标识**并返回错误文案。
     *
     * @return 出错时的文案；成功返回 null
     */
    suspend fun retryPendingForWork(workId: Int): String? {
        val state = AuthManager.state.value
        if (!state.isLoggedIn) return "尚未登录"

        val targets = pendingWrites().filter { it.workId == workId }
        if (targets.isEmpty()) return null

        var lastError: String? = null
        targets.forEach { write ->
            val result = runCatching {
                when (write) {
                    is PendingWrite.CollectionMark -> {
                        if (write.status == null) {
                            api.deleteCollection(write.workId)
                        } else {
                            api.updateCollection(
                                workId = write.workId,
                                progress = write.status.apiValue,
                                rating = write.rating
                            )
                        }
                        invalidateWorkMeta(write.workId)
                    }

                    is PendingWrite.PlaylistAdd ->
                        api.addWorksToPlaylist(write.playlistId, listOf(write.workId))

                    is PendingWrite.PlaylistRemove ->
                        api.removeWorksFromPlaylist(write.playlistId, listOf(write.workId))
                }
            }
            result.fold(
                onSuccess = {
                    clearPending(write)
                    if (write is PendingWrite.CollectionMark) {
                        // 云端已确认这一条 → 复查它所在的筛选缓存是否还有别的待同步项
                        markDirtyIfStillPending()
                    }
                },
                onFailure = { lastError = it.message ?: "重试失败" }
            )
        }
        notifyLibraryChanged()
        return lastError
    }

    /**
     * 没有待同步项了就清掉 dirty。
     *
     * dirty 的作用是"下次对齐别走 304、强制拿一次云端真实内容"；
     * 待同步项都补写成功后，本地与云端已一致，就该回到廉价的条件请求路径。
     *
     * 只有**一份**缓存需要处理（「全部」那份），状态筛选是它的派生视图。
     */
    private fun markDirtyIfStillPending() {
        if (pendingWrites().isNotEmpty()) return
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return
        val key = SyncCacheStore.SyncKeys.collection(state.host, state.displayName, null)
        if (SyncCacheStore.read(key, WorkPageCodec)?.dirty == true) {
            SyncCacheStore.touch(key, WorkPageCodec, dirty = false)
        }
    }

    /**
     * 补写所有待同步的操作。
     *
     * **顺序**（按需求）：校验完成之后才调用它 ——
     * 校验期间待同步的内容会被忽略（不被云端数据覆盖，见 [writeCollectionCache] 的
     * `preserveIds`），校验结束再把它们补写到云端。
     *
     * 同一目标的操作按记录时间升序重放；某一条成功就出队，
     * 失败则**保留在队列里**等下次机会（不反复重试，避免在服务端故障时打接口）。
     *
     * @return 成功补写的条数
     */
    suspend fun replayPendingWrites(): Int {
        val state = AuthManager.state.value
        if (!state.isLoggedIn) return 0
        var succeeded = 0
        for (write in pendingWrites()) {
            val ok = runCatching {
                when (write) {
                    is PendingWrite.CollectionMark -> {
                        if (write.status == null) {
                            // status == null 即"待删除"：本地已删，这里把云端也删掉
                            api.deleteCollection(write.workId)
                        } else {
                            api.updateCollection(
                                workId = write.workId,
                                progress = write.status.apiValue,
                                rating = write.rating
                            )
                        }
                        invalidateWorkMeta(write.workId)
                    }

                    is PendingWrite.PlaylistAdd ->
                        api.addWorksToPlaylist(write.playlistId, listOf(write.workId))

                    is PendingWrite.PlaylistRemove ->
                        api.removeWorksFromPlaylist(write.playlistId, listOf(write.workId))
                }
                true
            }.getOrDefault(false)

            if (ok) {
                clearPending(write)
                // 收藏夹内容变了 → 清 dirty，让下次对齐回到廉价的条件请求路径。
                //
                // ⚠️ 这里**不能**再 patch 一次内容：失败当时就已经"本地先行"过了，
                // 再应用一次同样的改动会重复增删件数（旧实现正是如此）。
                when (write) {
                    is PendingWrite.PlaylistAdd -> clearPlaylistCacheDirty(write.playlistId)
                    is PendingWrite.PlaylistRemove -> clearPlaylistCacheDirty(write.playlistId)
                    else -> Unit
                }
                succeeded++
            }
        }

        if (succeeded > 0) {
            // 补写成功后本地与云端已一致 → 清掉各筛选缓存上的 dirty，
            // 让下次对齐回到廉价的条件请求路径
            markDirtyIfStillPending()
        }
        return succeeded
    }

    /**
     * 把一次标记/取消标记落到**本地缓存**里。
     *
     * ## 这是"实时显示"的关键（重构后：本地是主数据源）
     *
     * 用户点完标记，收藏必须立刻能看到 —— 这是本地 JSON 的写入结果。
     * 云端对齐被限定为"每天首次进入 + 用户手动刷新"，所以这里**无条件**写本地：
     * 不依赖内存快照、也不依赖是否已经有一份完整基线。
     *
     * （早期版本要求"先有完整基线才敢改"，避免凭空造出"收藏里只有这一部作品"。
     * 现在不再需要这个顾虑 —— 本地就是权威，一份只含一条的列表也是**正确**的
     * 用户数据，而不是假象。）
     *
     * @param work 被操作的作品。新增时要用它的标题/封面等字段插入列表，所以不能只传 id。
     * @param markDirty 本地改动**尚未被服务端确认**（写入失败）时置 true。
     *   它会让下次对齐跳过 `If-None-Match`，改用无条件请求，拿一次云端真实内容来合并。
     */
    private fun patchCollectionCache(
        work: Work,
        status: CollectionStatus?,
        rating: Int?,
        remove: Boolean,
        markDirty: Boolean
    ) {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return

        // 这次操作后作品应有的样子
        val target = work.copy(
            progress = if (remove) null else status?.apiValue,
            userRating = if (remove) null else (rating ?: work.userRating)
        )

        // 只维护**一份**本地列表（「全部」那份）。
        // 各状态筛选是读取时按 progress 派生的（见 [cachedCollection]），
        // 所以这里不需要、也不该再往每个筛选各写一份 —— 那是冗余且会互相不一致。
        val key = SyncCacheStore.SyncKeys.collection(state.host, state.displayName, null)
        val existing = SyncCacheStore.read(key, WorkPageCodec)

        val items = existing?.value?.items?.map { it.toWork() }.orEmpty()
        val updated = CollectionCacheOps.dedupe(
            // filter = null：这是「全部」，任何状态的作品都留在这里
            CollectionCacheOps.apply(items, filter = null, work = target, remove = remove)
        )

        SyncCacheStore.write(
            key,
            WorkPageCodec,
            SyncCacheStore.CacheEntry(
                value = CachedWorkPage(
                    items = updated.map { CachedWork.fromWork(it) },
                    // 移除时总数减一；新加时加一。用"至少为已有条数"兜底
                    totalCount = when {
                        remove -> (existing?.value?.totalCount ?: items.size) - 1
                        existing?.value?.items?.any { it.id == work.id } == true ->
                            existing?.value?.totalCount ?: updated.size

                        else -> (existing?.value?.totalCount ?: updated.size) + 1
                    }.coerceAtLeast(updated.size),
                    // 页数保持原样：这里只是就地改了一条，没有多拉一页
                    loadedPages = existing?.value?.loadedPages ?: 1
                ),
                // ETag 必须作废：本地内容已经**不再等于**服务端那份，
                // 拿着旧 ETag 去问会错误地收到 304，从而永远对齐不上
                etag = null,
                // ⚠️ **不要**把 syncedAt 推到现在。
                //
                // 这个字段的语义是"上次与服务端对齐的时间"，界面用它显示
                // "刚刚更新 / 3 分钟前"。一次本地写入**并没有对齐过云端**，
                // 把它推到现在会显示成"刚刚更新"——那是在宣称一件没发生的事
                // （用户已经明确反馈过这一点）。
                //
                // 写入是否成功另有表达：成功→列表立刻可见；失败→卡片上的「待同步」标识。
                syncedAt = existing?.syncedAt ?: 0L,
                dirty = markDirty
            )
        )

        // 内存快照也顺手更新，避免同一会话内读到旧值
        if (collectionSnapshotKey != null) {
            collectionSnapshot = CollectionCacheOps.dedupe(
                CollectionCacheOps.apply(
                    collectionSnapshot.orEmpty(),
                    filter = null,
                    work = target,
                    remove = remove
                )
            )
        }

        // 告诉收藏"本地变了，重读一次本地缓存"（不发请求）
        notifyLibraryChanged()
    }

    /** 退出登录 / 切换账号：清掉与账号绑定的内存快照与在途登记 */
    fun onAccountChanged() {
        playlistsSnapshot = null
        collectionSnapshot = null
        collectionSnapshotKey = null
        SyncGate.reset()
    }

    // ─────────────────────────── 播放列表（收藏夹）───────────────────────────
    //
    // 收藏的两个入口之一：把作品加进收藏夹（另一个是 /api/review 的状态标记）。
    // 系统内置两个收藏夹：`__SYS_PLAYLIST_MARKED`（我标记的）与
    // `__SYS_PLAYLIST_LIKED`（我喜欢的），它们不允许删除。

    /** 内存快照：避免每次写盘后再从 JSON 反序列化一遍 */
    private var playlistsSnapshot: List<Playlist>? = null

    private fun currentPlaylistsKey(): String? {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return null
        return SyncCacheStore.SyncKeys.playlists(state.host, state.displayName)
    }

    /**
     * 收藏夹列表（带本地缓存 + 条件请求 + 每日门槛）。
     *
     * @param validate false = 只用本地，不发请求（见 [loadCollection] 的说明）
     *
     * `filterBy=all` 时服务端还会返回"我收藏的别人的"收藏夹，
     * 但**收藏作品的入口只应列出自己的**，所以按 `user_name` 过滤掉别人的。
     */
    suspend fun loadPlaylists(trigger: SyncTrigger? = SyncTrigger.DAILY_AUTO): List<Playlist> {
        val key = currentPlaylistsKey() ?: return emptyList()
        val existing = SyncCacheStore.read(key, PlaylistsCodec)

        if (trigger != null) {
            SyncGate.sync(key, trigger) {
                val result = api.getPlaylistsConditional(ifNoneMatch = existing?.etag)
                if (result == null) {
                    SyncCacheStore.touch(key, PlaylistsCodec)
                } else {
                    val me = AuthManager.state.value.displayName
                    val mine = result.page.playlists.filter { it.userName == null || it.userName == me }
                    SyncCacheStore.write(
                        key,
                        PlaylistsCodec,
                        SyncCacheStore.CacheEntry(
                            value = CachedPlaylists(mine),
                            etag = result.etag,
                            syncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return cachedPlaylists().orEmpty()
    }

    /** 只读本地：收藏夹列表 */
    fun cachedPlaylists(): List<Playlist>? {
        val key = currentPlaylistsKey() ?: return null
        val entry = SyncCacheStore.read(key, PlaylistsCodec) ?: return null
        playlistsSnapshot = entry.value.items
        return entry.value.items
    }

    /** 收藏夹列表的最后同步时间（用于「x 分钟前同步」） */
    fun playlistsSyncedAt(): Long {
        val key = currentPlaylistsKey() ?: return 0L
        return SyncCacheStore.read(key, PlaylistsCodec)?.syncedAt ?: 0L
    }

    /** 从内存快照同步取（弹窗打开时先用它铺界面，避免空白等待） */
    fun peekPlaylists(): List<Playlist>? = playlistsSnapshot ?: cachedPlaylists()

    /**
     * 【默认收藏夹】的选取规则：
     *  1. 有上次选择且那个收藏夹还在 → 用它（用户上次的意图优先）；
     *  2. 否则用「我标记的」（系统内置的第一个收藏夹）；
     *  3. 再否则用列表里的第一个。
     *
     * 对应需求："默认选择上一次选择的收藏夹，第一次则选择我标记的"。
     */
    fun defaultPlaylist(playlists: List<Playlist>): Playlist? {
        if (playlists.isEmpty()) return null
        val lastId = AppPrefs.lastSelectedPlaylistId.value
        playlists.firstOrNull { it.id == lastId }?.let { return it }
        playlists.firstOrNull { it.name == Playlist.SYS_MARKED }?.let { return it }
        return playlists.first()
    }

    /** 记住用户这次选的收藏夹（下次进入弹窗时默认选中它） */
    fun rememberSelectedPlaylist(playlistId: String) {
        AppPrefs.setLastSelectedPlaylistId(playlistId)
    }

    /**
     * 一次收藏夹写入的结果。
     *
     * [changed] 直接来自服务端返回的 `rowCount`（实测：改动了是 1，无操作是 0）。
     * 这个字段决定了缓存该怎么处理 —— 见 [addWorkToPlaylist] / [removeWorkFromPlaylist]。
     */
    data class PlaylistWriteResult(
        val error: String? = null,
        /** 服务端是否真的改动了数据 */
        val changed: Boolean = true
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 把"加入/移出收藏夹"落到本地缓存里，让收藏**立刻**反映出来。
     *
     * ## 与「标记」完全一致：本地是主数据源
     *
     * 用户的动作本地先行、立刻可见，网络只是尽力而为：
     *  - 加入 → 插进本地列表并立即显示；网络失败则打「待同步」标识
     *  - 移出 → 从本地列表移除并立即消失；网络失败则等下次对齐删云端
     *
     * ## 不再要求"本地这份内容必须是完整的"
     *
     * 曾经这里有一个 `entry.complete` 门槛：只有手里握着**完整的一份**内容才敢
     * 就地插入，否则作废缓存、等下次重新拉。为了守住那个前提，外面还长出了
     * 「收藏前先把内容缓存好」「新建时预置空缓存」「每日扫描补齐」
     * 「另一个文件单独记一个已缓存标识」等一整套兜底。
     *
     * 那些机制现在全部删掉了。理由是「标记」列表早就给出过的同一个结论：
     * **本地就是权威，一份只含一条的列表也是正确的用户数据，不是假象。**
     * 手里只有前几页时插一条，最坏情况也只是"暂时少显示几部"，
     * 下一次对齐（服务端 200 全量替换）就会补回来 —— 这比"什么都不显示"
     * 和"每次收藏前先多发一个读请求"都好。
     *
     * ⚠️ 注意只更新缓存里的 `worksCount`，**不重建**播放列表数组 ——
     * 之前重建过一次，`latestWorkId` 等字段会因此丢失（被读成 0）。
     *
     * @param workId 被操作的作品 id。**必须有**：移出时即使拿不到作品快照
     *   （`work` 为 null）也能把那条从列表里摘掉。
     * @param work 作品快照，加入时需要它的标题/封面才能插进列表卡片；
     *   移出时可以传 null。
     * @param markDirty 本地改动尚未被服务端确认时置 true，让下次对齐跳过 304、
     *   强制拿一次云端内容。
     */
    private fun patchPlaylistWorksCache(
        playlistId: String,
        workId: Int,
        remove: Boolean,
        work: Work? = null,
        markDirty: Boolean = false
    ) {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return
        if (playlistId.isBlank() || workId <= 0) return

        val worksKey = SyncCacheStore.SyncKeys.playlistWorks(state.host, state.displayName, playlistId)
        val entry = SyncCacheStore.read(worksKey, WorkPageCodec)

        // 没有基线时才去读列表里的件数（短路求值：有缓存时一个字段都不多读）
        val baselineTotal = entry?.value?.totalCount ?: cachedPlaylistWorksCount(playlistId) ?: 0

        // 该落成什么样全在纯函数里（可单测），这里只负责读、写、通知
        val plan = PlaylistPatchOps.compute(
            current = entry?.value,
            baselineTotal = baselineTotal,
            workId = workId,
            work = work,
            remove = remove
        ) ?: return

        plan.content?.let { content ->
            SyncCacheStore.write(
                worksKey,
                WorkPageCodec,
                SyncCacheStore.CacheEntry(
                    value = content,
                    // 本地列表变了，旧 ETag 不再代表服务端当前内容 → 置空，
                    // 下次核对会做无条件请求并全量替换（若服务端结果不同就纠正回来）
                    etag = null,
                    // 本地写入不等于与云端核对过，不能推高这个时间戳
                    syncedAt = entry?.syncedAt ?: System.currentTimeMillis(),
                    dirty = markDirty
                )
            )
        }

        // 收藏夹列表：只改件数，数组本身不动（见 patchPlaylistsWorksCount 的说明）
        patchPlaylistsWorksCount(playlistId, delta = plan.countDelta, latestWorkId = work?.id)
        notifyLibraryChanged()
    }

    /**
     * 只改收藏夹列表里的 `worksCount`（与 `latestWorkId`），**不动数组本身**。
     *
     * ⚠️ 为什么"不重建数组"：之前重建过一次，`latestWorkId` 等字段会因此
     * 丢失（被读成 0）。
     *
     * 用**增量**而不是布尔方向，是为了让调用方按"实际发生的改动"传值：
     * 拿不到作品快照、或作品本来就在里面时，增量为 0 就不会造成件数漂移。
     */
    private fun patchPlaylistsWorksCount(
        playlistId: String,
        delta: Int,
        latestWorkId: Int? = null
    ) {
        if (delta == 0) return
        val listKey = currentPlaylistsKey() ?: return
        val listEntry = SyncCacheStore.read(listKey, PlaylistsCodec) ?: run {
            playlistsSnapshot = null
            return
        }
        val patched = listEntry.value.items.map { p ->
            if (p.id != playlistId) return@map p
            p.copy(
                worksCount = (p.worksCount + delta).coerceAtLeast(0),
                latestWorkId = if (delta > 0) (latestWorkId ?: p.latestWorkId) else p.latestWorkId
            )
        }
        SyncCacheStore.write(
            listKey,
            PlaylistsCodec,
            SyncCacheStore.CacheEntry(
                value = CachedPlaylists(patched),
                etag = listEntry.etag,
                syncedAt = listEntry.syncedAt,
                dirty = listEntry.dirty
            )
        )
        playlistsSnapshot = patched
    }

    /**
     * 收藏夹列表缓存里记录的件数（R1 用的基线）。
     *
     * 只读本地、不发请求：它的唯一用途是"本地没有内容缓存时，
     * 别把 `totalCount` 写成 0 或 1"。
     */
    private fun cachedPlaylistWorksCount(playlistId: String): Int? {
        val listKey = currentPlaylistsKey() ?: return null
        val items = SyncCacheStore.read(listKey, PlaylistsCodec)?.value?.items ?: return null
        return items.firstOrNull { it.id == playlistId }?.worksCount
    }

    /**
     * 撤销一次"本地先行"的收藏夹改动。
     *
     * 用在服务端明确回答**收藏夹不存在**时：既然这次收藏没有成功、
     * 也不会重试，本地就不该继续显示它 —— 否则用户会看到"明明说失败了，
     * 列表里却多了一部作品"。
     *
     * "加入"那条如果当初就没能插入（拿不到作品快照），也就没什么可撤销的，
     * 这里直接跳过 —— 否则会凭空把件数减掉一条。
     */
    private fun revertPlaylistPatch(
        playlistId: String,
        workId: Int,
        work: Work?,
        remove: Boolean
    ) {
        val wasInsert = !remove
        if (wasInsert && work == null) return
        patchPlaylistWorksCache(
            playlistId = playlistId,
            workId = workId,
            remove = !remove,
            work = work
        )
    }

    /**
     * 写入已被服务端确认 → 只把 `dirty` 降级，**内容与件数一概不再改动**。
     *
     * ## 为什么不能在这里再调一次 patchPlaylistWorksCache
     *
     * 旧实现就是这么做的（成功后再 patch 一遍、只把 `markDirty` 换成 false），
     * 但 `patchPlaylistWorksCache` 会**无条件**把件数 ±1 —— 于是
     * "本地先行 +1"之后"成功确认再 +1"，**成功收藏一次，列表里显示 +2**。
     * 移出同理。虽然下一次列表同步会用服务端数字覆盖回来，但在那之前
     * 用户看到的就是错的，而且跨天才会自愈。
     *
     * 所以确认动作单独一个函数：它只做一件事 —— 清掉"尚未确认"这个标记。
     */
    private fun clearPlaylistCacheDirty(playlistId: String) {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return
        val key = SyncCacheStore.SyncKeys.playlistWorks(state.host, state.displayName, playlistId)
        val entry = SyncCacheStore.read(key, WorkPageCodec) ?: return
        if (!entry.dirty) return
        SyncCacheStore.write(key, WorkPageCodec, entry.copy(dirty = false))
    }

    /**
     * 把作品加入收藏夹（**本地优先**：先落本地、立刻可见，再尝试同步云端）。
     *
     * 网络失败时**不回滚本地** —— 内容照样显示在收藏夹里，
     * 只是打上「待同步」标识，之后可点击重试或等下一次对齐自动补上。
     *
     * **云端说"无操作"（`rowCount = 0`）也不算失败**：
     * 那说明这部作品本来就在里面，服务端内容与本地看到的一致。
     */
    suspend fun addWorkToPlaylist(playlistId: String, workId: Int, work: Work? = null): PlaylistWriteResult {
        if (!AuthManager.state.value.isLoggedIn) return PlaylistWriteResult(error = "未登录")

        val target = work ?: workCache[workId]

        // ① 本地先行 —— 用户立刻看得到（不再先"把内容缓存好"：见 patchPlaylistWorksCache 的说明）
        patchPlaylistWorksCache(
            playlistId = playlistId,
            workId = workId,
            remove = false,
            work = target,
            markDirty = true
        )

        return runCatching {
            val changed = rowCountOf(api.addWorksToPlaylist(playlistId, listOf(workId)))
            // 成功 → 清掉反方向（用户可能先前在离线时移除过）与本次的待同步记录
            clearPending(PendingWrite.PlaylistRemove(playlistId, workId, createdAt = 0L))
            clearPending(PendingWrite.PlaylistAdd(playlistId, workId, createdAt = 0L))
            // 只清 dirty —— 内容与件数在上面已经落好了，再 patch 一次会重复计数
            clearPlaylistCacheDirty(playlistId)
            PlaylistWriteResult(changed = changed)
        }.getOrElse { e ->
            // ★ 收藏夹已经不存在 → **不强行收藏**，也不记待补写。
            //
            // 这个场景：收藏夹在网页端/另一台设备被删了，而本地因为
            // "每天只同步一次"还显示着它。此时重试永远不会成功，
            // 记一条待补写只会留下永久失败的记录，并且每次对齐都多打一次请求。
            // 所以直接告诉用户原因，并把这笔本地改动撤销掉。
            if (e is PlaylistNotFoundException) {
                revertPlaylistPatch(playlistId, workId, target, remove = false)
                return PlaylistWriteResult(error = "这个收藏夹已不存在（可能已在网页端删除），没有收藏")
            }
            // 失败 → 保留本地内容 + 打标等待重试（下一次对齐或用户点击）
            recordPending(
                PendingWrite.PlaylistAdd(playlistId, workId, createdAt = System.currentTimeMillis())
            )
            PlaylistWriteResult(error = e.message ?: "加入收藏夹失败")
        }
    }

    /**
     * 把作品从收藏夹移除（**本地优先**：先落本地、立刻消失，再尝试同步云端）。
     *
     * 网络失败时本地**依然是删掉的状态**（用户看到的就是删掉了），
     * 等下一次对齐再把云端那条删掉。
     *
     * **云端说"无操作"（`rowCount = 0`）说明它本来就不在里面**，
     * 那正是我们想要的结果，不算失败。
     */
    suspend fun removeWorkFromPlaylist(playlistId: String, workId: Int): PlaylistWriteResult {
        if (!AuthManager.state.value.isLoggedIn) return PlaylistWriteResult(error = "未登录")

        // ① 本地先行 —— 用户立刻看到它消失了
        patchPlaylistWorksCache(
            playlistId = playlistId,
            workId = workId,
            remove = true,
            work = workCache[workId],
            markDirty = true
        )

        return runCatching {
            val changed = rowCountOf(api.removeWorksFromPlaylist(playlistId, listOf(workId)))
            // 成功 → 清掉反方向与本次的待同步记录
            clearPending(PendingWrite.PlaylistAdd(playlistId, workId, createdAt = 0L))
            clearPending(PendingWrite.PlaylistRemove(playlistId, workId, createdAt = 0L))
            // 同样只清 dirty：本地那次"先删掉"已经落好了，再 patch 一次会重复减件数
            clearPlaylistCacheDirty(playlistId)
            PlaylistWriteResult(changed = changed)
        }.getOrElse { e ->
            // ★ 收藏夹已经不存在 → 不记待补写（重试永远不会成功），
            // 并把本地那次"先删掉"撤销回来，避免界面与真实状态长期不一致。
            if (e is PlaylistNotFoundException) {
                revertPlaylistPatch(playlistId, workId, workCache[workId], remove = true)
                return PlaylistWriteResult(error = "这个收藏夹已不存在（可能已在网页端删除）")
            }
            // 失败 → 本地保持"已移除"，打标等下次对齐删云端
            recordPending(
                PendingWrite.PlaylistRemove(playlistId, workId, createdAt = System.currentTimeMillis())
            )
            PlaylistWriteResult(error = e.message ?: "移出收藏夹失败")
        }
    }

    /**
     * 从写接口的响应里取 `rowCount`。
     *
     * 实测响应形如 `{"id":"…","rowCount":1}`；`rowCount = 1` 表示真的改了，
     * `0` 表示"没有需要改的"（重复加入 / 本来就不在里面）。
     *
     * 拿不到这个字段时（响应体为空、或字段名变了）保守地当成"有改动"，
     * 于是走作废缓存那条更安全的路。
     */
    private fun rowCountOf(response: JSONObject): Boolean {
        if (response.length() == 0) return true   // 204 空响应，无从判断
        if (!response.has("rowCount")) return true
        return response.optInt("rowCount", 1) > 0
    }

    /**
     * 某个收藏夹里有没有这个作品（用来决定弹窗里显示「加入」还是「移出」）。
     *
     * **先查本地缓存**：收藏夹内容本来就整份存在本地，
     * 所以打开收藏弹窗不会因为"检查成员关系"而多打一次接口。
     * 只有本地没有这份内容时才回落到服务端。
     */
    suspend fun isWorkInPlaylist(playlistId: String, workId: Int): Boolean {
        cachedPlaylistWorks(playlistId)?.let { page ->
            return page.works.any { it.id == workId }
        }
        return runCatching {
            var page = 1
            while (page <= WORK_IN_PLAYLIST_MAX_PAGES) {
                val result = api.getPlaylistWorks(playlistId, page = page, pageSize = 50)
                if (result.items.any { it.id == workId }) return@runCatching true
                if (!result.hasMore || result.items.isEmpty()) break
                page++
            }
            false
        }.getOrDefault(false)
    }

    private fun currentPlaylistWorksKey(playlistId: String): String? {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return null
        return SyncCacheStore.SyncKeys.playlistWorks(state.host, state.displayName, playlistId)
    }

    /** 只读本地：某个收藏夹里的作品 */
    fun cachedPlaylistWorks(playlistId: String): CachedCollection? {
        val key = currentPlaylistWorksKey(playlistId) ?: return null
        val entry = SyncCacheStore.read(key, WorkPageCodec) ?: return null
        return CachedCollection(
            works = entry.value.items.map { it.toWork() },
            totalCount = entry.value.totalCount,
            loadedPages = entry.value.loadedPages,
            syncedAt = entry.syncedAt
        )
    }

    /** 收藏夹里的作品（带缓存 + 条件请求 + 每日门槛） */
    suspend fun loadPlaylistWorks(
        playlistId: String,
        trigger: SyncTrigger? = SyncTrigger.DAILY_AUTO
    ): CachedCollection? {
        val key = currentPlaylistWorksKey(playlistId) ?: return null
        val existing = SyncCacheStore.read(key, WorkPageCodec)

        if (trigger != null) {
            // 这个收藏夹里"本地已改、云端还没收到"的条目。
            // 替换本地数据时必须保护它们，否则用户刚加的作品会被云端旧列表抹掉。
            val pendingAdds = pendingPlaylistAddIds(playlistId)
            val pendingRemoves = pendingPlaylistRemoveIds(playlistId)

            SyncGate.sync(key, trigger) {
                val result = api.getPlaylistWorksConditional(
                    playlistId = playlistId,
                    page = 1,
                    pageSize = PLAYLIST_WORKS_PAGE_SIZE,
                    // 本地有未确认的改动 → 不做条件请求，强制拿一次云端真实内容，
                    // 否则会错误地收到 304、永远对齐不上
                    ifNoneMatch = existing?.etag.takeIf { existing?.dirty != true }
                )
                if (result == null) {
                    // 304：云端与本地一致。只把时间推新、清掉 dirty，
                    // 再清一遍"用户已移出但云端也移出了"的残留
                    SyncCacheStore.touch(key, WorkPageCodec, dirty = false)
                    purgeRemovedFromPlaylistCache(playlistId, pendingRemoves)
                } else {
                    writePlaylistWorksCache(
                        key = key,
                        playlistId = playlistId,
                        page = result.page,
                        etag = result.etag,
                        pendingAdds = pendingAdds,
                        pendingRemoves = pendingRemoves
                    )
                }
            }
            // 校验完成之后，才把该收藏夹的待同步操作补写到云端
            if (pendingAdds.isNotEmpty() || pendingRemoves.isNotEmpty()) {
                replayPendingWrites()
                // 补写可能改变了收藏夹内容 → 重新读一次本地
                cachedPlaylistWorks(playlistId)?.let { return it }
            }
        }
        return cachedPlaylistWorks(playlistId)
    }

    /**
     * 某一收藏夹里**待上传**的作品 id（本地已加、云端还没收到）。
     *
     * 与 [pendingAddIds]（那是 `/api/review` 的标记）分开：这是收藏夹的加入操作，
     * 走 `/api/playlist/add-works-to-playlist`，两套操作互不相关。
     */
    private fun pendingPlaylistAddIds(playlistId: String): Set<Int> = pendingWrites()
        .filterIsInstance<PendingWrite.PlaylistAdd>()
        .filter { it.playlistId == playlistId }
        .map { it.workId }
        .toSet()

    /** 某一收藏夹里**待删除**的作品 id（本地已移出、云端还留着） */
    private fun pendingPlaylistRemoveIds(playlistId: String): Set<Int> = pendingWrites()
        .filterIsInstance<PendingWrite.PlaylistRemove>()
        .filter { it.playlistId == playlistId }
        .map { it.workId }
        .toSet()

    /**
     * 把云端返回的收藏夹内容与**本地待同步状态**合并后写入。
     *
     * ## 为什么必须合并，而不是直接覆盖
     *
     * 这里曾经是个缺口：`/api/playlist/get-playlist-works` 返回 200 时
     * 直接 `items = result.page.items` 全量覆盖。后果是 ——
     * 用户刚把一部作品加进收藏夹、但那次上传失败（进了待补写队列），
     * 下次对齐拿到云端的旧列表（不含这部作品）→ **本地那条被抹掉**。
     * 待补写记录还在，稍后会重试，但在那之前用户看到的是"我加的作品消失了"。
     *
     * 合并规则与收藏标记一致（见 [CollectionCacheOps.resolveAgainstCloud]）：
     * 待删除的一律不出现、待上传的保留本地那份、其余以云端为准。
     */
    private fun writePlaylistWorksCache(
        key: String,
        playlistId: String,
        page: PageResult<Work>,
        etag: String?,
        pendingAdds: Set<Int> = emptySet(),
        pendingRemoves: Set<Int> = emptySet()
    ) {
        val remoteItems = page.items.map { CachedWork.fromWork(it) }
        val localItems = SyncCacheStore.read(key, WorkPageCodec)?.value?.items.orEmpty()

        val merged = if (pendingAdds.isEmpty() && pendingRemoves.isEmpty()) {
            // 没有待同步项 → 云端是唯一依据
            remoteItems
        } else {
            CollectionCacheOps.resolveAgainstCloud(
                remote = remoteItems.map { it.toWork() },
                local = localItems.map { it.toWork() },
                pendingAdds = pendingAdds,
                pendingRemoves = pendingRemoves
            ).map { CachedWork.fromWork(it) }
        }

        SyncCacheStore.write(
            key,
            WorkPageCodec,
            SyncCacheStore.CacheEntry(
                value = CachedWorkPage(
                    items = merged,
                    totalCount = maxOf(page.totalCount, merged.size),
                    loadedPages = page.currentPage
                ),
                etag = etag,
                syncedAt = System.currentTimeMillis(),
                // 有待同步项时标 dirty：下次不做条件请求，强制拿云端真实内容
                dirty = pendingAdds.isNotEmpty() || pendingRemoves.isNotEmpty()
            )
        )
    }

    /** 把"已被云端移出收藏夹"的作品从本地清掉（304 分支用） */
    private fun purgeRemovedFromPlaylistCache(playlistId: String, removedIds: Set<Int>) {
        if (removedIds.isEmpty()) return
        val key = currentPlaylistWorksKey(playlistId) ?: return
        val entry = SyncCacheStore.read(key, WorkPageCodec) ?: return
        val filtered = entry.value.items.filterNot { it.id in removedIds }
        if (filtered.size == entry.value.items.size) return

        SyncCacheStore.write(
            key,
            WorkPageCodec,
            entry.copy(
                value = entry.value.copy(
                    items = filtered,
                    totalCount = (entry.value.totalCount - (entry.value.items.size - filtered.size))
                        .coerceAtLeast(filtered.size)
                )
            )
        )
    }

    /** 收藏夹内的「加载更多」 */
    suspend fun loadMorePlaylistWorks(playlistId: String, nextPage: Int): CachedCollection? {
        val key = currentPlaylistWorksKey(playlistId) ?: return null
        val existing = SyncCacheStore.read(key, WorkPageCodec)
        if (existing != null && nextPage <= existing.value.loadedPages) {
            return cachedPlaylistWorks(playlistId)
        }

        SyncGate.sync("$key|page$nextPage", SyncTrigger.USER_REFRESH) {
            val result = api.getPlaylistWorksConditional(
                playlistId = playlistId,
                page = nextPage,
                pageSize = PLAYLIST_WORKS_PAGE_SIZE,
                ifNoneMatch = null
            ) ?: return@sync
            val merged =
                (existing?.value?.items.orEmpty() + result.page.items.map { CachedWork.fromWork(it) })
                    .distinctBy { it.id }
            SyncCacheStore.write(
                key,
                WorkPageCodec,
                SyncCacheStore.CacheEntry(
                    value = CachedWorkPage(merged, result.page.totalCount, loadedPages = nextPage),
                    etag = existing?.etag,
                    syncedAt = existing?.syncedAt ?: System.currentTimeMillis(),
                    dirty = existing?.dirty ?: false
                )
            )
        }
        return cachedPlaylistWorks(playlistId)
    }

    /** 单个收藏夹的元数据（先本地后网络；元数据很小，网络代价可忽略） */
    suspend fun getPlaylistMetadata(
        playlistId: String,
        trigger: SyncTrigger? = SyncTrigger.DAILY_AUTO
    ): Playlist? {
        val state = AuthManager.state.value
        if (state.host.isBlank() || state.displayName.isBlank()) return null
        val key = SyncCacheStore.SyncKeys.playlistMeta(state.host, state.displayName, playlistId)
        val existing = SyncCacheStore.read(key, PlaylistMetaCodec)

        if (trigger != null) {
            SyncGate.sync(key, trigger) {
                val result = api.getPlaylistMetadataConditional(playlistId, existing?.etag)
                if (result == null) {
                    SyncCacheStore.touch(key, PlaylistMetaCodec)
                } else {
                    SyncCacheStore.write(
                        key,
                        PlaylistMetaCodec,
                        SyncCacheStore.CacheEntry(
                            value = CachedPlaylistMeta(result.playlist),
                            etag = result.etag,
                            syncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return SyncCacheStore.read(key, PlaylistMetaCodec)?.value?.playlist
    }

    /**
     * 新建收藏夹。
     *
     * ## 不再需要"预置一份空内容缓存"
     *
     * 旧实现会在这里顺手写一份 `complete = true` 的空 `playlistWorks` 缓存，
     * 目的是让之后的"收藏"能走"有完整基线 → 就地插入"那条分支。
     *
     * 现在那条分支已经不存在了（见 [patchPlaylistWorksCache]），
     * 没有基线时收藏会直接以"件数基线 + 这一条"建出内容缓存，
     * 结果等价而少一次写盘、也少一个"必须与创建动作原子"的约束。
     */
    suspend fun createPlaylist(name: String, description: String? = null): Result<String?> {
        if (!AuthManager.state.value.isLoggedIn) return Result.failure(IllegalStateException("未登录"))
        return runCatching {
            val id = api.createPlaylist(name = name, description = description)
            // 列表缓存作废，让下次读取拿到带 id 的新列表
            currentPlaylistsKey()?.let { SyncCacheStore.delete(it) }
            playlistsSnapshot = null
            id
        }
    }

    /**
     * 删除收藏夹（系统收藏夹不能删，这里先挡一道）。
     *
     * ## 同时丢掉它的全部待补写操作
     *
     * 用户删掉收藏夹，语义上就是"**这个收藏夹里的内容我都不要了**"。
     * 于是那些"往它里面加作品"的待补写意图已经失去意义 ——
     * 留着只会让每次对齐都为它多打一次注定失败的请求
     * （收藏夹都不存在了，服务端只会回 `playlistNotFound`）。
     *
     * 所以这里一并清理，不再为它做下一次同步。
     */
    suspend fun deletePlaylist(playlist: Playlist): Result<Unit> {
        if (playlist.isSystem) {
            return Result.failure(IllegalStateException("「${playlist.displayName}」是系统收藏夹，不能删除"))
        }
        return runCatching {
            api.deletePlaylist(playlist.id)
            // 不再为这个收藏夹补写任何东西
            val state = AuthManager.state.value
            if (state.host.isNotBlank() && state.displayName.isNotBlank()) {
                PendingWriteStore.removeAllForPlaylist(state.host, state.displayName, playlist.id)
            }
            currentPlaylistsKey()?.let { SyncCacheStore.delete(it) }
            currentPlaylistWorksKey(playlist.id)?.let { SyncCacheStore.delete(it) }
            playlistsSnapshot = null
        }
    }

    /** 切换主机后清掉缓存，避免跨站数据混用 */
    fun clearCaches() {
        tagCache = null
        workCache.clear()
        trackCache.clear()
        playlistsSnapshot = null
        collectionSnapshot = null
        collectionSnapshotKey = null
        SyncCacheStore.clearAll()
        SyncGate.reset()
    }

    /** 判断"作品是否在收藏夹里"时最多查多少页（每页 50 → 2500 部） */
    private const val WORK_IN_PLAYLIST_MAX_PAGES = 50

    /** 标记列表每页条数（与服务端一致） */
    private const val COLLECTION_PAGE_SIZE = 20

    /** 收藏夹内每页条数 */
    private const val PLAYLIST_WORKS_PAGE_SIZE = 20
}

/**
 * 从缓存读出的一次收藏数据。
 *
 * 它**只是服务端内容的本地副本**，不是独立数据源：
 * 每次进入页面都会用 ETag 向服务端确认一次，变了就整体替换。
 * 所以这里没有"过期/待同步"这类状态 —— 是否需要新数据不由本地判断。
 */
data class CachedCollection(
    val works: List<Work>,
    val totalCount: Int,
    /**
     * 本地已覆盖到第几页。
     *
     * 「加载更多」靠它算下一页 —— 所以刷新后必须跟着缓存一起回退成 1，
     * 否则会以为"已经有 3 页了"而跳到第 4 页，中间第 2、3 页永远补不上。
     */
    val loadedPages: Int = 1,
    /** 上次与服务器确认的时间（仅用于展示，不参与任何策略判断） */
    val syncedAt: Long
)

/** 详情页的一次加载结果：元数据与文件树分开报告错误 */
data class DetailLoad(
    val work: Work?,
    val metaError: String?,
    val tracksError: String?
)
