package com.asmrone.app.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 收藏夹详情 —— 展示某个收藏夹里的作品。
 *
 * 顶栏用玻璃药丸栏（与作品详情页一致），标题是收藏夹名，副标题是作品数与隐私设置。
 * 系统收藏夹会在标题旁标注「系统」，并且**没有删除入口**。
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    onWorkClick: (Int) -> Unit
) {
    val viewModel: PlaylistDetailViewModel = viewModel()
    LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val state by viewModel.state.collectAsState()
    val hazeState = rememberHazeState()
    var removing by remember { mutableStateOf<Work?>(null) }
    val playlist = state.playlist

    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + 56.dp).roundToPx() })
    }
    val contentPadding = remember(barHeight, density) {
        PaddingValues(top = with(density) { barHeight.toDp() } + 12.dp, bottom = 96.dp)
    }

    // 轻量提示：只在用户主动刷新失败时用（自动对齐失败刻意安静）。
    // 展示后立刻清掉消息，避免离开页面再回来时重复弹出。
    val toast = rememberToastState()
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            toast.show(it)
            viewModel.clearToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(hazeState)
        ) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.pullRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
            when {
                state.loading && state.works.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                state.error != null && state.works.isEmpty() -> EmptyHint(
                    title = "加载失败",
                    description = state.error,
                    iconRes = R.drawable.ic_folder_fluent
                )

                state.works.isEmpty() -> EmptyHint(
                    title = "这个收藏夹还是空的",
                    description = "在作品详情页点右上角的收藏按钮，把作品加进来",
                    iconRes = R.drawable.ic_folder_fluent
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "summary") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "共 ${state.totalCount} 部作品",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                state.playlist?.let {
                                    SmallTagChip(text = it.privacyLabel, tone = TagTone.Neutral)
                                }
                            }
                            // 上次对齐时间（弱化小字，无操作含义）
                            SyncStateLine(syncedAt = state.syncedAt)
                        }
                    }

                    state.error?.let { error ->
                        item(key = "error") {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorColor,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    items(state.works, key = { it.id }) { work ->
                        PlaylistWorkRow(
                            work = work,
                            pending = work.id in state.pendingIds,
                            retrying = state.retryingId == work.id,
                            onClick = { onWorkClick(work.id) },
                            onRemove = { removing = work },
                            onRetrySync = { viewModel.retryPending(work) }
                        )
                    }

                    if (state.hasMore) {
                        item(key = "load_more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    TextButton(onClick = { viewModel.loadMore() }) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        GlassTopBar(
            title = playlist?.displayName ?: "收藏夹",
            subtitle = buildString {
                append("${state.totalCount} 部作品")
                if (playlist?.isSystem == true) append(" · 系统收藏夹")
            },
            onBack = onBack,
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
            // 顶栏刻意**不放刷新按钮**：下拉刷新已经覆盖了这个需求，
            // 而每日首次进入也会自动与云端对齐一次 —— 多一个入口只是噪音。
        )

        // 轻量提示（用户主动刷新失败时）—— 固定在偏下方，不随列表滚动
        ToastHost(
            state = toast,
            toastOffset = 72.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 移出确认
    removing?.let { work ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移出收藏夹") },
            text = {
                Text(
                    "把「${work.title}」从「${playlist?.displayName ?: "该收藏夹"}」移出？\n\n" +
                        "作品本身不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.removeWork(work)
                }) { Text("移出", color = ErrorColor) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("取消") }
            }
        )
    }
}

/** 收藏夹里的一行作品：缩略图 + 标题 + 社团，右侧是移出按钮 */
@Composable
private fun PlaylistWorkRow(
    work: Work,
    pending: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRetrySync: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = work.coverUrl(AsmrRepository.host),
            contentDescription = work.title,
            modifier = Modifier
                .width(Sizes.ROW_THUMB_WIDTH)
                .height(Sizes.ROW_THUMB_HEIGHT),
            shape = RoundedCornerShape(8.dp),
            contentScale = ContentScale.Crop,
            showBackdrop = false
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    work.circleName,
                    work.durationText.takeIf { it != "--:--" },
                    work.userRating?.let { "我评 $it 星" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // 待同步标识：这一条只在本地，云端还没收到。点它就重试这一个作品。
        if (pending) {
            if (retrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                AsmrFilterChip(
                    text = "待同步",
                    selected = true,
                    negativeTone = true,
                    onClick = onRetrySync
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove)
                .padding(6.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close_fluent),
                contentDescription = "移出收藏夹",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
