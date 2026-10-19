package com.asmrone.app.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.AudioFormatOption
import com.asmrone.app.data.model.AudioFormatPreference
import com.asmrone.app.data.cache.ExternalOpenFiles
import com.asmrone.app.data.cache.FolderStateStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val toastSeq: Int = 0
) {
    val tracks: List<Track> get() = work?.tracks ?: emptyList()
    val selectedFormat: AudioFormatOption? get() = formatOptions.getOrNull(selectedFormatIndex)
    /** 当前分支的播放列表（交给播放器的就是它） */
    val playbackList: List<Track> get() = selectedFormat?.tracks ?: emptyList()
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

    /** 上一次标记尝试的内容，供失败后「重试」用 */
    private var lastAttempt: Triple<CollectionStatus?, Int?, Boolean>? = null

    fun load(workId: Int, forceRefresh: Boolean = false) {
        if (!forceRefresh && loadedId == workId && _state.value.work != null) return
        loadedId = workId
        foldersTouched = false

        // 先用列表页缓存把界面铺出来（标题/封面/评分立刻可见），再去补齐文件树
        val peeked = AsmrRepository.peekWork(workId)
        val peekedTracks = AsmrRepository.peekTracks(workId)
        _state.value = DetailUiState(
            loading = peeked == null,
            refreshing = peeked != null,
            work = peeked?.copy(tracks = peekedTracks ?: emptyList()),
            formatOptions = (peekedTracks ?: emptyList()).audioFormatOptions(),
            selectedFormatIndex = preferredIndex(
                (peekedTracks ?: emptyList()).audioFormatOptions(),
                AppPrefs.audioFormat.value
            )
        )

        viewModelScope.launch {
            // 上次展开过哪些文件夹。和树一起用 —— 只有树到了才知道这些路径还有没有效
            val remembered = FolderStateStore.load(workId)

            // 内存里没有（冷启动直接从收藏页点进来）→ 先用**磁盘那份**铺界面。
            // 仍然不发请求：真正要不要联网由 getWorkDetail 里的保鲜期决定
            if (_state.value.work == null) {
                AsmrRepository.cachedWorkMeta(workId)?.let { cached ->
                    val tree = peekedTracks ?: cached.tracks
                    val options = tree.audioFormatOptions()
                    _state.value = DetailUiState(
                        loading = false,
                        refreshing = true,
                        work = cached.copy(tracks = tree),
                        expanded = restoreExpanded(remembered, tree),
                        formatOptions = options,
                        selectedFormatIndex = preferredIndex(options, AppPrefs.audioFormat.value)
                    )
                }
            }

            val result = AsmrRepository.getWorkDetail(workId, forceRefresh)
            val tree = result.work?.tracks ?: emptyList()
            val options = tree.audioFormatOptions()
            _state.value = _state.value.copy(
                loading = false,
                refreshing = false,
                work = result.work ?: _state.value.work,
                metaError = result.metaError,
                tracksError = if (tree.isEmpty()) result.tracksError else null,
                // 用户本次手动改过就听他的；否则回填上次展开的那些文件夹
                expanded = if (foldersTouched) _state.value.expanded else restoreExpanded(remembered, tree),
                formatOptions = options,
                selectedFormatIndex = preferredIndex(options, AppPrefs.audioFormat.value)
            )
        }
    }

    fun refresh() {
        if (loadedId != 0) load(loadedId, forceRefresh = true)
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
     */
    fun retryCollectionWrite() {
        val attempt = lastAttempt ?: return
        performCollectionWrite(attempt.first, attempt.second, attempt.third)
    }

    private fun performCollectionWrite(
        status: CollectionStatus?,
        rating: Int?,
        remove: Boolean
    ) {
        val work = _state.value.work ?: return
        if (!AuthManager.state.value.isLoggedIn) {
            // 没登录就不发请求：直接给出可执行的指引
            _state.value = _state.value.copy(collectionFailure = notLoggedInFailure())
            return
        }

        lastAttempt = Triple(status, rating, remove)

        // 移除整条标记时，两个字段都要清空
        val targetStatus = if (remove) null else status
        val targetRating = if (remove) null else rating

        val previous = _state.value.work
        // 乐观更新：状态与评分一起改，界面立刻反映（也顺手把 work 里的评分更新，
        // 否则下一次操作又会从这份旧值回落到错误的评分）
        _state.value = _state.value.copy(
            work = work.copy(
                progress = targetStatus?.apiValue,
                userRating = targetRating ?: work.userRating
            ),
            collectionBusy = true,
            collectionFailure = null
        )

        viewModelScope.launch {
            // 用 Result 拿到**原始异常**，才能按类型分类出真正的原因
            val result = runCatching {
                AsmrRepository.updateCollectionOrThrow(
                    workId = work.id,
                    status = targetStatus,
                    rating = targetRating,
                    remove = remove,
                    // 把作品本体传下去：新标记时要靠它的标题/封面插进本地列表，
                    // 这样收藏能立刻显示，而不是等一次云端核对
                    work = work
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
