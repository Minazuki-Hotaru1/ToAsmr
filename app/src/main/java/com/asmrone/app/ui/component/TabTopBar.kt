package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 主页面（底部标签页）的顶部栏 —— **透明底色 + 柔和顶部渐隐**。
 *
 * 与二级页面的 [GlassTopBar] 是同一套排版，区别只在于：
 *  - 主页面没有返回按钮，中间也不需要药丸，所以标题与操作直接透明排布；
 *  - 底衬只做一层背景色渐隐，不画任何实体背景 —— 状态栏与菜单栏区域整体通透，
 *    内容滚动时从下方透出并被柔化。
 *
 * 用法：整页套成 `Box { 内容(layerBackdrop) ; TabTopBar(...) }`，
 * 内容的顶部留白用 `onSizeChanged` 量出来的高度（加上 [fadeExtension]）。
 *
 * @param fadeExtension 顶部渐隐向下延伸的高度：让标题下方也有一段过渡
 * @param content 标题与右侧操作（行内布局：横向 16dp、纵向 6dp、48dp 触控高度）
 */
@Composable
fun TabTopBar(
    modifier: Modifier = Modifier,
    fadeExtension: Dp = 22.dp,
    content: @Composable RowScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部底色渐隐：位于内容之上、标题之下；顶部最强，向下渐隐到无
        Box(
            modifier = Modifier
                .matchParentSize()
                .topBarFade()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = rememberStatusBarTopDp())
                // 与 [GlassTopBar] **逐像素对齐**：横向 16dp、纵向 6dp、行高 48dp。
                // 两者的高度都是"状态栏 + 60dp"，所以主页与二级页的内容起始位置完全相同，
                // 切页签时内容不会上下跳 8dp（以前这里是 2dp，比玻璃栏少 8dp）。
                .padding(horizontal = 16.dp, vertical = Liquid.TOP_BAR_VERTICAL_PADDING)
                .heightIn(min = TouchTarget.minimum),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // 让底衬再往下延伸一截（本身不可见，只撑高外层 Box），标题下方才有可见的过渡
        Spacer(Modifier.fillMaxWidth().height(fadeExtension))
    }
}
