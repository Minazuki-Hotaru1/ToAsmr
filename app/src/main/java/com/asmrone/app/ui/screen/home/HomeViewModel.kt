package com.asmrone.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.model.HomeBoard
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单个板块的列表状态 */
data class BoardState(
    val items: List<Work> = emptyList(),
    val page: Int = 1,
    val totalCount: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** 是否已经成功加载过（用于「切换回来直接用缓存」） */
    val loaded: Boolean = false,
    val error: String? = null
)

data class HomeUiState(
    /** 当前选中的板块 */
    val board: HomeBoard = HomeBoard.ALL,
    /** 每个板块各自的状态：来回切换不会重新请求，也不会丢掉滚动数据 */
    val boards: Map<HomeBoard, BoardState> = emptyMap(),
    /** 「设置 → 外观 → 首页 → 加载首页榜单」被关闭 */
    val disabled: Boolean = false
) {
    val current: BoardState get() = boards[board] ?: BoardState(loading = true)
}

/**
 * 首页 ViewModel —— 三个板块（所有内容 / 热门作品 / 推荐作品）各自分页。
 *
 * 与上一版「五条榜单并发拉取 + 一个大瀑布流」不同：现在**一次只加载一个板块**，
 * 既省流量，也让「切换板块」这件事有明确的语义。每个板块的结果留在 [HomeUiState.boards]
 * 里，切回来是瞬时的。
 */
class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** 每个板块一个加载任务：切换板块不会打断另一个板块正在进行的请求 */
    private val jobs = mutableMapOf<HomeBoard, Job>()

    init {
        // 「设置 → 外观 → 加载首页榜单」要能当场生效：
        // 之前只在 [load] 里读一次 `AppPrefs.homeEnabled.value`，而关掉开关时没有任何人
        // 再调用 load()，首页就停在旧画面上，必须重启才变。
        // 这里订阅它 —— 关掉立刻切成「加载已关闭」并取消在飞的请求，打开就马上拉。
        viewModelScope.launch {
            AppPrefs.homeEnabled.collect { enabled ->
                if (enabled) {
                    // 之前加载过的板块留着（切回来是瞬时的），没加载过才发请求
                    _state.value = _state.value.copy(disabled = false)
                    load(_state.value.board)
                } else {
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    _state.value = _state.value.copy(
                        disabled = true,
                        boards = _state.value.boards.mapValues { (_, board) ->
                            board.copy(loading = false, loadingMore = false)
                        }
                    )
                }
            }
        }
    }

    /** 切换板块：已加载过的用缓存，没加载过的才发请求 */
    fun selectBoard(board: HomeBoard) {
        if (_state.value.board == board) return
        _state.value = _state.value.copy(board = board)
        if (_state.value.boards[board]?.loaded != true) load(board)
    }

    fun load(board: HomeBoard = _state.value.board, force: Boolean = false) {
        if (!AppPrefs.homeEnabled.value) {
            _state.value = _state.value.copy(disabled = true)
            return
        }
        val existing = _state.value.boards[board]
        if (!force && existing?.loaded == true) return

        jobs[board]?.cancel()
        updateBoard(board) {
            it.copy(
                loading = true,
                error = null,
                // 重试时清空旧数据，避免错误态与旧列表混在一起
                items = if (force) emptyList() else it.items,
                page = if (force) 1 else it.page
            )
        }
        jobs[board] = viewModelScope.launch {
            runCatching { AsmrRepository.getBoard(board, page = 1) }
                .onSuccess { result ->
                    updateBoard(board) {
                        it.copy(
                            items = result.items,
                            page = result.currentPage,
                            totalCount = result.totalCount,
                            hasMore = result.hasMore,
                            loading = false,
                            loaded = true,
                            error = null
                        )
                    }
                }
                .onFailure { e ->
                    updateBoard(board) {
                        it.copy(loading = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun retry() = load(_state.value.board, force = true)

    fun loadMore() {
        val board = _state.value.board
        val current = _state.value.boards[board] ?: return
        if (current.loading || current.loadingMore || !current.hasMore) return

        updateBoard(board) { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { AsmrRepository.getBoard(board, page = current.page + 1) }
                .onSuccess { result ->
                    // 期间可能已经切到别的板块，但状态是按板块存的，这里始终更新自己那一份
                    updateBoard(board) {
                        it.copy(
                            // 分页边界上偶发重复；瀑布流的 key 是 work.id，
                            // 重复键会让列表直接抛异常，所以在这里就去掉
                            items = (it.items + result.items).distinctBy { item -> item.id },
                            page = result.currentPage,
                            totalCount = result.totalCount,
                            hasMore = result.hasMore && result.items.isNotEmpty(),
                            loadingMore = false
                        )
                    }
                }
                .onFailure { updateBoard(board) { it.copy(loadingMore = false) } }
        }
    }

    private inline fun updateBoard(board: HomeBoard, transform: (BoardState) -> BoardState) {
        _state.update { s ->
            s.copy(boards = s.boards + (board to transform(s.boards[board] ?: BoardState())))
        }
    }
}
