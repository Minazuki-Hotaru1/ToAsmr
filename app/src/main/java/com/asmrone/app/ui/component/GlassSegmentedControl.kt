package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.sin

/** 液态玻璃分段控件里的一段 */
data class GlassSegment(
    val id: String,
    /** 文字（同时也是图标模式下的无障碍描述） */
    val label: String,
    /**
     * 图标资源；**给了图标就只显示图标**（首页顶栏那种"纯图形"切换器用它），
     * 为 null 时回退为显示 [label] 文字。
     */
    @param:DrawableRes val iconRes: Int? = null
)

/**
 * 悬浮「液态玻璃」分段切换器（首页三个板块用它切换）—— 本次随材质整体重写。
 *
 * 与底部导航栏同一套材质与动效语言：
 *  - 外壳走 [backdropButton]（`vibrancy + blur + lens` + 半透明色纱）；
 *  - 选中段下方是一枚**液态胶囊**：位移 320ms easeOutCubic，同时按 `sin(πt)`
 *    做体积拉伸（+28%）与上下挤压（各 2.5dp）—— 切换时像一滴液体被拉过去；
 *  - 胶囊是**独立的一层**，画在文字之下；选中/未选中的内容色平滑过渡。
 *
 * 只做点击（不做拖动）：这是顶栏上的小控件，点击语义已经足够，也不与页面横滑冲突。
 *
 * @param backdrop 背景取样源；为 null 时退化为半透明实底 + 发丝描边
 */
@Composable
fun GlassSegmentedControl(
    segments: List<GlassSegment>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    if (segments.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pillShape = RoundedCornerShape(percent = 50)
    val isLightTheme = cs.background.luminanceIsDark().not()

    // 每一段在内容行坐标系里的实际边界：胶囊定位用
    val itemBounds = remember { mutableStateListOf<Rect>() }
    val selectedIndex = remember(selectedId, segments) {
        segments.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    }

    // 与底部导航栏一致的位移进度：位置套 easeOutCubic，拉伸直接用 sin(πt)
    val progress = remember { Animatable(1f) }
    var animFrom by remember { mutableFloatStateOf(0f) }
    var animTo by remember { mutableFloatStateOf(0f) }
    var pillReady by remember { mutableStateOf(false) }

    fun currentCenter(): Float {
        val t = progress.value
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)   // easeOutCubic
        return animFrom + (animTo - animFrom) * eased
    }

    // 首次测量 / 选中段变化：驱动胶囊位移
    LaunchedEffect(selectedIndex, itemBounds.size) {
        val bounds = itemBounds.getOrNull(selectedIndex) ?: return@LaunchedEffect
        val target = bounds.left + bounds.width / 2f
        if (!pillReady) {
            animFrom = target
            animTo = target
            progress.snapTo(1f)
            pillReady = true
            return@LaunchedEffect
        }
        if (animTo == target) return@LaunchedEffect
        animFrom = currentCenter()
        // 先把终点设为起点，snapTo(0) 的那一帧才不会闪到目标位置
        animTo = animFrom
        progress.snapTo(0f)
        animTo = target
        progress.animateTo(1f, tween(SegmentPillMoveMs))
    }

    Row(
        modifier = modifier.backdropButton(backdrop = backdrop, shape = pillShape)
    ) {
        Box(modifier = Modifier.padding(SegmentContainerPadding)) {
            // ── 液态胶囊（画在文字之下）──
            val bounds0 = itemBounds.firstOrNull()
            if (bounds0 != null && pillReady) {
                val stretch = sin(PI * progress.value).toFloat()
                val squashPx = with(density) { SegmentPillSquash.toPx() } * stretch
                val pillScaleY =
                    if (bounds0.height > 0f) (bounds0.height - squashPx * 2f) / bounds0.height else 1f
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = currentCenter() - bounds0.width / 2f
                            scaleX = 1f + SegmentPillStretch * stretch
                            scaleY = pillScaleY
                        }
                        .size(
                            width = with(density) { bounds0.width.toDp() },
                            height = with(density) { bounds0.height.toDp() }
                        )
                        .clip(pillShape)
                        // 与底部导航栏同一套中性胶囊：浅色黑 10%、深色白 10%（不抢主色）
                        .background(
                            if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                            else Color.White.copy(alpha = 0.1f),
                            pillShape
                        )
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                segments.forEachIndexed { index, segment ->
                    val selected = index == selectedIndex
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) cs.primary else cs.onSurfaceVariant,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                        label = "segmentColor"
                    )
                    val iconRes = segment.iconRes
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInParent()
                                if (index < itemBounds.size) itemBounds[index] = b
                                else itemBounds.add(b)
                            }
                            .clip(pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(segment.id) }
                            )
                            // 图标段留稍大的点击区（图标本身不占文字那样的宽度）
                            .padding(
                                horizontal = if (iconRes != null) 11.dp else 10.dp,
                                vertical = if (iconRes != null) 7.dp else 6.dp
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (iconRes != null) {
                            FluentIcon(
                                res = iconRes,
                                contentDescription = segment.label,
                                tint = contentColor,
                                size = 20.dp
                            )
                        } else {
                            Text(
                                text = segment.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 玻璃外框与内容行之间的内边距（坐标换算依赖它，所以提成常量） */
private val SegmentContainerPadding = 4.dp

/** 胶囊位移动画时长 */
private const val SegmentPillMoveMs = 320

/** 胶囊拉伸峰值（宽度最多放大到 1.28 倍） */
private const val SegmentPillStretch = 0.28f

/** 胶囊上下挤压量 */
private val SegmentPillSquash = 2.5.dp
