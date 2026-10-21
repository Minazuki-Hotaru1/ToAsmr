package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.asmrone.app.R
import com.asmrone.app.ui.theme.Sizes

/**
 * 悬浮液态玻璃迷你播放器 —— 本次随材质整体重写。
 *
 * 设计意图：底部导航胶囊之上再叠一条更小的玻璃胶囊，两者材质完全一致
 * （同一个 [backdrop] 取样源、同一份 `vibrancy + blur + lens` 配方），
 * 于是「浮起来的东西」仍然只有一类元素；播放器只在有内容时出现，不干扰浏览。
 *
 * 形态：
 *  - 左：40dp 圆角封面
 *  - 中：曲目名（bodyMedium，一行）+ 作品名（labelSmall）
 *  - 右：播放/暂停 + 下一首（44dp 触控区）
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
    backdrop: Backdrop? = null
) {
    val shape = RoundedCornerShape(percent = 50)
    val density = LocalDensity.current
    // 把自己真实占用的高度上报给提示宿主：播放器一出现就把提示顶上去，
    // 一消失立刻归零（本组件在 AnimatedVisibility 里，隐藏时离开组合）。
    DisposableEffect(Unit) {
        onDispose { MiniPlayerInset.height = 0.dp }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.MINI_PLAYER_HEIGHT)
            .onSizeChanged { MiniPlayerInset.height = with(density) { it.height.toDp() } }
            .backdropButton(backdrop = backdrop, shape = shape)
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
                // 与全应用的作品封面统一为 4:3（原来 40dp 正方形，
                // CoverImage 默认 Fit + 模糊垫底，于是非正方形封面上下各多出一条模糊填充）
                .height(40.dp)
                .aspectRatio(Sizes.CARD_IMAGE_ASPECT)
                .clip(RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            // 非 4:3 的封面直接裁切填满，不再留模糊底
            contentScale = ContentScale.Crop,
            showBackdrop = false
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

        // 玻璃胶囊内部的控件保持"扁平"：玻璃只属于浮起来的那一层，
        // 不给里面的小按钮再叠一层玻璃（与底栏标签同一原则）。
        IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
            FluentIcon(
                res = if (isPlaying) R.drawable.ic_pause_fluent else R.drawable.ic_play_fluent,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(44.dp)) {
            FluentIcon(
                res = R.drawable.ic_skip_next_fluent,
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
    barCount: Int = 3,
    color: Color = MaterialTheme.colorScheme.primary
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
                    .background(color)
            )
        }
    }
}
