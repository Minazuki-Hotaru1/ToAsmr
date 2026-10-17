package com.asmrone.app.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.RatingStar
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 「收藏状态」选择弹窗 —— 对齐参考实现 KikoFlu 的 `ReviewProgressDialog`
 * （testAsmrOne/lib/src/widgets/review_progress_dialog.dart）。
 *
 * 参考实现是一个底部弹窗（竖屏）/ 居中对话框（横屏），左侧三个状态、右侧两个状态，
 * 右下角一个「移除」。本项目按同一套信息结构做了扁平化：
 * 五种状态竖排 + 星级评分 + 底部「移除标记」/「取消」/「确认」。
 *
 * 与参考实现一致的两个行为细节：
 *  1. **勾选是临时的**，只有点「确认」才提交（和 PlaylistPickerDialog 的思路一致）；
 *  2. **只有在已有标记时才显示「移除」** —— 没标记过的作品没有东西可移除。
 *
 * @param currentStatus 当前状态，null = 未标记
 * @param currentRating 当前用户评分（1~5），null = 未评分
 * @param onConfirm 确认回调；status 为 null 表示移除标记
 */
@Composable
fun CollectionStatusDialog(
    title: String,
    currentStatus: CollectionStatus?,
    currentRating: Int?,
    onConfirm: (status: CollectionStatus?, rating: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedStatus by remember { mutableStateOf(currentStatus) }
    var selectedRating by remember { mutableStateOf(currentRating) }
    val hasExistingMark = currentStatus != null || currentRating != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // ── 五种收藏状态 ───────────────────────────
                CollectionStatus.entries.forEach { status ->
                    val selected = selectedStatus == status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            // 点已选中的状态 = 取消该选择（回到"未标记"）
                            .clickable {
                                selectedStatus = if (selected) null else status
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(status.iconRes),
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = status.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_fluent),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(10.dp))

                // ── 用户评分（1~5 星，再点一次同一颗星可清除）──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "我的评分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedRating != null) {
                        Text(
                            text = "$selectedRating / 5",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..5).forEach { star ->
                        val filled = selectedRating != null && star <= selectedRating!!
                        Icon(
                            painter = painterResource(
                                if (filled) R.drawable.ic_star_fluent
                                else R.drawable.ic_star_outline_fluent
                            ),
                            contentDescription = "$star 星",
                            tint = if (filled) RatingStar
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    // 再点同一颗 = 取消评分
                                    selectedRating = if (selectedRating == star) null else star
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedStatus, selectedRating) },
                enabled = selectedStatus != null || selectedRating != null
            ) { Text("确认") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 移除标记：只有已经标记过才出现（与参考实现一致）
                if (hasExistingMark) {
                    TextButton(
                        onClick = { onConfirm(null, null) },
                        modifier = Modifier.heightIn(min = TouchTarget.compact)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_fluent),
                            contentDescription = null,
                            tint = ErrorColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("移除", color = ErrorColor)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 收藏状态的只读小角标（列表卡片右下角用）。
 *
 * 复用全应用的 [SmallTagChip]，颜色语义与状态一致：
 * 想听/重听偏中性，在听用主色（"正在进行"），听过了用成功色。
 */
@Composable
fun CollectionStatusChip(status: CollectionStatus) {
    SmallTagChip(
        text = status.label,
        tone = when (status) {
            CollectionStatus.LISTENING -> TagTone.Primary
            CollectionStatus.LISTENED -> TagTone.Download
            else -> TagTone.Neutral
        }
    )
}
