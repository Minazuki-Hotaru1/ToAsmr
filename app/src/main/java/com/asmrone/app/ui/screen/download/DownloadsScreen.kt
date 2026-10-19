package com.asmrone.app.ui.screen.download

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.asmrone.app.data.download.DownloadManager
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.Sizes
import kotlin.math.roundToInt

/**
 * 「下载」页：上半是**正在下载**的进度条，下半是**已下载**作品的卡片网格。
 *
 * 卡片刻意与首页 / 收藏页完全同构（同一个 [WorkCard]、同一套瀑布流参数），
 * 所以它看起来就是"另一个作品列表"，而不是一个功能面板。
 *
 * 数据全部来自 [DownloadManager] 的两个 StateFlow：一个协程都不会因为进出本页而重启，
 * 所以下载进度在切页时不会丢。
 */
@Composable
fun DownloadsScreen(
    onWorkClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tasks by DownloadManager.tasks.collectAsState()
    val library by DownloadManager.library.collectAsState()

    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }
    val bottomInset = Liquid.CONTENT_BOTTOM_INSET_WITH_PLAYER
    val contentPadding = remember(barHeight, density, bottomInset) {
        PaddingValues(
            top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP,
            bottom = bottomInset
        )
    }

    val listState = rememberLazyStaggeredGridState()
    CollapsibleBottomBarEffect {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    val activeTasks = remember(tasks) {
        tasks.values.filter { it.isActive }.sortedBy { it.title }
    }
    val animatedKeys = remember { mutableSetOf<Int>() }
    var pendingDelete by remember { mutableStateOf<DownloadManager.DownloadedWork?>(null) }

    // 首次进入时把磁盘上的清单读出来（进程重启、或直接从通知点进来时 library 还可能是空的）
    LaunchedEffect(Unit) {
        if (library.isEmpty()) DownloadManager.refreshLibrary()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            library.isEmpty() && activeTasks.isEmpty() -> EmptyHint(
                title = "还没有下载",
                description = "打开任意作品详情页，点「下载」把音声存到本地，之后没网也能听。"
            )

            else -> LazyVerticalStaggeredGrid(
                state = listState,
                columns = StaggeredGridCells.Adaptive(minSize = Sizes.GRID_MIN_CELL),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp
            ) {
                if (activeTasks.isNotEmpty()) {
                    item(key = "active", span = StaggeredGridItemSpan.FullLine) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeTasks.forEach { task ->
                                ActiveDownloadRow(
                                    task = task,
                                    onCancel = { DownloadManager.cancel(task.workId) }
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }

                items(library, key = { it.work.id }) { item ->
                    val alpha = remember(item.work.id) {
                        Animatable(if (item.work.id in animatedKeys) 1f else 0f)
                    }
                    LaunchedEffect(item.work.id) {
                        if (item.work.id !in animatedKeys) {
                            animatedKeys.add(item.work.id)
                            val index = library.indexOfFirst { it.work.id == item.work.id }
                            alpha.animateCardEntrance(if (index < 0) 0 else index)
                        }
                    }
                    Box(modifier = Modifier.cardEntrance(alpha)) {
                        WorkCard(
                            work = item.work,
                            host = item.host,
                            onClick = { onWorkClick(item.work.id) },
                            onLongClick = { pendingDelete = item },
                            // 与收藏页一致：不显示标签（标签在手机上一行放不下、信息价值低）
                            showTags = false
                        )
                    }
                }

                item(key = "bottom_space", span = StaggeredGridItemSpan.FullLine) {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        TabTopBar(modifier = Modifier.onSizeChanged { barHeight = it.height }) {
            Text(
                text = "下载",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (library.isNotEmpty()) {
                Text(
                    text = "${library.size} 部 · ${formatBytes(library.sumOf { it.bytes })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = {
                Text("将删除《${target.work.title}》在本地已下载的 ${target.fileCount} 个文件（${formatBytes(target.bytes)}）。此操作不会影响云端内容。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    DownloadManager.remove(target.work.id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 进行中的一条：封面 + 标题 + 进度条 + 取消按钮 */
@Composable
private fun ActiveDownloadRow(
    task: DownloadManager.Task,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val cover = task.coverUrl
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val detail = when (task.status) {
                DownloadManager.Status.QUEUED -> "等待中…"
                DownloadManager.Status.FAILED -> task.error ?: "下载失败"
                DownloadManager.Status.CANCELLED -> "已取消"
                else -> {
                    val percent = (task.progress * 100).roundToInt()
                    "${task.doneFiles}/${task.totalFiles} · $percent%"
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == DownloadManager.Status.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            if (task.isActive) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
        if (task.isActive) {
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 1.2 MB 这种；下载页顶栏与删除确认共用 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}
