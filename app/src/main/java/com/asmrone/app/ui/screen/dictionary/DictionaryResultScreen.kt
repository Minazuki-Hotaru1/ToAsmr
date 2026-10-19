package com.asmrone.app.ui.screen.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.repo.DictionaryQuery
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.WorkResultGrid
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 「某一项字典资源的作品」页 —— 详情页点标签 / 声优 / 社团**直达**的结果页。
 *
 * 与搜索结果页的区别只在**数据从哪来**：
 *
 * | | 搜索结果页 | 本页 |
 * |---|---|---|
 * | 入口 | 搜索页提条件后跳进来 | 详情页点一下就进来 |
 * | 条件 | 用户拼的（可多条件） | 固定一项 |
 * | 展示 | `WorkResultGrid`（公共组件） | **同一个**公共组件 |
 *
 * 两者共用 `WorkResultGrid`，所以列表行为（翻页、动画、空态）完全一致，
 * 但**互不依赖** —— 这正是把结果展示抽成公共组件的意义：
 * "展示一批作品"不再绑在"搜索"这个概念上。
 */
@Composable
fun DictionaryResultScreen(
    query: DictionaryQuery,
    onBack: () -> Unit,
    onWorkClick: (Int) -> Unit
) {
    val viewModel: DictionaryResultViewModel = viewModel()
    LaunchedEffect(query) { viewModel.start(query) }

    val state by viewModel.state.collectAsState()
    val host = remember { AsmrRepository.host }
    val progressMap by LibraryStore.progress.collectAsState()
    val backdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                state.error != null -> ErrorHint(
                    message = state.error ?: "",
                    onRetry = { viewModel.retry() }
                )

                state.works.isEmpty() -> EmptyHint(
                    title = "没有找到作品",
                    description = "「${state.title}」下暂时没有作品。"
                )

                else -> WorkResultGrid(
                    works = state.works,
                    host = host,
                    progressMap = progressMap,
                    // 顶部让开玻璃栏的高度（与搜索结果页同一套算法）
                    contentPadding = PaddingValues(
                        top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP,
                        bottom = Liquid.CONTENT_BOTTOM_INSET
                    ),
                    onWorkClick = onWorkClick,
                    onLoadMore = { viewModel.loadMore() },
                    // 「N 部作品 · 按什么筛出来的」不再放顶栏副标题，
                    // 而是和搜索结果页一样作为列表**第一行**，随内容一起滚动
                    headerText = if (state.totalCount > 0) {
                        "${state.totalCount} 部作品 · ${state.description}"
                    } else {
                        state.description
                    },
                    hasMore = state.hasMore,
                    loadingMore = state.loadingMore
                )
            }
        }

        // 标题就是这一项的名字；数量与"按什么筛出来的"已移到列表第一行
        GlassTopBar(
            title = state.title.ifBlank { query.displayName },
            onBack = onBack,
            backdrop = backdrop,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        )
    }
}
