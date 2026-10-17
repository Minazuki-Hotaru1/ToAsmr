package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.theme.Elevation
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.HazeState

/**
 * 悬浮玻璃迷你播放器（本次新增，沿用 [glassSurface] 材质）。
 *
 * 设计意图：底部导航胶囊之上再叠一条更小的玻璃胶囊，两者材质完全一致，
 * 于是「浮起来的东西」仍然只有一类元素；播放器只在有内容时出现，不干扰浏览。
 *
 * 形态：
 *  - 左：36dp 圆角封面
 *  - 中：曲目名（bodyMedium，一行）+ 作品名（labelSmall）
 *  - 右：播放/暂停 + 下一首（48dp 触控区）
 *  - 底部：2dp 进度线（贴住圆角内缘）
 */
@Composable
fun MiniPlayerBar(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    hasNext: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.MINI_PLAYER_HEIGHT)
            .glassSurface(shape = shape, hazeState = hazeState, elevation = Elevation.glass)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            backdropBlur = 16.dp
        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "未在播放" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause_fluent else R.drawable.ic_play_fluent),
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next_fluent),
                contentDescription = "下一首",
                tint = if (hasNext) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * 播放进度线（2dp），贴在迷你播放器底部。
 * 单独抽出来是因为它需要在外层 `Box` 里对齐到底边，与胶囊的投影不冲突。
 */
@Composable
fun MiniPlayerProgressLine(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 播放中的「跳动的三根竖条」指示器（列表里标记正在播放的曲目） */
@Composable
fun PlayingIndicator(
    modifier: Modifier = Modifier,
    barCount: Int = 3
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            Box(
                modifier = Modifier
                    .padding(end = 2.dp)
                    .width(2.dp)
                    .height(if (index == 1) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
