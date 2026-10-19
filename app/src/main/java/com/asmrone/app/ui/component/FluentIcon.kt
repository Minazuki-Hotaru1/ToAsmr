package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的 Fluent UI System Icons 入口。
 *
 * 图标资源来自 `res/drawable/ic_*_fluent.xml`（MIT，Fluent UI System Icons）。
 * 用 Painter 而不是 Material `ImageVector`：整套图标都是自带的矢量图，
 * 不依赖 material-icons-extended（那会把 dex 体积撑起来），也方便统一尺寸与旋转。
 */
@Composable
fun FluentIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    rotation: Float = 0f
) {
    Icon(
        painter = painterResource(res),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation)
    )
}
