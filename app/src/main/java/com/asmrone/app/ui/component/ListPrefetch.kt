package com.asmrone.app.ui.component

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlin.math.ceil

/**
 * 提前量：还剩多少「屏」内容时就开始取下一页。
 *
 * 1.5 屏 ≈ 用户再滑一屏多一点才会真的到底，够网络把下一页取回来；
 * 再大就会在还看不到底的时候白跑请求（快速上下滑时尤其明显）。
 */
private const val PREFETCH_DISTANCE_SCREENS = 1.5f

/**
 * 列表「提前一屏」翻页。
 *
 * ## 为什么要提前
 *
 * 旧写法是「看见最后一项才请求」（`lastVisibleIndex >= total - 6`，阈值写死 6）：
 * 触底那一帧才开始发网络请求，网速稍慢就会先看到一截空白、或者转圈停一下 ——
 * 体感是"卡住了"。这里把触发点提前到**还剩约一屏半**的位置，等用户真滚到底，
 * 下一页通常已经在列表里了，滚动是连续的。
 *
 * ## 为什么按"屏"而不是按"条"
 *
 * 瀑布流卡片高度不一：固定条数在小屏上可能只提前半屏（来不及），
 * 在平板/折叠屏上又会提前两三屏（白费请求）。用**当前可见项数**折算，
 * 窗口多大，提前量就跟着多大。
 *
 * ## 调用约定
 *
 * - `enabled` 传「还有下一页 且 当前不在加载中」。带上 loading 是为了
 *   一页返回后回到"可加载"状态时能自动续上；已经加载完（hasMore = false）
 *   就别再喊了。
 * - 去重由各 ViewModel 的 `loadMore()` 负责（hasMore / loading / loadingMore
 *   为真直接 return），这里只在"进入提前区"的那一次调用，不是每帧都调。
 * - 列表还没铺满一屏（可滚动项不足）时也会触发一次，否则"到底了但没内容"
 *   就只剩手动按钮可点。
 *
 * @param enabled 是否允许触发（通常是 `hasMore && !loadingMore`）
 * @param onLoadMore 取下一页；实现里要有自己的并发守卫
 */
@Composable
fun LazyStaggeredGridState.PrefetchNearEnd(enabled: Boolean, onLoadMore: () -> Unit) {
    // key 用 this：首页三个板块共用一次组合、状态实例会随板块切换，
    // 不重新派生的话会拿着上一个板块的 layoutInfo 判断。
    val nearEnd by remember(this) {
        derivedStateOf {
            val layout = layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (total == 0 || lastVisible < 0) {
                false
            } else {
                val perScreen = layout.visibleItemsInfo.size.coerceAtLeast(1)
                val distance = ceil(perScreen * PREFETCH_DISTANCE_SCREENS).toInt()
                total - 1 - lastVisible <= distance
            }
        }
    }
    // nearEnd 与 enabled 一起做 key：一页加载完成后 enabled 由 false 变回 true，
    // 若此时仍在提前区内（比如这一页短得没把用户顶出去），会接着取下一页。
    LaunchedEffect(nearEnd, enabled) {
        if (nearEnd && enabled) onLoadMore()
    }
}
