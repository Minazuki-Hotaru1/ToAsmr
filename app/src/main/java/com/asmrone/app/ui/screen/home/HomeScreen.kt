package com.asmrone.app.ui.screen.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.HomeBoard
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.GlassSegment
import com.asmrone.app.ui.component.GlassSegmentedControl
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.WorkCardShimmer
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 首页 —— 三个板块（所有内容 / 热门作品 / 推荐作品）用右上角的液态玻璃分段控件切换。
 *
 * ```
 * Box(背景) {
 *     Box(hazeSource) {                    ← 被取样的内容层
 *         当前板块的瀑布流（无限滚动 + 错峰入场）
 *     }
 *     TabTopBar(透明 + 顶部渐变模糊) {
 *         标题                        GlassSegmentedControl   ← 右上角
 *     }
 * }
 * ```
 *
 * 与上一版相比：**不再把所有榜单堆在同一页**（取消了 4 条横滑榜单 + 网格的混排），
 * 同时按需求**删除了右上角的刷新按钮** —— 换板块即刷新，失败时列表内有「重试」。
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onWorkClick: (Int) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val board = state.board
    val boardState = state.current
    val listState = rememberLazyStaggeredGridState()
    val host = remember { AsmrRepository.host }
    val progressMap by LibraryStore.progress.collectAsState()

    // 向下滚动收起底部导航、向上滚动展开（可在「设置 → 外观」关闭）
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    // 切换板块后回到列表顶部
    LaunchedEffect(board) { listState.scrollToItem(0) }

    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    val structuralTopPx = with(density) { (statusTopDp + 56.dp).roundToPx() }
    var barHeight by remember { mutableIntStateOf(structuralTopPx) }
    val contentPadding = remember(barHeight, density) {
        PaddingValues(top = with(density) { barHeight.toDp() } + 12.dp, bottom = 100.dp)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(hazeState)
        ) {
            when {
                state.disabled -> EmptyHint(
                    title = "首页加载已关闭",
                    description = "可在「设置 → 通用」中开启「加载首页」",
                    modifier = Modifier.padding(contentPadding)
                )

                boardState.loading && boardState.items.isEmpty() -> HomeSkeleton(contentPadding)

                boardState.error != null && boardState.items.isEmpty() -> ErrorHint(
                    message = boardState.error ?: "加载失败",
                    onRetry = { viewModel.retry() },
                    modifier = Modifier.padding(contentPadding)
                )

                boardState.items.isEmpty() -> EmptyHint(
                    title = "这个板块暂时没有内容",
                    description = board.description,
                    modifier = Modifier.padding(contentPadding)
                )

                else -> BoardGrid(
                    board = board,
                    boardState = boardState,
                    host = host,
                    progressMap = progressMap,
                    contentPadding = contentPadding,
                    listState = listState,
                    onWorkClick = onWorkClick,
                    onLoadMore = { viewModel.loadMore() }
                )
            }
        }

        // 顶部：左侧标题 + 右上角液态玻璃板块切换器（已按需求去掉刷新按钮）
        TabTopBar(
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        ) {
            Text(
                text = "音声库",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            GlassSegmentedControl(
                // 只显示图标（不用文字）：所有内容 / 热门作品 / 推荐作品
                segments = remember {
                    HomeBoard.entries.map { GlassSegment(it.id, it.label, it.iconRes()) }
                },
                selectedId = board.id,
                onSelect = { viewModel.selectBoard(HomeBoard.fromId(it)) },
                hazeState = hazeState
            )
        }
    }
}

/**
 * 三个板块在切换器上用的图标（Fluent UI System Icons · Regular）。
 *
 * 语义：所有内容 = 2×2 方块（全部）；热门作品 = 火焰（热度）；
 * 推荐作品 = 闪光（为你推荐）。想换图标只改这里即可。
 */
@DrawableRes
private fun HomeBoard.iconRes(): Int = when (this) {
    HomeBoard.ALL -> R.drawable.ic_board_all_fluent
    HomeBoard.POPULAR -> R.drawable.ic_board_popular_fluent
    HomeBoard.RECOMMEND -> R.drawable.ic_board_recommend_fluent
}

@Composable
private fun BoardGrid(
    board: HomeBoard,
    boardState: BoardState,
    host: String,
    progressMap: Map<Int, Float>,
    contentPadding: PaddingValues,
    listState: LazyStaggeredGridState,
    onWorkClick: (Int) -> Unit,
    onLoadMore: () -> Unit
) {
    // 入场动画按板块分别记录：换板块时新板块的卡片会重新播放入场
    val animatedKeys = remember(board) { mutableSetOf<Int>() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && boardState.hasMore) onLoadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp
    ) {
        // 板块说明 + 总数（替代上一版那条横滑榜单的标题）
        item(key = "board_info", span = StaggeredGridItemSpan.FullLine) {
            Column(modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
                Text(
                    text = board.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (boardState.totalCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "共 ${boardState.totalCount} 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }

        itemsIndexed(boardState.items, key = { _: Int, work: Work -> work.id }) { index, work ->
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
                WorkCard(
                    work = work,
                    host = host,
                    onClick = { onWorkClick(work.id) },
                    progress = progressMap[work.id]
                )
            }
        }

        if (boardState.loadingMore) {
            item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSkeleton(contentPadding: PaddingValues) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp
    ) {
        items(10) { WorkCardShimmer() }
    }
}
