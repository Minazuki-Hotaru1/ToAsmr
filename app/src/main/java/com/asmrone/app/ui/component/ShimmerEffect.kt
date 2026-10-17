package com.asmrone.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Sizes

/**
 * Shimmer 加载骨架屏效果（提炼自 comicApp 的 ShimmerEffect.kt）
 * 用于封面 / 列表加载时的占位动画 —— 轻快的流光效果。
 */
@Composable
fun ShimmerEffect(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp)
) {
    // 主题感知的 shimmer 颜色：基于 surfaceVariant 明暗变化
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val shimmerColors = listOf(
        baseColor,
        baseColor.copy(alpha = 0.6f),
        baseColor
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

/** 音声作品卡片骨架屏（对应 [WorkCard]：4:3 图片 + 标题/社团/声优/标签） */
@Composable
fun WorkCardShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShimmerEffect(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Sizes.CARD_IMAGE_ASPECT),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        )
        Column(modifier = Modifier.padding(10.dp)) {
            // 标题
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(12.dp),
                shape = RoundedCornerShape(4.dp)
            )
            Spacer(Modifier.height(6.dp))
            // 社团
            ShimmerEffect(
                modifier = Modifier
                    .width(52.dp)
                    .height(10.dp),
                shape = RoundedCornerShape(4.dp)
            )
            Spacer(Modifier.height(6.dp))
            // 声优
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(10.dp),
                shape = RoundedCornerShape(4.dp)
            )
            Spacer(Modifier.height(6.dp))
            // 标签
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ShimmerEffect(
                    modifier = Modifier.width(34.dp).height(10.dp),
                    shape = RoundedCornerShape(4.dp)
                )
                ShimmerEffect(
                    modifier = Modifier.width(46.dp).height(10.dp),
                    shape = RoundedCornerShape(4.dp)
                )
            }
        }
    }
}

/** 横向列表行卡片骨架屏（对应 [WorkRowCard]） */
@Composable
fun WorkRowShimmer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .height(Sizes.ROW_THUMB_HEIGHT + 20.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(10.dp))
        ShimmerEffect(
            modifier = Modifier
                .width(Sizes.ROW_THUMB_WIDTH)
                .height(Sizes.ROW_THUMB_HEIGHT),
            shape = RoundedCornerShape(8.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerEffect(
                modifier = Modifier.fillMaxWidth(0.9f).height(12.dp),
                shape = RoundedCornerShape(4.dp)
            )
            Spacer(Modifier.height(8.dp))
            ShimmerEffect(
                modifier = Modifier.fillMaxWidth(0.5f).height(10.dp),
                shape = RoundedCornerShape(4.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}
