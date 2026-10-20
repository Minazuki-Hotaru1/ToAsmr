package com.asmrone.app.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.cache.SyncTrigger
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.sync.PendingSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 「列表」页签（收藏夹列表）状态 */
data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val loading: Boolean = true,
    /** 正在响应用户的下拉刷新（只驱动指示器，不影响内容展示） */
    val refreshing: Boolean = false,
    val error: String? = null,
    val syncedAt: Long = 0L,
    /** 用户主动刷新失败时要弹的轻量提示（自动对齐失败不填这里） */
    val toastMessage: String? = null
)

/** 单个收藏夹内部的状态 */
data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val works: List<Work> = emptyList(),
    val totalCount: Int = 0,
    val loadedPages: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** 正在响应用户的下拉刷新 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val syncedAt: Long = 0L,
    /** 本地已加、云端还没收到的作品（卡片上标「待同步」，可点击重试） */
    val pendingIds: Set<Int> = emptySet(),
    /** 正在重试的作品 id */
    val retryingId: Int? = null,
    /** 用户主动刷新失败时要弹的轻量提示（自动对齐失败不填这里） */
    val toastMessage: String? = null
)

/**
 * 收藏夹列表 ViewModel。
 *
 * 与「标记」页签同一套策略：**进入页面时只有当天还没核对过才发请求**；
 * 用户主动刷新、新建/删除收藏夹则立即核对。
 * 问到就以服务端为准（200 全量替换，304 继续用本地）。
 *
 * 系统收藏夹（我标记的 / 我喜欢的）排在前面 —— 它们是常用入口，顺序不该被"最近更新"打乱。
 */
class PlaylistsViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    /**
     * 进入页面 / 切回本页签。
     *
     * ## 两种进入方式
     *
     * - `reinitialize = false`（普通进入）：走 [SyncTrigger.DAILY_AUTO]，
     *   **发不发请求由 SyncGate 决定**（本次会话第一次打开这份数据才放行）。
     *   所以当天第二次起切到收藏是零请求的。
     * - `reinitialize = true`（账号真的变了）：整份丢掉重来，并强制核对一次。
     *   换了账号后本地是空的（缓存键含账号），必须单独放行。
     */
    fun onEnter(reinitialize: Boolean = false) {
        if (reinitialize) {
            _state.value = PlaylistsUiState(loading = true)
            sync(SyncTrigger.ACCOUNT_CHANGED, userInitiated = false)
            return
        }
        sync(SyncTrigger.DAILY_AUTO, userInitiated = false)
    }

    fun onAccountChanged() = onEnter(reinitialize = true)

    fun load() = sync(SyncTrigger.USER_REFRESH, userInitiated = false, notifyOnFailure = true)

    /** 用户下拉刷新：同样立即对齐，只是要让指示器转起来 */
    fun pullRefresh() = sync(
        SyncTrigger.USER_REFRESH,
        userInitiated = true,
        notifyOnFailure = true
    )

    /**
     * 收藏夹数据在别处被改了（加入/移出收藏夹、改标记状态）→ 就地重读本地。
     *
     * **不发请求**：这是本地写入的结果，要立刻可见。
     */
    fun onLocalDataChanged() {
        AsmrRepository.cachedPlaylists()?.let { cached ->
            _state.value = _state.value.copy(
                playlists = sortPlaylists(cached),
                loading = false,
                syncedAt = AsmrRepository.playlistsSyncedAt()
            )
        }
    }

    /**
     * @param successToast 对齐成功后要弹的一条提示（新建收藏夹用）。
     *
     * ⚠️ 必须由这里在**这次**同步的落点上写，而不是调用方先 `copy(toastMessage=…)`
     * 再调 [sync]：同步的收尾会整体重建 `PlaylistsUiState`，那条提示会被立刻覆盖掉，
     * 用户什么也看不到。
     */
    private fun sync(
        trigger: SyncTrigger?,
        userInitiated: Boolean,
        notifyOnFailure: Boolean = false,
        successToast: String? = null
    ) {
        // 先铺本地，避免对齐期间空白
        AsmrRepository.cachedPlaylists()?.let { cached ->
            _state.value = _state.value.copy(
                playlists = sortPlaylists(cached),
                loading = false,
                syncedAt = AsmrRepository.playlistsSyncedAt()
            )
        }
        if (userInitiated) {
            _state.value = _state.value.copy(refreshing = true, error = null, toastMessage = null)
        }

        viewModelScope.launch {
            runCatching { AsmrRepository.loadPlaylists(trigger) }
                .onSuccess { list ->
                    _state.value = PlaylistsUiState(
                        playlists = sortPlaylists(list),
                        loading = false,
                        refreshing = false,
                        syncedAt = AsmrRepository.playlistsSyncedAt(),
                        toastMessage = successToast
                    )
                }
                .onFailure { e ->
                    // 对齐失败不惊动用户：本地就是主数据源，列表照常展示。
                    // 只有两种情况要反馈：用户主动刷新（弹轻量提示）、
                    // 或本地什么都没有（整页错误态）。
                    val hasContent = _state.value.playlists.isNotEmpty()
                    val reason = e.message ?: "刷新失败"
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = if (hasContent) null else reason,
                        // 新建成功但这次对齐失败：收藏夹确实建好了，提示不能被错误信息顶掉
                        toastMessage = successToast ?: if (notifyOnFailure) reason else null
                    )
                }
        }
    }

    /** 系统收藏夹优先，其余保持服务端给的顺序 */
    private fun sortPlaylists(list: List<Playlist>): List<Playlist> =
        list.sortedByDescending { it.isSystem }

    /**
     * 删除收藏夹（**本地先行**：列表里立刻消失，云端在后台删）。
     *
     * 与收藏/标记同一套语义：先让用户看到结果，网络失败再靠一次对齐把它读回来并提示。
     * 系统收藏夹在这里就拦掉，仓库里还有第二道拦截。
     */
    fun delete(playlist: Playlist) {
        if (playlist.isSystem) {
            _state.value = _state.value.copy(
                toastMessage = "「${playlist.displayName}」是系统收藏夹，不能删除"
            )
            return
        }
        // ① 本地先行：清缓存 + 从列表里摘掉
        AsmrRepository.deletePlaylistLocally(playlist)
        _state.value = _state.value.copy(
            playlists = _state.value.playlists.filterNot { it.id == playlist.id },
            // 本地已经没了 → 立刻给反馈（云端失败会再提示一次并把它读回来）
            toastMessage = "已删除「${playlist.displayName}」"
        )

        // ② 云端后台删；失败就把列表对齐回来（收藏夹其实还在）并提示
        val key = "playlist-delete:${playlist.id}"
        PendingSync.hold(key) {
            val result = AsmrRepository.deletePlaylistRemote(playlist)
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "网络异常"
                sync(SyncTrigger.USER_REFRESH, userInitiated = false)
                _state.value = _state.value.copy(toastMessage = "删除失败：$reason")
            }
        }
        PendingSync.schedule(key, 0L)
    }

    /**
     * 新建收藏夹（「列表」页签顶栏的按钮）。
     *
     * 创建成功后**必须重新拉一次列表**：`createPlaylist` 只返回 id、
     * 并作废了本地列表缓存（见 `AsmrRepository.createPlaylist`），
     * 新条目要等服务端那份列表回来才有名字/封面/系统标记这些字段。
     *
     * 名字为空不当作错误弹错误页，只给一条轻量提示 —— 用户可能只是手滑点了按钮。
     */
    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(toastMessage = "收藏夹名字不能为空")
            return
        }
        viewModelScope.launch {
            AsmrRepository.createPlaylist(trimmed).fold(
                onSuccess = {
                    // 提示交给 sync 的收尾去写（见 sync 的 successToast 说明），
                    // 它能活过"整体重建 state"这一下
                    sync(
                        SyncTrigger.USER_REFRESH,
                        userInitiated = false,
                        successToast = "已新建「$trimmed」"
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        toastMessage = e.message ?: "新建收藏夹失败"
                    )
                }
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 提示已展示过 → 清掉，避免离开页面再回来时重复弹一次 */
    fun clearToast() {
        if (_state.value.toastMessage != null) {
            _state.value = _state.value.copy(toastMessage = null)
        }
    }

    /** 退出登录时清空 */
    fun reset() {
        _state.value = PlaylistsUiState(loading = true)
    }
}

/**
 * 收藏夹详情 ViewModel。
 *
 * 进入页面时走 DAILY_AUTO（每天第一次才真的问服务端）；用户下拉刷新不受此限。
 * 本地已有时先铺出来，所以打开是瞬时的；核对在后台进行。
 * 问到就以服务端为准 —— 网页端刚加/删的作品会在核对后反映出来。
 */
class PlaylistDetailViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private var playlistId: String = ""

    fun load(playlistId: String) {
        this.playlistId = playlistId
        // 唯一的一条自动路径：发不发请求由 SyncGate 决定
        sync(SyncTrigger.DAILY_AUTO, userInitiated = false)
    }

    /**
     * 用户**下拉刷新**：唯一的手动刷新入口。
     *
     * 顶栏的刷新按钮已按需求删除 —— 下拉已经覆盖了这个需求，
     * 本次会话第一次进入也会自动对齐一次，多一个入口只是噪音。
     * 失败时会弹一条轻量提示（用户明确要求了，得告诉他）。
     */
    fun pullRefresh() = sync(
        SyncTrigger.USER_REFRESH,
        userInitiated = true,
        notifyOnFailure = true
    )

    private fun sync(
        trigger: SyncTrigger?,
        userInitiated: Boolean,
        notifyOnFailure: Boolean = false
    ) {
        if (playlistId.isBlank()) return

        // ① 先铺本地
        AsmrRepository.cachedPlaylistWorks(playlistId)?.let { result ->
            _state.value = _state.value.copy(
                works = result.works,
                totalCount = result.totalCount,
                // 页数必须跟着缓存回退，否则「加载更多」会跳页
                loadedPages = result.loadedPages,
                hasMore = result.works.size < result.totalCount,
                loading = false,
                syncedAt = result.syncedAt,
                // 每次重读本地都刷新"待同步"集合：重试成功后它要立刻变小
                pendingIds = AsmrRepository.pendingWorkIdsInPlaylist(playlistId)
            )
        }
        if (_state.value.works.isEmpty() && !userInitiated) {
            _state.value = _state.value.copy(loading = true, error = null)
        }
        if (userInitiated) {
            _state.value = _state.value.copy(refreshing = true, error = null, toastMessage = null)
        }

        viewModelScope.launch {
            // ② 元数据（本地有就不重复请求；由节流与 ETag 兜住）
            if (_state.value.playlist == null) {
                runCatching { AsmrRepository.getPlaylistMetadata(playlistId, trigger) }
                    .getOrNull()?.let { meta ->
                        _state.value = _state.value.copy(playlist = meta)
                    }
            }

            // ③ 作品列表
            runCatching { AsmrRepository.loadPlaylistWorks(playlistId, trigger) }
                .onSuccess { result ->
                    result?.let {
                        _state.value = _state.value.copy(
                            works = it.works,
                            totalCount = it.totalCount,
                            loadedPages = it.loadedPages,
                            hasMore = it.works.size < it.totalCount,
                            syncedAt = it.syncedAt
                        )
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        toastMessage = null
                    )
                }
                .onFailure { e ->
                    // 同上：本地优先。用户主动刷新 → 轻量提示；
                    // 连本地内容都没有 → 整页错误态。
                    val hasContent = _state.value.works.isNotEmpty()
                    val reason = e.message ?: "刷新失败"
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = if (hasContent) null else reason,
                        toastMessage = if (notifyOnFailure) reason else null
                    )
                }
        }
    }

    /**
     * 本地收藏数据被改动（长按弹窗里移出/移动、别处收藏进本夹）后的重读。
     *
     * **只读本地缓存、不发请求**：改动刚由 App 自己写进本地，
     * 服务端还在 6 秒反悔窗口里没收到，这时去问云端反而会把界面拉回旧状态。
     * 和 [PlaylistsViewModel.onLocalDataChanged] 同一套思路。
     */
    fun onLocalDataChanged() {
        val result = AsmrRepository.cachedPlaylistWorks(playlistId) ?: return
        _state.value = _state.value.copy(
            works = result.works,
            totalCount = result.totalCount,
            loadedPages = result.loadedPages,
            hasMore = result.works.size < result.totalCount,
            syncedAt = result.syncedAt,
            pendingIds = AsmrRepository.pendingWorkIdsInPlaylist(playlistId)
        )
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.value = s.copy(loadingMore = true)
        val nextPage = s.loadedPages + 1
        viewModelScope.launch {
            runCatching { AsmrRepository.loadMorePlaylistWorks(playlistId, nextPage) }
                .onSuccess { result ->
                    if (result != null) {
                        _state.value = _state.value.copy(
                            works = result.works,
                            totalCount = result.totalCount,
                            loadedPages = nextPage,
                            hasMore = result.works.size < result.totalCount,
                            loadingMore = false
                        )
                    } else {
                        _state.value = _state.value.copy(loadingMore = false)
                    }
                }
                .onFailure { _state.value = _state.value.copy(loadingMore = false, hasMore = false) }
        }
    }

    /** 把作品移出当前收藏夹（本地先行：界面立刻消失，网络后台补发） */
    fun removeWork(work: Work) {
        // 本地立刻摘掉，用户的视角是即时的；同时给一条反馈
        _state.value = _state.value.copy(
            works = _state.value.works.filterNot { it.id == work.id },
            totalCount = (_state.value.totalCount - 1).coerceAtLeast(0),
            playlist = _state.value.playlist?.let {
                it.copy(worksCount = (it.worksCount - 1).coerceAtLeast(0))
            },
            toastMessage = "已从收藏夹移出"
        )
        // 本地缓存也补一笔（deferNetwork = true 时只会写本地），网络等进程级计时器发
        viewModelScope.launch {
            AsmrRepository.removeWorkFromPlaylist(
                playlistId = playlistId,
                workId = work.id,
                deferNetwork = true
            )
        }
        val key = "playlist-write:$playlistId:${work.id}:rm"
        PendingSync.hold(key) {
            val result = AsmrRepository.removeWorkFromPlaylist(playlistId, work.id)
            if (!result.ok) {
                // 失败：本地保持"已移除"，只是提示还没送到云端
                _state.value = _state.value.copy(
                    error = "${result.error}（本地已移除，会在下次同步时重试）"
                )
            }
        }
        PendingSync.schedule(key, 0L)
    }

    /**
     * 重试某个作品的「待同步」（点卡片上的标识）。
     *
     * 只针对这一个作品，不发整页请求。
     */
    fun retryPending(work: Work) {
        if (_state.value.retryingId != null) return
        _state.value = _state.value.copy(retryingId = work.id, error = null)
        viewModelScope.launch {
            val error = AsmrRepository.retryPendingForWork(work.id)
            _state.value = _state.value.copy(
                retryingId = null,
                pendingIds = AsmrRepository.pendingWorkIdsInPlaylist(playlistId),
                error = error,
                toastMessage = if (error == null) "已同步" else "同步失败：$error"
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 提示已展示过 → 清掉，避免离开页面再回来时重复弹一次 */
    fun clearToast() {
        if (_state.value.toastMessage != null) {
            _state.value = _state.value.copy(toastMessage = null)
        }
    }
}
