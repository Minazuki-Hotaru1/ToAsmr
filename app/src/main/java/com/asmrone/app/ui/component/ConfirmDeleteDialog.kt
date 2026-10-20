package com.asmrone.app.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.asmrone.app.ui.theme.ErrorColor

/**
 * 「删除」的二次确认弹窗。
 *
 * 为什么要有它：标记/收藏弹窗里的「删除」是一次性删掉整条记录
 * （标记 = 星级 + 状态；收藏 = 从所有收藏夹移出），既没有回收站也没法撤销。
 * 这类操作必须再问一次，而且要把"会删掉什么"说清楚，
 * 不能只写一句「确定删除吗？」。
 *
 * 约定：
 *  - 文案由调用方给（[title] / [message]），因为两种删除删的东西不一样；
 *  - 「取消」= 什么都不做，弹窗关掉、外层弹窗保持原样；
 *  - 只有点「删除」才真正执行 [onConfirm]。
 *
 * @param title 标题，如「删除标记？」
 * @param message 说明会删掉什么、能不能恢复
 * @param onConfirm 用户确认删除
 * @param onDismiss 用户取消（不动任何数据）
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = ErrorColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
