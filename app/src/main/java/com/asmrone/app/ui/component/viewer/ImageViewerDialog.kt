package com.asmrone.app.ui.component.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.SubcomposeAsyncImage
import com.asmrone.app.data.model.OpenableFile
import kotlin.math.min

/**
 * 背景遮罩：半透明黑。
 *
 * 取 0.65 而不是接近不透明：看图时仍能隐约看到下面的详情页，
 * 「点空白退出」的落点也就不需要额外的提示文字。
 */
private val ViewerScrim = Color.Black.copy(alpha = 0.65f)

/**
 * 内置**图片查看器**：同目录左右滑动 + 双指缩放。
 *
 * ## 交互约定（2026-10-21 改）
 *
 * - 界面上**没有任何按钮**：没有顶栏文件名/关闭，也没有「上一张/下一张」；
 * - 背景（含状态栏那一条）是一层半透明黑，由本文件的 [ViewerScrim] 画；
 * - **点图片以外的区域退出**。图片按 `ContentScale.Fit` 显示，四周留黑边，
 *   点黑边即关闭 —— 所以不需要关闭按钮，单手也能退。
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

    // 对话框窗口默认会再叠一层 dim（约 60%），和我们自己画的半透明黑叠起来
    // 就成了"纯黑"，看不出背后还有什么。这里把系统那层关掉。
    val view = LocalView.current
    LaunchedEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 关键：让窗口铺到状态栏下面。否则状态栏那一条还是系统底色，
            // 看起来像"上面缺了一块"
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerScrim)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = siblings[page]
                ZoomableAsyncImage(
                    url = item.url,
                    contentDescription = item.title,
                    onTapOutside = onDismiss
                )
            }
        }
    }
}

/**
 * 单张可缩放图片。
 *
 * [zoom] 与 [offset] 走 `graphicsLayer`，只是矩阵变换，不重新解码。
 * 缩放范围 1x–5x：1x 是"适配屏幕"，5x 足够看清原图细节；
 * 松手后**不自动回弹**（看图时把人弹回去很烦）。
 *
 * 点击命中判定用的是 `ContentScale.Fit` **之后**的图片矩形：
 * 图片有宽高比，屏幕也有，四周必然留黑边；点在黑边上就是"点图片以外"。
 */
@Composable
private fun ZoomableAsyncImage(
    url: String,
    contentDescription: String,
    onTapOutside: () -> Unit
) {
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }

    var containerSize by remember(url) { mutableStateOf(IntSize.Zero) }
    var intrinsicSize by remember(url) { mutableStateOf(Size.Zero) }
    val imageRect = remember(containerSize, intrinsicSize) {
        fitRect(containerSize, intrinsicSize)
    }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onSuccess = { state -> intrinsicSize = state.painter.intrinsicSize },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(url, imageRect) {
                // ⚠️ 这里不能用 `detectTransformGestures`：它单指拖也会上报 pan 并
                // **吃掉**事件，`HorizontalPager` 就永远收不到横滑，左右切图直接失效。
                // 所以手写一遍：两指才缩放（并接管事件），单指只在已放大时平移，
                // 1x 下的单指拖动完全不消费，交给 Pager 翻页。
                awaitEachGesture {
                    // awaitEachGesture 的 lambda 本身就是 AwaitPointerEventScope，
                    // 不需要（也不能）再包一层 awaitPointerEventScope
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val pan = event.calculatePan()
                            zoom = newZoom
                            if (newZoom > 1f) {
                                offsetX = clampPan(offsetX + pan.x, newZoom, imageRect?.width ?: size.width.toFloat())
                                offsetY = clampPan(offsetY + pan.y, newZoom, imageRect?.height ?: size.height.toFloat())
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1 && zoom > 1f) {
                            val pan = event.calculatePan()
                            offsetX = clampPan(offsetX + pan.x, zoom, imageRect?.width ?: size.width.toFloat())
                            offsetY = clampPan(offsetY + pan.y, zoom, imageRect?.height ?: size.height.toFloat())
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(url, imageRect, zoom) {
                detectTapGestures { offset ->
                    // 放大状态下图片可能已经盖住整屏，这时点边缘退出太容易误触，
                    // 只保留 1x（全图可见）时的"点空白退出"。
                    val outside = imageRect == null || !imageRect.contains(offset)
                    if (zoom <= 1f && outside) onTapOutside()
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

/**
 * 把平移量夹在"放大后多出来的那部分"里，避免把图片拖出屏幕只剩一角。
 *
 * `graphicsLayer` 的缩放是绕中心做的，所以某个方向可平移的最大距离
 * 是 `(zoom - 1) * 原始长度 / 2`。
 */
private fun clampPan(value: Float, zoom: Float, unzoomedExtent: Float): Float {
    val max = (zoom - 1f) * unzoomedExtent / 2f
    return value.coerceIn(-max, max)
}

/**
 * `ContentScale.Fit` 之后图片在容器里占的矩形（居中）。
 *
 * 容器或图片尺寸还没量出来（首帧、加载中）时返回 null，
 * 调用方把"null"当作"没有图片区域"处理即可。
 */
private fun fitRect(container: IntSize, intrinsic: Size): Rect? {
    if (container.width <= 0 || container.height <= 0) return null
    if (intrinsic.width <= 0f || intrinsic.height <= 0f) return null
    val scale = min(
        container.width / intrinsic.width,
        container.height / intrinsic.height
    )
    val width = intrinsic.width * scale
    val height = intrinsic.height * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left = left, top = top, right = left + width, bottom = top + height)
}
