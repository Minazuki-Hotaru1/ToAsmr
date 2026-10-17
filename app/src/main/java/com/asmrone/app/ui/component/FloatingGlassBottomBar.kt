package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Elevation
import com.asmrone.app.ui.theme.Glass
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** 悬浮玻璃导航栏的一个标签项（图标为 Fluent 矢量图资源） */
data class GlassTabItem(
    val route: String,
    val label: String,
    // 显式用 @param: 目标，避免 Kotlin 对「构造参数 vs 属性」注解归属的告警
    @param:DrawableRes val selectedIconRes: Int,
    @param:DrawableRes val unselectedIconRes: Int
)

/**
 * 悬浮「液态玻璃」底部导航栏。
 *
 * 玻璃材质与二级页面顶部栏共用 [glassSurface]（真实背景模糊 + 半透明色纱 + 发丝描边）；
 * 选中态则按「液态玻璃」参考实现的配方做了**液态胶囊**：
 *
 *  - 胶囊是**独立的一层**，位于图标/文字之下，随选中项移动；
 *  - 位移 [Glass.PILL_MOVE_MS]ms easeOutCubic，同时按 `sin(πt)` 做**体积拉伸**
 *    （宽度峰值 +[Glass.PILL_STRETCH]）与上下挤压（各 [Glass.PILL_SQUASH]），
 *    于是切换时像一滴液体被"拉"过去，而不是硬跳；
 *  - 选中图标额外做 [Glass.ICON_SCALE_MS]ms easeOutBack 的 [Glass.ICON_SELECTED_SCALE] 倍放大；
 *  - **拖动**：手指按住横向滑动时胶囊零延迟跟手（宽度不变）、经过哪一项就预览高亮哪一项；
 *    **松手才真正换页**并按等分吸附回去（无 fling、无橡皮筋）。
 *
 * @param tabs 标签项
 * @param currentRoute 当前选中的路由
 * @param onSelect 点击 / 拖动松手后的切换回调
 * @param hazeState 背景模糊用的 HazeState；为 null 时退化为普通半透明底（不模糊）
 */
@Composable
fun FloatingGlassBottomBar(
    tabs: List<GlassTabItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val cs = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(percent = 50)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 每项在内容行坐标系里的实际边界：拖动命中判定与胶囊定位都用它
    val itemBounds = remember { mutableStateListOf<Rect>() }
    // 拖动回调里必须读到最新值
    val latestTabs by rememberUpdatedState(tabs)
    val latestOnSelect by rememberUpdatedState(onSelect)

    val selectedIndex = remember(currentRoute, tabs) {
        tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    }

    /**
     * 胶囊**当前应处**的项 —— 与「当前页面」分开计数。
     *
     * 为什么不直接绑 [selectedIndex]：换页是异步生效的（导航要一两帧才回到
     * `currentRoute`）。松手瞬间若用旧的 selectedIndex 驱动动画，胶囊会先闪回原项、
     * 等路由更新后再跳过去。所以：
     *  - 平常（含点击、页面切换完成后）它与页面保持一致；
     *  - 拖动松手时由拖动结果**立即**决定，动画只绑它，页面随后跟上；
     *  - 页面切换真正完成后再由下面的 effect 拉回一致（这时两者本来就相等，是空操作）。
     */
    var pillIndex by remember { mutableIntStateOf(selectedIndex) }

    // ── 胶囊：位移进度（线性 0..1，位置再自己套 easeOutCubic，拉伸直接用 sin(πt)）──
    val progress = remember { Animatable(1f) }
    var animFrom by remember { mutableFloatStateOf(0f) }
    var animTo by remember { mutableFloatStateOf(0f) }
    var pillReady by remember { mutableStateOf(false) }
    // 松手瞬间的位置：让它成为下一次位移动画的起点，胶囊才不会先跳回旧位置
    var pendingFrom by remember { mutableStateOf<Float?>(null) }

    // 拖动状态：非空表示正在拖动，值为手指在内容行里的横坐标
    var dragX by remember { mutableStateOf<Float?>(null) }
    /**
     * 拖动中"手指所覆盖的项"。**只在真正与某项重叠时更新**：
     * 各项之间有 4dp 间隙，手指落在间隙（或边界抖动）时若回退到 pillIndex，
     * 原选中项的图标就会跟着亮一下 —— 这就是"每经过一项，第一页图标闪一下"的原因。
     * 所以间隙里保持上一次命中的项。
     */
    var dragPreview by remember { mutableStateOf<Int?>(null) }

    // 页面切换完成 / 非拖动情况下切页：让胶囊与页面保持一致
    LaunchedEffect(selectedIndex) {
        if (dragX == null) pillIndex = selectedIndex
    }

    fun targetCenter(index: Int): Float? =
        itemBounds.getOrNull(index)?.let { it.left + it.width / 2f }

    /** 当前胶囊中心（拖动时跟手，否则按进度插值） */
    fun currentCenter(): Float {
        val from = animFrom
        val to = animTo
        val t = progress.value
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)   // easeOutCubic
        return from + (to - from) * eased
    }

    suspend fun animatePillTo(target: Float) {
        animFrom = pendingFrom ?: currentCenter()
        pendingFrom = null
        // 先把终点设为起点，snapTo(0) 的那一帧才不会闪到目标位置
        animTo = animFrom
        progress.snapTo(0f)
        animTo = target
        progress.animateTo(1f, tween(Glass.PILL_MOVE_MS))
    }

    // 首次测量 / 胶囊目标项变化：驱动位移（**只绑 pillIndex**，不绑页面）
    LaunchedEffect(pillIndex, itemBounds.size) {
        val target = targetCenter(pillIndex) ?: return@LaunchedEffect
        if (!pillReady) {
            animFrom = target
            animTo = target
            progress.snapTo(1f)
            pillReady = true
            return@LaunchedEffect
        }
        if (dragX != null) return@LaunchedEffect      // 拖动期间由手势直接驱动
        if (animTo == target && pendingFrom == null) return@LaunchedEffect
        animatePillTo(target)
    }

    // 内容行相对玻璃外框的内边距。拖动回调拿到的是**外框**坐标，而各子项边界
    // （boundsInParent）与胶囊都是以内容行为原点的，所以要统一减掉它。
    val innerPadPx = with(density) { BarInnerPadding.toPx() }

    Row(
        modifier = modifier
            // 与二级页面顶部栏共用的玻璃材质
            .glassSurface(shape = pillShape, hazeState = hazeState, elevation = Elevation.glass)
    ) {
        // 内层内容行：pointerInput 与各子项同坐标系，boundsInParent() 可直接与手指坐标比较
        Box(
            modifier = Modifier
                .padding(horizontal = BarInnerPadding, vertical = BarInnerPadding)
                .pointerInput(Unit) {
                    // 手指横坐标落在哪一项上（含边界）；落在间隙返回 -1
                    fun hitIndex(x: Float): Int =
                        itemBounds.indexOfFirst { x >= it.left && x <= it.right }

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val x = offset.x - innerPadPx
                            dragX = x
                            hitIndex(x).takeIf { it >= 0 }?.let { dragPreview = it }
                        },
                        onHorizontalDrag = { change, _ ->
                            val x = change.position.x - innerPadPx
                            dragX = x
                            // 只有真正压在某一项上才更新预览；间隙里保持上一次，避免闪烁
                            hitIndex(x).takeIf { it >= 0 }?.let { dragPreview = it }
                        },
                        onDragEnd = {
                            val x = dragX
                            // 与高亮用同一套判定，保证"胶囊落点"和"高亮项"永远一致
                            val index = dragPreview ?: -1
                            dragX = null
                            dragPreview = null
                            // 松手位置作为动画起点，胶囊不会先跳回旧项
                            pendingFrom = x
                            if (index >= 0 && index != pillIndex) {
                                // 先**同步**决定胶囊去哪（动画只绑它），再换页。
                                // 导航是异步生效的，这样就不会出现"先闪回原项再跳过去"。
                                pillIndex = index
                                latestOnSelect(latestTabs[index].route)
                            } else {
                                // 没换项（松在同一项上，或落在间隙/按钮外）：
                                // 胶囊从松手位置弹回当前项
                                val target = targetCenter(pillIndex)
                                if (target != null) scope.launch { animatePillTo(target) }
                            }
                        },
                        onDragCancel = {
                            dragX = null
                            dragPreview = null
                            pendingFrom = null
                        }
                    )
                }
        ) {
            // ── 液态胶囊（画在图标文字之下）──
            val bounds0 = itemBounds.firstOrNull()
            if (bounds0 != null && pillReady) {
                val pillWidth = bounds0.width
                val pillHeight = bounds0.height
                // 位置优先级：手指（拖动中）→ 松手点（松手后、动画尚未启动的那一两帧）→ 动画插值。
                // 中间那一项必不可少：位移动画是在 LaunchedEffect 里启动的（组合之后才跑），
                // 若这里直接回退到 currentCenter()，松手后会先按**上一轮的旧位置**画一帧，
                // 表现就是"胶囊先闪回原项，再跳过去"。
                val center = dragX ?: pendingFrom ?: currentCenter()
                val stretch = if (dragX != null) 0f else sin(PI * progress.value).toFloat()
                val squashPx = with(density) { Glass.PILL_SQUASH.toPx() } * stretch
                val pillScaleY =
                    if (pillHeight > 0f) (pillHeight - squashPx * 2f) / pillHeight else 1f
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = center - pillWidth / 2f
                            scaleX = 1f + Glass.PILL_STRETCH * stretch
                            scaleY = pillScaleY
                        }
                        .size(
                            width = with(density) { pillWidth.toDp() },
                            height = with(density) { pillHeight.toDp() }
                        )
                        .clip(pillShape)
                        // 参考实现：浅色用黑 8%、深色用白 18% 的中性胶囊（不抢主色）
                        .background(
                            if (cs.background.luminanceIsDark()) {
                                Color.White.copy(alpha = 0.18f)
                            } else {
                                Color.Black.copy(alpha = 0.08f)
                            },
                            pillShape
                        )
                )
            }

            Row(
                modifier = Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    // 拖动时"预览"手指所在的项（间隙里保持上一次，避免闪烁）；
                    // 松手后立即跟随胶囊（页面稍后跟上）
                    val highlighted = index == (dragPreview ?: pillIndex)
                    val contentColor by animateColorAsState(
                        targetValue = if (highlighted) cs.primary else cs.onSurfaceVariant,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                        label = "glassTabColor"
                    )
                    // 选中图标轻微放大（easeOutBack 近似）
                    val iconScale by animateFloatAsState(
                        targetValue = if (highlighted) Glass.ICON_SELECTED_SCALE else 1f,
                        animationSpec = tween(Glass.ICON_SCALE_MS, easing = EaseOutBack),
                        label = "glassTabIcon"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInParent()
                                if (index < itemBounds.size) itemBounds[index] = b
                                else itemBounds.add(b)
                            }
                            .clip(pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    // 点击也先同步移动胶囊：页面随后跟上，观感更跟手
                                    pillIndex = index
                                    onSelect(tab.route)
                                }
                            )
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(if (highlighted) tab.selectedIconRes else tab.unselectedIconRes),
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
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

/** 玻璃外框与内容行之间的内边距（拖动坐标换算也依赖它，所以提成常量） */
private val BarInnerPadding = 6.dp

/** easeOutBack 的近似曲线（图标放大时带一点点回弹） */
private val EaseOutBack = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
