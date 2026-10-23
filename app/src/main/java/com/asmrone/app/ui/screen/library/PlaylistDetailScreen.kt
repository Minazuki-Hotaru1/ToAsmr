package com.asmrone.app.ui.screen.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.component.PrefetchNearEnd
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.Sizes
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 收藏夹详情 —— 展示某个收藏夹里的作品。
 *
 * 作品卡与「标记」页**完全同源**：同一个 `WorkCard` + 同一个瀑布流。
 * 卡片上只多一件事（收藏夹特有的）：
 *  - 「待同步」：这条改动还没送到云端，点它重试。
 *
 * 移出/移动作品走**长按卡片** → 收藏夹修改弹窗（多选差集），
 * 卡片上不再有"点 × 直接移出"的入口：作品可能同时在多个收藏夹里，
 * 一键移出很容易误操作，而且表达不了"移到另一个收藏夹"。
 *
 * 星数刻意不显示：收藏夹里的作品没有"我的评分"这份数据。
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    onWorkClick: (Int) -> Unit
) {
    val viewModel: PlaylistDetailViewModel = viewModel()
    LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val state by viewModel.state.collectAsState()
    val backdrop = rememberLayerBackdrop()
    val playlist = state.playlist

    // 长按卡片 → 收藏夹修改弹窗（与详情页收藏按钮同一套多选差集）。
    // 收藏夹里不再有"点 × 直接移出"这种入口：移出必须经过弹窗，
    // 因为作品可能同时属于多个收藏夹，直接移出很容易误操作。
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerWork by remember { mutableStateOf<Work?>(null) }
    val pickerViewModel: PlaylistPickerViewModel = viewModel()
    val pickerState by pickerViewModel.state.collectAsState()
    val pickerResult by pickerViewModel.result.collectAsState()

    // 本地收藏数据被改动 → 只重读本地缓存（弹窗里移出/移动、别处收藏进本夹）。
    // 不发请求：改动刚写进本地，云端还在反悔窗口里，问它会把界面拉回旧状态。
    LaunchedEffect(Unit) {
        AsmrRepository.libraryChanges.collect { viewModel.onLocalDataChanged() }
    }

    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }
    // 底部留白来自宿主实测的悬浮栏高度（导航栏 + 迷你播放器），需在 remember 外读取
    val bottomInset = Liquid.CONTENT_BOTTOM_INSET
    val contentPadding = remember(barHeight, density, bottomInset) {
        PaddingValues(
            top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP,
            bottom = bottomInset
        )
    }
    val listState = rememberLazyStaggeredGridState()

    // 还剩约一屏半就开始取下一页（与首页 / 搜索结果 / 收藏同一套机制）
    listState.PrefetchNearEnd(
        enabled = state.hasMore && !state.loadingMore,
        onLoadMore = { viewModel.loadMore() }
    )
    // 入场动画只播一次：滚动回来 / 切出去再进来不重播
    val animatedKeys = remember { mutableSetOf<Int>() }

    // 轻量提示：只在用户主动刷新失败时用（自动对齐失败刻意安静）。
    // 展示后立刻清掉消息，避免离开页面再回来时重复弹出。
    val toast = rememberToastState()
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            toast.show(it)
            viewModel.clearToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .layerBackdrop(backdrop)
        ) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.pullRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.loading && state.works.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    state.error != null && state.works.isEmpty() -> EmptyHint(
                        title = "加载失败",
                        description = state.error,
                        iconRes = R.drawable.ic_folder_fluent,
                        action = { TextButton(onClick = { viewModel.pullRefresh() }) { Text("刷新") } }
                    )

                    state.works.isEmpty() -> EmptyHint(
                        title = "这个收藏夹还是空的",
                        description = "在作品详情页点右上角的收藏按钮，把作品加进来",
                        iconRes = R.drawable.ic_folder_fluent,
                        // 空态不可滚动，下拉刷新触发不了 —— 给显式按钮
                        action = { TextButton(onClick = { viewModel.pullRefresh() }) { Text("刷新") } }
                    )

                    else -> LazyVerticalStaggeredGrid(
                        state = listState,
                        columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp
                    ) {
                        // 上次对齐时间（跨整行）。
                        // 作品数与隐私标识**不在这里**：数量已按需求去掉，
                        // 隐私（私享 / 不公开 / 公开）改成标题后面的括号文字。
                        item(key = "sync_state", span = StaggeredGridItemSpan.FullLine) {
                            SyncStateLine(syncedAt = state.syncedAt)
                        }

                        state.error?.let { error ->
                            item(key = "error", span = StaggeredGridItemSpan.FullLine) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ErrorColor,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }

                        itemsIndexed(state.works, key = { _: Int, work: Work -> work.id }) { index, work ->
                            // 与「标记」页完全同一套入场：淡入 + 上移，按序号错峰
                            val alpha = remember(work.id) {
                                Animatable(if (work.id in animatedKeys) 1f else 0f)
                            }
                            LaunchedEffect(work.id) {
                                if (work.id !in animatedKeys) {
                                    animatedKeys.add(work.id)
                                    alpha.animateCardEntrance(index)
                                }
                            }
                            Box(modifier = Modifier.cardEntrance(alpha)) {
                                PlaylistWorkCard(
                                    work = work,
                                    pending = work.id in state.pendingIds,
                                    retrying = state.retryingId == work.id,
                                    onClick = { onWorkClick(work.id) },
                                    onLongPress = {
                                        pickerWork = work
                                        pickerOpen = true
                                        pickerViewModel.open(work)
                                    },
                                    onRetrySync = { viewModel.retryPending(work) }
                                )
                            }
                        }

                        if (state.hasMore) {
                            item(key = "load_more", span = StaggeredGridItemSpan.FullLine) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        TextButton(onClick = { viewModel.loadMore() }) {
                                            Text("加载更多")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 顶栏标题 = 收藏夹名 + 隐私（私享 / 不公开 / 公开），括号跟在标题后面。
        // 隐私是这个收藏夹的属性，跟着标题走比单独占一行更省空间；
        // 作品数按需求不再显示。
        GlassTopBar(
            title = buildString {
                append(playlist?.displayName ?: "收藏夹")
                playlist?.let { append("（${it.privacyLabel}）") }
            },
            onBack = onBack,
            backdrop = backdrop,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
            // 顶栏刻意**不放刷新按钮**：下拉刷新已经覆盖了这个需求，
            // 而每日首次进入也会自动与云端对齐一次 —— 多一个入口只是噪音。
        )

        // 轻量提示（用户主动刷新失败时）—— 抬到迷你播放器之上，不随列表滚动
        ToastHost(
            state = toast,
            toastOffset = Liquid.TOAST_OFFSET,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 修改收藏夹的结果：关弹窗 + 提示（与详情页同一套消息）
    LaunchedEffect(pickerResult) {
        val message = pickerResult
        if (message != null) {
            pickerOpen = false
            if (message.isNotEmpty()) toast.show(message)
            pickerViewModel.clearResult()
        }
    }

    // 移出确认 → 改成长按打开「修改收藏夹」弹窗
    if (pickerOpen) {
        PlaylistPickerDialog(
            workTitle = pickerWork?.title.orEmpty(),
            state = pickerState,
            canRemove = true,
            onToggle = { pickerViewModel.toggle(it.id) },
            onConfirm = { pickerViewModel.confirm() },
            onRemove = { pickerViewModel.removeFromAllPlaylists() },
            onStartCreate = { pickerViewModel.startCreate() },
            onNewNameChange = { pickerViewModel.onNewNameChange(it) },
            onCreateConfirm = { pickerViewModel.createAndAdd() },
            onCancelCreate = { pickerViewModel.cancelCreate() },
            onDismiss = { pickerOpen = false }
        )
    }
}

/**
 * 收藏夹里的一张作品卡：与「标记」页同一张 [WorkCard]。
 *
 * 卡片上只有一件事是收藏夹特有的：「待同步」角标（点它重试）。
 * 移出/移动作品一律走长按 → 收藏夹修改弹窗，卡片上不再有任何直接移出的按钮。
 *
 * 星数不显示：收藏夹里的作品没有"我的评分"这份数据。
 */
@Composable
private fun PlaylistWorkCard(
    work: Work,
    pending: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRetrySync: () -> Unit
) {
    WorkCard(
        work = work,
        host = AsmrRepository.host,
        onClick = onClick,
        // 与「标记」页一致：不显示标签；星数也不显示（收藏夹没有这份数据）
        showTags = false,
        onLongClick = onLongPress,
        badge = {
            if (pending) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !retrying, onClick = onRetrySync)
                ) {
                    if (retrying) BusyDot() else OverlayPill(text = "待同步", color = ErrorColor)
                }
            }
        }
    )
}
