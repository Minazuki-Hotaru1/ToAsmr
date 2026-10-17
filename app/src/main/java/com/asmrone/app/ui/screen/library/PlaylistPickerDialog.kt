package com.asmrone.app.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.TouchTarget

/** 收藏弹窗的状态（由 [DetailViewModel] 持有，弹窗本身无状态） */
data class PlaylistPickerState(
    val playlists: List<Playlist> = emptyList(),
    /** 当前选中的收藏夹 id */
    val selectedId: String = "",
    val loading: Boolean = true,
    /** 这个作品当前是否已在选中的收藏夹里（决定按钮显示"加入"还是"移出"） */
    val workInside: Boolean = false,
    val checkingMembership: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** 新建收藏夹的输入框是否展开 */
    val creating: Boolean = false,
    val newName: String = "",
    val createError: String? = null
) {
    val selected: Playlist? get() = playlists.firstOrNull { it.id == selectedId }
}

/**
 * 「加入收藏夹」弹窗 —— 作品详情页点收藏按钮时弹出。
 *
 * 行为（对应需求）：
 *  - 列出我的收藏夹，**默认选中上次选过的那个**；第一次则选中「我标记的」；
 *  - 两个系统收藏夹（我标记的 / 我喜欢的）**不提供删除入口**，并明确标注；
 *  - 选中的收藏夹里已有这个作品时，按钮变成「移出」（避免重复加入）；
 *  - 支持新建收藏夹，并且新建后自动选中它。
 *
 * 与参考实现 `AddToPlaylistDialog` / `PlaylistAddWorksDialog` 的差异：
 * 参考实现是"多选收藏夹 + 一次提交"，这里按需求做成**单选 + 立即提交**，
 * 因为默认项要跟随"上次选择"，多选会让这个偏好失去意义。
 */
@Composable
fun PlaylistPickerDialog(
    workTitle: String,
    state: PlaylistPickerState,
    onSelect: (Playlist) -> Unit,
    onConfirm: () -> Unit,
    onStartCreate: () -> Unit,
    onNewNameChange: (String) -> Unit,
    onCreateConfirm: () -> Unit,
    onCancelCreate: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.creating) "新建收藏夹" else "收藏到") },
        text = {
            Column {
                if (state.creating) {
                    // ── 新建收藏夹 ──────────────────────────
                    Text(
                        text = "为自己的收藏夹起个名字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    CompactSearchField(
                        value = state.newName,
                        onValueChange = onNewNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "收藏夹名称"
                    )
                    state.createError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorColor
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "新建后会自动选中它，并把这部作品加入其中。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // ── 收藏夹列表 ──────────────────────────
                    Text(
                        text = workTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(10.dp))

                    if (state.loading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "正在获取收藏夹…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        state.playlists.forEach { playlist ->
                            val selected = playlist.id == state.selectedId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable(enabled = !state.submitting) { onSelect(playlist) }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (playlist.isSystem) R.drawable.ic_folder_fluent
                                        else R.drawable.ic_folder_open_fluent
                                    ),
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${playlist.worksCount} 部作品",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // 系统收藏夹标注出来，并且不给删除入口
                                if (playlist.isSystem) {
                                    SmallTagChip(text = "系统", tone = TagTone.Neutral)
                                    Spacer(Modifier.width(6.dp))
                                } else if (!state.submitting) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onDeletePlaylist(playlist) }
                                            .padding(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_delete_fluent),
                                            contentDescription = "删除收藏夹",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
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

                        if (state.playlists.isEmpty() && !state.loading) {
                            Text(
                                text = "还没有可用的收藏夹。正常情况下服务端会自带" +
                                    "「我标记的」与「我喜欢的」两个系统收藏夹。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onStartCreate,
                            enabled = !state.submitting,
                            modifier = Modifier.heightIn(min = TouchTarget.compact)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add_fluent),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("新建收藏夹")
                        }
                    }

                    state.error?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorColor
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.creating) {
                TextButton(
                    onClick = onCreateConfirm,
                    enabled = state.newName.isNotBlank() && !state.submitting
                ) { Text("创建并加入") }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = !state.loading && !state.submitting && state.selectedId.isNotBlank()
                ) {
                    Text(
                        when {
                            state.submitting -> "处理中…"
                            // 已在收藏夹里 → 按钮语义反过来，避免重复添加
                            state.workInside -> "移出"
                            else -> "加入"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (state.creating) onCancelCreate() else onDismiss() },
                enabled = !state.submitting
            ) { Text("取消") }
        }
    )
}

/**
 * 收藏夹删除确认。
 *
 * 系统收藏夹走不到这里 —— 弹窗里对它们根本不渲染删除按钮，
 * [com.asmrone.app.data.repo.AsmrRepository.deletePlaylist] 里还有第二道拦截。
 */
@Composable
fun DeletePlaylistConfirmDialog(
    playlist: Playlist,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除收藏夹") },
        text = {
            Text(
                "确定要删除「${playlist.displayName}」吗？\n\n" +
                    "收藏夹里的 ${playlist.worksCount} 部作品不会被删除，只是不再归入这个收藏夹。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = ErrorColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
