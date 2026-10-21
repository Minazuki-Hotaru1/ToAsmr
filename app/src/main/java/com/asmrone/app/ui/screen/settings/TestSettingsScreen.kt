package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron

/**
 * 测试设置二级页 —— 排查问题用的工具，正常使用不会进来。
 *
 * 单独成页而不是直接挂在设置首页上：首页那一层每一行都代表一种"设置"，
 * 而这一页里的东西是诊断用的开关/查看器，混在一起会让首页显得吵。
 */
@Composable
fun TestSettingsScreen(onBack: () -> Unit) {
    var showHttpLog by remember { mutableStateOf(false) }

    SettingsScaffold(title = "测试设置", onBack = onBack) {
        FlatSection(title = "诊断") {
            FlatRow(
                title = "网络日志",
                description = "最近的请求、HTTP 状态码与是否带上登录信息",
                onClick = { showHttpLog = true },
                trailing = { RowChevron() }
            )
        }
    }

    if (showHttpLog) {
        HttpLogDialog(onDismiss = { showHttpLog = false })
    }
}

/**
 * 网络日志弹窗。
 *
 * 提供「复制」是刻意的：这类问题的定位完全依赖"请求到底发了什么"，
 * 让用户能一键把日志复制出来，比要一份 logcat 现实得多。
 */
@Composable
private fun HttpLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 每次打开都重新取，保证看到的是最新记录
    val dump = remember { HttpLog.dump() }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络日志") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "看每一行的「token=有/无」与「HTTP 状态码」：\n" +
                        "· token=无 且 401 → 登录状态没带上（请重新登录）\n" +
                        "· host 不是你预期的服务器 → 请求打到了别的主机\n" +
                        "· HTTP 5xx → 服务端问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = dump,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                manager.setPrimaryClip(android.content.ClipData.newPlainText("ToAsmr 网络日志", dump))
                copied = true
            }) { Text(if (copied) "已复制" else "复制全部") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { HttpLog.clear(); onDismiss() }) { Text("清空并关闭") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}
