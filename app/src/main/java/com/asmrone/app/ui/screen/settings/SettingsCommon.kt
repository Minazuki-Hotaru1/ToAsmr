package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.component.FlatPlate
import com.asmrone.app.ui.component.FluentIcon
import com.asmrone.app.R

/**
 * 设置页共用的小组件 —— 单独放一个文件，避免它们挂在某个页面里，
 * 页面一改写就会把别处的引用一起删掉（与 comicApp 的 `SettingsCommon.kt` 对齐）。
 *
 * ## 行高约定
 *
 * 一级页（[SettingsScreen]）的行由 `FlatRow` 自带内边距：**横向 16dp、纵向 14dp**，
 * 于是单行内容高度约 48dp（正好是 `TouchTarget` 的标准）。
 * 二级页里凡是「自己画行」的地方（说明文字、折叠选项、自定义卡片内容）都要用
 * [SettingsRowPadding]，行高才能和一级页对齐。
 */
internal val SettingsRowPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/**
 * 设置分组容器 —— 供「行内容完全自定义」的页面使用（如服务器预设、同步状态卡）。
 *
 * 样式与一级页的分组完全一致：白底 + 发丝描边 + 16dp 圆角、无投影；
 * 纵向内边距同样取 14dp（与 `FlatRow` 对齐），组间距 24dp。
 *
 * 只需要「标题 + 说明 + 尾部控件」这类标准行的页面，请直接用 `FlatSection` /
 * `FlatRow` / `FlatDivider`（行自带 16dp/14dp 内边距与发丝分割线）。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    FlatPlate(
        modifier = modifier.padding(bottom = 24.dp),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * 设置页统一的小号单选按钮（作为 `FlatRow` 的 leading 使用）。
 *
 * M3 的 `RadioButton` 在布局上占 48dp，直接放进行首会把标题推得太远；
 * 这里把它的**布局尺寸**收到 20dp（视觉不变）。
 */
@Composable
internal fun SettingsRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    RadioButton(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.size(20.dp)
    )
}

/** 设置页统一的小号填充按钮（比 Material 默认矮一档，适合放在行内/卡片内的操作区） */
@Composable
internal fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        enabled = enabled,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 14.dp),
        content = content
    )
}

/** 设置页统一的小号描边按钮 */
@Composable
internal fun SettingsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        content = content
    )
}

/** 设置页统一的小号图标按钮（视觉 40dp，M3 仍会保证 48dp 的触点） */
@Composable
internal fun SettingsIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp), content = content)
}

/** 可点行的尾部箭头（Fluent 版，与 `FlatSurface.kt` 里的同名组件一致） */
@Composable
internal fun SettingsRowChevron() {
    FluentIcon(
        res = R.drawable.ic_chevron_right_fluent,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
