package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Elevation
import com.asmrone.app.ui.theme.Glass
import com.asmrone.app.ui.theme.Sizes
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * ─────────────────────────────────────────────────────────────
 * 液态玻璃材质套件（对齐 comicApp 2026-10-14 重做的新配方）
 *
 * 相比上一版（模糊 26dp / 噪点 0.05 / 1dp 渐变高光描边），新版：
 *  - **轻模糊**：真实背景模糊半径降到 12dp —— 玻璃要"透"，糊成一片就失去玻璃感；
 *  - **半透明色纱**：surface 约 60% 不透明度（参考实现 #F2F2F7 @ 63%）；
 *  - **发丝描边**：0.8dp 纯白（浅色主题 40% / 深色主题 20%），这是观感变精致的关键；
 *  - **去噪点**、投影收敛到 8dp。
 *
 * 参数全部来自 `ui/theme/Tokens.kt` 的 [Glass]，想整体调风格只改那一处。
 * ─────────────────────────────────────────────────────────────
 */

/**
 * 顶部「渐变模糊」底衬 —— 全应用统一：二级页面的悬浮顶栏、主页面的透明顶栏都用它。
 *
 * 用「普通模糊 + 竖直渐变遮罩」实现：顶部模糊最强、向下渐隐到无，
 * 比 Haze 的 progressive 更平滑可控，性能开销也可以忽略。
 * 挂着它的这一层要位于**内容之上、标题/药丸之下**。
 */
fun Modifier.topGradientBlur(hazeState: HazeState): Modifier = hazeEffect(state = hazeState) {
    blurRadius = Glass.topBlurRadius
    noiseFactor = 0f
    mask = Brush.verticalGradient(
        colors = listOf(
            Color.Black,                       // 顶部：模糊最强
            Color.Black.copy(alpha = 0.5f),
            Color.Transparent                  // 底部：完全不模糊
        )
    )
}

/**
 * 发丝描边的颜色：浅色主题白色 40%、深色主题白色 20%。
 *
 * 按**页面背景亮度**判断深浅色，而不是 `isSystemInDarkTheme()` ——
 * 这样应用内的主题切换（跟随系统 / 强制亮色 / 强制暗色）同样生效。
 */
@Composable
fun glassHairline(): Color {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    return Color.White.copy(alpha = if (dark) Glass.HAIRLINE_DARK else Glass.HAIRLINE_LIGHT)
}

/**
 * 全应用统一的「液态玻璃」材质 —— 底部导航栏、二级页面顶部栏、迷你播放器共用。
 *
 *  - 有 [hazeState] 时：用 Haze 取下方内容做**真实背景模糊** + 半透明色纱；
 *  - 没有时：退化为半透明实底（不模糊）；
 *  - 最后叠 0.8dp 发丝描边与投影。
 *
 * 注意：使用 [hazeState] 时，被取样的内容需要挂 `Modifier.hazeSource(state)`。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    hazeState: HazeState?,
    elevation: Dp = Elevation.glass,
    tintAlpha: Float = Glass.TINT_ALPHA,
    blurRadius: Dp = Glass.blurRadius
): Modifier {
    val cs = MaterialTheme.colorScheme
    val hairline = glassHairline()
    // 注意：这里必须显式从 Modifier 起头，不能用隐式接收者（`this`），
    // 否则会把调用方传入的 Modifier 再串一次（内边距/inset 被重复应用）。
    val filled = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(cs.surface.copy(alpha = tintAlpha)),
                blurRadius = blurRadius,
                noiseFactor = Glass.NOISE
            )
        )
    } else {
        Modifier.background(cs.surface.copy(alpha = Glass.FALLBACK_ALPHA), shape)
    }
    return this
        .shadow(elevation = elevation, shape = shape, clip = false)
        .clip(shape)
        .then(filled)
        .border(width = Glass.borderWidth, color = hairline, shape = shape)
}

/**
 * 圆形玻璃药丸按钮（返回 / 收藏 / 刷新等），默认 44dp。
 *
 * 与 [glassSurface] 的差别：**不使用 `shadow`**。
 * 诊断结论：小尺寸节点上再叠一层 shadow 的图形层，会在图标周围留下白色渲染杂讯
 * （去掉玻璃即消失）。所以圆形按钮只用「圆形裁剪 → Haze 真实模糊 → 发丝描边」实现：
 *  - `blurredEdgeTreatment = Unbounded`：避免圆形边缘的模糊被截断；
 *  - 显式给 `fallbackTint`：系统不支持模糊时退化为半透明玻璃底，而不是白底；
 *  - 图标作为兄弟节点单独绘制，不参与玻璃层。
 *
 * 图标为 Fluent 矢量图资源（`@DrawableRes`）。
 */
@Composable
fun GlassCircleButton(
    hazeState: HazeState?,
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 与顶栏标题药丸共用同一个高度 Token，保证两者永远等高
    size: Dp = Sizes.TOP_BAR_CONTROL,
    tint: Color = MaterialTheme.colorScheme.onBackground
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 第一层：玻璃本体（不含内容，也不叠 shadow）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Color.Transparent,
                                tint = HazeTint(cs.surface.copy(alpha = Glass.PILL_TINT_ALPHA)),
                                blurRadius = Glass.pillBlurRadius,
                                noiseFactor = Glass.NOISE,
                                fallbackTint = HazeTint(cs.surface.copy(alpha = Glass.FALLBACK_ALPHA))
                            )
                        ) { blurredEdgeTreatment = BlurredEdgeTreatment.Unbounded }
                    } else {
                        Modifier.background(cs.surface.copy(alpha = Glass.FALLBACK_ALPHA), CircleShape)
                    }
                )
                .border(width = Glass.borderWidth, color = glassHairline(), shape = CircleShape)
        )
        // 第二层：图标（独立于玻璃层）
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 底色偏暗判定（0.299 / 0.587 / 0.114 是常用的亮度近似权重） */
internal fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
