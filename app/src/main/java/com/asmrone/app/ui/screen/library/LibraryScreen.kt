package com.asmrone.app.ui.screen.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
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
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.ListHint
import com.asmrone.app.ui.component.PrefetchNearEnd
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.FluentIcon
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.TopBarAction
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.RatingStar
import com.asmrone.app.ui.theme.Sizes
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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

    // 页签选择**持久化**：切到「列表」后，离开页面 / 杀进程重开都停在「列表」，
    // 直到用户主动切回「标记」。
    val tab by AppPrefs.libraryTab.collectAsState()
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    /**
     * 「标记」页签的状态筛选条是否展开（顶栏右侧的玻璃胶囊控制）。
     *
     * ⚠️ 与 comicApp 的「偏好选择」有一处刻意不同：
     * 那边是 `activeIds.isNotEmpty() || expanded` —— **选中过之后就收不起来了**。
     * 这里折叠与否只看用户点没点，选了状态照样能收起；
     * 收起后按钮上会显示当前状态名（全部 / 想听 / 在听…），所以不会"不知道现在筛了什么"。
     *
     * 默认**收起**：收藏页的第一屏留给作品本身。
     */
    var filtersExpanded by remember { mutableStateOf(false) }

    // 「列表」页签顶栏的「新建收藏夹」：弹窗开关与输入中的名字。
    // 名字放在这里（而不是弹窗内部）是为了让"取消后再次打开"仍然是空的 ——
    // 打开时清空一次，比依赖 remember 的作用域更不容易漏。
    var createPlaylistOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    LaunchedEffect(createPlaylistOpen) {
        if (createPlaylistOpen) newPlaylistName = ""
    }

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

    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }
    // 底部留白来自宿主实测的悬浮栏高度（导航栏 + 迷你播放器），需在 remember 外读取
    val bottomInset = Liquid.CONTENT_BOTTOM_INSET_WITH_PLAYER
    val contentPadding = remember(barHeight, density, bottomInset) {
        PaddingValues(top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP, bottom = bottomInset)
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
                .layerBackdrop(backdrop)
        ) {
            if (!authState.isLoggedIn) {
                EmptyHint(
                    title = "登录后查看你的收藏",
                    description = "「标记」与「列表」都保存在账号里。" +
                        "请到「设置 → 账号」登录后再回来。"
                )
            } else {
                // 「标记 ⇄ 列表」切换：参考 comicApp 收藏页的「目录 ⇄ 默认」。
                //
                // 那边不是整屏推拉，而是**只横移 1/5 屏**（280ms）+ 淡入淡出 ——
                // 观感上像"整页轻轻错开一下"，比整屏滑动安静，也不会让两张卡
                // 同时甩过屏幕。方向：去「列表」（右）新页从右侧偏入，回「标记」相反。
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val direction = if (targetState == TAB_PLAYLISTS) 1 else -1
                        (
                            slideInHorizontally(animationSpec = tween(280)) { width ->
                                width / 5 * direction
                            } + fadeIn(animationSpec = tween(220))
                            ) togetherWith (
                            slideOutHorizontally(animationSpec = tween(280)) { width ->
                                -width / 5 * direction
                            } + fadeOut(animationSpec = tween(160))
                            )
                    },
                    label = "libraryTab"
                ) { current ->
                    when (current) {
                        TAB_MARKED -> CollectionTab(
                            state = collection,
                            contentPadding = contentPadding,
                            filtersExpanded = filtersExpanded,
                            onWorkClick = onWorkClick,
                            onSelectFilter = { collectionViewModel.selectFilter(it) },
                            onRefresh = { collectionViewModel.refresh() },
                            onPullRefresh = { collectionViewModel.pullRefresh() },
                            onLoadMore = { collectionViewModel.loadMore() },
                            onChanged = { work, status -> collectionViewModel.setStatus(work, status) },
                            onRemoveMark = { collectionViewModel.removeMark(it) },
                            onRetryPending = { collectionViewModel.retryPending(it) }
                        )

                        else -> PlaylistsTab(
                            state = playlists,
                            contentPadding = contentPadding,
                            onOpenPlaylist = onOpenPlaylist,
                            onRefresh = { playlistsViewModel.load() },
                            onPullRefresh = { playlistsViewModel.pullRefresh() },
                            onDelete = { playlist ->
                                // 系统收藏夹（我标记的 / 我喜欢的）不能删：给一句提示，
                                // 而不是让长按毫无反应（用户会以为卡住了）
                                if (playlist.isSystem) {
                                    toast.show("「${playlist.displayName}」是系统收藏夹，不能删除")
                                } else {
                                    pendingDelete = playlist
                                }
                            }
                        )
                    }
                }
            }
        }

        TabTopBar(
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        ) {
            Text(
                text = "收藏",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // 左上角这块动作区在两个页签里**是同一个位置**，只是内容不同：
            //   「标记」→ 状态筛选胶囊（文字＝当前状态，点击展开/收起下方 chip 条）
            //   「列表」→ 新建收藏夹
            //
            // 所以用 AnimatedContent 做"换了一身衣服"的过渡：淡入淡出 + 宽度过渡
            // （SizeTransform 负责长度动画），而不是两个元素各自进退 ——
            // 后者会让右侧的页签胶囊先被挤开再弹回，看起来像两个按钮在抢位置。
            if (authState.isLoggedIn) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220)) togetherWith
                            fadeOut(animationSpec = tween(160)))
                            .using(SizeTransform(clip = false) { _, _ -> tween(260) })
                    },
                    label = "libraryLeftAction"
                ) { current ->
                    if (current == TAB_MARKED) {
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (filtersExpanded) 90f else 0f,
                            animationSpec = tween(200),
                            label = "filterArrow"
                        )
                        TopBarAction(
                            onClick = { filtersExpanded = !filtersExpanded },
                            backdrop = backdrop,
                            iconRes = R.drawable.ic_chevron_right_fluent,
                            label = collection.filter?.label ?: "全部",
                            iconRotation = arrowRotation
                        )
                    } else {
                        TopBarAction(
                            onClick = { createPlaylistOpen = true },
                            backdrop = backdrop,
                            iconRes = R.drawable.ic_add_fluent,
                            label = "新建收藏夹"
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            // 顶栏切换用 comicApp 的 TopBarAction（40dp 液态玻璃胶囊），
            // 而不是两枚 chip：主页面右上角只允许「玻璃」这一类可点元素。
            TopBarAction(
                onClick = { AppPrefs.setLibraryTab(if (tab == TAB_MARKED) TAB_PLAYLISTS else TAB_MARKED) },
                backdrop = backdrop,
                iconRes = if (tab == TAB_MARKED) R.drawable.ic_bookmark_fluent
                else R.drawable.ic_library_fluent,
                // 两个标签都是两字：切换时按钮宽度不变，标题不会被挤得跳一下
                label = if (tab == TAB_MARKED) "标记" else "列表"
            )
        }

        // 轻量提示（用户主动刷新失败时）—— 固定在偏下方，不随列表滚动。
        // 抬到「导航栏 + 迷你播放器」之上，否则会被它们压住看不见。
        ToastHost(
            state = toast,
            toastOffset = Liquid.TOAST_OFFSET,
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

    if (createPlaylistOpen) {
        CreatePlaylistDialog(
            name = newPlaylistName,
            onNameChange = { newPlaylistName = it },
            onConfirm = {
                // 先关弹窗再发请求：新建要等一次网络往返，
                // 把弹窗留在屏幕上"转圈"没有任何额外信息，列表里出现新条目就是反馈
                createPlaylistOpen = false
                playlistsViewModel.create(newPlaylistName)
            },
            onDismiss = { createPlaylistOpen = false }
        )
    }
}

/**
 * 新建收藏夹弹窗（「列表」页签顶栏那个按钮的落点）。
 *
 * 与 [PlaylistPickerDialog] 里的"输入名字"保持同一套控件（[CompactSearchField]），
 * 两处观感一致；这里只做"起名字 + 创建"，不涉及具体作品。
 */
@Composable
private fun CreatePlaylistDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建收藏夹") },
        text = {
            Column {
                Text(
                    text = "为收藏夹起个名字，创建后可以在「列表」里看到它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                CompactSearchField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "收藏夹名称"
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank()
            ) { Text("新建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ─────────────────────────── 「标记」页签 ───────────────────────────

@Composable
private fun CollectionTab(
    state: CollectionUiState,
    contentPadding: PaddingValues,
    /** 状态筛选条是否展开（由顶栏玻璃胶囊控制） */
    filtersExpanded: Boolean,
    onWorkClick: (Int) -> Unit,
    onSelectFilter: (CollectionStatus?) -> Unit,
    onRefresh: () -> Unit,
    onPullRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onChanged: (Work, CollectionStatus?) -> Unit,
    /** 弹窗里的「删除」：评分与状态整条移出 */
    onRemoveMark: (Work) -> Unit,
    /** 点某个作品卡片上的「待同步」→ 只重试这一个 */
    onRetryPending: (Int) -> Unit
) {
    var editing by remember { mutableStateOf<Work?>(null) }
    val listState = rememberLazyStaggeredGridState()

    // 滚动时收起底部栏（与首页同一套交互）
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    // 还剩约一屏半就开始取下一页；带筛选时列表是本地算的，不分页，别白喊
    listState.PrefetchNearEnd(
        enabled = state.filter == null && state.hasMore && !state.loadingMore,
        onLoadMore = onLoadMore
    )

    // 入场动画只播一次：滚动回来不重播（与首页 / 搜索结果的 animatedKeys 同一套做法）
    val animatedKeys = remember { mutableSetOf<Int>() }

    // 切换「全部 / 想听 / …」时的渐变。
    //
    // 筛选是本地即时算出来的：数据在点击的那一帧就换掉了，
    // 不做过渡会"啪"地一下换一屏。这里让**卡片**从 0 淡入 240ms，
    // 筛选条与"上次对齐时间"保持不动 —— 被点的那枚 chip 不该跟着闪。
    //
    // ⚠️ 必须用 `remember(state.filter)` 把初值直接定为 0：
    // 若写成 `remember { Animatable(1f) }` 再在 LaunchedEffect 里 `snapTo(0f)`，
    // 新内容会**先以 alpha=1 画出一帧**，下一帧才被 snapTo 盖成透明 ——
    // 表现就是"内容闪一下 → 被渐变盖住 → 再浮现"。
    val filterFade = remember(state.filter) { Animatable(0f) }
    LaunchedEffect(state.filter) {
        filterFade.animateTo(1f, tween(240))
    }

    // 下拉刷新：指示器由 state.refreshing 驱动，内容照常展示
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyVerticalStaggeredGrid(
            state = listState,
            columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp
        ) {
            // ── 顶部信息区：状态过滤条 + 上次对齐时间 ────────────
            //
            // 这两块**合并成同一个 item**，而不是两个 item。
            //
            // 原因：LazyGrid 的 `verticalItemSpacing` 是"item 与 item 之间"的距离，
            // 哪怕过滤器收起后高度变成 0，那个 item 依然存在 —— 于是它下面会
            // 白白多出 10dp，收藏页的内容起始位置就比其它页面低一截。
            // 合并之后：收起时它的高度就是"上次对齐时间"这一行，起始位置与其它页面一致。
            //
            // 展开/收起沿用 comicApp「偏好选择」的 `expandVertically + fadeIn` /
            // `shrinkVertically + fadeOut`（200ms）；区别只有一处：
            // **选过状态依然可以收起**（那边会强制保持展开）。
            item(key = "header", span = StaggeredGridItemSpan.FullLine) {
                Column {
                    AnimatedVisibility(
                        visible = filtersExpanded,
                        enter = expandVertically(animationSpec = tween(200)) +
                            fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(200)) +
                            fadeOut(animationSpec = tween(200))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 六个 chip 在窄屏放不下：与 comicApp 一样横向滚动，而不是挤成一排
                                .horizontalScroll(rememberScrollState())
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

                    // 上次对齐时间（弱化小字，无操作含义）
                    SyncStateLine(syncedAt = state.syncedAt)
                }
            }

            // 说明：这里刻意**没有**"待同步汇总条"和"同步失败"提示。
            // 本地是主数据源，所以"没对齐上"不需要惊动用户；
            // 而"改动没送到云端"已经由每张卡片上的「待同步」角标就地表达了 ——
            // 精确到作品、可以点它重试，比一条汇总文案更有用。

            state.error?.let { error ->
                item(key = "error", span = StaggeredGridItemSpan.FullLine) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
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
                item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                    LoadingRow("正在获取标记…")
                }
            }

            if (!state.loading && state.loaded && state.works.isEmpty() && state.error == null) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    // 空态也跟着淡入：切到"没有作品"的状态时不会突然蹦出一段文字
                    Box(modifier = Modifier.graphicsLayer { this.alpha = filterFade.value }) {
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
            }

            // ── 作品卡片（与首页同一套 WorkCard + 瀑布流）──────────
            //
            // 收藏特有的信息以**图片角标**的形式叠在封面上：
            //  - 右上角：听歌状态（想听 / 在听 / …，点它打开状态选择）＋「待同步」（点它重试）；
            //  - 右下角：我的评分（星标 + 数字，未评分不显示）。
            itemsIndexed(state.works, key = { _: Int, work: Work -> work.id }) { index, work ->
                val alpha = remember(work.id) {
                    Animatable(if (work.id in animatedKeys) 1f else 0f)
                }
                LaunchedEffect(work.id) {
                    if (work.id !in animatedKeys) {
                        animatedKeys.add(work.id)
                        alpha.animateCardEntrance(index)
                    }
                }
                // 两个 alpha 相乘：入场（cardEntrance）与筛选淡入（filterFade）互不覆盖
                Box(
                    modifier = Modifier
                        .cardEntrance(alpha)
                        .graphicsLayer { this.alpha = filterFade.value }
                ) {
                    WorkCard(
                        work = work,
                        host = AsmrRepository.host,
                        onClick = { onWorkClick(work.id) },
                        // 收藏页显示「社团 / 声优 / 年龄分级 / 星级」；
                        // 标签在网页端也没有展示价值，这里关掉。
                        showTags = false,
                        // 长按 = 修改标记（与点状态角标同一个弹窗），
                        // 不再提供"长按清掉标记"这种不可撤销的快捷操作
                        onLongClick = { editing = work },
                        badge = {
                            CollectionStatusBadge(
                                status = work.collectionStatus,
                                busy = state.mutatingId == work.id,
                                pending = work.id in state.pendingIds,
                                retrying = state.retryingId == work.id,
                                onChangeStatus = { editing = work },
                                onRetrySync = { onRetryPending(work.id) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                            )
                            work.userRating?.let { rating ->
                                RatingPill(
                                    rating = rating,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                )
                            }
                        }
                    )
                }
            }

            if (state.hasMore && state.works.isNotEmpty()) {
                item(key = "load_more", span = StaggeredGridItemSpan.FullLine) {
                    LoadMoreRow(loading = state.loadingMore, onLoadMore = onLoadMore)
                }
            }

            item(key = "bottom_space", span = StaggeredGridItemSpan.FullLine) {
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editing?.let { work ->
        CollectionStatusDialog(
            title = "标记状态",
            workTitle = work.title,
            currentStatus = work.collectionStatus,
            currentRating = work.userRating,
            // 从卡片上的状态角标打开的，说明本来就有标记 → 可以删除
            canRemove = true,
            onRemove = {
                editing = null
                onRemoveMark(work)
            },
            onConfirm = { status, _ ->
                editing = null
                onChanged(work, status)
            },
            onDismiss = { editing = null }
        )
    }
}

/**
 * 收藏卡片**右上角**的状态角标（听歌状态 + 待同步）。
 *
 * 两条信息各自可点、互不干扰：
 *  - 状态（想听 / 在听 / …）→ 打开状态选择弹窗；
 *  - 「待同步」→ 立刻重试这一个作品的云端写入。
 *
 * 没有状态时**什么都不画**（旧的「未标记」标识已按需求删除）。
 * 底色用近乎不透明的 surface：叠在花哨封面上时文字仍然读得清。
 */
@Composable
private fun CollectionStatusBadge(
    status: CollectionStatus?,
    busy: Boolean,
    pending: Boolean,
    retrying: Boolean,
    onChangeStatus: () -> Unit,
    onRetrySync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 有状态才显示；正在写云端时显示进度圈
        if (status != null || busy) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy, onClick = onChangeStatus)
            ) {
                if (busy) {
                    BusyDot()
                } else {
                    OverlayPill(
                        text = status!!.label,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (pending && !busy) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !retrying, onClick = onRetrySync)
            ) {
                if (retrying) BusyDot() else OverlayPill(text = "待同步", color = ErrorColor)
            }
        }
    }
}

/**
 * 我的评分（右下角）：`★ 4`。
 *
 * 用星标 + 数字而不是「我评 4 星」：卡片的信息区已经留给社团 / 声优，
 * 评分做成封面角落的小药丸，既不打断卡片节奏，也能一眼扫到。
 */
@Composable
private fun RatingPill(rating: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val color = RatingStar
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FluentIcon(
            res = R.drawable.ic_star_fluent,
            contentDescription = null,
            tint = color,
            size = 11.dp
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = rating.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/** 叠在封面上的小药丸：近不透明底 + 细字，任何封面上都读得清 */
@Composable
internal fun OverlayPill(text: String, color: Color) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/** 角标位置的加载指示：小圆底 + 进度圈，尺寸与药丸相当 */
@Composable
internal fun BusyDot() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
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
    // 入场动画只播一次：切回来 / 滚动回来不重播（与「标记」页同一套做法）
    val animatedKeys = remember { mutableSetOf<String>() }
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
            itemsIndexed(state.playlists, key = { _: Int, playlist: Playlist -> playlist.id }) { index, playlist ->
                // 与「标记」页 / 首页同一套入场：淡入 + 轻微上移，按序号错峰。
                // 收藏夹数量一般不多，错峰看起来是"一张张落下来"，不会拖沓。
                val alpha = remember(playlist.id) {
                    Animatable(if (playlist.id in animatedKeys) 1f else 0f)
                }
                LaunchedEffect(playlist.id) {
                    if (playlist.id !in animatedKeys) {
                        animatedKeys.add(playlist.id)
                        alpha.animateCardEntrance(index)
                    }
                }
                Box(modifier = Modifier.cardEntrance(alpha)) {
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist) },
                        onLongClick = { onDelete(playlist) }
                    )
                }
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
    onLongClick: (() -> Unit)?
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

    ListHint(
        text = "${lastSyncedText(syncedAt, now)}同步",
        // 左边距交给列表自身的 12dp 网格内边距（原来这里再加 16dp + 图标，
        // 文字比首页的小字提示整整右移了一截）；
        // 也不再额外加 top 4dp —— 否则比首页那条低 4dp。
        modifier = Modifier.padding(bottom = 4.dp)
    )
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
