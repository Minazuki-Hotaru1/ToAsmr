package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.FluentIcon
import com.asmrone.app.ui.component.TopBarScrim
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 设置体系的**唯一**页面框架 —— 一级页（[SettingsScreen]）与全部二级页共用同一份实现
 * （与 comicApp 的 `SettingsScaffold` 对齐）。
 *
 * ```
 *  ┌ Box ──────────────────────────────────────────────┐
 *  │  Column                                           │
 *  │    标题栏：状态栏 + 6dp + ≥48dp（与全局顶栏等高）   │
 *  │            [可选返回按钮]  大标题(主色)  [actions] │
 *  │    内容区：左右 16dp、顶部 12dp、底部 112dp、可滚动 │
 *  │  TopBarScrim（滚动后顶部渐显毛玻璃）               │
 *  └───────────────────────────────────────────────────┘
 * ```
 *
 * 页面内的分组请一律使用 `FlatSection` / `FlatRow` / `FlatDivider`，
 * 行内控件用 `SettingSwitch` / [SettingsButton] 等小号组件。
 *
 * @param onBack 传 null 表示这是一级页（不显示返回按钮）
 * @param collapseBottomBarOnScroll 一级页才需要（二级页看不到底部导航栏）
 * @param scroll false 时不自带滚动容器，供内部使用 LazyColumn 的页面自行处理
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    collapseBottomBarOnScroll: Boolean = false,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollFraction = (scrollState.value / 200f).coerceIn(0f, 1f)

    if (collapseBottomBarOnScroll) {
        // 向下滚动时收起底部导航栏、向上滚动展开（可在「设置 → 外观」里关闭）
        CollapsibleBottomBarEffect { 0 to scrollState.value }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏：横向 16dp、纵向 2dp，行高钉在 48dp 触控标准上
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = rememberStatusBarTopDp())
                    .padding(horizontal = 16.dp, vertical = Liquid.TOP_BAR_VERTICAL_PADDING)
                    .heightIn(min = TouchTarget.minimum),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(TouchTarget.minimum)
                    ) {
                        FluentIcon(
                            res = R.drawable.ic_arrow_left_fluent,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                            // 触控区域保持 48dp，仅把可见图标稍微收小
                            size = 22.dp
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                actions()
            }

            if (scroll) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(Liquid.CONTENT_TOP_GAP))
                    content()
                    // 底部留白 — 防止 64dp 悬浮导航栏 + 导航栏 inset 遮挡
                    Spacer(Modifier.height(Liquid.CONTENT_BOTTOM_INSET_WITH_PLAYER))
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Spacer(Modifier.height(Liquid.CONTENT_TOP_GAP))
                    content()
                }
            }
        }

        TopBarScrim(scrollFraction = scrollFraction)
    }
}
