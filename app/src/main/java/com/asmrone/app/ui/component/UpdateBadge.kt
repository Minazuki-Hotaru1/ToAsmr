package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 「有新版本」圆点的颜色 —— 浅橙黄，在青绿主色里不抢眼但一眼能分辨 */
val UpdateBadgeColor = Color(0xFFFFC46B)

/**
 * 「有新版本」的小圆点标记。
 *
 * 用固定色而不是主题色：它表达的是"有件事需要你处理"，与主题色（青绿系）同族
 * 会看起来像排版装饰；换主题色时这个提示也不该跟着变。
 *
 * [ring] 会在圆点外描一圈容器色，把它从同色背景上摘出来 —— 放在白色设置卡片上
 * 需要，放在底栏的玻璃模糊层上反而不需要（那层本身就够暗）。
 */
@Composable
fun UpdateBadgeDot(
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    ring: Boolean = true,
    contentDescription: String? = "有新版本"
) {
    val shape = CircleShape
    val ringColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .size(size)
            .background(UpdateBadgeColor, shape)
            .then(
                if (ring) {
                    Modifier.border(width = 1.25.dp, color = ringColor, shape = shape)
                } else {
                    Modifier
                }
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    )
}
