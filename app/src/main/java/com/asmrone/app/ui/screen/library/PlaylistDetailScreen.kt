package com.asmrone.app.ui.screen.library

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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.GlassTopBar
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
 * 卡片上只多两件事（都是收藏夹特有的）：
 *  - 「待同步」：这条改动还没送到云端，点它重试；
 *  - 封面右下角的移出按钮（长按卡片同样可以移出）。
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
    val progressMap by LibraryStore.progress.collectAsState()
    var removing by remember { mutableStateOf<Work?>(null) }
    val playlist = state.playlist

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
                        iconRes = R.drawable.ic_folder_fluent
                    )

                    state.works.isEmpty() -> EmptyHint(
                        title = "这个收藏夹还是空的",
                        description = "在作品详情页点右上角的收藏按钮，把作品加进来",
                        iconRes = R.drawable.ic_folder_fluent
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

                        items(state.works, key = { it.id }) { work ->
                            PlaylistWorkCard(
                                work = work,
                                progress = progressMap[work.id],
                                pending = work.id in state.pendingIds,
                                retrying = state.retryingId == work.id,
                                onClick = { onWorkClick(work.id) },
                                onRemove = { removing = work },
                                onRetrySync = { viewModel.retryPending(work) }
                            )
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

        // 轻量提示（用户主动刷新失败时）—— 固定在偏下方，不随列表滚动
        ToastHost(
            state = toast,
            toastOffset = 72.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 移出确认
    removing?.let { work ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移出收藏夹") },
            text = {
                Text(
                    "把「${work.title}」从「${playlist?.displayName ?: "该收藏夹"}」移出？\n\n" +
                        "作品本身不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.removeWork(work)
                }) { Text("移出", color = ErrorColor) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 收藏夹里的一张作品卡：与「标记」页同一张 [WorkCard]。
 *
 * 卡片上只有两件事是收藏夹特有的：「待同步」角标、封面右下角的移出按钮。
 * 长按卡片同样可以移出（与按钮等价，都会先弹确认）。
 */
@Composable
private fun PlaylistWorkCard(
    work: Work,
    progress: Float?,
    pending: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRetrySync: () -> Unit
) {
    WorkCard(
        work = work,
        host = AsmrRepository.host,
        onClick = onClick,
        progress = progress,
        // 与「标记」页一致：不显示标签；星数也不显示（收藏夹没有这份数据）
        showTags = false,
        onLongClick = onRemove,
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

            // 移出：右下角的小圆钮（半透明白底，任何封面上都看得清）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_fluent),
                    contentDescription = "移出收藏夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    )
}
