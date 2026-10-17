package com.asmrone.app.ui.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.theme.ErrorColor

/**
 * 标记失败的详细报错。
 *
 * 结构上刻意分三层，对应排查问题时真正需要的三件事：
 *  1. **什么失败了**（标题，比如"登录状态已失效"）
 *  2. **为什么 / 该怎么办**（hint，一句可执行的话）
 *  3. **原始详情**（默认折叠，点「详情」才展开 —— 既不干扰普通用户，
 *     又能让开发者/反馈时拿到确切原因）
 *
 * 之前这里只有 `"收藏操作失败：<异常>"` 一行，用户既看不懂也不知道下一步做什么，
 * 我这边排查也只能靠猜。这个组件就是为了消除那种情况。
 */
@Composable
fun MarkFailureCard(
    failure: MarkFailure,
    modifier: Modifier = Modifier,
    /** 可选的重试入口；成功后由调用方清掉 failure */
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var showDetail by remember(failure) { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ErrorColor.copy(alpha = 0.10f))
                .border(1.dp, ErrorColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            // ① 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_circle_fluent),
                    contentDescription = null,
                    tint = ErrorColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.labelLarge,
                    color = ErrorColor,
                    modifier = Modifier.weight(1f)
                )
            }

            // ② 原因与建议
            Spacer(Modifier.height(6.dp))
            Text(
                text = failure.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ③ 原始详情（默认折叠）
            failure.detail?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (showDetail) "详情" else "详情 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { showDetail = !showDetail }
                )
                if (showDetail) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        // 等宽字体便于对照日志/反馈
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(8.dp)
                    )
                }
            }

            // ④ 操作
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("重试") }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}
