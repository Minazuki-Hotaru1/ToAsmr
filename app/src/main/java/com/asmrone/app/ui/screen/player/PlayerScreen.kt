package com.asmrone.app.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import com.asmrone.app.R
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.player.AudioController
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.PlayingIndicator
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.rememberHazeState

/**
 * 全屏播放器（本次新增）。
 *
 * 布局从上到下：玻璃顶栏 → 大封面 → 曲目名 → 进度条 → 时间 → 传输控制
 * → 定时/增益快捷项 → 曲目列表。整体沿用同一套 Token 与玻璃材质，
 * 视觉上就是"comicApp 的阅读页换成了音频"。
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit
) {
    val state by AudioController.state.collectAsState()
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()

    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    var barHeight by remember { mutableIntStateOf(with(density) { (statusTopDp + 56.dp).roundToPx() }) }

    // 拖动进度条时用本地值，避免与 500ms 的位置刷新打架
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val gain by AppPrefs.gain.collectAsState()
    val sleepMinutes by AppPrefs.sleepTimerMinutes.collectAsState()

    LaunchedEffect(state.workId) {
        // 切歌时把列表滚到当前曲目
        if (state.index >= 0) listState.animateScrollToItem(state.index)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(with(density) { barHeight.toDp() }))

            // ── 大封面 ──────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CoverImage(
                    url = state.coverUrl,
                    contentDescription = state.workTitle,
                    modifier = Modifier
                        .size(Sizes.PLAYER_COVER_MAX)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    backdropBlur = 40.dp
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 标题 ────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.trackTitle.ifBlank { "未在播放" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.workTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 进度条 ──────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Slider(
                    value = if (dragging) dragValue else state.progress,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        AudioController.seekToFraction(dragValue)
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = Work.formatDuration(((if (dragging) dragValue else state.progress) * state.durationMs / 1000f).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Work.formatDuration((state.durationMs / 1000).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 传输控制 ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { AudioController.skipBy(-15) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_rewind_fluent),
                        contentDescription = "后退 15 秒",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { AudioController.prev() },
                    enabled = state.hasPrev
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous_fluent),
                        contentDescription = "上一首",
                        tint = if (state.hasPrev) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { AudioController.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering && !state.isPrepared) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) R.drawable.ic_pause_fluent
                                else R.drawable.ic_play_fluent
                            ),
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { AudioController.next() },
                    enabled = state.hasNext
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next_fluent),
                        contentDescription = "下一首",
                        tint = if (state.hasNext) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
                IconButton(onClick = { AudioController.skipBy(30) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fast_forward_fluent),
                        contentDescription = "前进 30 秒",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 睡眠定时 + 音量增益 ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0, 15, 30, 60, 90).forEach { minutes ->
                    AsmrFilterChip(
                        text = if (minutes == 0) "不定时" else "${minutes}分",
                        selected = sleepMinutes == minutes,
                        onClick = {
                            AppPrefs.setSleepTimerMinutes(minutes)
                            AudioController.setSleepTimer(minutes)
                        }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "音量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = gain,
                    onValueChange = {
                        AppPrefs.setGain(it)
                        AudioController.setGain(it)
                    },
                    valueRange = 0f..2f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = "${(gain * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.sleepTimerRemainingMs > 0) {
                Text(
                    text = "睡眠定时：${state.sleepTimerRemainingMs / 60000} 分 ${(state.sleepTimerRemainingMs % 60000) / 1000} 秒后暂停",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = com.asmrone.app.ui.theme.ErrorColor,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 曲目列表 ────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                itemsIndexed(
                    state.tracks,
                    // path 兜底：异常数据下为空时退回下标，避免 LazyColumn key 冲突
                    key = { index: Int, t -> t.path.ifBlank { "track-$index" } }
                ) { index, track ->
                    val playing = index == state.index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { AudioController.playTrackAt(index) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(26.dp)) {
                            if (playing) {
                                PlayingIndicator()
                            } else {
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (playing) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = track.durationSec?.let { Work.formatDuration(it.toLong()) } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 玻璃顶栏 ────────────────────────────────────────
        GlassTopBar(
            title = state.workTitle.ifBlank { "播放器" },
            subtitle = if (state.tracks.isNotEmpty()) {
                "第 ${state.index + 1} / ${state.tracks.size} 首"
            } else null,
            onBack = onBack,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeight = it.height }
        )
    }
}
