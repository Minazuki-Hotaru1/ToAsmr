package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage

/**
 * 统一封面组件（提炼自 comicApp `ComicCard` 的「模糊底图 + 完整前景」双图方案）。
 *
 * 解决的问题：音声封面有方图、竖图、横图三种，如果统一 `Crop` 会把横版封面的两侧裁掉。
 * 这里改为：
 *  ① 底图 —— 同一张封面 `Crop` 放大填满并**重度模糊**，用来填补留白；
 *  ② 前景 —— 封面 `Fit` 完整呈现，不裁切；
 *  ③ 加载中 —— 复用 [ShimmerEffect] 骨架，加载失败显示占位底色。
 *
 * 卡片、列表缩略图、详情头图、播放器大图全部复用这一个组件，保证封面观感一致。
 */
@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    backdropBlur: Dp = 32.dp,
    contentScale: ContentScale = ContentScale.Fit,
    showBackdrop: Boolean = true,
    /** 底部渐变遮罩高度（卡片上让标题更可读） */
    bottomScrimHeight: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // ① 模糊底图
        if (showBackdrop) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(backdropBlur),
                contentScale = ContentScale.Crop
            )
        }

        // ② 前景封面（加载中/失败由 SubcomposeAsyncImage 的槽位接管）
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            loading = {
                ShimmerEffect(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp)
                )
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        )

        // ③ 底部渐变遮罩
        if (bottomScrimHeight > 0.dp) {
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomScrimHeight)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                        )
                    )
            )
        }
    }
}
