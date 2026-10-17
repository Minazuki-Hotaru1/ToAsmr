package com.asmrone.app.ui.component.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.asmrone.app.data.model.OpenableFile
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.text.DecodedText
import com.asmrone.app.data.text.TextCharset
import com.asmrone.app.data.text.TextDecoder

/**
 * 内置**文本查看器**。
 *
 * ## 为什么 txt 不走外部 App
 *
 * 实测判断：Android **没有**内置纯文本查看器，也没有约定俗成的第三方
 * 接管 `text/plain`。外跳 `ACTION_VIEW` 的常见结果是
 * "没有应用可执行此操作" —— 也就是用户点了没反应。所以 txt 一律内置。
 *
 * ## 这个查看器要解决的真问题：编码
 *
 * 服务端对文本只回 `Content-Type: text/plain`，**不带 charset**（实测）。
 * 而内容确实有多种编码，且用字节级严格校验测出：
 *
 * | 判定 | 数量 |
 * |---|---|
 * | UTF-8（无歧义） | 187 |
 * | Shift-JIS 与 GBK **都合法** | 24 |
 *
 * 那 24 个自动判不准 —— 所以顶栏常驻一个**编码切换**按钮。
 * 用户看到乱码点一下换一个，比"猜错了还嘴硬"有用得多。
 * 若是手动选的编码解出了替换符，会额外提示"可能选错"。
 */
@Composable
fun TextViewerDialog(file: OpenableFile, onDismiss: () -> Unit) {
    var loading by remember(file.url) { mutableStateOf(true) }
    var error by remember(file.url) { mutableStateOf<String?>(null) }
    var raw by remember(file.url) { mutableStateOf<ByteArray?>(null) }
    var decoded by remember(file.url) { mutableStateOf<DecodedText?>(null) }
    // null = 自动判断；非 null = 用户手动指定
    var manual by remember(file.url) { mutableStateOf<TextCharset?>(null) }

    // 取字节（只取一次；切换编码不重新下载）
    LaunchedEffect(file.url) {
        loading = true
        error = null
        runCatching { AsmrRepository.fetchBytes(file.url) }
            .onSuccess {
                raw = it
                decoded = TextDecoder.decode(it, file.title, hint = null)
                loading = false
            }
            .onFailure {
                error = it.message ?: "下载失败"
                loading = false
            }
    }

    // 切换编码：用**同一份字节**重新解，不重新下载
    LaunchedEffect(manual) {
        val bytes = raw ?: return@LaunchedEffect
        decoded = TextDecoder.decode(bytes, file.title, hint = manual)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶栏 ────────────────────────────────────
                ViewerTopBar(
                    title = file.title,
                    subtitle = buildString {
                        val size = file.sizeText
                        if (size.isNotEmpty()) append(size)
                        decoded?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it.charset.displayName)
                            if (it.uncertain) append("（可能选错）")
                        }
                    },
                    onDismiss = onDismiss
                )

                // ── 编码切换（三类编码各一个入口）─────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "编码：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { manual = null },
                        enabled = manual != null
                    ) { Text("自动", style = MaterialTheme.typography.labelMedium) }
                    TextCharset.entries.forEach { cs ->
                        TextButton(
                            onClick = { manual = cs },
                            enabled = decoded?.charset != cs || manual != cs
                        ) {
                            Text(
                                text = cs.displayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    error != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onDismiss) { Text("关闭") }
                        }
                    }

                    else -> TextBody(decoded?.text.orEmpty())
                }
            }
        }
    }
}

/**
 * 正文。
 *
 * 用 [LazyColumn] 按行渲染而不是一个巨大的 `Text`：实测长文本能到 684KB，
 * 一次性交给 Compose 文本布局会明显卡顿。按行懒加载后，
 * 滚动到哪一行才排版哪一行。
 */
@Composable
private fun TextBody(text: String) {
    val lines = remember(text) { text.split('\n') }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        itemsIndexed(lines) { _, line ->
            // 文本要可选中复制（台本常见用法是抄一份关键词去搜）
            SelectionContainer {
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

/** 查看器共用的顶栏（文本用；图片/视频各自有深色底上的版本） */
@Composable
private fun ViewerTopBar(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
