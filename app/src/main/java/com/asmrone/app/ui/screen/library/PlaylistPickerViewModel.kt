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

/**
 * 「收藏到哪些收藏夹」弹窗的 ViewModel。
 *
 * ## 一个作品可以同时在多个收藏夹里
 *
 * 服务端的数据是"收藏夹 → 作品列表"的关系，同一个作品出现在几个收藏夹里是很正常的
 * （网页端也是这么用的）。所以这里**不能**用"一个作品只属于一个收藏夹"的模型：
 *  - 弹窗是多选：每个收藏夹一个勾，进来时按真实成员关系预勾；
 *  - 确认时按差集提交：新勾上的加入、取消勾的移出，其余的完全不动；
 *  - 收藏图标只表示"在至少一个收藏夹里"，不表示"在某个默认收藏夹里"。
 *
 * 旧实现是单选 + "同一个按钮即加入即移出"，于是会出现两个问题：
 *  1. 在收藏夹 A 里点"移出"，实际移出的是上次选择过的 B；
 *  2. 从 A 移出后，B 里明明还有这部作品，详情页图标却灭了。
 * 这两点都是"作品只属于一个收藏夹"这个错误前提造成的。
 *
 * ## 核对同样受每日限制
 *
 * 打开弹窗时：
 *  1. 先用**本地缓存**的收藏夹列表 + 每份内容的成员关系铺出来（瞬时）；
 *  2. 只有**当天还没核对过**才去问服务端（条件请求，未变化为 304 = 0 字节）；
 *  3. 本地没有某份收藏夹内容时（`cachedIsWorkInPlaylist` 返回 null），
 *     该项先在后台逐条核（见 [resolveMembership]），结果回来前按"未勾选"显示。
 */
class PlaylistPickerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlaylistPickerState())
    val state: StateFlow<PlaylistPickerState> = _state.asStateFlow()

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    // ─────────────────────── 直接收藏（不弹窗） ───────────────────────

    private val _quick = MutableStateFlow(QuickCollectState())
    val quick: StateFlow<QuickCollectState> = _quick.asStateFlow()

    private val _quickEvent = MutableStateFlow<QuickCollectEvent?>(null)
    val quickEvent: StateFlow<QuickCollectEvent?> = _quickEvent.asStateFlow()

    /** 快速收藏正在进行中：防止连点两次同向请求 */
    private var quickBusy = false

    private var workId: Int = 0

    /**
     * 当前操作的作品。
     *
     * 保留整个对象（而不是只留 id）：加入收藏夹后要把它**立刻**插进
     * 本地缓存里的收藏夹内容，这样回到收藏就能看到，
     * 而不必等一次云端核对 —— 那需要标题、封面等字段。
     */
    private var work: Work? = null

    /** 用户手动勾选/取消过的收藏夹：后台核对的结果不再覆盖这些项 */
    private val touchedIds = mutableSetOf<String>()

    /**
     * 已经落地到本地、但**还没发网络**的那次快捷收藏。
     *
     * 界面在 6 秒反悔窗口结束后调 [pendingQuickCollect] 取它再发给服务端；
     * 期间用户点「修改」打开弹窗的话，由 [dropPendingQuickCollect] 作废。
     * 名字带 Request 是为了不和同名的取值函数撞车（Kotlin 里属性与函数不能同名）。
     */
    private var pendingCollectRequest: QuickCollectRequest? = null


    // ─────────────────────────── 弹窗 ───────────────────────────

    fun open(work: Work, unsyncedPlaylistId: String? = null) {
        this.workId = work.id
        this.work = work
        this.touchedIds.clear()
        _result.value = null

        // ① 先用本地缓存铺出来，避免校验期间空白
        val cached = AsmrRepository.peekPlaylists().orEmpty()
        val inside = cached.idsInside(work.id)

        // 「刚收藏、还没发出去」的那个收藏夹：进勾选、但**不进基线**。
        //
        // 这样用户直接点「完成」时，差集里仍然有它 → 那笔加入会被正常发出去。
        // 若放进基线，本地缓存里已经有它，差集为空，`onConfirm` 又把
        // PendingSync 里那笔清掉，这次收藏就永远到不了云端。
        val unsynced = unsyncedPlaylistId?.takeIf { id -> cached.any { it.id == id } }
        val checked = if (unsynced != null) inside + unsynced else inside

        _state.value = PlaylistPickerState(
            playlists = cached,
            checkedIds = checked,
            originalIds = inside,
            loading = cached.isEmpty()
        )
        // 本地没有内容的收藏夹：后台逐条核，界面先用"未勾选"
        resolveMembership(cached.idsUnknown(work.id))

        // ② 再按闸门对齐一次（未变化时 304 = 0 字节；单飞保证并发只发一次）。
        //
        //    这里用 DAILY_AUTO：弹窗是"用户主动打开"的，属于该知道最新数据的时候；
        //    每天仍只自动一次。
        viewModelScope.launch {
            val error = runCatching {
                AsmrRepository.loadPlaylists(SyncTrigger.DAILY_AUTO)
            }.fold(onSuccess = { null }, onFailure = { it.message ?: "获取收藏夹失败" })

            val list = AsmrRepository.peekPlaylists().orEmpty()
            val insideNow = list.idsInside(work.id)
            val unknownNow = list.idsUnknown(work.id)

            _state.value = _state.value.copy(
                playlists = list,
                checkedIds = _state.value.checkedIds + insideNow,
                // 只有确认在里面的才进基线：差集只拿"确定的事实"算，
                // 避免把"本地不知道"误判成"用户取消了勾选"而误删。
                originalIds = _state.value.originalIds + insideNow,
                loading = false,
                error = error
            )
            resolveMembership(unknownNow)
        }
    }

    /**
     * 勾 / 取消勾选一个收藏夹。
     *
     * 只改本地选择，不发请求 —— 提交在 [confirm] 里按差集一次做完。
     */
    fun toggle(playlistId: String) {
        if (_state.value.submitting) return
        val s = _state.value
        touchedIds += playlistId
        _state.value = s.copy(
            checkedIds = if (playlistId in s.checkedIds) s.checkedIds - playlistId
            else s.checkedIds + playlistId,
            error = null
        )
    }

    /**
     * 后台核对"这个作品在不在这些收藏夹里"。
     *
     * 只在本地没有该收藏夹内容时才会走到这里；一条一条来，避免打开弹窗就并发打一串请求。
     * 结果只补"在"这一种事实（顺便进基线），**不会**把已勾选的取消掉。
     */
    private fun resolveMembership(ids: List<String>) {
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(checking = true)
        viewModelScope.launch {
            for (id in ids) {
                val inside = runCatching { AsmrRepository.isWorkInPlaylist(id, workId) }
                    .getOrDefault(false)
                if (!inside) continue
                if (id in touchedIds) continue
                val s = _state.value
                if (id !in s.playlists.map { it.id }) continue
                _state.value = s.copy(
                    checkedIds = s.checkedIds + id,
                    originalIds = s.originalIds + id
                )
            }
            _state.value = _state.value.copy(checking = false)
        }
    }

    /**
     * 提交：按差集加入 / 移出。
     *
     * 与旧的"单选按钮即加入即移出"相比，这里**只做用户明确改动过的收藏夹**，
     * 所以绝不会出现"想从 A 移出、结果动了 B"。
     *
     * ## 本地先行（与「标记」同一套）
     *
     * 点「保存」时**只写本地缓存**，弹窗立刻关闭、提示立刻出现；
     * 网络那半交给 [PendingSync] 在后台补发（进程级 scope，退出详情页也照样发）。
     * 发不出去时仓库会记一条待补写，卡片上会出现「待同步」，下次对齐自动重试 ——
     * 这正是「标记」的处理方式，不用为了等网络把弹窗卡在"处理中"。
     */
    fun confirm() {
        val s = _state.value
        if (s.submitting || workId == 0) return

        val toAdd = s.playlists.filter { it.id in s.checkedIds && it.id !in s.originalIds }
        val toRemove = s.playlists.filter { it.id in s.originalIds && it.id !in s.checkedIds }
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            // 空串 = "什么都不用做，直接关掉"，界面不会弹提示（用户只是点开看了一眼）
            _result.value = ""
            return
        }

        _state.value = s.copy(submitting = true, error = null)
        val pendingWorkId = workId
        viewModelScope.launch {
            // ① 本地先行：全部改动先落本地缓存（deferNetwork = true 时函数只做本地那一半）
            val failures = mutableListOf<String>()
            for (playlist in toRemove) {
                val result = AsmrRepository.removeWorkFromPlaylist(playlist.id, workId, deferNetwork = true)
                if (!result.ok) failures += "「${playlist.displayName}」移出失败"
            }
            for (playlist in toAdd) {
                val result = AsmrRepository.addWorkToPlaylist(playlist.id, workId, work, deferNetwork = true)
                if (!result.ok) failures += "「${playlist.displayName}」加入失败"
            }
            // 记住最后一次加进去的收藏夹：下次点收藏按钮还进它
            toAdd.lastOrNull()?.let { AsmrRepository.rememberSelectedPlaylist(it.id) }

            // ② 界面以本地缓存为事实：这一轮的结果立刻生效
            val insideNow = AsmrRepository.peekPlaylists().orEmpty().idsInside(workId)
            _quick.value = _quick.value.copy(inside = insideNow.isNotEmpty())
            _state.value = _state.value.copy(submitting = false)

            if (failures.isNotEmpty()) {
                _state.value = _state.value.copy(error = failures.joinToString("；"))
                return@launch
            }
            _result.value = summaryOf(toAdd, toRemove, changed = true)

            // ③ 网络在后台发（每个收藏夹各一条，互不影响；失败会记待补写）
            for (playlist in toRemove) {
                schedulePlaylistWrite(playlist.id, playlist.displayName, pendingWorkId, remove = true)
            }
            for (playlist in toAdd) {
                schedulePlaylistWrite(playlist.id, playlist.displayName, pendingWorkId, remove = false)
            }
        }
    }

    /**
     * 把"发网络"这一步交给 [PendingSync]：`hold` 登记、`schedule(0)` 立刻发。
     *
     * 用进程级 scope 而不是 `viewModelScope`：用户点完「保存」很可能马上返回，
     * 挂着 ViewModel 的协程会随页面一起被取消 —— 本地改了、云端却永远不知道。
     */
    private fun schedulePlaylistWrite(
        playlistId: String,
        playlistName: String,
        targetWorkId: Int,
        remove: Boolean
    ) {
        // key 带上方向与收藏夹：同一次点击里的加入/移出各发各的，不会互相覆盖
        val key = "playlist-write:$playlistId:$targetWorkId:${if (remove) "rm" else "add"}"
        PendingSync.hold(key) {
            val result = if (remove) {
                AsmrRepository.sendDeferredPlaylistRemove(playlistId, targetWorkId)
            } else {
                AsmrRepository.sendDeferredPlaylistAdd(playlistId, targetWorkId)
            }
            val error = result.error ?: return@hold
            // 夹在后台跑完才发现收藏夹被删了：仓库已撤销本地那笔，图标要跟着重画
            if (AsmrRepository.cachedIsWorkInPlaylist(playlistId, targetWorkId) == false) {
                prepareQuick(targetWorkId)
            }
            _quickEvent.value = QuickCollectEvent("「$playlistName」${if (remove) "移出" else "加入"}失败：$error")
        }
        PendingSync.schedule(key, 0L)
    }

    private fun summaryOf(
        toAdd: List<Playlist>,
        toRemove: List<Playlist>,
        changed: Boolean
    ): String = when {
        toAdd.isNotEmpty() && toRemove.isNotEmpty() -> "已更新收藏夹"
        toAdd.isNotEmpty() -> "已收藏到${toAdd.joinToString("") { "「${it.displayName}」" }}"
        toRemove.isNotEmpty() -> "已从${toRemove.joinToString("") { "「${it.displayName}」" }}移出"
        !changed -> "云端本来就是这个状态"
        else -> "已更新收藏夹"
    }

    // ─────────────────────── 详情页顶栏的收藏图标 ───────────────────────

    /**
     * 详情页拿到作品后调一次：只读本地，**不发任何请求**。
     *
     * 只回答"在不在**某个**收藏夹里"：
     *  - 至少一个收藏夹确定有 → `true`；
     *  - 所有收藏夹本地都确定没有 → `false`；
     *  - 还有收藏夹本地没内容（不知道） → `null`，界面按"未收藏"画。
     *
     * 这样从收藏夹 B 打开的作品，即使已经从 A 移出，图标照样是亮的。
     */
    fun prepareQuick(workId: Int) {
        if (workId <= 0) return
        val list = AsmrRepository.peekPlaylists().orEmpty()
        val target = AsmrRepository.defaultPlaylist(list)
        val inside = list.idsInside(workId)
        val unknown = list.idsUnknown(workId)
        _quick.value = QuickCollectState(
            playlistId = target?.id.orEmpty(),
            playlistName = target?.displayName.orEmpty(),
            inside = when {
                inside.isNotEmpty() -> true
                unknown.isEmpty() && list.isNotEmpty() -> false
                else -> null
            }
        )
    }

    /**
     * 点收藏按钮（**只在"还没收藏"时调用**）：直接写进默认收藏夹，不弹窗。
     *
     * 已经在某个收藏夹里时，界面应当直接打开弹窗让用户修改，而不是走这里 ——
     * 收藏是"多对多"关系，一个"取消收藏"按钮没法表达"从哪个收藏夹移出"。
     *
     * ## 本地优先（与「标记」状态同一套逻辑）
     *
     * 点下去的**第一帧**就要有反馈：方向只看本地缓存，图标与提示立刻更新，**不等网络**。
     * 云端同步在后台跑：
     *  - 成功：什么都不用改，本地已经是对的；
     *  - 暂时失败：本地**不回滚**，仓库把这次改动记进待补写队列（界面照样显示已收藏，
     *    下次对齐自动补上）——这正是「标记」的语义；
     *  - 收藏夹真的不存在：仓库会撤销本地那笔改动，这里再把图标跟着改回去并报原因。
     */
    fun quickCollect(work: Work) {
        if (quickBusy) return
        quickBusy = true
        this.workId = work.id
        this.work = work

        // ① 本地先行：不等网络，先把图标与提示定下来
        val target = localTargetPlaylist()
        _quick.value = QuickCollectState(
            playlistId = target.id,
            playlistName = target.displayName,
            inside = true
        )
        _quickEvent.value = QuickCollectEvent("已收藏至「${target.displayName}」", canModify = true)
        // 记住这次落到哪个收藏夹：下次点收藏还进它
        AsmrRepository.rememberSelectedPlaylist(target.id)

        // ② 本地内容也立刻落盘：图标说"已收藏"，点进收藏夹就必须能看到这部作品。
        //    只写本地（不发网络）—— 缺了这一步，作品要等后台发完 + 下一次对齐才出现。
        AsmrRepository.addWorkToPlaylistLocally(target.id, work.id, work)

        // ② 网络那半**先不发**：等 6 秒反悔窗口结束后由界面触发 [sendQuickCollect]。
        //    用户在这段时间里点「修改」就是"我反悔了"，要等他在弹窗里选完再发。
        pendingCollectRequest = QuickCollectRequest(
            playlistId = target.id,
            workId = work.id,
            work = work
        )
        quickBusy = false
    }

    /**
     * 弹窗点了「取消」：把刚才那次快捷收藏撤掉，等于什么都没发生。
     *
     * 只回滚本地并删掉那条待补写 —— 网络那一半还在 6 秒反悔窗口里，从未发出。
     */
    fun revertQuickCollect(request: QuickCollectRequest) {
        if (pendingCollectRequest == request) pendingCollectRequest = null
        AsmrRepository.revertLocalPlaylistAdd(request.playlistId, request.workId)
        _quick.value = _quick.value.copy(
            inside = AsmrRepository.peekPlaylists().orEmpty().idsInside(request.workId).isNotEmpty()
        )
    }

    /**
     * 「删除」：把这个作品从**所有**收藏夹里移除。
     *
     * 作品与收藏夹是多对多，所以"取消收藏"只能表达成"从每个含它的收藏夹移出"。
     * 与其它收藏操作一样本地先行：先落本地、界面立刻更新，网络在后台逐个补发
     * （每个收藏夹各一条，互不影响；失败会记待补写并在卡片上显示「待同步」）。
     */
    fun removeFromAllPlaylists() {
        val s = _state.value
        if (s.submitting || workId == 0) return
        val id = workId

        // 刚才那次还没发出去的快捷收藏，先撤销：它本来就不该存在于任何收藏夹里
        val pending = pendingCollectRequest
        if (pending != null) {
            AsmrRepository.revertLocalPlaylistAdd(pending.playlistId, pending.workId)
            pendingCollectRequest = null
        }

        val targets = s.playlists.filter { AsmrRepository.cachedIsWorkInPlaylist(it.id, id) == true }
        _state.value = s.copy(submitting = true, error = null)
        viewModelScope.launch {
            for (playlist in targets) {
                AsmrRepository.removeWorkFromPlaylist(playlist.id, id, deferNetwork = true)
            }
            _quick.value = QuickCollectState(inside = false)
            _state.value = _state.value.copy(submitting = false)
            _result.value = "已从全部收藏夹移除"
            for (playlist in targets) {
                schedulePlaylistWrite(playlist.id, playlist.displayName, id, remove = true)
            }
        }
    }

    /**
     * 反悔窗口结束后才有意义：把刚才那次收藏的**网络那一半**发出去。
     *
     * 不碰界面主状态（可能已经离开详情页），本地内容在 [quickCollect] 时就落好了。
     * 只有一种情况要把图标改回去：收藏夹已被删除，仓库撤销了本地那笔改动。
     */
    suspend fun sendQuickCollect(request: QuickCollectRequest) {
        val result = AsmrRepository.sendDeferredPlaylistAdd(request.playlistId, request.workId)
        if (pendingCollectRequest == request) pendingCollectRequest = null

        val error = result.error ?: return
        val cached = AsmrRepository.cachedIsWorkInPlaylist(request.playlistId, request.workId)
        if (cached == false) {
            // 仓库撤销了本地那笔：重新按"到底还在不在任何收藏夹里"画
            prepareQuick(request.workId)
            _quickEvent.value = QuickCollectEvent(error)
        }
    }

    /** 还没发出去的那次收藏（界面拿来登记反悔窗口） */
    fun pendingQuickCollect(): QuickCollectRequest? = pendingCollectRequest

    /** 弹窗确认了别的选择：这次快捷收藏作废 */
    fun dropPendingQuickCollect() {
        pendingCollectRequest = null
    }

    /** 一次快捷收藏要发的全部内容（离开页面后也能发） */
    data class QuickCollectRequest(
        val playlistId: String,
        val workId: Int,
        val work: Work
    )

    /**
     * 本次操作落到哪个收藏夹 —— **只读本地**。
     *
     * 本地一次都没同步过收藏夹列表时，回退到「我喜欢的」：它的 id 是服务端常量
     * （[Playlist.SYS_LIKED]），不需要先拉列表也能知道往哪儿写。
     * 若这个账号真的没有该收藏夹，写入会失败并提示原因。
     */
    private fun localTargetPlaylist(): Playlist =
        AsmrRepository.defaultPlaylist(AsmrRepository.peekPlaylists().orEmpty())
            ?: Playlist(id = Playlist.SYS_LIKED, name = Playlist.SYS_LIKED)

    fun clearQuickEvent() {
        _quickEvent.value = null
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

    /** 新建收藏夹，然后自动把它勾上并把作品加进去 */
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

                    if (target == null) {
                        _state.value = _state.value.copy(
                            creating = false,
                            newName = "",
                            submitting = false,
                            playlists = list,
                            createError = "收藏夹已创建，请在列表里勾选它"
                        )
                        return@fold
                    }

                    AsmrRepository.rememberSelectedPlaylist(target.id)
                    _state.value = _state.value.copy(
                        creating = false,
                        newName = "",
                        submitting = false,
                        playlists = list,
                        // 新收藏夹默认勾上，原基线不变 → 它会被算进差集的"加入"
                        checkedIds = _state.value.checkedIds + target.id
                    )
                    _quick.value = _quick.value.copy(inside = true)
                    // 交给 [confirm] 走同一套差集：新建的这个收藏夹、以及"刚点收藏
                    // 还没发出去"的那个，会在这一轮里一起落本地 + 排队补发，
                    // 不用在这里再单独发一次（否则两条路径各发各的，容易漏）。
                    confirm()
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
}

/** 本地确定包含该作品的收藏夹 */
private fun List<Playlist>.idsInside(workId: Int): Set<String> = buildSet {
    for (playlist in this@idsInside) {
        if (AsmrRepository.cachedIsWorkInPlaylist(playlist.id, workId) == true) add(playlist.id)
    }
}

/** 本地没有内容、还回答不了"在不在里面"的收藏夹 */
private fun List<Playlist>.idsUnknown(workId: Int): List<String> =
    filter { AsmrRepository.cachedIsWorkInPlaylist(it.id, workId) == null }.map { it.id }

/**
 * 「直接收藏」的状态，只给详情页顶栏的收藏图标用。
 *
 * [inside] 允许为 null：本地没有**任何一个**收藏夹的内容时我们不知道，
 * 与其为它发一串请求，不如先按"未收藏"画，等用户点了再查/再弹窗。
 */
data class QuickCollectState(
    val playlistId: String = "",
    val playlistName: String = "",
    val inside: Boolean? = null
)

/**
 * 直接收藏的一次性提示。
 *
 * [canModify] 为真时提示里带「修改」按钮，点了才打开收藏夹选择弹窗 ——
 * 也就是"收藏默认不打断用户，想换收藏夹再进一步"。
 */
data class QuickCollectEvent(
    val message: String,
    val canModify: Boolean = false
)
