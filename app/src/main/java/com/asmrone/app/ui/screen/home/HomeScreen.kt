package com.asmrone.app.ui.screen.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.ListHint
import com.asmrone.app.ui.component.PrefetchNearEnd
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.GlassSegment
import com.asmrone.app.ui.component.GlassSegmentedControl
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.WorkCardShimmer
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.Sizes
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 首页 —— 三个板块（所有内容 / 热门作品 / 推荐作品）用右上角的液态玻璃分段控件切换。
 *
 * ```
 * Box(背景) {
 *     Box(layerBackdrop) {                 ← 被取样的内容层
 *         当前板块的瀑布流（无限滚动 + 错峰入场）
 *     }
 *     TabTopBar(透明 + 顶部渐隐) {
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

    // 向下滚动收起底部导航、向上滚动展开（可在「设置 → 外观」关闭）
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    // 切换板块后回到列表顶部
    LaunchedEffect(board) { listState.scrollToItem(0) }

    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    val structuralTopPx = with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() }
    var barHeight by remember { mutableIntStateOf(structuralTopPx) }
    // 底部留白来自宿主实测的悬浮栏高度（导航栏 + 迷你播放器），需在 remember 外读取
    val bottomInset = Liquid.CONTENT_BOTTOM_INSET_WITH_PLAYER
    val contentPadding = remember(barHeight, density, bottomInset) {
        PaddingValues(top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP, bottom = bottomInset)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .layerBackdrop(backdrop)
        ) {
            when {
                state.disabled -> EmptyHint(
                    title = "首页加载已关闭",
                    description = "可在「设置 → 外观 → 首页」中开启「加载首页榜单」",
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
                    contentPadding = contentPadding,
                    listState = listState,
                    onWorkClick = onWorkClick,
                    onLoadMore = { viewModel.loadMore() }
                )
            }
        }

        // 顶部：左侧标题 + 右上角液态玻璃板块切换器（已按需求去掉刷新按钮）
        TabTopBar(
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
                backdrop = backdrop
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
    contentPadding: PaddingValues,
    listState: LazyStaggeredGridState,
    onWorkClick: (Int) -> Unit,
    onLoadMore: () -> Unit
) {
    // 入场动画按板块分别记录：换板块时新板块的卡片会重新播放入场
    val animatedKeys = remember(board) { mutableSetOf<Int>() }

    // 快到底部（还剩约一屏半）就先取下一页，别等用户滚到最后一张卡才开始请求。
    // 与搜索结果 / 收藏页共用同一套 [PrefetchNearEnd]。
    listState.PrefetchNearEnd(
        enabled = boardState.hasMore && !boardState.loadingMore,
        onLoadMore = onLoadMore
    )

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp
    ) {
        // 板块说明 + 总数（替代上一版那条横滑榜单的标题）
        //
        // 「共 N 部」不再单独占第二行，而是跟在板块说明后面、用括号括起来：
        // 一行读完，首张卡片的起始位置也不会被这段说明压低。
        // 小字提示统一用 [ListHint]（与搜索结果 / 收藏页同一套样式）。
        item(key = "board_info", span = StaggeredGridItemSpan.FullLine) {
            ListHint(
                text = if (boardState.totalCount > 0) {
                    "${board.description}（共 ${boardState.totalCount} 部）"
                } else {
                    board.description
                },
                modifier = Modifier.padding(bottom = 4.dp)
            )
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
                    onClick = { onWorkClick(work.id) }
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
