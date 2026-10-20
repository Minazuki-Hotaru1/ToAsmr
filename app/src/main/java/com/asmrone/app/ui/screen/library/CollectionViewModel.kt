package com.asmrone.app.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.cache.SyncTrigger
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.sync.PendingSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 「标记」页签状态 */
data class CollectionUiState(
    /** null = 全部（不按状态过滤） */
    val filter: CollectionStatus? = null,
    val works: List<Work> = emptyList(),
    val totalCount: Int = 0,
    /** 这一份数据已加载到第几页（「加载更多」据此续拉） */
    val loadedPages: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /**
     * 正在响应用户的**下拉刷新**。
     *
     * 与 [loading] 分开：[loading] 表示"没有内容可显示，正在首次加载"，
     * 会占满整屏；这个只驱动下拉刷新的指示器，内容照常展示。
     */
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** 上次与服务端确认的时间（仅展示"x 分钟前同步"） */
    val syncedAt: Long = 0L,
    /**
     * 处于「待同步」状态的作品 id（本地已改、云端还没收到）。
     *
     * 界面据此在**对应卡片上**显示「待同步」标识，点它就能重试。
     * 这是唯一需要让用户看见的同步状态 —— 精确到作品，比一条汇总文案有用。
     */
    val pendingIds: Set<Int> = emptySet(),
    /** 正在提交状态修改的作品 id */
    val mutatingId: Int? = null,
    /** 正在重试「待同步」标识的作品 id（点了卡片上的标识） */
    val retryingId: Int? = null,
    /**
     * 需要弹一条轻量提示的内容（用户**主动**刷新失败时）。
     *
     * 与 [error] 的区别：`error` 是"没有内容可看"的整页错误态；
     * 这个只是给用户的一次操作反馈。自动对齐失败**不会**填这里 ——
     * 本地 JSON 是主数据源，没对齐上不影响使用，不该打扰用户。
     */
    val toastMessage: String? = null
)

/**
 * 「标记」页签 ViewModel（`/api/review`）。
 *
 * ## 策略：每天一次自动核对，其余时间只读本地
 *
 *  - **进入页面** → 只有当天还没核对过才真的发请求（每个 key 各一次条件请求）；
 *    当天已核对过就直接用本地 JSON，零请求。
 *  - **用户主动刷新 / 切筛选 / 登录切换账号** → 立即核对，不受每日限制。
 *  - **收藏、取消收藏等写操作** → 只做那一次写，**不附带任何读取校验**。
 *
 * ## 冲突时以云端为准
 *
 * 一旦真的问到服务端，就单边听它的：200 全量替换本地（哪怕作品已被网页端删光），
 * 304 才继续用本地。每日限制只影响"什么时候问"，不改变"问了听谁的"。
 *
 * 并发由 [SyncGate] 的单飞合并，所以狂点最多只产生一次请求。
 */
class CollectionViewModel : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * 进入页面 / 切回本页签。
     *
     * ## 两种进入方式，区别在**要不要绕过每日门槛**
     *
     * - `reinitialize = false`（普通进入）：只读本地，去不去问服务端**全看每日门槛**
     *   （当天还没核对过才放行一次）。所以当天第二次起切到收藏是零请求的。
     * - `reinitialize = true`（账号真的变了）：整份丢掉重来，并**强制核对一次**。
     *   必要性：换了账号后本地那份是空的（缓存键含账号），
     *   而"每天首次核对"的标记可能已被上一个账号用掉 —— 不绕过就会一整天空白。
     *
     * ⚠️ 调用方必须把两者分清。曾经"每次进入页面"都走 [onAccountChanged]，
     * 于是每切一次页签都会绕过门槛打一次接口（表现为网络日志里频繁出现
     * `/api/review` 的 200/304）。
     */
    fun onEnter(reinitialize: Boolean = false) {
        if (reinitialize) {
            job?.cancel()
            _state.value = CollectionUiState()
            sync(SyncTrigger.ACCOUNT_CHANGED, userInitiated = false)
            return
        }
        // 唯一的一条自动路径。**这次到底发不发请求由 SyncGate 决定**
        // （本次会话第一次打开这份数据才放行），所以这里不需要判断
        // "本地有没有缓存""今天有没有试过" —— 那些判断正是以前出 bug 的地方。
        sync(SyncTrigger.DAILY_AUTO, userInitiated = false)
    }

    /** 账号变了：整份丢掉重来（绝不能留上一个账号的收藏），并立即核对 */
    fun onAccountChanged() = onEnter(reinitialize = true)

    /**
     * 切换状态筛选。
     *
     * ## 不发请求（除非本地压根没有这份数据）
     *
     * 「全部、想听、在听…」共用同一份本地 JSON（「全部」那份），
     * 各筛选是读取时按 `progress` **本地派生**的 —— 切筛选只是换个视图看同一份数据。
     *
     * 依据是实测确认的：`GET /api/review` 不带 `filter` 时每条都带 `progress`，
     * 所以"昨天对齐过、本地没有想听的作品"就意味着**云端也真的没有**，
     * 不需要再问一次。你提出的正是这一点：数据匹配过之后，空就是真的空。
     *
     * 唯一例外：本地**连一份收藏数据都没有**（换过服务器、清过缓存、或从没对齐成功过），
     * 这时"空"是不可信的，才去问一次。
     */
    fun selectFilter(filter: CollectionStatus?) {
        val s = _state.value
        if (s.filter == filter) return

        // 本地有没有可用的收藏数据（读「全部」那份；不碰网络）
        val hasAnyLocal = AsmrRepository.cachedCollection(null) != null
        job?.cancel()
        _state.value = s.copy(
            filter = filter,
            // 本地有数据就立刻出结果，不显示加载态（切筛选应当是瞬间的）
            loading = !hasAnyLocal
        )
        // 一个请求都不发：筛选是本地派生的。
        //
        // 原先这里在"本地连一份收藏数据都没有"时会兜底请求一次，现在取消了 ——
        // 那种情况（换过服务器 / 清过缓存）会由 DAILY_AUTO 在进入页面时
        // 自动放行一次，不需要在切筛选这条路径上再补一次。
        sync(trigger = null, userInitiated = false)
    }

    /**
     * 用户点刷新按钮：立即对齐，不受每日限制。
     *
     * 带上 `notifyOnFailure = true` —— 用户明确要求了，失败就得告诉他。
     */
    fun refresh() = sync(SyncTrigger.USER_REFRESH, userInitiated = false, notifyOnFailure = true)

    /**
     * 用户**下拉刷新**：与 [refresh] 做同样的事，只是要让指示器转起来。
     *
     * 下拉是明确的手势，所以不受任何限制，失败也要提示。
     */
    fun pullRefresh() = sync(
        SyncTrigger.USER_REFRESH,
        userInitiated = true,
        notifyOnFailure = true
    )

    /**
     * 收藏数据在别处被改了（详情页标记、收藏改状态、收藏夹操作）→ 就地重读本地。
     *
     * **刻意不发任何请求**：这是本地写入的结果，用户要的是"立刻看到"，
     * 而不是等一次云端核对。云端何时核对仍由 [onEnter] 的每日策略决定。
     *
     * 这解决了"进详情页标记 → 返回收藏看不到新内容"的问题：
     * 收藏的 ViewModel 挂在导航层、不随页面进出重建，
     * 所以 `onEnter()` 不会再跑一次，必须靠这个信号触发热刷新。
     */
    fun onLocalDataChanged() {
        val filter = _state.value.filter
        val cached = AsmrRepository.cachedCollection(filter) ?: return
        // apply() 已经会带上 works / totalCount / loadedPages / syncedAt / pendingIds
        apply(cached)
        _state.value = _state.value.copy(loading = false, loaded = true)
    }

    /**
     * @param trigger 为什么要跟服务器说话（见 [SyncTrigger]）。
     *   **null = 只读本地，一个请求都不发。**
     * @param userInitiated 只驱动下拉指示器，不改变是否发请求
     * @param notifyOnFailure 失败时是否弹一条轻量提示。**只给用户主动触发的路径用**；
     *   自动对齐失败要安静（本地 JSON 是主数据源，没对齐上不影响使用）
     */
    private fun sync(
        trigger: SyncTrigger?,
        userInitiated: Boolean,
        notifyOnFailure: Boolean = false
    ) {
        job?.cancel()
        val filter = _state.value.filter

        // ① 先把本地铺出来（有的话），避免对齐期间空着
        val local = AsmrRepository.cachedCollection(filter)
        local?.let { apply(it) }
        _state.value = _state.value.copy(
            // 本地已经有内容就直接展示，不要显示加载态。
            // 注意 `works.isEmpty()` 不能作为判据：某个筛选**本来就可能是空的**
            // （比如一条"想听"都没有），那不是"还在加载"。
            loading = local == null && !userInitiated,
            refreshing = userInitiated,
            error = null,
            toastMessage = null,
            pendingIds = AsmrRepository.pendingWorkIds()
        )

        job = viewModelScope.launch {
            runCatching { AsmrRepository.loadCollection(filter, trigger) }
                .onSuccess { result ->
                    result?.let { apply(it) }
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        loaded = true,
                        error = null,
                        toastMessage = null
                    )
                }
                .onFailure { e ->
                    // 对齐失败是**正常情况**，不该惊动用户：本地 JSON 就是主数据源，
                    // 内容照常展示。
                    //
                    // 两种情况才需要反馈：
                    //  · 用户主动刷新 → 弹一条轻量提示（他明确要求了）
                    //  · 本地什么都没有、连内容都拿不到 → 整页错误态
                    val hasContent = _state.value.works.isNotEmpty()
                    val reason = e.message ?: "刷新失败"
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        loaded = true,
                        error = if (hasContent) null else reason,
                        toastMessage = if (notifyOnFailure) reason else null
                    )
                }
        }
    }

    private fun apply(result: com.asmrone.app.data.repo.CachedCollection) {
        _state.value = _state.value.copy(
            works = result.works,
            totalCount = result.totalCount,
            // 页数跟着缓存走：刷新后缓存回退成 1 页，这里也必须回退，
            // 否则「加载更多」会跳到第 4 页、把第 2~3 页漏掉
            loadedPages = result.loadedPages,
            // 状态筛选是本地派生的，只能看到**已加载的那些页**，
            // 所以这个视图里不再提供「加载更多」（加载更多只在「全部」里有意义）
            hasMore = result.works.size < result.totalCount && _state.value.filter == null,
            syncedAt = result.syncedAt,
            // 拿到了内容就是拿到了 —— 哪怕它是空的（空也是确定的结果）
            loading = false,
            // 每次都重新读一遍待同步集合：补写成功后它要立刻变小
            pendingIds = AsmrRepository.pendingWorkIds()
        )
    }

    /**
     * 只重试某一个作品的待同步记录（点卡片上的「待同步」标识）。
     *
     * 这是"用户自行操作触发对齐"的一种 —— 不受每日限制，
     * 而且**只针对这一个作品**，不会顺带重拉整份列表。
     */
    fun retryPending(workId: Int) {
        if (_state.value.retryingId != null) return
        _state.value = _state.value.copy(retryingId = workId, error = null)
        viewModelScope.launch {
            val error = AsmrRepository.retryPendingForWork(workId)
            _state.value = _state.value.copy(
                retryingId = null,
                pendingIds = AsmrRepository.pendingWorkIds(),
                error = error,
                toastMessage = if (error == null) "已同步" else "同步失败：$error"
            )
        }
    }

    /**
     * 「加载更多」：多拉一页**「全部」列表**。
     *
     * 只在「全部」视图里出现 —— 状态筛选是本地派生的，只能看到已加载的页，
     * 在那里放「加载更多」会让人以为"还有更多符合这个状态的"，
     * 而实际上它拉的是同一份全部列表的下一页。
     */
    fun loadMore() {
        val s = _state.value
        if (s.filter != null || s.loading || s.loadingMore || !s.hasMore) return
        _state.value = s.copy(loadingMore = true)
        val nextPage = s.loadedPages + 1
        viewModelScope.launch {
            runCatching { AsmrRepository.loadMoreCollection(s.filter, nextPage) }
                .onSuccess { result ->
                    if (result != null) {
                        // 复用 apply()，别在这里重复写一套 hasMore / loading 的推导
                        apply(result)
                        _state.value = _state.value.copy(loadedPages = nextPage, loadingMore = false)
                    } else {
                        _state.value = _state.value.copy(loadingMore = false)
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(loadingMore = false, hasMore = false)
                }
        }
    }

    /**
     * 修改某个作品的标记状态。
     *
     * **本地先行**（与详情页的快捷标记同一套）：先写本地缓存、立刻更新列表，
     * 网络那半交给 [PendingSync] 后台补发 —— 用户不必等着"保存中"转圈。
     * 发不出去时仓库会记一条待补写，卡片显示「待同步」，下次对齐自动重试。
     */
    fun setStatus(work: Work, status: CollectionStatus?) {
        if (_state.value.mutatingId != null) return
        // 列表页点掉已选中的状态 = 清空状态（评分不动）。
        // 不显式说明的话 PUT 里就没有 progress，云端会一直留着旧状态。
        // 但也只在「这份列表原本确实显示着状态」时才送 null：
        // 本地可能是过期副本，凭空送 null 会删掉云端真实存在的标记
        val clearStatus = status == null && work.progress != null
        _state.value = _state.value.copy(mutatingId = work.id)
        viewModelScope.launch {
            // ① 只写本地那一半（deferNetwork = true 时函数立即返回，不发请求）
            val error = AsmrRepository.updateCollection(
                workId = work.id,
                status = status,
                work = work,
                clearStatus = clearStatus,
                deferNetwork = true
            )
            if (error != null) {
                _state.value = _state.value.copy(mutatingId = null, error = error)
                return@launch
            }

            // ② 本地列表立刻套用这次改动，省掉一次整页重拉
            val filter = _state.value.filter
            val updated = _state.value.works.map { item ->
                if (item.id == work.id) item.copy(progress = status?.apiValue) else item
            }.let { list ->
                if (filter == null || status == filter) list else list.filterNot { it.id == work.id }
            }
            _state.value = _state.value.copy(
                works = updated,
                totalCount = _state.value.totalCount - (_state.value.works.size - updated.size),
                mutatingId = null,
                // 刚刚确认过一次写入，时间戳往前推
                syncedAt = System.currentTimeMillis(),
                // 本地已经生效 → 立刻给反馈（网络失败会另外以「待同步」标记出现）
                toastMessage = "已更新标记"
            )

            // ③ 网络后台补发：进程级 scope，退出收藏页也照样发得出去
            val key = "collection-write:${work.id}"
            PendingSync.hold(key) {
                AsmrRepository.sendDeferredCollectionUpdate(
                    workId = work.id,
                    status = status,
                    rating = null,
                    remove = false,
                    work = work,
                    clearStatus = clearStatus,
                    clearRating = false
                )
            }
            PendingSync.schedule(key, 0L)
        }
    }

    /**
     * 「删除」：把评分与状态整条移出（本地先行）。
     *
     * 与 [setStatus] 传 null 不同 —— 那只是"清掉状态、评分不动"，而弹窗里的
     * 「删除」语义是"这件作品我没标记过"，所以星级也一起清。
     */
    fun removeMark(work: Work) {
        if (_state.value.mutatingId != null) return
        _state.value = _state.value.copy(mutatingId = work.id)
        viewModelScope.launch {
            val error = AsmrRepository.updateCollection(
                workId = work.id,
                status = null,
                rating = null,
                remove = true,
                work = work,
                clearStatus = true,
                clearRating = true,
                deferNetwork = true
            )
            if (error != null) {
                _state.value = _state.value.copy(mutatingId = null, error = error)
                return@launch
            }

            // 列表立刻套用：评分与状态都清掉；按状态筛选时这条记录整体移出列表
            val filter = _state.value.filter
            val updated = _state.value.works.mapNotNull { item ->
                when {
                    item.id != work.id -> item
                    filter == null -> item.copy(progress = null, userRating = null)
                    else -> null
                }
            }
            _state.value = _state.value.copy(
                works = updated,
                totalCount = _state.value.totalCount - (_state.value.works.size - updated.size),
                mutatingId = null,
                syncedAt = System.currentTimeMillis(),
                toastMessage = "已移除标记"
            )

            val key = "collection-write:${work.id}"
            PendingSync.hold(key) {
                AsmrRepository.sendDeferredCollectionUpdate(
                    workId = work.id,
                    status = null,
                    rating = null,
                    remove = true,
                    work = work,
                    clearStatus = true,
                    clearRating = true
                )
            }
            PendingSync.schedule(key, 0L)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 提示已展示过，清掉它。
     *
     * 界面在 `LaunchedEffect(toastMessage)` 里消费掉这条消息后必须调用它 ——
     * 否则离开页面再回来时 effect 会重新执行，把一条**已经看过**的提示再弹一次。
     */
    fun clearToast() {
        if (_state.value.toastMessage != null) {
            _state.value = _state.value.copy(toastMessage = null)
        }
    }

    /** 退出登录时清空（不要把上一个账号的收藏留在内存里） */
    fun clear() {
        job?.cancel()
        _state.value = CollectionUiState()
    }
}
