package com.asmrone.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 顶部毛玻璃遮罩 —— 覆盖在状态栏 + 标题栏区域
 * （提炼自 comicApp 的 FrostedBar.kt，作为「不支持 Haze 实时模糊」时的廉价兜底方案）
 *
 * @param scrollFraction 归一化滚动进度 0f(顶部)..1f(已滚动)
 */
@Composable
fun TopBarScrim(
    scrollFraction: Float,
    modifier: Modifier = Modifier
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = (scrollFraction * 0.92f).coerceIn(0f, 0.92f),
        animationSpec = tween(200),
        label = "scrimAlpha"
    )

    val scrimColor = MaterialTheme.colorScheme.background.copy(alpha = animatedAlpha)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(
                Brush.verticalGradient(
                    colors = listOf(scrimColor, scrimColor, Color.Transparent),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        // 底部边缘的渐变过渡线（8dp）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(scrimColor, Color.Transparent)
                    )
                )
        )
    }
}

/**
 * 底部毛玻璃遮罩 —— 在导航栏 / 迷你播放器上方做渐变过渡，
 * 让悬浮玻璃元素与内容之间不会出现生硬的切边。
 */
@Composable
fun BottomBarScrim(modifier: Modifier = Modifier) {
    val scrimColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, scrimColor)
                )
            )
    )
}
