package com.asmrone.app.ui.screen.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.ui.component.TreeRow
import com.asmrone.app.ui.component.collectFolderPaths
import com.asmrone.app.ui.component.flattenVisibleTree
import com.asmrone.app.ui.component.trackTreeSummary
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 下载选择器的下半屏弹窗。
 *
 * ## 选择模型
 *
 * - **文件夹**：点它 = 选中/取消它下面的**全部叶子文件**；勾选框显示三态
 *   （全选 / 部分 / 未选）。箭头单独负责展开收起，两者不抢手势。
 * - **文件**：点它就是选/不选。
 * - **已下载的文件**：勾选框固定为选中且禁用，并标「已下载」——
 *   下载只会补缺，不会重下已有文件（续传靠 `.part`，不靠这里）。
 *
 * 下载的是**叶子文件**（音频、字幕、图片、PDF 都算），文件夹本身不占空间。
 * 默认不选任何东西：用户点哪个下哪个，避免误点「下载」就把 300 个文件拖下来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPickerSheet(
    workTitle: String,
    tree: List<Track>,
    downloadedPaths: Set<String>,
    onDownload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 文件夹 path → 它下面的所有叶子文件 path（一次算好，避免每帧遍历整棵树）
    val leavesUnder = remember(tree) { folderLeaves(tree) }
    val downloadableLeaves = remember(tree) {
        leavesUnder.values.flatten().toSet().filter { path ->
            findNode(tree, path)?.let { node ->
                node.mediaStreamUrl.orEmpty().isNotBlank() ||
                    node.mediaDownloadUrl.orEmpty().isNotBlank() ||
                    !node.hash.isNullOrBlank()
            } ?: false
        }.toSet()
    }

    // 默认全展开：这是"选内容"的界面，收起来反而要多点好几下才能看到文件
    var expanded by remember(tree) {
        mutableStateOf<Set<String>>(collectFolderPaths(tree, limit = Int.MAX_VALUE).toHashSet())
    }
    var selected by remember(tree) { mutableStateOf(emptySet<String>()) }

    fun toggleNode(row: TreeRow) {
        val node = row.node
        if (node.children.isEmpty()) {
            if (node.path in downloadedPaths) return
            selected = if (node.path in selected) selected - node.path else selected + node.path
        } else {
            val leaves = leavesUnder[row.path].orEmpty().filter { it in downloadableLeaves }
            val selectable = leaves - downloadedPaths
            if (selectable.isEmpty()) return
            val allSelected = selectable.all { it in selected }
            selected = if (allSelected) selected - selectable.toSet() else selected + selectable
        }
    }

    fun toggleFolder(row: TreeRow) {
        expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
    }

    val rows = remember(tree, expanded) { flattenVisibleTree(tree, expanded) }
    val selectedBytes = remember(selected) {
        selected.sumOf { path -> findNode(tree, path)?.size ?: 0L }
    }
    val totalLeaves = downloadableLeaves.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.88f)) {
            // ── 顶栏：标题在左，「下载」在右上角 ──────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择下载内容",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = workTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = {
                        onDownload(selected.toList())
                        onDismiss()
                    },
                    enabled = selected.isNotEmpty()
                ) {
                    Text(
                        text = "下载",
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            // ── 汇总 + 快捷操作 ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已选 ${selected.size} 个" +
                        if (selectedBytes > 0) " · ${formatBytes(selectedBytes)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    expanded = if (expanded.size >= collectFolderPaths(tree, Int.MAX_VALUE).size) {
                        emptySet()
                    } else {
                        collectFolderPaths(tree, Int.MAX_VALUE).toHashSet()
                    }
                }) {
                    Text(
                        text = if (expanded.isEmpty()) "全部展开" else "全部收起",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = {
                    val audio = downloadableLeaves.filter { path ->
                        findNode(tree, path)?.let { TrackKind.of(it) == TrackKind.AUDIO } ?: false
                    }.toSet() - downloadedPaths
                    selected = if (audio.isNotEmpty() && audio.all { it in selected }) {
                        selected - audio
                    } else {
                        selected + audio
                    }
                }) {
                    Text(
                        text = if (selected.size == totalLeaves) "取消全选" else "全选音频",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = 16.dp
                )
            ) {
                items(rows, key = { it.key }) { row ->
                    val kind = TrackKind.of(row.node)
                    val isFolder = row.node.children.isNotEmpty()
                    val isDownloaded = !isFolder && row.path in downloadedPaths
                    val leaves = leavesUnder[row.path].orEmpty().filter { it in downloadableLeaves } - downloadedPaths
                    val checked = when {
                        isFolder -> leaves.isNotEmpty() && leaves.all { it in selected }
                        else -> isDownloaded || row.path in selected
                    }
                    val partially = isFolder && !checked && leaves.any { it in selected }
                    val selectable = if (isFolder) leaves.isNotEmpty() else !isDownloaded

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = selectable) { toggleNode(row) }
                            .padding(end = 12.dp)
                            .sizeIn(minHeight = 44.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = if (partially) null else {
                                { toggleNode(row) }
                            },
                            enabled = if (partially) false else selectable,
                            modifier = Modifier
                                .size(TouchTarget.minimum)
                                .padding(10.dp)
                        )
                        if (isFolder) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { toggleFolder(row) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right_fluent),
                                    contentDescription = if (row.path in expanded) "收起" else "展开",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .rotate(if (row.path in expanded) 90f else 0f),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(Modifier.width(28.dp))
                        }
                        Icon(
                            painter = painterResource(kindIcon(kind)),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.node.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val trailing = when {
                            isDownloaded -> "已下载"
                            isFolder -> {
                                val count = leavesUnder[row.path].orEmpty().size
                                if (count > 0) "$count 个" else ""
                            }
                            else -> row.node.sizeText
                        }
                        if (trailing.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = trailing,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                item(key = "summary") {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "共 ${trackTreeSummary(tree)}。下载后可离线播放，" +
                                "中途退出会保留已完成的部分，下次继续。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 工具 ───────────────────────────

private fun folderLeaves(nodes: List<Track>): Map<String, List<String>> {
    val map = HashMap<String, List<String>>()

    fun walk(node: Track): List<String> {
        if (node.children.isEmpty()) return listOf(node.path)
        val leaves = ArrayList<String>()
        node.children.forEach { child -> leaves.addAll(walk(child)) }
        map[node.path] = leaves
        return leaves
    }

    nodes.forEach { walk(it) }
    // 根目录不在 nodes 里，这里补一条"整棵树"的映射，供"全选"使用
    map[""] = nodes.flatMap { walk(it) }.distinct()
    return map
}

private fun findNode(nodes: List<Track>, path: String): Track? {
    for (node in nodes) {
        if (node.path == path) return node
        val hit = findNode(node.children, path)
        if (hit != null) return hit
    }
    return null
}

private fun kindIcon(kind: TrackKind): Int = when (kind) {
    TrackKind.FOLDER -> R.drawable.ic_folder_fluent
    TrackKind.AUDIO -> R.drawable.ic_audio_fluent
    TrackKind.VIDEO -> R.drawable.ic_video_fluent
    TrackKind.IMAGE -> R.drawable.ic_image_fluent
    TrackKind.SUBTITLE, TrackKind.TEXT, TrackKind.PDF -> R.drawable.ic_text_fluent
    TrackKind.UNSUPPORTED -> R.drawable.ic_file_fluent
}
