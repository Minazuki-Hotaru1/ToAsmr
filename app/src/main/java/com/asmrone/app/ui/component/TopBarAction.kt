package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Liquid
import com.kyant.backdrop.Backdrop

/**
 * 主页面顶部操作区的统一按钮（首页刷新、搜索、收藏/订阅页的切换等）。
 *
 * 所有顶部动作共用同一套尺寸与排版：
 *  - 高度 40dp，胶囊形；
 *  - 图标 18dp，文字 labelMedium；
 *  - 图标 + 文字时左右 12dp，纯图标时 40×40；
 *  - 图标与文字间距 6dp。
 *
 * [glass] 为 true 时使用 Backdrop 液态玻璃（默认）；保留该参数是为了
 * 以后需要平面按钮时还能复用同一套排版。
 */
@Composable
fun TopBarAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    @DrawableRes iconRes: Int? = null,
    label: String? = null,
    contentDescription: String? = label,
    glass: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconRotation: Float = 0f
) {
    val cs = MaterialTheme.colorScheme
    val shape = LiquidCapsule
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "topBarActionScale"
    )

    var base = modifier
        .height(Liquid.ACTION_HEIGHT)
        .then(if (label == null) Modifier.width(Liquid.ACTION_HEIGHT) else Modifier)
        .clip(shape)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick
        )

    base = if (glass) {
        base.backdropButton(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = cs.surface,
            scaleX = scale,
            scaleY = scale
        )
    } else {
        base
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(cs.surface.copy(alpha = 0.62f))
    }

    if (label == null) {
        Box(modifier = base, contentAlignment = Alignment.Center) {
            if (iconRes != null) {
                FluentIcon(
                    res = iconRes,
                    contentDescription = contentDescription,
                    tint = tint,
                    size = 18.dp,
                    rotation = iconRotation
                )
            }
        }
    } else {
        Row(
            modifier = base
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (iconRes != null) {
                FluentIcon(
                    res = iconRes,
                    contentDescription = null,
                    tint = tint,
                    size = 18.dp,
                    rotation = iconRotation
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
