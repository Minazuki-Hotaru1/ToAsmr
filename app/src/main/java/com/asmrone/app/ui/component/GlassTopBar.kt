package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.HazeState

/**
 * 二级页面顶部栏 —— 形态移植自 Telegram 频道页（提炼自 comicApp 的 GlassTopBar）：
 *
 * ```
 * ┌───┐                      ┌──────┐
 * │ ← │    标题信息（药丸）    │ 操作 │
 * └───┘                      └──────┘
 * ```
 *
 *  - 最左：圆形玻璃药丸（返回）
 *  - 中间：一条玻璃药丸，显示标题（可选副标题，例如声优 / 社团）
 *  - 最右：其他重要操作按钮（若干圆形玻璃药丸，可选）
 *
 * 玻璃材质与底部导航栏完全一致（共用 [glassSurface] + Haze 真实背景模糊）。
 * 药丸是悬浮的，所以页面内容可以滚动到它们下方透出；内容会自动从状态栏下方开始排布。
 *
 * 另外，在**药丸之下的整条顶部区域**还有一层**向上渐强的渐变模糊**：
 * 内容滚到顶部时越靠上越模糊、向下渐隐。
 *
 * @param hazeState 背景模糊用的 HazeState；页面里被取样的内容需挂 `Modifier.hazeSource(state)`
 * @param actions 右侧操作区（一般放 [GlassCircleButton]）
 */
@Composable
fun GlassTopBar(
    title: AnnotatedString,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hazeState: HazeState? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部渐变模糊底衬：位于内容之上、药丸之下（与主页面顶栏共用同一套参数）
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
                // 顶部让出状态栏：优先用宿主提供的**稳定**高度（见 LocalStableStatusBarTop），
                // 这样播放器沉浸隐藏/恢复状态栏时，返回本页不会带着内容一起上下移动。
                .then(
                    LocalStableStatusBarTop.current?.let { stable ->
                        Modifier.padding(top = stable)
                    } ?: Modifier.statusBarsPadding()
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 最左：返回
            GlassCircleButton(
                hazeState = hazeState,
                iconRes = R.drawable.ic_arrow_left_fluent,
                contentDescription = "返回",
                onClick = onBack
            )

            Spacer(Modifier.width(8.dp))

            // 中间：标题信息
            //  - fill = false → 药丸按内容宽度收缩，右侧操作按钮因此能**紧贴标题**（标题旁）
            //  - height 与返回/操作圆形药丸一致 → 整条顶栏控件上下对齐（标题不再比返回按钮高）
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .height(Sizes.TOP_BAR_CONTROL)
                    .glassSurface(
                        shape = RoundedCornerShape(percent = 50),
                        hazeState = hazeState,
                        elevation = 8.dp
                    )
                    .padding(horizontal = 16.dp),
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
        // 让渐变模糊底衬再向下延伸一截（本身不可见，只撑高外层 Box）：
        // 这样药丸下方也有一段可见的「越往上越模糊」过渡，而不是全被药丸盖住。
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
    hazeState: HazeState? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    GlassTopBar(
        title = AnnotatedString(title),
        onBack = onBack,
        modifier = modifier,
        subtitle = subtitle,
        hazeState = hazeState,
        actions = actions
    )
}
