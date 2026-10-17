package com.asmrone.app.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.cache.SyncTrigger
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
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

    private fun sync(
        trigger: SyncTrigger?,
        userInitiated: Boolean,
        notifyOnFailure: Boolean = false
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
                        syncedAt = AsmrRepository.playlistsSyncedAt()
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
                        toastMessage = if (notifyOnFailure) reason else null
                    )
                }
        }
    }

    /** 系统收藏夹优先，其余保持服务端给的顺序 */
    private fun sortPlaylists(list: List<Playlist>): List<Playlist> =
        list.sortedByDescending { it.isSystem }

    fun delete(playlist: Playlist) {
        viewModelScope.launch {
            AsmrRepository.deletePlaylist(playlist).fold(
                onSuccess = { sync(SyncTrigger.USER_REFRESH, userInitiated = false) },
                onFailure = { e ->
                    _state.value = _state.value.copy(error = e.message ?: "删除失败")
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

    /** 把作品移出当前收藏夹（本地先行：界面立刻消失，网络失败只打标等待重试） */
    fun removeWork(work: Work) {
        // 本地立刻摘掉，用户的视角是即时的
        _state.value = _state.value.copy(
            works = _state.value.works.filterNot { it.id == work.id },
            totalCount = (_state.value.totalCount - 1).coerceAtLeast(0),
            playlist = _state.value.playlist?.let {
                it.copy(worksCount = (it.worksCount - 1).coerceAtLeast(0))
            }
        )
        viewModelScope.launch {
            val result = AsmrRepository.removeWorkFromPlaylist(playlistId, work.id)
            if (!result.ok) {
                // 失败：本地保持"已移除"，只是提示还没送到云端
                _state.value = _state.value.copy(
                    error = "${result.error}（本地已移除，会在下次同步时重试）"
                )
                return@launch
            }
            if (!result.changed) {
                // 云端本来就没有它 —— 正好是我们想要的结果，不必再拉一次列表
                _state.value = _state.value.copy(error = null)
            }
        }
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
                error = error
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
