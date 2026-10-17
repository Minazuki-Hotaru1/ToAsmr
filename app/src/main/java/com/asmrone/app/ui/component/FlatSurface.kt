package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asmrone.app.R

/**
 * 扁平分组组件 —— 设置 / 收藏这类「内容展示」页面的统一骨架
 * （提炼自 comicApp 的 ui/component/FlatSurface.kt）。
 *
 * ## 为什么不用浮起的卡片
 *
 * 通用 Material 卡片（白底 + 圆角 + 投影）会让页面显得鼓、缺少层次，
 * 一屏里十几张卡堆起来也很普通。这里改成**扁平分组**：
 *  - 去掉投影，只用「白底 + 1dp 发丝描边 + 16dp 大圆角」划出分组；
 *  - 分组内用发丝分割线区分条目，而不是每条都做成一张卡；
 *  - 分组标题改成细体 + 字距的小标签，弱化层级、让内容本身成为重点。
 *
 * 这样内容面保持安静，画面上真正"浮起"的只有悬浮玻璃元素
 * （顶栏药丸、底部导航、迷你播放器），视觉重心不会和内容抢。
 */

/** 发丝描边用的分割线颜色：比 outline 更淡，避免在小圆角上显得生硬 */
@Composable
private fun hairlineColor() = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

/** 扁平分组容器：白底 + 发丝描边 + 16dp 圆角，**无投影**。 */
@Composable
fun FlatPlate(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, hairlineColor(), shape)
            .padding(contentPadding),
        content = content
    )
}

/** 分组标题：细体 + 字距，弱化的青绿，比粗体标题更轻 */
@Composable
fun FlatSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        modifier = modifier.padding(bottom = 8.dp)
    )
}

/** 分组 = 可选标题 + 扁平容器 */
@Composable
fun FlatSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) FlatSectionLabel(title)
        FlatPlate(contentPadding = contentPadding, content = content)
    }
}

/** 分组内的发丝分割线，左右按 [inset] 内缩，不顶到圆角上 */
@Composable
fun FlatDivider(inset: Dp = 16.dp, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = inset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    )
}

/**
 * 标准设置行：左侧「标题 + 说明」，右侧尾部控件（开关 / 箭头 / 自定义）。
 * [onClick] 非空时整行可点，涟漪会被外层的 FlatPlate 裁进圆角里。
 * [enabled] = false 时标题与说明变淡（用于依赖其它开关的行）。
 */
@Composable
fun FlatRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val titleColor = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.5f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 设置行右侧的统一「›」箭头（Fluent 图标） */
@Composable
fun RowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right_fluent),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 设置页开关的统一配色 */
@Composable
fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.primary
)

/** 设置页开关（带统一配色的便捷封装） */
@Composable
fun SettingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = settingsSwitchColors()
    )
}
