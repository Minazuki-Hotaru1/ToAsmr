package com.asmrone.app.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.AudioFormatOption
import com.asmrone.app.data.model.AudioFormatPreference
import com.asmrone.app.data.cache.ExternalOpenFiles
import com.asmrone.app.data.cache.FolderStateStore
import com.asmrone.app.data.download.DownloadManager
import com.asmrone.app.data.download.DownloadSources
import com.asmrone.app.data.download.DownloadStorage
import com.asmrone.app.data.download.DownloadStore
import com.asmrone.app.data.download.filterTreeTo
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.OpenableFile
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.model.audioFormatOptions
import com.asmrone.app.data.model.pickPreferred
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.collectFolderPaths
import com.asmrone.app.ui.error.MarkFailure
import com.asmrone.app.ui.error.classifyMarkFailure
import com.asmrone.app.ui.error.notLoggedInFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 详情页「标记」按钮一点即给的默认星级（满分即 5 星） */
private const val QUICK_RATE_STARS = 5

/**
 * 详情页状态。
 *
 * 与上一版相比：**不再分区**（原站音声没有评论区那种「详情/评论」页签），
 * 因此状态里直接持有「元数据 + 文件树 + 展开状态 + 音质分支」四件事，
 * 界面上一次性呈现。
 */
data class DetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val work: Work? = null,
    val metaError: String? = null,
    val tracksError: String? = null,
    /** 收藏（云端状态标记）写入中 */
    val collectionBusy: Boolean = false,
    /**
     * 标记失败的详细原因（标题 + 建议 + 原始详情）。
     *
     * 用分类对象而不是一句字符串：用户能看懂"是没登录还是网络不通"，
     * 也才有下一步可做；排查时也能拿到原始异常。
     */
    val collectionFailure: MarkFailure? = null,
    /** 文件树里已展开的文件夹路径 */
    val expanded: Set<String> = emptySet(),
    /** 可选音质分支（mp3 / wav / 全部…），多于一个时界面才显示切换 */
    val formatOptions: List<AudioFormatOption> = emptyList(),
    val selectedFormatIndex: Int = 0,
    /**
     * 一条一次性提示（用共享的 Toast 组件显示）。
     *
     * 主要用在"点了外部打开但系统里没有能处理的 App"这类**必须有反馈**的场合 ——
     * 静默失败会让用户以为点击没生效，反复点。
     *
     * [toastSeq] 是配套的：同一条文案连续出现两次时，只靠文本变化判断不出
     * "又发生了一次"，所以用序号驱动。
     */
    val toastMessage: String? = null,
    val toastSeq: Int = 0,
    /**
     * **完整的**文件树（不做下载过滤），下载选择器要用它。
     *
     * 详情页本身只显示已下载的内容（[tracks] 已经是过滤后的），但"再下几个文件"
     * 必须能看到还没下的那些 —— 所以原始树单独留一份。
     */
    val downloadTree: List<Track> = emptyList(),
    /** 已下载的文件路径；非空即表示"这部作品下载过" */
    val downloadedPaths: Set<String> = emptySet()
) {
    val tracks: List<Track> get() = work?.tracks ?: emptyList()
    val selectedFormat: AudioFormatOption? get() = formatOptions.getOrNull(selectedFormatIndex)
    /** 当前分支的播放列表（交给播放器的就是它） */
    val playbackList: List<Track> get() = selectedFormat?.tracks ?: emptyList()

    /** 这部作品是否下载过（下载过就只显示本地已有的内容） */
    val isDownloaded: Boolean get() = downloadedPaths.isNotEmpty()

    /** 选择器里要展示的树：优先用原始树（可能还没到），退回当前树 */
    val pickerTree: List<Track> get() = downloadTree.ifEmpty { tracks }
}

class DetailViewModel : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadedId: Int = 0

    /**
     * 用户在这次浏览里是否亲手改过展开态。
     *
     * 为 true 时，树晚到也不能用"上次记下的展开集合"覆盖他刚做的操作。
     */
    private var foldersTouched: Boolean = false

    /** 上一次标记尝试的完整意图（含两层"清空"语义），供失败后「重试」用 */
    private var lastAttempt: CollectionAttempt? = null

    /** 已经落本地、但网络还没发出去的快捷评分（见 [sendQuickRate]） */
    private var pendingQuickRate: QuickRateRequest? = null

    /** 监听下载完成的协程（每个 ViewModel 只挂一个） */
    private var downloadObserverJob: Job? = null

    fun load(workId: Int, forceRefresh: Boolean = false) {
        if (!forceRefresh && loadedId == workId && _state.value.work != null) return
        loadedId = workId
        foldersTouched = false

        // 已下载的作品只显示本地已有的内容。这里用同步的 cached 读数：
        // 启动时 DownloadManager 会预热，命中时第一帧就是过滤后的树，不会先闪一下全量。
        val cachedDownloaded = DownloadSources.cachedPaths(workId)
        val peeked = AsmrRepository.peekWork(workId)
        val peekedTracks = AsmrRepository.peekTracks(workId)
        val peekedVisible = filterDownloaded(peekedTracks ?: emptyList(), cachedDownloaded)
        _state.value = DetailUiState(
            loading = peeked == null,
            refreshing = peeked != null,
            work = peeked?.copy(tracks = peekedVisible),
            formatOptions = peekedVisible.audioFormatOptions(),
            selectedFormatIndex = preferredIndex(
                peekedVisible.audioFormatOptions(),
                AppPrefs.audioFormat.value
            ),
            downloadTree = peekedTracks ?: emptyList(),
            downloadedPaths = cachedDownloaded
        )

        viewModelScope.launch {
            // 上次展开过哪些文件夹。和树一起用 —— 只有树到了才知道这些路径还有没有效
            val remembered = FolderStateStore.load(workId)
            // 清单可能还没预热到，这里补一次（已缓存的直接返回内存里的那份）
            val downloaded = DownloadSources.paths(workId)

            // 内存里没有（冷启动直接从收藏页点进来）→ 先用**磁盘那份**铺界面。
            // 仍然不发请求：真正要不要联网由 getWorkDetail 里的保鲜期决定
            if (_state.value.work == null) {
                AsmrRepository.cachedWorkMeta(workId)?.let { cached ->
                    val tree = peekedTracks ?: cached.tracks
                    val visible = filterDownloaded(tree, downloaded)
                    val options = visible.audioFormatOptions()
                    _state.value = DetailUiState(
                        loading = false,
                        refreshing = true,
                        work = cached.copy(tracks = visible),
                        expanded = restoreExpanded(remembered, visible),
                        formatOptions = options,
                        selectedFormatIndex = preferredIndex(options, AppPrefs.audioFormat.value),
                        downloadTree = tree,
                        downloadedPaths = downloaded
                    )
                }
            }

            val result = AsmrRepository.getWorkDetail(workId, forceRefresh)
            // 仓库返回的永远是**完整树**（已下载的作品则来自本地清单），
            // 过滤只发生在展示层 —— 这样"再下几个"还能选到没下的文件
            val fullTree = result.work?.tracks ?: _state.value.downloadTree
            val visible = filterDownloaded(fullTree, downloaded)
            val options = visible.audioFormatOptions()
            _state.value = _state.value.copy(
                loading = false,
                refreshing = false,
                work = (result.work ?: _state.value.work)?.copy(tracks = visible),
                metaError = result.metaError,
                tracksError = if (fullTree.isEmpty()) result.tracksError else null,
                // 用户本次手动改过就听他的；否则回填上次展开的那些文件夹
                expanded = if (foldersTouched) _state.value.expanded else restoreExpanded(remembered, visible),
                formatOptions = options,
                selectedFormatIndex = preferredIndex(options, AppPrefs.audioFormat.value),
                downloadTree = fullTree,
                downloadedPaths = downloaded
            )
        }

        // 下载完成 / 删除后重新过滤（纯本地，不发请求）
        if (downloadObserverJob?.isActive != true) {
            downloadObserverJob = viewModelScope.launch {
                DownloadManager.tasks.collect { tasks ->
                    // 用 loadedId 而不是外层捕获的 workId：同一个 ViewModel 可能被
                    // 复用去加载另一部作品（如点语言版本），捕获值会过期
                    val task = tasks[loadedId] ?: return@collect
                    if (task.isActive) return@collect
                    if (_state.value.work == null) return@collect
                    applyDownloadedState()
                }
            }
        }
    }

    /** 把当前内存里的完整树按磁盘上的下载记录重新过滤一遍 */
    private suspend fun applyDownloadedState() {
        val workId = loadedId
        if (workId == 0) return
        val downloaded = DownloadSources.paths(workId)
        val full = _state.value.downloadTree
        if (full.isEmpty()) return
        val visible = filterDownloaded(full, downloaded)
        val options = visible.audioFormatOptions()
        _state.value = _state.value.copy(
            work = _state.value.work?.copy(tracks = visible),
            expanded = _state.value.expanded.intersect(collectFolderPaths(visible, Int.MAX_VALUE).toSet()),
            formatOptions = options,
            selectedFormatIndex = _state.value.selectedFormatIndex.coerceIn(
                0,
                (options.size - 1).coerceAtLeast(0)
            ),
            downloadedPaths = downloaded
        )
    }

    /**
     * 详情页只显示已下载的内容。
     *
     * [paths] 为空 = 没下载过 → 原样返回（不是"什么都不显示"）。
     */
    private fun filterDownloaded(tree: List<Track>, paths: Set<String>): List<Track> =
        if (paths.isEmpty()) tree else filterTreeTo(tree, paths)

    fun refresh() {
        if (loadedId != 0) load(loadedId, forceRefresh = true)
    }

    // ─────────────────────────── 下载 ───────────────────────────

    /**
     * 开始下载选中的文件（树内路径），并给一条"已加入下载"的提示。
     *
     * 这里**只入队**：真正的网络请求由 [DownloadManager] 在后台排队执行，
     * 详情页不必等待，用户可以立刻返回或退出。
     */
    fun downloadSelected(paths: Collection<String>) {
        val work = _state.value.work ?: return
        val tree = _state.value.pickerTree
        if (paths.isEmpty() || tree.isEmpty()) return
        // 存哪儿由「下载」页那个开关决定（默认 App 内，也能切成公共目录）
        val storage = AppPrefs.downloadStorage.value
        DownloadManager.enqueue(work, tree, paths, storage)
        val count = paths.size
        val where = if (storage == DownloadStorage.LOCAL) "本地文件夹" else "App 内"
        notify("已加入下载队列（$count 个文件，保存到$where），可在「下载」页查看进度")
    }

    // ─────────────────────────── 我的收藏（云端状态）───────────────────────────

    /**
     * 设置 / 更新这个作品的收藏状态与**我的评分**。
     *
     * @param status 标记状态，null = 不设置状态（不表示"移除"）
     * @param rating 用户选的星级，null = 不改动评分
     * @param remove 是否**移除整条标记**（弹窗里的「移除」按钮）
     *
     * ⚠️ 这里刻意把"只评分不打状态"和"移除标记"分开：
     * 早期版本只有一个 `status` 参数，于是"只选了星级就确认"会走到
     * `status = null` → 被当成移除 → **把整条记录删掉**。
     * 那是比丢评分更严重的行为。
     * 需要登录 —— 未登录时直接给出提示，不做乐观更新（否则会假装成功）。
     */
    fun setCollectionStatus(
        status: CollectionStatus?,
        rating: Int? = null,
        remove: Boolean = false
    ) {
        performCollectionWrite(status, rating, remove)
    }

    /**
     * 失败后重试上一次的标记尝试。
     *
     * 保留这个入口是因为失败原因多为**暂时性**的（网络、登录失效后重登），
     * 让用户能一键重来，而不是重新点开弹窗再选一遍状态和星级。
     *
     * 清空意图必须**沿用上次算好的结果**，不能在重试时重新推断：
     * 那时界面上的 work 已经被乐观更新改成了目标值（评分已变 null），
     * 再推断一次就会得出"原本没有评分"，于是这次重试什么也不发 —— 意图静默丢失。
     */
    fun retryCollectionWrite() {
        val attempt = lastAttempt ?: return
        performCollectionWrite(
            status = attempt.status,
            rating = attempt.rating,
            remove = attempt.remove,
            clearStatusOverride = attempt.clearStatus,
            clearRatingOverride = attempt.clearRating,
            keepStatus = attempt.keepStatus
        )
    }

    /**
     * 详情页「标记」按钮的快捷评分：直接给 5 星，**不改状态**。
     *
     * 为什么不直接用 `setCollectionStatus(null, rating)`：
     * 它默认会把"状态为 null"推断成"清掉状态"（只要本地原本有状态），
     * 快捷评分于是会顺手抹掉用户原来的「想听/在听」。这里显式
     * `clearStatusOverride = false` + `keepStatus = true`，只写星级。
     *
     * 再点一次仍是 5 星（幂等）；要改分数或状态走提示里的「修改」。
     */
    fun quickRate(stars: Int = QUICK_RATE_STARS) {
        val work = _state.value.work ?: return
        pendingQuickRate = QuickRateRequest(
            workId = work.id,
            stars = stars,
            // 记下点之前的样子：「取消」要把这一笔原样还回去
            previousRating = work.userRating,
            previousStatus = work.collectionStatus
        )
        performCollectionWrite(
            status = null,
            rating = stars,
            remove = false,
            clearStatusOverride = false,
            clearRatingOverride = false,
            keepStatus = true,
            // 只落本地：网络那半等 6 秒反悔窗口结束后由界面调 [sendQuickRate]
            deferNetwork = true
        )
    }

    /**
     * 反悔窗口结束后才有意义：把刚才那次快捷评分的**网络那一半**发出去。
     *
     * 不碰界面状态（可能已经离开详情页了），本地内容在 [quickRate] 时就落好了；
     * 失败会由仓库记进待补写队列，下一次对齐自动补上。
     */
    suspend fun sendQuickRate(request: QuickRateRequest) {
        runCatching {
            AsmrRepository.sendDeferredCollectionUpdate(
                workId = request.workId,
                status = null,
                rating = request.stars,
                remove = false,
                work = null,
                clearStatus = false,
                clearRating = false,
                keepStatus = true
            )
        }
        if (pendingQuickRate == request) pendingQuickRate = null
    }

    /** 还没发出去的那次快捷评分（界面拿来登记反悔窗口） */
    fun pendingQuickRate(): QuickRateRequest? = pendingQuickRate

    /** 弹窗确认了别的选择：这次快捷评分作废 */
    fun dropPendingQuickRate() {
        pendingQuickRate = null
    }

    /**
     * 弹窗点了「取消」：把这次快捷评分**原样还回去**，等于什么都没发生。
     *
     * 只写本地、不发网络 —— 那笔网络请求还在 6 秒反悔窗口里，从来没发出去过。
     *
     * ## 为什么"还原"要分两种情况
     *
     * 快捷评分只会在"这部作品还没进过收藏"时被触发（没有状态、也没有星级），所以撤销
     * 通常意味着**这条记录本来就不该存在**。此时必须把本地那一行**整条删掉**；
     * 只把状态/评分写成空是不行的 —— [AsmrRepository.patchCollectionCache] 在没有
     * `remove` 时是"就地更新 / 插入"，于是会留下一条「无状态、无评分」的空记录：
     * 收藏页看得见它，直到下次与云端对齐才消失（用户报过这个现象）。
     *
     * 反过来，如果点之前确实有状态或星级（未来别的入口可能这样调），那就把两个字段
     * 还原成点之前的值，不能误删整条记录。
     */
    fun revertQuickRate(request: QuickRateRequest) {
        if (pendingQuickRate == request) pendingQuickRate = null
        val wasCollected = request.previousStatus != null || request.previousRating != null

        // 界面也得还回去：否则取消之后详情页仍然亮着 5 星，看起来像没撤销成功
        _state.value.work?.let { work ->
            if (work.id == request.workId) {
                _state.value = _state.value.copy(
                    work = work.copy(
                        progress = request.previousStatus?.apiValue,
                        userRating = request.previousRating
                    ),
                    collectionFailure = null
                )
            }
        }

        viewModelScope.launch {
            runCatching {
                AsmrRepository.updateCollection(
                    workId = request.workId,
                    status = request.previousStatus,
                    rating = request.previousRating,
                    // 之前不在收藏里 → 整条移除（本地即时生效、不发请求）
                    remove = !wasCollected,
                    work = _state.value.work,
                    // 点之前没有的东西要显式清掉（部分更新不传等于"别动"）
                    clearStatus = wasCollected && request.previousStatus == null,
                    clearRating = wasCollected && request.previousRating == null,
                    deferNetwork = true
                )
            }
        }
    }

    /** 一次快捷评分要发的全部内容（只带 id/星级：离开页面后也能发） */
    data class QuickRateRequest(
        val workId: Int,
        val stars: Int,
        /** 点之前的样子：弹窗「取消」时按它回滚 */
        val previousRating: Int? = null,
        val previousStatus: CollectionStatus? = null
    )

    /** 一次标记尝试的完整意图（含两层"清空"语义），供重试原样复用 */
    private data class CollectionAttempt(
        val status: CollectionStatus?,
        val rating: Int?,
        val remove: Boolean,
        val clearStatus: Boolean,
        val clearRating: Boolean,
        val keepStatus: Boolean = false
    )

    private fun performCollectionWrite(
        status: CollectionStatus?,
        rating: Int?,
        remove: Boolean,
        clearStatusOverride: Boolean? = null,
        clearRatingOverride: Boolean? = null,
        keepStatus: Boolean = false,
        deferNetwork: Boolean = false
    ) {
        val work = _state.value.work ?: return
        if (!AuthManager.state.value.isLoggedIn) {
            // 没登录就不发请求：直接给出可执行的指引
            _state.value = _state.value.copy(collectionFailure = notLoggedInFailure())
            return
        }

        // 移除整条标记时，两个字段都要清空
        val targetStatus = if (remove) null else status
        val targetRating = if (remove) null else rating

        // 写入前的快照，失败时回滚用（就是上面那个非空 work）
        val previous = work
        // 「清空某个字段」只有**本地原本确实有这个值**时才成立：
        //   - 用户想清空 → 弹窗里原本亮着的星星 / 选中的状态被他点掉，previous 里当然有值
        //   - 本地原本就是空 → 那不是「清空」，而是「本来没有」，不该向云端送 null。
        //     云端可能其实有（本地用的是过期的离线副本），送 null 会**静默删掉用户的真实标记**。
        // 所以不能用「目标值为空」推断清空，必须看「原来有、现在没有」。
        val clearStatus = clearStatusOverride
            ?: (!remove && targetStatus == null && previous.progress != null)
        val clearRating = clearRatingOverride
            ?: (!remove && targetRating == null && previous.userRating != null)
        lastAttempt = CollectionAttempt(
            status = targetStatus,
            rating = targetRating,
            remove = remove,
            clearStatus = clearStatus,
            clearRating = clearRating,
            keepStatus = keepStatus
        )

        // 乐观更新：状态与评分一起改，界面立刻反映。
        // ⚠️ 评分这里**不能**写成 `targetRating ?: work.userRating`：
        // 那样"清空评分"会被回填成旧值，星星跳回来，用户以为没生效。
        // keepStatus 时状态原样留着（快捷评分只动星级）。
        _state.value = _state.value.copy(
            work = work.copy(
                progress = if (keepStatus) previous.progress else targetStatus?.apiValue,
                userRating = targetRating
            ),
            collectionBusy = true,
            collectionFailure = null
        )

        viewModelScope.launch {
            if (deferNetwork) {
                // 只落本地：网络那半由界面在「反悔窗口」结束后调 [sendQuickRate]
                runCatching {
                    AsmrRepository.updateCollectionOrThrow(
                        workId = work.id,
                        status = targetStatus,
                        rating = targetRating,
                        remove = remove,
                        work = work,
                        clearStatus = clearStatus,
                        clearRating = clearRating,
                        keepStatus = keepStatus,
                        deferNetwork = true
                    )
                }
                _state.value = _state.value.copy(collectionBusy = false)
                return@launch
            }
            // 用 Result 拿到**原始异常**，才能按类型分类出真正的原因
            val result = runCatching {
                AsmrRepository.updateCollectionOrThrow(
                    workId = work.id,
                    status = targetStatus,
                    rating = targetRating,
                    remove = remove,
                    // 把作品本体传下去：新标记时要靠它的标题/封面插进本地列表，
                    // 这样收藏能立刻显示，而不是等一次云端核对
                    work = work,
                    // 弹窗里的选中值就是**完整的目标状态**（它从当前状态初始化），
                    // 所以"状态被点掉"表达的是"清掉状态、保留评分"，
                    // 必须显式告诉服务端 —— 不然 PUT 是部分更新，云端会原样留着旧状态
                    clearStatus = clearStatus,
                    // 同理：星级被点掉 = 清空评分（保留状态）
                    clearRating = clearRating,
                    // 快捷评分只写星级：本地与云端的状态都保持原样
                    keepStatus = keepStatus
                )
            }
            _state.value = if (result.isSuccess) {
                _state.value.copy(collectionBusy = false, collectionFailure = null)
            } else {
                _state.value.copy(
                    // 回滚到写入前的样子 —— 界面不能显示一个其实没保存的状态
                    work = previous,
                    collectionBusy = false,
                    collectionFailure = classifyMarkFailure(result.exceptionOrNull())
                )
            }
        }
    }

    /** 弹窗里的「移除」会用到；同时把失败提示收起来 */
    fun setCollectionStatusRemove() {
        performCollectionWrite(status = null, rating = null, remove = true)
    }

    fun dismissCollectionFailure() {
        _state.value = _state.value.copy(collectionFailure = null)
    }

    // ─────────────────────────── 文件树交互 ───────────────────────────

    fun toggleFolder(path: String) {
        val expanded = _state.value.expanded
        val next = if (path in expanded) expanded - path else expanded + path
        foldersTouched = true
        _state.value = _state.value.copy(expanded = next)
        persistExpanded(next)
    }

    /** 展开全部（给一个上限，避免 1000+ 节点的作品一次性铺满界面） */
    fun expandAll() {
        val tree = _state.value.tracks
        val next = collectFolderPaths(tree).toSet()
        foldersTouched = true
        _state.value = _state.value.copy(expanded = next)
        persistExpanded(next)
    }

    fun collapseAll() {
        foldersTouched = true
        _state.value = _state.value.copy(expanded = emptySet())
        // 全部收起 = 删掉记录（收起本来就是默认值，没必要占配额）
        persistExpanded(emptySet())
    }

    /**
     * 记下"这部作品展开了哪些文件夹"，下次进来直接回填。
     *
     * 每次点击都写一次：它只有几百字节，换来的是"退出应用也不会忘"。
     * 写入放在 IO 线程，不阻塞点击。
     */
    private fun persistExpanded(paths: Set<String>) {
        val workId = loadedId
        if (workId <= 0) return
        // 树还没到就点了「展开/收起全部」：这时的空集不代表用户把文件夹都收起了，
        // 写下去会把上次的记忆抹掉
        if (paths.isEmpty() && _state.value.tracks.isEmpty()) return
        viewModelScope.launch { FolderStateStore.save(workId, paths) }
    }

    /**
     * 把上次记下的展开路径套到**当前这棵树**上。
     *
     * 只保留现在仍然是文件夹的路径：作者改了目录结构、或某个目录被删掉时，
     * 强行套上去只会得到"展开了但下面什么都没有"的死条目。
     */
    private fun restoreExpanded(remembered: Set<String>, tree: List<Track>): Set<String> {
        if (remembered.isEmpty() || tree.isEmpty()) return emptySet()
        val folders = collectFolderPaths(tree, limit = Int.MAX_VALUE).toHashSet()
        return remembered.filterTo(HashSet()) { it in folders }
    }

    // ─────────────────────────── 音质分支 ───────────────────────────

    fun selectFormat(index: Int) {
        val options = _state.value.formatOptions
        if (index !in options.indices) return
        _state.value = _state.value.copy(selectedFormatIndex = index)
        // 记住偏好：标签里含 mp3/wav/m4a/flac 的写入设置，其余（全部）存空
        val label = options[index].label.lowercase()
        AudioFormatPreference.entries.firstOrNull { it.value.isNotEmpty() && label.contains(it.value) }
            ?.let { AppPrefs.setAudioFormat(it.value) }
            ?: AppPrefs.setAudioFormat(AudioFormatPreference.ALL.value)
    }

    // ─────────────────── 非音频节点：图片 / 视频 / 文本 / PDF ───────────────────
    //
    // 文件树里除音频外还有四类内容（实测比例：图片 : 音频 ≈ 1.16 : 1）：
    //   图片  3381 个 —— 内置查看
    //   视频    94 个 —— 内置播放（.mp4 的 type 是 audio，见 TrackKind）
    //   文本   274 个 —— 内置阅读
    //   PDF     24 个 —— 交外部阅读器
    // 它们的处理方式与必要的判定见 TrackKind / 各查看器。

    /**
     * 把一个树节点解析成"可打开的文件"。
     *
     * @return null 表示这个节点没有可用的打开方式（没有 URL，或类型不支持）
     */
    fun openableOf(node: Track): OpenableFile? {
        val url = node.mediaStreamUrl?.takeIf { it.isNotBlank() } ?: return null
        val kind = TrackKind.of(node)
        if (kind == TrackKind.FOLDER || kind == TrackKind.AUDIO) return null

        return OpenableFile(
            title = node.title,
            kind = kind,
            url = url,
            sizeBytes = node.size ?: 0L,
            // 图片才需要"同目录左右滑动"
            siblings = if (kind == TrackKind.IMAGE) imageSiblingsOf(node) else emptyList()
        )
    }

    /**
     * 同一父目录下的**全部图片**，用于图片查看页的左右滑动。
     *
     * 为什么要按"父目录"而不是"整棵树的图片"：一个作品里图片常分散在
     * 若干目录（`特典/高画质イラスト`、`特典/表紙サンプル`、`本編/...`），
     * 把不同目录混成一条序列会让用户滑到无关内容。
     *
     * 父目录由 [Track.path] 推出（path 是"父路径/标题"逐级拼成的），
     * 这与树本身的构造方式一致，不需要额外维护父指针。
     */
    private fun imageSiblingsOf(node: Track): List<OpenableFile> {
        val parent = node.path.substringBeforeLast('/', "")
        val out = mutableListOf<OpenableFile>()
        collectSameDirImages(_state.value.tracks, parent, out)
        return out.ifEmpty {
            // 兜底：至少把自己放进去，否则 Pager 会是空的
            listOf(
                OpenableFile(
                    title = node.title,
                    kind = TrackKind.IMAGE,
                    url = node.mediaStreamUrl.orEmpty(),
                    sizeBytes = node.size ?: 0L
                )
            )
        }
    }

    private fun collectSameDirImages(nodes: List<Track>, parent: String, out: MutableList<OpenableFile>) {
        nodes.forEach { n ->
            if (n.children.isNotEmpty()) {
                collectSameDirImages(n.children, parent, out)
            } else if (TrackKind.of(n) == TrackKind.IMAGE) {
                val dir = n.path.substringBeforeLast('/', "")
                val url = n.mediaStreamUrl
                if (dir == parent && !url.isNullOrBlank()) {
                    out += OpenableFile(
                        title = n.title,
                        kind = TrackKind.IMAGE,
                        url = url,
                        sizeBytes = n.size ?: 0L
                    )
                }
            }
        }
    }

    /**
     * 下载 PDF 到缓存目录，准备交给外部阅读器。
     *
     * 走 suspend 而不是后台 fire-and-forget：调用方要等它完成才能发 Intent，
     * 而且要知道成功与否以便提示。实测 PDF 最大 17MB，
     * 所以这里用"先下完再打开"，不是"边下边看"。
     *
     * @return 落盘的文件；失败返回 null（并把原因写进 [DetailUiState.toastMessage]）
     */
    suspend fun downloadForExternalOpen(
        context: android.content.Context,
        file: OpenableFile
    ): java.io.File? {
        if (file.kind != TrackKind.PDF) return null
        return runCatching {
            val bytes = AsmrRepository.fetchBytes(file.url)
            ExternalOpenFiles.write(
                context = context,
                key = file.url,
                extension = file.title.substringAfterLast('.', "pdf").lowercase(),
                bytes = bytes
            )
        }.getOrNull()
    }

    /** 让界面显示一条一次性提示（toast） */
    fun notify(message: String) {
        _state.value = _state.value.copy(toastMessage = message, toastSeq = _state.value.toastSeq + 1)
    }

    /** 提示已展示，清掉，避免重组时重复弹 */
    fun consumeToast() {
        _state.value = _state.value.copy(toastMessage = null)
    }

    private fun preferredIndex(options: List<AudioFormatOption>, preference: String): Int {
        if (options.isEmpty()) return 0
        val picked = options.pickPreferred(AudioFormatPreference.fromValue(preference))
        return options.indexOf(picked).coerceAtLeast(0)
    }
}
