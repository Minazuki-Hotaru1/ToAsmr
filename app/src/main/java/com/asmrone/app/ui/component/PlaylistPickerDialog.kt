package com.asmrone.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R

/** 可勾选的分组（收藏夹 / 播放列表） */
data class PickableGroup(
    val id: String,
    val name: String,
    val count: Int
)

/**
 * 收藏夹 / 播放列表选择弹窗
 * （提炼自 comicApp 的 `FavoriteFolderDialog`，语义换成音声场景的「收藏夹 / 播放列表」）。
 *
 * 交互与 comicApp 保持一致：勾选是**临时集合**，只有「确认」才写回，「取消」直接丢弃；
 * 右上角可以就地新建，新建成功后自动勾选。
 */
@Composable
fun PlaylistPickerDialog(
    title: String = "选择收藏夹",
    groups: List<PickableGroup>,
    initialChecked: (String) -> Boolean,
    onConfirm: (Set<String>) -> Unit,
    onCreateGroup: (String) -> String?,
    onDismiss: () -> Unit,
    itemUnit: String = "部"
) {
    var showCreate by remember { mutableStateOf(false) }
    var checkedIds by remember {
        mutableStateOf(groups.filter { initialChecked(it.id) }.map { it.id }.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showCreate = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_fluent),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("新建", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            if (groups.isEmpty()) {
                Text(
                    text = "还没有收藏夹，点击右上角新建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        val checked = group.id in checkedIds
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    checkedIds = if (checked) checkedIds - group.id
                                    else checkedIds + group.id
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        checkedIds = if (it) checkedIds + group.id
                                        else checkedIds - group.id
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${group.count} $itemUnit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checkedIds) }) {
                Text("确认", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )

    // 新建分组：命名弹窗
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("新建收藏夹", color = MaterialTheme.colorScheme.onBackground) },
            text = {
                CompactSearchField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "收藏夹名称"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        val newId = onCreateGroup(trimmed)
                        if (newId != null) checkedIds = checkedIds + newId
                        showCreate = false
                    }
                }) { Text("创建", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}
