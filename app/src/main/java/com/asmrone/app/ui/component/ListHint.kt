package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * 内容页首行的**小字提示**（全应用统一样式）。
 *
 * 用到的地方：
 *  - 首页：「所有作品（共 N 部）」
 *  - 搜索结果：「N 个结果」
 *  - 字典结果：「N 部作品 · 标签「xx」」
 *  - 收藏 / 收藏夹：「x 分钟前同步」
 *
 * ## 统一到什么程度
 *
 * 之前这几处各自写 `Text`：字号同为 labelSmall，但颜色透明度（100% / 65%）、
 * 左边距（有的额外 16dp、有的 2dp）、图标（同步那条有图标、其它没有）都不一样，
 * 并排对比时能明显看出参差。
 *
 * 现在统一为：**labelSmall + onSurfaceVariant + 无图标 + 单行省略**，
 * 左边距交给各列表自身的 12dp 网格内边距，上下留白由调用方的
 * `Modifier.padding(bottom = 4.dp)` 统一给出 —— 四处的文字起点、字号、
 * 颜色、行高完全一致。
 */
@Composable
fun ListHint(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
