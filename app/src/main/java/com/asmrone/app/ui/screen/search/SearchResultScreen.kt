package com.asmrone.app.ui.screen.search

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.WorkResultGrid
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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
    val backdrop = rememberLayerBackdrop()

    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
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
                    // 顶部让开玻璃栏的高度（与收藏夹详情页同一套算法），
                    // 否则第一行卡片会被顶栏压住
                    contentPadding = PaddingValues(
                        top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP,
                        bottom = Liquid.CONTENT_BOTTOM_INSET
                    ),
                    onWorkClick = onWorkClick,
                    onLoadMore = { viewModel.loadMore() },
                    // 数量不做顶栏副标题，而是列表的第一行（随内容一起滚动）
                    headerText = if (state.totalCount > 0) "${state.totalCount} 个结果" else null,
                    hasMore = state.hasMore,
                    loadingMore = state.loadingMore
                )
            }
        }

        // 玻璃药丸栏（与收藏夹详情页同一套）。
        //
        // 标题 = **这次搜了什么**（标签 / 声优 / 社团，排除项是红色的 `-声优「xx」`）；
        // 数量移到列表第一行（见上面的 headerText），顶栏保持只有一行内容。
        GlassTopBar(
            title = if (state.appliedSummary.isNotEmpty()) {
                // 大标题 = 这次搜了什么；排除项是红色的 `-声优「xx」`
                state.appliedSummary.toSummaryAnnotatedString()
            } else if (state.descriptionSegments.isNotEmpty()) {
                state.descriptionSegments.toSummaryAnnotatedString()
            } else {
                AnnotatedString("搜索结果")
            },
            onBack = onBack,
            backdrop = backdrop,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        )
    }
}
