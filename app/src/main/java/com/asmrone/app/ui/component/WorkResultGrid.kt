package com.asmrone.app.ui.component

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.model.Work

/**
 * 作品结果列表 —— **公共组件**。
 *
 * ## 为什么把它抽出来
 *
 * 原先这套瀑布流（自适应列宽、触底自动翻页、入场动画）长在"搜索结果页"里，
 * 于是**只有能进搜索页的功能才用得上它**。结果是：详情页点一个标签，
 * 得先跳到搜索页、把条件塞进去、再触发搜索 —— 明明用户只是想"看这个标签的作品"，
 * 却被迫走了一遍"提搜索条件"的流程。
 *
 * 抽成组件之后，任何"有一批作品要展示"的地方都能直接用：
 *  - 搜索结果页（多条件检索）
 *  - 标签 / 声优 / 社团的作品页（详情页点进来）
 *
 * 调用方只需要给"数据 + 三个回调"，不关心它是怎么来的。
 *
 * ## 它管什么、不管什么
 *
 * 管：列表布局、翻页触发、卡片入场动画、加载/空态
 * 不管：顶栏（各页标题不同）、数据怎么取（各 ViewModel 自己负责）
 *
 * @param totalCount 服务端给的总条数；0 表示未知（则不显示"已加载 N"那截）
 * @param description 这批结果的来源说明（如「标签「ASMR」」）；空则不显示
 */
@Composable
fun WorkResultGrid(
    works: List<Work>,
    host: String,
    progressMap: Map<Int, Float>,
    contentPadding: PaddingValues,
    onWorkClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    totalCount: Int = 0,
    description: String = "",
    hasMore: Boolean = false,
    loadingMore: Boolean = false
) {
    val listState = rememberLazyStaggeredGridState()
    // 滚动时收起底部栏（与首页同一套交互）
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    val animatedKeys = remember { mutableSetOf<Int>() }
    val shouldLoadMore by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp
    ) {
        // 首项占满整行做一个"结果说明"。
        //
        // 同时给**总条数**与"已加载多少"：滚到下面时顶栏标题会被滚走，
        // 而翻页时"已加载 / 总数"本身就是有用的进度
        // （20 / 18320 与 18320 个结果说的是两件事）。
        item(span = StaggeredGridItemSpan.FullLine) {
            val summary = buildString {
                if (totalCount > 0) {
                    append("共 ").append(totalCount).append(" 个结果")
                    if (works.size < totalCount) {
                        append("（已加载 ").append(works.size).append("）")
                    }
                    if (description.isNotBlank()) append(" · ")
                }
                append(description)
            }
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        itemsIndexed(works, key = { _: Int, work: Work -> work.id }) { index, work ->
            val alpha = remember { Animatable(if (work.id in animatedKeys) 1f else 0f) }
            LaunchedEffect(Unit) {
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

        // 触底自动加载之外也留一个手动入口：自动加载偶发失败时，
        // 用户至少还有个地方能点（否则只能退出重进）。
        if (hasMore) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadingMore) {
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
        }
    }
}
