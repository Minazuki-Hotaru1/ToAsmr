package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatPlate
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.FlatSectionLabel
import com.asmrone.app.ui.component.LiquidCapsule
import com.asmrone.app.ui.component.LiquidSlider
import com.asmrone.app.ui.component.SettingSwitch
import com.asmrone.app.ui.component.backdropButton
import com.asmrone.app.ui.theme.GlassPreset
import com.asmrone.app.ui.theme.GlassStyle
import com.asmrone.app.ui.theme.GlassStylePrefs
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/**
 * 玻璃样式设置（与 comicApp 同一个页面）。
 *
 * 参数直接对应 Backdrop 的效果：模糊、折射高度、折射强度、表面不透明度、色散。
 * 拖动 Slider 时只更新内存状态（实时预览），松手后再写入 SharedPreferences，
 * 避免拖动过程中每一帧都做磁盘写入。
 */
@Composable
fun GlassSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val style by GlassStylePrefs.style.collectAsState()
    val activePreset = remember(style) { GlassPreset.fromStyle(style) }
    val previewBackdrop = rememberLayerBackdrop()

    fun set(newStyle: GlassStyle) = GlassStylePrefs.set(newStyle)
    fun persist(newStyle: GlassStyle) = GlassStylePrefs.persist(context, newStyle)

    SettingsScaffold(title = "液态玻璃", onBack = onBack) {
        GlassPreview(backdrop = previewBackdrop)

        Spacer(Modifier.height(10.dp))
        Text(
            text = "调整会立即应用到全应用的液态玻璃；拖动时实时预览，松手后保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatSectionLabel("预设", modifier = Modifier.weight(1f))
            TextButton(
                onClick = { GlassStylePrefs.reset(context) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "恢复默认",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        FlatPlate {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = preset == activePreset,
                            onClick = { GlassStylePrefs.applyPreset(context, preset) },
                            label = {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.16f
                                ),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        FlatSection("参数") {
            GlassSliderRow(
                title = "模糊半径",
                valueText = "%.1f dp".format(style.blurRadius),
                value = style.blurRadius,
                valueRange = 0f..24f,
                onValueChange = { set(style.copy(blurRadius = it)) },
                onValueChangeFinished = { persist(GlassStylePrefs.style.value) }
            )
            FlatDivider()
            GlassSliderRow(
                title = "折射高度",
                valueText = "%.1f dp".format(style.refractionHeight),
                value = style.refractionHeight,
                valueRange = 0f..24f,
                onValueChange = { set(style.copy(refractionHeight = it)) },
                onValueChangeFinished = { persist(GlassStylePrefs.style.value) }
            )
            FlatDivider()
            GlassSliderRow(
                title = "折射强度",
                valueText = "%.1f dp".format(style.refractionAmount),
                value = style.refractionAmount,
                valueRange = 0f..48f,
                onValueChange = { set(style.copy(refractionAmount = it)) },
                onValueChangeFinished = { persist(GlassStylePrefs.style.value) }
            )
            FlatDivider()
            GlassSliderRow(
                // 对用户暴露的是"透明度"：值越大越透。
                // 内部 surfaceAlpha 仍是"不透明度"，所以这里做 1 - x 映射。
                title = "表面透明度",
                valueText = "${((1f - style.surfaceAlpha) * 100).roundToInt()}%",
                value = 1f - style.surfaceAlpha,
                valueRange = 0.20f..0.80f,
                onValueChange = { set(style.copy(surfaceAlpha = 1f - it)) },
                onValueChangeFinished = { persist(GlassStylePrefs.style.value) }
            )
            FlatDivider()
            FlatRow(
                title = "色散",
                description = "玻璃边缘出现轻微彩边，关闭后更干净",
                trailing = {
                    SettingSwitch(
                        checked = style.chromaticAberration,
                        onCheckedChange = {
                            val newStyle = style.copy(chromaticAberration = it)
                            set(newStyle)
                            persist(newStyle)
                        }
                    )
                }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 实时预览：彩色背景 + 一枚玻璃胶囊。
 *
 * 背景层单独挂 `layerBackdrop`，玻璃胶囊作为它的兄弟节点，
 * 避免玻璃把自己也录进取样层。
 */
@Composable
private fun GlassPreview(backdrop: LayerBackdrop) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = cs.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8FD8CB),
                                Color(0xFFF0D0A6),
                                Color(0xFF7FA8D8)
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .offset(x = 26.dp, y = 20.dp)
                    .background(Color.White.copy(alpha = 0.28f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 28.dp, y = 24.dp)
                    .background(Color(0xFF0F5B54).copy(alpha = 0.24f), CircleShape)
            )
            Text(
                text = "LIQUID",
                modifier = Modifier.align(Alignment.CenterStart).offset(x = 22.dp, y = (-24).dp),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = "GLASS",
                modifier = Modifier.align(Alignment.CenterStart).offset(x = 54.dp, y = 22.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.55f)
            )
        }

        // 玻璃样品：位于取样层之上，实时应用全局参数
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(196.dp)
                .height(62.dp)
                .backdropButton(
                    backdrop = backdrop,
                    shape = LiquidCapsule
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "液态玻璃",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface
            )
        }
    }
}

/** 设置页里的滑杆行：标题 + 当前值 + LiquidSlider。 */
@Composable
private fun GlassSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            visibilityThreshold = ((valueRange.endInclusive - valueRange.start) / 1000f)
                .coerceAtLeast(0.001f),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
