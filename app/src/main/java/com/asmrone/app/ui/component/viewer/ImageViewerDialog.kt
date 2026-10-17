package com.asmrone.app.ui.component.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import com.asmrone.app.data.model.OpenableFile
import kotlinx.coroutines.launch

/**
 * 内置**图片查看器**：同目录左右滑动 + 双指缩放。
 *
 * ## 为什么用 Coil 而不是引入图片库
 *
 * 项目里已经有 `coil-compose`（封面加载在用），它自带内存/磁盘缓存、
 * 请求去重、生命周期感知。图片查看器缺的只有"手势"，
 * 而 Compose 框架自带了 `detectTransformGestures` —— 所以这一块
 * **不需要任何新依赖**，也就避开了成熟图片库都是 View 体系
 * （要 `AndroidView` 包一层）或拖 JetBrains Compose（与本项目冲突）的问题。
 *
 * ## 缩放的实现取舍
 *
 * 缩放/平移用 `graphicsLayer` 改矩阵，**不重新请求图片** —— 所以放大看细节
 * 看到的是原图分辨率（服务端给的就是原图，没有多级瓦片）。
 * 图片最大实测 21MB，普通手机内存扛得住；真要显示"海报级"巨图才需要
 * 超采样库（如 subsampling-scale-image-view），这里用不上。
 */
@Composable
fun ImageViewerDialog(file: OpenableFile, onDismiss: () -> Unit) {
    val siblings = file.siblings.ifEmpty { listOf(file) }
    val pagerState = rememberPagerState(initialPage = file.indexInSiblings) { siblings.size }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = siblings[page]
                ZoomableAsyncImage(
                    url = item.url,
                    contentDescription = item.title
                )
            }

            // ── 顶栏：文件名 + 序号 + 关闭 ──────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = siblings.getOrNull(pagerState.currentPage)?.title.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (siblings.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${siblings.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = Color.White)
                }
            }

            // ── 底部：同目录切换（横滑之外再给一个明确入口）──────
            if (siblings.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        onClick = {
                            val prev = pagerState.currentPage - 1
                            if (prev >= 0) scope.launch { pagerState.animateScrollToPage(prev) }
                        }
                    ) { Text("上一张", color = Color.White.copy(alpha = 0.8f)) }
                    TextButton(
                        onClick = {
                            val next = pagerState.currentPage + 1
                            if (next < siblings.size) {
                                scope.launch { pagerState.animateScrollToPage(next) }
                            }
                        }
                    ) { Text("下一张", color = Color.White.copy(alpha = 0.8f)) }
                }
            }
        }
    }
}

/**
 * 单张可缩放图片。
 *
 * [zoom] 与 [offset] 走 `graphicsLayer`，只是矩阵变换，不重新解码。
 * 缩放范围 1x–5x：1x 是"适配屏幕"，5x 足够看清原图细节；
 * 松手后**不自动回弹**（看图时把人弹回去很烦），双击可复位。
 */
@Composable
private fun ZoomableAsyncImage(url: String, contentDescription: String) {
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(url) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                    // 只在放大状态允许平移；1x 时把位移归零，
                    // 否则会和 Pager 的横滑打架（左右滑切换图片失效）
                    if (newZoom > 1f) {
                        zoom = newZoom
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        zoom = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = offsetX
                translationY = offsetY
            },
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "图片加载失败\n$url",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    )
}
