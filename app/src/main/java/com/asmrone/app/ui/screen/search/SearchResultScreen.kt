package com.asmrone.app.ui.screen.search

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.WorkResultGrid
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 搜索结果页 —— 搜索**之后**的独立展示界面。
 *
 * ## 为什么把结果从搜索页拆出来
 *
 * 搜索页原先既管输入又管结果，导致两个问题：
 *
 *  1. **输入区被结果挤压**：高级搜索有好几个条件栏，结果一出来输入区就被顶到
 *     很窄的位置，改条件很别扭。
 *  2. **返回语义混乱**：想在结果里换一组条件，只能留在原页清空重来，
 *     没有"退回去改条件再搜"这条自然路径。
 *
 * 拆开之后职责很清楚：搜索页只管**提条件**，这里只管**看结果**；
 * 系统的返回键天然就是"回去改条件"。
 *
 * 结果状态仍由导航级的 [SearchViewModel] 持有，所以返回后再进来看到的是同一批结果，
 * 不会因为离开页面就丢掉（也就没必要做结果缓存）。
 */
@Composable
fun SearchResultScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onWorkClick: (Int) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val host = remember { AsmrRepository.host }
    val progressMap by LibraryStore.progress.collectAsState()
    val hazeState = rememberHazeState()

    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) {
            androidx.compose.foundation.layout.WindowInsets.statusBars
                .asPaddingValues().calculateTopPadding()
        }
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + 56.dp).roundToPx() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            when {
                // 首次加载：整页转圈（还没有任何结果可展示）
                state.loading && state.results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                state.error != null -> ErrorHint(
                    message = state.error ?: "",
                    // 重试要按**原来那组条件**重发：这个 ViewModel 里就存着它们
                    onRetry = { viewModel.research() }
                )

                state.results.isEmpty() -> EmptyHint(
                    title = "没有找到结果",
                    description = if (state.conditions.isNotEmpty()) {
                        "这些条件同时满足的作品暂时没有。退回去去掉一两个条件试试 —— " +
                            "多条件是取交集，条件越多结果越少。"
                    } else {
                        "换个关键词试试，或在详情页点标签 / 声优 / 社团继续浏览"
                    }
                )

                else -> WorkResultGrid(
                    works = state.results,
                    host = host,
                    progressMap = progressMap,
                    // 顶部让开玻璃栏的高度（与收藏夹详情页同一套算法），
                    // 否则第一行卡片会被顶栏压住
                    contentPadding = PaddingValues(
                        top = with(density) { barHeight.toDp() } + 12.dp,
                        bottom = 100.dp
                    ),
                    onWorkClick = onWorkClick,
                    onLoadMore = { viewModel.loadMore() },
                    totalCount = state.totalCount,
                    description = state.description,
                    hasMore = state.hasMore,
                    loadingMore = state.loadingMore
                )
            }
        }

        // 玻璃药丸栏（与收藏夹详情页同一套）。
        //
        // 标题显示**总条数**（服务端 `pagination.totalCount`），与原版网站一致 ——
        // 而不是"已加载的条数"。后者首屏永远是 20，用户看了会以为结果只有 20 条。
        // 副标题是这次按什么搜的：多条件的结果往往少得让人困惑，
        // 把条件摊开能直接回答"凭什么只有这几条"。
        GlassTopBar(
            title = if (state.totalCount > 0) "${state.totalCount} 个结果" else "搜索结果",
            subtitle = state.description.ifBlank { null },
            onBack = onBack,
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        )
    }
}
