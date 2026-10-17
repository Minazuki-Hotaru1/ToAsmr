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

/**
 * 「加入收藏夹」弹窗的 ViewModel。
 *
 * 状态生命周期与详情页不同（关掉就该丢），所以独立成一个 ViewModel。
 *
 * ## 核对同样受每日限制
 *
 * 打开弹窗时：
 *  1. 先用**本地缓存**的收藏夹列表铺出来（瞬时）；
 *  2. 只有**当天还没核对过**才去问服务端（条件请求，未变化为 304 = 0 字节）；
 *     当天已核对过就直接用本地，零请求。
 *  3. "这个作品在不在选中的收藏夹里"也**先查本地**（收藏夹内容整份存在本地），
 *     本地没有才回落服务端（见 AsmrRepository.isWorkInPlaylist）。
 */
class PlaylistPickerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlaylistPickerState())
    val state: StateFlow<PlaylistPickerState> = _state.asStateFlow()

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    private var workId: Int = 0

    /**
     * 当前操作的作品。
     *
     * 保留整个对象（而不是只留 id）：加入收藏夹后要把它**立刻**插进
     * 本地缓存里的收藏夹内容，这样回到收藏就能看到，
     * 而不必等一次云端核对 —— 那需要标题、封面等字段。
     */
    private var work: Work? = null

    fun open(work: Work) {
        this.workId = work.id
        this.work = work
        _result.value = null

        // ① 先用本地缓存铺出来，避免校验期间空白
        val cached = AsmrRepository.peekPlaylists().orEmpty()
        _state.value = PlaylistPickerState(
            playlists = cached,
            selectedId = AsmrRepository.defaultPlaylist(cached)?.id.orEmpty(),
            loading = cached.isEmpty()
        )
        refreshMembership()

        // ② 再按闸门对齐一次（未变化时 304 = 0 字节；单飞保证并发只发一次）。
        //
        //    这里用 DAILY_AUTO：弹窗是"用户主动打开"的，属于该知道最新数据的时候；
        //    每天仍只自动一次。
        //    但它仍然是个受闸门约束的自动路径 —— 本次会话已经对齐过就直接用本地，
        //    不会因为反复开关弹窗而反复打接口。
        viewModelScope.launch {
            val error = runCatching {
                AsmrRepository.loadPlaylists(SyncTrigger.DAILY_AUTO)
            }.fold(onSuccess = { null }, onFailure = { it.message ?: "获取收藏夹失败" })
            val list = AsmrRepository.peekPlaylists().orEmpty()
            // 重新定默认项（上次选的收藏夹可能已被删掉）
            val selected = list.firstOrNull { it.id == _state.value.selectedId }
                ?: AsmrRepository.defaultPlaylist(list)
            _state.value = _state.value.copy(
                playlists = list,
                selectedId = selected?.id.orEmpty(),
                loading = false,
                error = error
            )
            refreshMembership()
        }
    }

    fun select(playlist: Playlist) {
        if (_state.value.submitting) return
        _state.value = _state.value.copy(selectedId = playlist.id, workInside = false)
        refreshMembership()
    }

    /** 查"这个作品是否已在当前选中的收藏夹里" —— 优先本地 */
    private fun refreshMembership() {
        val id = _state.value.selectedId
        if (id.isBlank() || workId == 0) return
        _state.value = _state.value.copy(checkingMembership = true)
        viewModelScope.launch {
            val inside = AsmrRepository.isWorkInPlaylist(id, workId)
            // 期间用户可能又换了收藏夹，那就丢弃这次结果
            if (_state.value.selectedId != id) return@launch
            _state.value = _state.value.copy(workInside = inside, checkingMembership = false)
        }
    }

    /**
     * 确认：加入或移出。
     *
     * 依据 [PlaylistPickerState.workInside] 决定方向 —— 同一个按钮既能收藏也能取消，
     * 不会出现"重复加入同一收藏夹"。**只发一次写请求，不发读请求**。
     */
    fun confirm() {
        val s = _state.value
        val playlist = s.selected ?: return
        if (s.submitting || workId == 0) return

        _state.value = s.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = if (s.workInside) {
                AsmrRepository.removeWorkFromPlaylist(playlist.id, workId)
            } else {
                AsmrRepository.addWorkToPlaylist(playlist.id, workId, work)
            }

            if (!result.ok) {
                _state.value = _state.value.copy(submitting = false, error = result.error)
                return@launch
            }

            // 服务端说"没有需要改的"（rowCount = 0）时按结果区分：
            //  · 移除时无改动 → 说明云端本来就没有这部作品（多半在网页端已删），
            //    本地那条是过期的，明确告诉用户，别让他以为是自己刚删掉的
            //  · 加入时无改动 → 本来就在里面，结果符合用户意图，正常提示即可
            AsmrRepository.rememberSelectedPlaylist(playlist.id)
            _result.value = when {
                s.workInside && !result.changed ->
                    "云端收藏夹里已经没有这部作品了，已同步最新状态"
                s.workInside -> "已从「${playlist.displayName}」移出"
                else -> "已收藏到「${playlist.displayName}」"
            }

            // 本地同步件数，免得列表里数字不动（不发请求）
            _state.value = _state.value.copy(
                submitting = false,
                workInside = !s.workInside,
                playlists = _state.value.playlists.map {
                    if (it.id == playlist.id) {
                        it.copy(
                            worksCount = (it.worksCount + if (s.workInside) -1 else 1)
                                .coerceAtLeast(0)
                        )
                    } else it
                }
            )
        }
    }

    // ─────────────────────────── 新建收藏夹 ───────────────────────────

    fun startCreate() {
        _state.value = _state.value.copy(creating = true, newName = "", createError = null)
    }

    fun cancelCreate() {
        _state.value = _state.value.copy(creating = false, newName = "", createError = null)
    }

    fun onNewNameChange(name: String) {
        _state.value = _state.value.copy(newName = name, createError = null)
    }

    /** 新建收藏夹，然后自动选中它并把作品加进去 */
    fun createAndAdd() {
        val name = _state.value.newName.trim()
        if (name.isEmpty() || _state.value.submitting) return

        _state.value = _state.value.copy(submitting = true, createError = null)
        viewModelScope.launch {
            AsmrRepository.createPlaylist(name).fold(
                onSuccess = { newId ->
                    // 刚建完必须重新拉一次，好让新收藏夹出现在列表里（并拿到它的 id）
                    val list = runCatching { AsmrRepository.loadPlaylists(SyncTrigger.USER_REFRESH) }
                        .getOrDefault(emptyList())
                    val target = newId?.let { id -> list.firstOrNull { it.id == id } }
                        ?: list.firstOrNull { it.name == name }
                    _state.value = _state.value.copy(
                        creating = false,
                        newName = "",
                        submitting = false,
                        playlists = list,
                        selectedId = target?.id ?: _state.value.selectedId,
                        workInside = false
                    )
                    if (target != null) {
                        AsmrRepository.rememberSelectedPlaylist(target.id)
                        val add = AsmrRepository.addWorkToPlaylist(target.id, workId, work)
                        _result.value = if (add.ok) {
                            "已创建「${target.displayName}」并收藏"
                        } else {
                            "收藏夹已创建，但加入失败：${add.error}"
                        }
                        _state.value = _state.value.copy(submitting = false)
                    } else {
                        _result.value = "收藏夹已创建，请在列表里选择它"
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        createError = e.message ?: "新建收藏夹失败"
                    )
                }
            )
        }
    }

    fun clearResult() {
        _result.value = null
    }

    /** 删除收藏夹（系统收藏夹会被仓库层拦下），成功后刷新列表 */
    fun deletePlaylist(playlist: Playlist) {
        if (playlist.isSystem || _state.value.submitting) return
        _state.value = _state.value.copy(submitting = true, error = null)
        viewModelScope.launch {
            AsmrRepository.deletePlaylist(playlist).fold(
                onSuccess = {
                    // 删除后重新拉一次，确认服务端已经移除
                    val list = runCatching { AsmrRepository.loadPlaylists(SyncTrigger.USER_REFRESH) }
                        .getOrDefault(emptyList())
                    val selected = list.firstOrNull { it.id == _state.value.selectedId }
                        ?: AsmrRepository.defaultPlaylist(list)
                    _state.value = _state.value.copy(
                        playlists = list,
                        selectedId = selected?.id.orEmpty(),
                        submitting = false,
                        workInside = false
                    )
                    _result.value = "已删除「${playlist.displayName}」"
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        error = e.message ?: "删除失败"
                    )
                }
            )
        }
    }
}
