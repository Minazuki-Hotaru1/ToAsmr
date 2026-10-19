package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.GlassStylePrefs
import com.asmrone.app.ui.theme.Liquid
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/**
 * ═══════════════════════════════════════════════════════════
 * 液态玻璃材质内核（与 comicApp 的 `GlassSurface.kt` 同一份实现）
 *
 * 材质由 Backdrop 提供：
 *  - `vibrancy()`        —— 让底下的颜色透上来更"鲜活"
 *  - `blur(dp)`          —— 轻模糊，玻璃要"透"，糊成一片就失去玻璃感
 *  - `lens(h, amount)`   —— **边缘折射**，这是"液态"的关键；可开启色散
 *  - `onDrawSurface`     —— 半透明色纱（默认 surface @ 38%）
 *
 * 所有数值来自 [GlassStylePrefs]（设置 → 外观 → 液态玻璃可实时调），
 * 所以全应用的玻璃永远保持一致。
 *
 * ⚠️ 使用前提：被取样的页面内容层要挂
 * `Modifier.layerBackdrop(backdrop)`，玻璃元素再把同一个 backdrop 传给 `drawBackdrop`。
 * ═══════════════════════════════════════════════════════════
 */

/**
 * 液态玻璃按钮的统一配方。
 *
 * 顶栏标题胶囊、返回/刷新圆钮、详情页「开始播放」、迷你播放器都用这一份：
 *  - 有 backdrop：`vibrancy + blur + lens` + 半透明色纱
 *  - 没有 backdrop（如弹窗、未挂取样层的页面）：退化为半透明实底 + 0.8dp 白描边
 *
 * @param scaleX/scaleY 按压缩放。传进来时玻璃层自己会反向变换，
 *        所以折射不会因为缩放而错位。
 */
@Composable
fun Modifier.backdropButton(
    backdrop: Backdrop?,
    shape: Shape,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    scaleX: Float = 1f,
    scaleY: Float = 1f
): Modifier {
    val style by GlassStylePrefs.style.collectAsState()
    val fallbackBorder = Color.White.copy(alpha = 0.35f)
    return if (backdrop != null) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(style.blurRadius.dp.toPx())
                lens(
                    refractionHeight = style.refractionHeight.dp.toPx(),
                    refractionAmount = style.refractionAmount.dp.toPx(),
                    chromaticAberration = style.chromaticAberration
                )
            },
            layerBlock = if (scaleX != 1f || scaleY != 1f) {
                {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
            } else {
                null
            },
            onDrawSurface = { drawRect(surfaceColor.copy(alpha = style.surfaceAlpha)) }
        )
    } else {
        this
            .background(
                surfaceColor.copy(alpha = (style.surfaceAlpha + 0.45f).coerceAtMost(0.9f)),
                shape
            )
            .border(0.8.dp, fallbackBorder, shape)
    }
}

/**
 * 顶栏下方的柔和底色过渡（替代旧的「渐变模糊」）。
 *
 * 二级页的药丸本身已经提供了足够的可读性，所以用较低的不透明度保留封面；
 * 主页面的大标题没有玻璃底，使用更高的不透明度压住滚动内容。
 */
@Composable
fun Modifier.topBarFade(maxAlpha: Float = Liquid.TOP_FADE_ALPHA): Modifier {
    val background = MaterialTheme.colorScheme.background
    // 显式返回接收者，否则 matchParentSize 等布局修饰符会丢失
    return this.drawBehind {
        drawRect(
            Brush.verticalGradient(
                0.0f to background.copy(alpha = maxAlpha),
                0.55f to background.copy(alpha = maxAlpha * 0.45f),
                1.0f to Color.Transparent
            )
        )
    }
}

/**
 * 圆形液态玻璃按钮（返回 / 收藏 / 播放…），默认 48dp。
 *
 * 与 [backdropButton] 的区别在于**它自己处理按压动画**：
 * 整个玻璃圆盘轻微缩小到 0.94，`drawBackdrop` 的 layerBlock 会同步反向变换背景。
 * 加载中只替换内容（换成一枚 20dp 的进度圈），外框尺寸保持不变。
 *
 * ⚠️ 这里**不叠 `shadow`**：小尺寸节点上再叠一层阴影的图形层，
 * 会在图标周围留下白色渲染杂讯（comicApp 里记过同样一条坑）。
 */
@Composable
fun GlassCircleButton(
    backdrop: Backdrop?,
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Liquid.CONTROL_HEIGHT,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    loading: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Liquid.CIRCLE_PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "glassCircleScale"
    )

    Box(
        modifier = modifier
            .size(size)
            // 点击区域包在玻璃外层：整个圆盘都可点，不会被内部绘制层裁小
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !loading,
                onClick = onClick
            )
            .backdropButton(
                backdrop = backdrop,
                shape = CircleShape,
                surfaceColor = cs.surface,
                scaleX = scale,
                scaleY = scale
            ),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = cs.primary
            )
        } else {
            FluentIcon(
                res = iconRes,
                contentDescription = contentDescription,
                tint = tint,
                size = 22.dp
            )
        }
    }
}

/** 标题药丸 / 大按钮统一使用的胶囊形状。 */
val LiquidCapsule = Capsule()

/**
 * 底色偏暗判定（0.299 / 0.587 / 0.114 是常用的亮度近似权重）。
 *
 * 用**页面背景亮度**判断深浅色，而不是 `isSystemInDarkTheme()` ——
 * 这样应用内的主题切换（跟随系统 / 强制亮色 / 强制暗色）同样生效。
 */
internal fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
