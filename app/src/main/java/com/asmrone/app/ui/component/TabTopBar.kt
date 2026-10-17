package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.TouchTarget
import dev.chrisbanes.haze.HazeState

/**
 * 主页面（底部标签页）的顶部栏 —— **透明底色 + 顶部渐变模糊**
 * （提炼自 comicApp 的 TabTopBar）。
 *
 * 与二级页面的 [GlassTopBar] 是同一套材质思路，区别只在于：
 *  - 主页面没有返回按钮，中间也不需要药丸，所以标题与操作直接透明排布；
 *  - 底衬只做「渐变模糊」这一层，不画任何背景色 —— 于是状态栏与菜单栏区域整体透明，
 *    内容滚动时从下方透出并被逐渐模糊。
 *
 * 用法：整页套成 `Box { 内容(hazeSource) ; TabTopBar(...) }`，
 * 内容的顶部留白用 `onSizeChanged` 量出来的高度（加上 [fadeExtension]）。
 *
 * @param hazeState 背景模糊的取样源；页面内容层需挂 `Modifier.hazeSource(state)`
 * @param fadeExtension 渐变模糊向下延伸的高度：让标题下方也有一段可见的过渡
 * @param content 标题与右侧操作（行内布局：横向 16dp、纵向 2dp、48dp 触控高度）
 */
@Composable
fun TabTopBar(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    fadeExtension: Dp = 22.dp,
    content: @Composable RowScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // 渐变模糊底衬：位于内容之上、标题之下；顶部最强，向下渐隐到无
        if (hazeState != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .topGradientBlur(hazeState)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .heightIn(min = TouchTarget.minimum),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // 让底衬再往下延伸一截（本身不可见，只撑高外层 Box），标题下方才有可见的过渡
        Spacer(Modifier.fillMaxWidth().height(fadeExtension))
    }
}
