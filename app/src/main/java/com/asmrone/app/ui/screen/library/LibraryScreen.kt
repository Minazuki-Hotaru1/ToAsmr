package com.asmrone.app.ui.screen.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 收藏页（原「收藏」，已按需求改名为「收藏」）—— 两个页签，对应收藏的**两个入口**：
 *
 * | 页签 | 数据来源 | 含义 |
 * |---|---|---|
 * | **标记** | `GET /api/review` | 我给作品打的标记：想听 / 在听 / 听过 / 重听 / 搁置 + 我的评分 |
 * | **列表** | `GET /api/playlist/...` | 我的收藏夹（系统内置「我标记的」「我喜欢的」）及其中的作品 |
 *
 * 两者都来自账号，需要登录。原先的「本地收藏」与「收听历史」已按需求移除：
 * 详情页的收藏按钮改为写入收藏夹，不再有独立的本地书签概念。
 */
private const val TAB_MARKED = 0
private const val TAB_PLAYLISTS = 1

@Composable
fun LibraryScreen(
    onWorkClick: (Int) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit
) {
    val authState by AuthManager.state.collectAsState()

    val collectionViewModel: CollectionViewModel = viewModel()
    val collection by collectionViewModel.state.collectAsState()

    val playlistsViewModel: PlaylistsViewModel = viewModel()
    val playlists by playlistsViewModel.state.collectAsState()

    var tab by remember { mutableIntStateOf(TAB_MARKED) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    // ① 进入收藏页 → 两份数据都"报个到"。
    //
    //    为什么**两个都调**，而不是只调当前页签那一个：
    //    「标记」与「列表」是两份独立的数据、两个独立的接口
    //    （`/api/review` 与 `/api/playlist/get-playlists`，实测内容互不相交）。
    //    只调当前页签的话，用户若整天只看「标记」，「列表」就永远拿不到更新。
    //
    //    **这里没有任何"该不该发请求"的判断** —— 完全由 SyncGate 决定：
    //    每份数据每天只自动对齐一次，当天之后的调用是纯空操作
    //    （只读本地，一个请求都不发）。所以这份代码每进一次页面都会跑，
    //    但当天只有第一次真的发请求。
    LaunchedEffect(Unit) {
        collectionViewModel.onEnter()
        playlistsViewModel.onEnter()
    }

    // ② 切换页签 → 只是让那个页签把本地内容铺出来（同样受闸门约束，不会重复请求）
    LaunchedEffect(tab) {
        when (tab) {
            TAB_MARKED -> collectionViewModel.onEnter()
            else -> playlistsViewModel.onEnter()
        }
    }

    // ③ 本地收藏数据被改动（详情页标记、改状态、加入/移出收藏夹）。
    //    这里**只重读本地缓存，不发请求** —— 用户要的是立刻看到自己刚做的改动，
    //    而云端核对仍由 ② 的每日策略决定。
    //
    //    为什么不能靠 ②：收藏的 ViewModel 挂在导航层，不会随页面进出重建，
    //    所以"进详情页标记 → 返回"时 ② 不会重跑，本地改了也看不见。
    LaunchedEffect(Unit) {
        AsmrRepository.libraryChanges.collect {
            collectionViewModel.onLocalDataChanged()
            playlistsViewModel.onLocalDataChanged()
        }
    }

    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + 56.dp).roundToPx() })
    }
    val contentPadding = remember(barHeight, density) {
        PaddingValues(top = with(density) { barHeight.toDp() } + 12.dp, bottom = 100.dp)
    }

    // 轻量提示：**只在用户主动刷新失败时**用。
    // 自动对齐失败刻意不提示 —— 本地 JSON 是主数据源，没对齐上不影响使用。
    val toast = rememberToastState()
    // 两个页签各自的失败信息汇总到同一个提示框。
    // 展示后立刻清掉消息 —— 否则离开页面再回来时会把已看过的提示重弹一次。
    LaunchedEffect(collection.toastMessage) {
        collection.toastMessage?.let {
            toast.show(it)
            collectionViewModel.clearToast()
        }
    }
    LaunchedEffect(playlists.toastMessage) {
        playlists.toastMessage?.let {
            toast.show(it)
            playlistsViewModel.clearToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(hazeState)
        ) {
            if (!authState.isLoggedIn) {
                EmptyHint(
                    title = "登录后查看你的收藏",
                    description = "「标记」与「列表」都保存在账号里。" +
                        "请到「设置 → 账号」登录后再回来。"
                )
            } else {
                when (tab) {
                    TAB_MARKED -> CollectionTab(
                        state = collection,
                        contentPadding = contentPadding,
                        onWorkClick = onWorkClick,
                        onSelectFilter = { collectionViewModel.selectFilter(it) },
                        onRefresh = { collectionViewModel.refresh() },
                        onPullRefresh = { collectionViewModel.pullRefresh() },
                        onLoadMore = { collectionViewModel.loadMore() },
                        onChanged = { work, status -> collectionViewModel.setStatus(work, status) },
                        onRetryPending = { collectionViewModel.retryPending(it) }
                    )

                    else -> PlaylistsTab(
                        state = playlists,
                        contentPadding = contentPadding,
                        onOpenPlaylist = onOpenPlaylist,
                        onRefresh = { playlistsViewModel.load() },
                        onPullRefresh = { playlistsViewModel.pullRefresh() },
                        onDelete = { pendingDelete = it }
                    )
                }
            }
        }

        TabTopBar(
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        ) {
            Text(
                text = "收藏",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            AsmrFilterChip(
                text = if (collection.totalCount > 0) "标记 ${collection.totalCount}" else "标记",
                selected = tab == TAB_MARKED,
                onClick = { tab = TAB_MARKED }
            )
            Spacer(Modifier.width(6.dp))
            AsmrFilterChip(
                text = if (playlists.playlists.isNotEmpty()) {
                    "列表 ${playlists.playlists.size}"
                } else {
                    "列表"
                },
                selected = tab == TAB_PLAYLISTS,
                onClick = { tab = TAB_PLAYLISTS }
            )
        }

        // 轻量提示（用户主动刷新失败时）—— 固定在偏下方，不随列表滚动
        ToastHost(
            state = toast,
            toastOffset = 72.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    pendingDelete?.let { playlist ->
        DeletePlaylistConfirmDialog(
            playlist = playlist,
            onConfirm = {
                pendingDelete = null
                playlistsViewModel.delete(playlist)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ─────────────────────────── 「标记」页签 ───────────────────────────

@Composable
private fun CollectionTab(
    state: CollectionUiState,
    contentPadding: PaddingValues,
    onWorkClick: (Int) -> Unit,
    onSelectFilter: (CollectionStatus?) -> Unit,
    onRefresh: () -> Unit,
    onPullRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onChanged: (Work, CollectionStatus?) -> Unit,
    /** 点某个作品卡片上的「待同步」→ 只重试这一个 */
    onRetryPending: (Int) -> Unit
) {
    var editing by remember { mutableStateOf<Work?>(null) }

    // 下拉刷新：指示器由 state.refreshing 驱动，内容照常展示
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
        // ── 状态过滤条（全部 + 五种状态）────────────────
        item(key = "filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsmrFilterChip(
                    text = "全部",
                    selected = state.filter == null,
                    onClick = { onSelectFilter(null) }
                )
                CollectionStatus.entries.forEach { status ->
                    AsmrFilterChip(
                        text = status.label,
                        selected = state.filter == status,
                        onClick = { onSelectFilter(status) }
                    )
                }
            }
        }

        // ── 上次对齐时间（弱化小字，无操作含义）──────────
        item(key = "sync_state") {
            SyncStateLine(syncedAt = state.syncedAt)
        }

        // 说明：这里刻意**没有**"待同步汇总条"和"同步失败"提示。
        // 本地是主数据源，所以"没对齐上"不需要惊动用户；
        // 而"改动没送到云端"已经由每张卡片上的「待同步」标识就地表达了 ——
        // 精确到作品、可以点它重试，比一条汇总文案更有用。

        state.error?.let { error ->
            item(key = "error") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorColor,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }
        }

        if (state.loading) {
            item(key = "loading") { LoadingRow("正在获取标记…") }
        }

        if (!state.loading && state.loaded && state.works.isEmpty() && state.error == null) {
            item(key = "empty") {
                InlineEmpty(
                    iconRes = R.drawable.ic_bookmark_outline_fluent,
                    title = if (state.filter == null) {
                        "还没有标记任何作品"
                    } else {
                        "没有「${state.filter.label}」状态的作品"
                    },
                    description = "在作品详情页可以给作品打标记（想听 / 在听 / 听过 / 重听 / 搁置）"
                )
            }
        }

        items(state.works, key = { it.id }) { work ->
            // 新插入的条目会因为 key 是新的而首次组合，
            // AnimatedCollectionRow 自己会播入场动画 —— 不需要额外的触发机制
            AnimatedCollectionRow(
                work = work,
                index = state.works.indexOf(work),
                busy = state.mutatingId == work.id,
                pending = work.id in state.pendingIds,
                retrying = state.retryingId == work.id,
                onClick = { onWorkClick(work.id) },
                onChangeStatus = { editing = work },
                onRetrySync = { onRetryPending(work.id) }
            )
        }

        if (state.hasMore && state.works.isNotEmpty()) {
            item(key = "load_more") {
                LoadMoreRow(loading = state.loadingMore, onLoadMore = onLoadMore)
            }
        }

        item(key = "bottom_space") { Spacer(Modifier.height(12.dp)) }
        }
    }

    editing?.let { work ->
        CollectionStatusDialog(
            title = "标记状态",
            currentStatus = work.collectionStatus,
            currentRating = work.userRating,
            onConfirm = { status, _ ->
                editing = null
                onChanged(work, status)
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun AnimatedCollectionRow(
    work: Work,
    index: Int,
    busy: Boolean,
    pending: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onChangeStatus: () -> Unit,
    onRetrySync: () -> Unit
) {
    val animatedIds = remember { mutableSetOf<Int>() }
    val alpha = remember { Animatable(if (work.id in animatedIds) 1f else 0f) }
    LaunchedEffect(work.id) {
        if (work.id !in animatedIds) {
            animatedIds.add(work.id)
            alpha.animateCardEntrance(index)
        }
    }
    Box(modifier = Modifier.cardEntrance(alpha)) {
        CollectionRow(
            work = work,
            busy = busy,
            pending = pending,
            retrying = retrying,
            onClick = onClick,
            onChangeStatus = onChangeStatus,
            onRetrySync = onRetrySync
        )
    }
}

/** 标记列表里的一行：缩略图 + 标题 + 社团 + 状态角标（点右侧角标改状态） */
@Composable
private fun CollectionRow(
    work: Work,
    busy: Boolean,
    pending: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onChangeStatus: () -> Unit,
    onRetrySync: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = work.coverUrl(AsmrRepository.host),
            contentDescription = work.title,
            modifier = Modifier
                .width(Sizes.ROW_THUMB_WIDTH)
                .height(Sizes.ROW_THUMB_HEIGHT),
            shape = RoundedCornerShape(8.dp),
            contentScale = ContentScale.Crop,
            showBackdrop = false
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    work.circleName,
                    work.durationText.takeIf { it != "--:--" },
                    work.userRating?.let { "我评 $it 星" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !busy, onClick = onChangeStatus)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                val status = work.collectionStatus
                if (status != null) {
                    CollectionStatusChip(status)
                } else {
                    SmallTagChip(text = "未标记", tone = TagTone.Neutral)
                }
            }
        }
        // 待同步标记：这次改动只落在本地，云端还没收到。
        // **点它就会重试这一个作品**（不必等下一次每日对齐）。
        if (pending && !busy) {
            Spacer(Modifier.width(4.dp))
            if (retrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                AsmrFilterChip(
                    text = "待同步",
                    selected = true,
                    // 用 chip 的"排除态"配色（红底描边）表示"需要注意、还没送达云端"
                    negativeTone = true,
                    onClick = onRetrySync
                )
            }
        }
    }
}

// ─────────────────────────── 「列表」页签（收藏夹）───────────────────────────

@Composable
private fun PlaylistsTab(
    state: PlaylistsUiState,
    contentPadding: PaddingValues,
    onOpenPlaylist: (Playlist) -> Unit,
    onRefresh: () -> Unit,
    onPullRefresh: () -> Unit,
    onDelete: (Playlist) -> Unit
) {
    val listState = rememberLazyStaggeredGridState()
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        when {
        state.loading && state.playlists.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { LoadingRow("正在获取收藏夹…") }

        state.error != null && state.playlists.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorColor
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("重试") }
            }
        }

        state.playlists.isEmpty() -> EmptyHint(
            title = "还没有收藏夹",
            description = "正常情况下服务端会自带「我标记的」与「我喜欢的」两个系统收藏夹"
        )

        else -> LazyVerticalStaggeredGrid(
            state = listState,
            columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp
        ) {
            // 上次同步时间横跨整行（弱化小字，无操作含义）
            item(key = "sync_state", span = StaggeredGridItemSpan.FullLine) {
                SyncStateLine(syncedAt = state.syncedAt)
            }
            items(state.playlists, key = { it.id }) { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    onClick = { onOpenPlaylist(playlist) },
                    onLongClick = { if (!playlist.isSystem) onDelete(playlist) }
                )
            }
        }
        }
    }
}

/**
 * 收藏夹卡片。
 *
 * 系统收藏夹（我标记的 / 我喜欢的）带一个「系统」角标并**不响应长按删除** ——
 * 这是需求里"这两个收藏夹不能被删除"在界面上的体现
 * （即使误触发，`AsmrRepository.deletePlaylist` 里还有第二道拦截）。
 */
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // ⚠️ 点击与长按必须由**同一个**手势修饰符处理。
            //
            // 原先这里是两层叠着：
            //   外层 .clickable(onClick = onClick)
            //   内层 .combinedClickable(onClick = {}, onLongClick = …)   ← 空 onClick
            // Compose 里更内层的手势先拿到并消费事件，于是卡片的点击被内层那个
            // **空** onClick 吃掉 —— 表现正是「点了没反应、进不去」。
            // 而系统收藏夹看起来正常，恰恰因为它不挂长按、只有外层那一个 clickable。
            .combinedClickableCompat(
                interactionSource = interactionSource,
                onClick = onClick,
                // 系统收藏夹不允许删除 → 连长按都不挂
                onLongClick = if (playlist.isSystem) null else onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box {
                if (playlist.worksCount > 0) {
                    CoverImage(
                        url = playlist.coverUrl(AsmrRepository.host),
                        contentDescription = playlist.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(Sizes.CARD_IMAGE_ASPECT),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        contentScale = ContentScale.Crop,
                        showBackdrop = false
                    )
                } else {
                    // 空收藏夹没有封面，用一个大文件夹图标代替（系统收藏夹的初始状态就是这样）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(Sizes.CARD_IMAGE_ASPECT)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                if (playlist.isSystem) R.drawable.ic_folder_fluent
                                else R.drawable.ic_folder_open_fluent
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                if (playlist.isSystem) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                        SmallTagChip(text = "系统", tone = TagTone.Neutral)
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = playlist.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${playlist.worksCount} 部作品",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

// ─────────────────────────── 共用小组件 ───────────────────────────

/**
 * 上次对齐时间（弱化的一行小字）。
 *
 * 刻意**不做**"可能已过期，请点击同步"这类提示 —— 是否加载新数据不由时间判断，
 * 而是每个自然日首次进入时自动与云端对齐一次；这一行
 * 只是让用户知道"刚才确实与服务端对齐过"，没有任何操作含义。
 *
 * 需要用户知道的同步状态只有一种：**某次改动还没送到云端** ——
 * 那由每张卡片上的「待同步」标识表达（精确到作品，且可点击重试）。
 *
 * 这里有个容易忽略的点：文案本身含相对时间（"5 分钟前"），
 * 而 [syncedAt] 是个**不会自己变化**的时间戳 —— 若只在状态更新时重算，
 * 页面停着不动时"刚刚"会一直显示下去。所以在"不满 1 小时"这个区间内
 * 让它每 30 秒自刷新一次；超过 1 小时就显示具体时刻，不再需要刷新。
 */
@Composable
internal fun SyncStateLine(syncedAt: Long) {
    if (syncedAt <= 0L) return

    // 只在"相对时间还可能变化"的窗口内保持刷新，避免长期空转
    var now by remember(syncedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val needsTicking = now - syncedAt < 60 * 60 * 1000L
    if (needsTicking) {
        LaunchedEffect(syncedAt) {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                now = System.currentTimeMillis()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sync_fluent),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${lastSyncedText(syncedAt, now)}同步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
    }
}

/**
 * 把时间戳说成"刚刚 / x 分钟前 / x 小时前"。
 *
 * 超过一天就直接显示日期 —— "3 天前"这种表述容易让人以为数据出了问题，
 * 而实际上进入页面时会按节流自动核对、用户也能手动刷新，所以只陈述事实即可。
 */
private fun lastSyncedText(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return ""
    val diff = (now - at).coerceAtLeast(0L)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadMoreRow(loading: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            TextButton(onClick = onLoadMore) { Text("加载更多") }
        }
    }
}

@Composable
private fun InlineEmpty(
    @androidx.annotation.DrawableRes iconRes: Int,
    title: String,
    description: String
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * 点击 + **可选**长按。
 *
 * 两种手势必须由**这一个**修饰符同时承担。
 *
 * ⚠️ 绝不能改成"先 `.clickable(onClick)`、再叠一个 `combinedClickable`"：
 * 同一节点上后声明（更内层）的手势会先拿到并消费事件，外层的点击就永远收不到 ——
 * 收藏夹卡片曾经因此点不开（内层那个 `onClick` 是空的）。
 *
 * @param onLongClick null = 不挂长按（系统收藏夹不允许删除）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = this.combinedClickable(
    interactionSource = interactionSource,
    indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    onClick = onClick,
    onLongClick = onLongClick
)
