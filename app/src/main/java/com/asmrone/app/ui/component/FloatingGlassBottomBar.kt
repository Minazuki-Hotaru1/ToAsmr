package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.asmrone.app.ui.theme.Liquid
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 悬浮玻璃导航栏的一个标签项 */
data class GlassTabItem(
    val route: String,
    val label: String,
    @param:DrawableRes val selectedIcon: Int,
    @param:DrawableRes val unselectedIcon: Int,
    /** 图标右上角的「有新版本」圆点（目前只有「设置」会用到） */
    val badge: Boolean = false
)

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

/**
 * 按 Backdrop 示例 `LiquidBottomTabs` 移植的底部导航栏（与 comicApp 同一份实现）。
 *
 * 结构和参数尽量与示例保持一致：
 *  - 底栏 64dp 高，4dp 内边距，`vibrancy + blur(8dp) + lens(24dp, 24dp)`；
 *  - 选中胶囊 56dp 高，使用 `rememberCombinedBackdrop(页面, 底栏)` 做嵌套玻璃；
 *  - 拖动时 `DampedDragAnimation` 的 `pressedScale = 78/56`，胶囊会放大；
 *  - 拖动速度驱动横向拉伸 / 纵向压缩；
 *  - 松手后按目标索引吸附，并通知导航切页。
 *
 * 与示例的差异只有内容与配色适配：图标/文字换成 ToAsmr 的四个标签，
 * 容器色使用主题 surface（40%）。
 *
 * 调用方需要在内容层挂 `Modifier.layerBackdrop(backdrop)`。
 */
@Composable
fun FloatingGlassBottomBar(
    tabs: List<GlassTabItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    if (tabs.isEmpty()) return

    val cs = MaterialTheme.colorScheme
    val isLightTheme = cs.background.luminanceIsDark().not()
    val accentColor = cs.primary
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = cs.surface.copy(alpha = 0.4f)

    val selectedIndex = remember(currentRoute, tabs) {
        tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { currentIndex = selectedIndex }

    // DampedDragAnimation 会被 remember 缓存；回调里不能直接捕获 selectedIndex，
    // 否则会一直拿首次进入页面时的旧值，拖回首次页面时误判为"没换页"。
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val tabsBackdrop = rememberLayerBackdrop()
    val pillBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else {
        null
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val animationScope = rememberCoroutineScope()
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabs.size
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val dampedDragAnimation = remember(animationScope, tabs.size) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabs.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = Liquid.BOTTOM_PILL_PRESSED_SCALE,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabs.lastIndex)
                    currentIndex = targetIndex
                    // 用最新路由值判断，避免 remember 缓存了首次页面索引
                    if (targetIndex != latestSelectedIndex) {
                        latestOnSelect(tabs[targetIndex].route)
                    }
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabs.size - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    // 只负责胶囊动画；真正切页在点击/拖动松手处触发，避免外部路由变化时重复导航
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
        }

        // 基础栏：所有标签的常驻图标/文字
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(8f.dp.toPx())
                                lens(24f.dp.toPx(), 24f.dp.toPx())
                            },
                            layerBlock = {
                                val progress = dampedDragAnimation.pressProgress
                                val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                    } else {
                        Modifier.background(containerColor, Capsule())
                    }
                )
                .height(Liquid.BOTTOM_BAR_HEIGHT)
                .fillMaxWidth()
                .padding(Liquid.BOTTOM_BAR_INSET),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                GlassBottomTab(
                    onClick = {
                        currentIndex = index
                        if (index != selectedIndex) onSelect(tab.route)
                    }
                ) {
                    TabIcon(
                        selected = index == currentIndex,
                        tab = tab,
                        tint = contentColor
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        maxLines = 1
                    )
                }
            }
        }

        // 隐藏层：把标签内容录进 tabsBackdrop，供选中胶囊做"放大镜"取样
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { Capsule() },
                                effects = {
                                    val progress = dampedDragAnimation.pressProgress
                                    vibrancy()
                                    blur(8f.dp.toPx())
                                    lens(
                                        24f.dp.toPx() * progress,
                                        24f.dp.toPx() * progress
                                    )
                                },
                                highlight = {
                                    val progress = dampedDragAnimation.pressProgress
                                    Highlight.Default.copy(alpha = progress)
                                },
                                onDrawSurface = { drawRect(containerColor) }
                            )
                        } else {
                            Modifier.background(containerColor, Capsule())
                        }
                    )
                    .height(Liquid.BOTTOM_PILL_HEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = Liquid.BOTTOM_BAR_INSET)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    // 隐藏层只负责给选中胶囊提供取样内容，不能带 clickable，
                    // 否则它会盖在真实标签上方吞掉所有点击。
                    GlassBottomTabVisual {
                        TabIcon(
                            selected = index == currentIndex,
                            tab = tab,
                            tint = contentColor
                        )
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

        // 选中胶囊：用户拖动/按压时按 pressedScale = 78/56 放大
        Box(
            Modifier
                .padding(horizontal = Liquid.BOTTOM_BAR_INSET)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(dampedDragAnimation.modifier)
                .then(
                    if (pillBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = pillBackdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            shadow = {
                                Shadow(alpha = dampedDragAnimation.pressProgress)
                            },
                            innerShadow = {
                                val progress = dampedDragAnimation.pressProgress
                                InnerShadow(radius = 8f.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    if (isLightTheme) Color.Black.copy(0.1f)
                                    else Color.White.copy(0.1f),
                                    alpha = 1f - progress
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            }
                        )
                    } else {
                        Modifier.background(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            Capsule()
                        )
                    }
                )
                .height(Liquid.BOTTOM_PILL_HEIGHT)
                .fillMaxWidth(1f / tabs.size)
        )
    }
}

/**
 * 标签图标 + 可选的「有新版本」圆点。
 *
 * 抽成组件是因为它要在两层里各画一遍：可见层和给选中胶囊做取样用的隐藏层。
 * 两层的图标必须像素级一致，否则放大镜里会看到"没有圆点"的旧画面。
 */
@Composable
private fun TabIcon(
    selected: Boolean,
    tab: GlassTabItem,
    tint: Color
) {
    Box(contentAlignment = Alignment.Center) {
        FluentIcon(
            res = if (selected) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = tab.label,
            tint = tint,
            size = 24.dp
        )
        if (tab.badge) {
            // 贴在图标右上角外侧一点：24dp 图标 + 8dp 圆点若完全内切会压住线条
            UpdateBadgeDot(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp),
                size = 8.dp,
                ring = true
            )
        }
    }
}

@Composable
private fun RowScope.GlassBottomTab(
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier = Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
/**
 * 隐藏取样层专用的标签内容：与 [GlassBottomTab] 同样的布局和缩放，
 * 但**没有 clickable / pointerInput**，不会拦截真实标签的点击。
 */
@Composable
private fun RowScope.GlassBottomTabVisual(
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
