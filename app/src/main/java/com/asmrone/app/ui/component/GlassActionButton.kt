package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Liquid
import com.kyant.backdrop.Backdrop

/**
 * 「液态玻璃」主操作按钮（胶囊形态）—— 全应用统一的玻璃动作按钮。
 *
 * 材质使用 Backdrop 的 `vibrancy + blur + lens`，并带按压回弹：
 * 按下时整体缩到 0.97，折射会跟随形变同步反算，不会出现"背景没动、玻璃动了"的错位。
 * 当前使用方：
 *  - 详情页的「开始播放」；
 *  - 设置 → 液态玻璃页的「液态玻璃」样例。
 *
 * 调用方需要在页面内容层挂 `Modifier.layerBackdrop(backdrop)`。
 */
@Composable
fun GlassActionButton(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Liquid.ACTION_PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 500f),
        label = "glassActionScale"
    )

    Row(
        modifier = modifier
            .backdropButton(
                backdrop = backdrop,
                shape = LiquidCapsule,
                scaleX = scale,
                scaleY = scale
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FluentIcon(
            res = iconRes,
            contentDescription = null,
            tint = contentColor,
            size = 20.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}
