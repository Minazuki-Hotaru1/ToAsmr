package com.asmrone.app.ui.screen.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.repo.AsmrRepository

/**
 * 外部链接 / RJ 号的落地页。
 *
 * 它在导航图里只是一层"中转"：进来立刻把线索换成 workId，换到了就
 * `navigate(detail)` 并把**自己从返回栈里摘掉**（否则从详情页返回会回到
 * 这个纯转圈的页面）。所以用户看到的是"点链接 → 直接就是作品详情"。
 *
 * 为什么不让调用方自己 `resolve` 再导航：换 id 要发一次网络请求，失败要
 * 有地方显示错误。放在这一页里，冷启动（外部点链接拉起 App）和热启动
 * （App 已在后台）走的是同一条路，不用各自处理一遍。
 */
@Composable
fun WorkLinkScreen(
    token: String,
    onResolved: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var failed by remember(token) { mutableStateOf(false) }
    // 重试就是把它 +1：LaunchedEffect 的 key 变了会重跑
    var attempt by remember(token) { mutableIntStateOf(0) }

    LaunchedEffect(token, attempt) {
        failed = false
        val id = AsmrRepository.resolveWorkId(token)
        if (id != null) {
            onResolved(id)
        } else {
            failed = true
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (failed) {
            Text(
                text = "打不开这个作品",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "没找到「$token」对应的作品。请确认 RJ 号或链接是否正确，" +
                    "或者换一个 API 主机再试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { attempt += 1 }) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在打开 $token",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
