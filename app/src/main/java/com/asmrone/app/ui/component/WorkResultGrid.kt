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
 * 管：列表布局、翻页触发、卡片入场动画、加载/空态、可选的列表首行说明
 * 不管：顶栏（各页标题不同）、数据怎么取（各 ViewModel 自己负责）
 *
 * @param headerText 列表**首行**的说明文字（搜索页把「N 个结果」放这里）；
 *   null / 空白则不显示。它是列表内容的一部分，会随滚动一起走。
 */
@Composable
fun WorkResultGrid(
    works: List<Work>,
    host: String,
    contentPadding: PaddingValues,
    onWorkClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    headerText: String? = null,
    hasMore: Boolean = false,
    loadingMore: Boolean = false
) {
    val listState = rememberLazyStaggeredGridState()
    // 滚动时收起底部栏（与首页同一套交互）
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    val animatedKeys = remember { mutableSetOf<Int>() }

    // 按 id 去重再铺：下面 `key = work.id`，同一部作品在两页里各出现一次就会抛
    // 「Key … was already used」，整块列表当场白掉 —— 服务端分页边界确实会重叠。
    // 兜在这一层，调用方（搜索页 / 字典结果页）就不用各自防一遍。
    val items = remember(works) { works.distinctBy { it.id } }

    // 快到底部（还剩约一屏半）就先取下一页，别等用户真滚到「加载更多」那一行。
    // 加载中的重复触发由 ViewModel 的 loadMore() 守卫，这里只负责"什么时候喊"。
    listState.PrefetchNearEnd(enabled = hasMore && !loadingMore, onLoadMore = onLoadMore)

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp
    ) {
        // 列表首行的说明（搜索页的「N 个结果」放这里：它是内容的一部分，
        // 跟着列表一起滚动，而不是压在顶栏的副标题上）。
        // 样式统一走 [ListHint]，与首页 / 字典结果 / 同步提示保持一致。
        if (!headerText.isNullOrBlank()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                ListHint(
                    text = headerText,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        itemsIndexed(items, key = { _: Int, work: Work -> work.id }) { index, work ->
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
                    onClick = { onWorkClick(work.id) }
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
