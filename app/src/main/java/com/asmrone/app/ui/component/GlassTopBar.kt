package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.theme.Liquid
import com.kyant.backdrop.Backdrop

/**
 * 二级页面顶部栏 —— 液态玻璃版本。
 *
 * ```
 * ┌───┐                      ┌──────┐
 * │ ← │    标题信息（药丸）    │ 操作 │
 * └───┘                      └──────┘
 * ```
 *
 *  - 最左：圆形液态玻璃（返回）
 *  - 中间：连续曲率胶囊，显示标题（可选副标题）
 *  - 最右：其他操作按钮（若干圆形玻璃，可选）
 *
 * 所有玻璃都从页面内容层的 `layerBackdrop` 取样，带轻微模糊、边缘折射和高光。
 * 玻璃药丸之下的顶部区域用一层柔和的背景色渐隐保证滚动内容可读。
 *
 * @param backdrop 页面内容层的液态玻璃取样源
 * @param actions 右侧操作区（一般放 [GlassCircleButton] / [TopBarAction]）
 */
@Composable
fun GlassTopBar(
    title: AnnotatedString,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backdrop: Backdrop? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部底色渐隐：位于内容之上、药丸之下（二级页用较低的不透明度，保留封面）
        Box(
            modifier = Modifier
                .matchParentSize()
                .topBarFade(maxAlpha = Liquid.TOP_FADE_ALPHA_SECONDARY)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶部让出状态栏：所有顶栏统一走 rememberStatusBarTopDp()
                // （优先宿主给的稳定值，避免播放器隐藏/恢复系统栏时页面上下移动）
                .padding(top = rememberStatusBarTopDp())
                .padding(horizontal = 8.dp, vertical = Liquid.TOP_BAR_VERTICAL_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 最左：返回
            GlassCircleButton(
                backdrop = backdrop,
                iconRes = R.drawable.ic_arrow_left_fluent,
                contentDescription = "返回",
                onClick = onBack
            )

            Spacer(Modifier.width(8.dp))

            // 中间：标题信息（胶囊与圆钮等高：heightIn(min) + 相同的纵向内边距）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .backdropButton(
                        backdrop = backdrop,
                        shape = LiquidCapsule
                    )
                    .heightIn(min = Liquid.CONTROL_HEIGHT)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 最右：其他重要操作
            if (actions != null) {
                Spacer(Modifier.width(8.dp))
                actions()
            }
        }
        // 让底色渐隐再向下延伸一截（本身不可见，只撑高外层 Box）
        Spacer(Modifier.fillMaxWidth().height(22.dp))
    }
}

/** 纯文本标题的便捷重载 */
@Composable
fun GlassTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backdrop: Backdrop? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    GlassTopBar(
        title = AnnotatedString(title),
        onBack = onBack,
        modifier = modifier,
        subtitle = subtitle,
        backdrop = backdrop,
        actions = actions
    )
}
