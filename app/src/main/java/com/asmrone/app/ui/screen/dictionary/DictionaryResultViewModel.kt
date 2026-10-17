package com.asmrone.app.ui.screen.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.repo.DictionaryQuery
import com.asmrone.app.data.repo.recordDictionarySearch
import com.asmrone.app.data.repo.toWorkFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「某一项字典资源的作品」页 —— 详情页点标签 / 声优 / 社团**直达**的结果页。
 *
 * ## 为什么单独有这个 ViewModel
 *
 * 原先详情页点标签要**先跳到搜索页**、把条件塞进输入栏、再触发搜索。
 * 那等于说"看一个标签的作品"必须走一遍"提搜索条件"的流程 ——
 * 而用户根本没打算提条件，他只是点了下标签。
 *
 * 现在这条路直连：详情页 → 这里 → 出结果。搜索页完全不参与。
 *
 * ## 与搜索页的复用关系
 *
 * 取数据的口径是同一个（单条件走 `/api/tags/{id}/works` 这类**按 id** 的端点，
 * 比按名字查更精确），展示用的是公共组件 `WorkResultGrid`。
 * 所以两处看起来、行为上都是一致的，但不互相依赖。
 */
class DictionaryResultViewModel : ViewModel() {

    private val _state = MutableStateFlow(DictionaryResultUiState())
    val state: StateFlow<DictionaryResultUiState> = _state.asStateFlow()

    private var query: DictionaryQuery? = null
    private var loadJob: Job? = null

    /** 只初始化一次（重组 / 配置变更不该重查） */
    private var started = false

    fun start(query: DictionaryQuery) {
        if (started) return
        started = true
        this.query = query

        // 记进搜索历史 —— 从这条路径进来的检索同样算"一次搜索"
        recordDictionarySearch(query)

        _state.value = _state.value.copy(
            title = query.displayName,
            description = query.label,
            loading = true
        )
        loadFirstPage()
    }

    fun retry() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, works = emptyList())
        loadFirstPage()
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.loading) return
        val q = query ?: return

        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            runCatching {
                AsmrRepository.getWorks(
                    page = s.page + 1,
                    pageSize = PAGE_SIZE,
                    filter = q.toWorkFilter()
                )
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    works = _state.value.works + result.items,
                    page = result.currentPage,
                    hasMore = result.hasMore,
                    totalCount = result.totalCount,
                    loadingMore = false
                )
            }.onFailure {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    private fun loadFirstPage() {
        val q = query ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                AsmrRepository.getWorks(page = 1, pageSize = PAGE_SIZE, filter = q.toWorkFilter())
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    works = result.items,
                    page = result.currentPage,
                    hasMore = result.hasMore,
                    totalCount = result.totalCount,
                    loading = false,
                    error = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    companion object {
        /** 与搜索页同一个分页大小，翻页手感一致 */
        const val PAGE_SIZE = 20
    }
}

data class DictionaryResultUiState(
    /** 顶栏标题：这一项的名字 */
    val title: String = "",
    /** 来源说明，如 `标签「ASMR」` */
    val description: String = "",
    val works: List<Work> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
)
