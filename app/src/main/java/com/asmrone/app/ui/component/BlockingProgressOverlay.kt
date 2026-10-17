package com.asmrone.app.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.asmrone.app.ui.theme.Radius
import com.asmrone.app.ui.theme.Spacing

/**
 * **阻断式**加载遮罩（提炼自 comicApp 的 BlockingProgressOverlay）。
 *
 * 用于「首次建立本地索引 / 校验服务器连通性」这类不能被中途打断的操作：
 *  1. 用 [Dialog]（`dismissOnBackPress = false` / `dismissOnClickOutside = false`）
 *     吃掉返回键与点击外部，用户无法离开；
 *  2. 铺满全屏的半透明底，挡住下层所有控件的点击；
 *  3. 显示进度指示 + 说明文字，让用户知道正在做什么、要等多久。
 *
 * @param title 主标题，如「正在连接 asmr.one」
 * @param message 说明文字，讲清为什么需要等待
 */
@Composable
fun BlockingProgressOverlay(
    title: String,
    message: String
) {
    BackHandler(enabled = true) { /* 有意吞掉返回：阻塞期间不允许离开 */ }

    Dialog(
        onDismissRequest = { /* 不允许通过返回键 / 点击外部关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.xl),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(Spacing.xl)
                    .widthIn(min = 260.dp, max = 340.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
