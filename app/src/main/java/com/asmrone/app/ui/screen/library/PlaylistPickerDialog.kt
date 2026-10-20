package com.asmrone.app.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Playlist
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.ConfirmDeleteDialog
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 收藏弹窗的状态（由 [PlaylistPickerViewModel] 持有，弹窗本身无状态）。
 *
 * [checkedIds] 是用户**当前**的勾选，[originalIds] 是进来时从本地/云端确认过的成员关系；
 * 提交时按两者的差集计算加入与移出，所以没动过的收藏夹绝不会被误改。
 */
data class PlaylistPickerState(
    val playlists: List<Playlist> = emptyList(),
    /** 当前勾选的收藏夹 id */
    val checkedIds: Set<String> = emptySet(),
    /** 进来时确定已在其中的收藏夹 id（差集的基线） */
    val originalIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val submitting: Boolean = false,
    /** 正在后台核对"在不在某些收藏夹里" */
    val checking: Boolean = false,
    val error: String? = null,
    /** 新建收藏夹的输入框是否展开 */
    val creating: Boolean = false,
    val newName: String = "",
    val createError: String? = null
) {
    val addCount: Int get() = (checkedIds - originalIds).size
    val removeCount: Int get() = (originalIds - checkedIds).size
    val hasChanges: Boolean get() = checkedIds != originalIds
}

/**
 * 「收藏到…」弹窗 —— 详情页点收藏按钮（已收藏时）或提示里的「修改」时弹出。
 *
 * 行为（对应"一个作品可以同时在多个收藏夹里"这个事实）：
 *  - 列出我的收藏夹，进来时**按真实成员关系预勾**（本地缓存优先）；
 *  - 支持多选：勾上 = 要加进去、取消勾 = 要移出，点「保存」按差集提交；
 *  - 任何收藏夹都不提供删除入口（删除收藏夹是收藏页那边的事）；
 *  - 系统收藏夹跟在名字后面用小标签标出来；
 *  - 支持新建收藏夹，新建后自动勾上并加入；
 *  - **已经收藏过**的作品多一个「删除」：整件作品从所有收藏夹移出（见 [onRemove]）。
 *
 * 旧版是"单选 + 同一个按钮即加入即移出"，那套模型下无法表达"同时属于 A 和 B"，
 * 移出时也只能靠"上次选过的收藏夹"猜，于是会误删另一个收藏夹里的同一作品。
 *
 * @param canRemove 是否显示「删除」：只有**打开弹窗之前就已经收藏过**才显示
 *   （刚点收藏一次、还没发出去的那种不算 —— 点「取消」就是撤销那次收藏）。
 * @param onRemove 删除回调：把作品从所有收藏夹里移除
 */
@Composable
fun PlaylistPickerDialog(
    workTitle: String,
    state: PlaylistPickerState,
    canRemove: Boolean,
    onToggle: (Playlist) -> Unit,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
    onStartCreate: () -> Unit,
    onNewNameChange: (String) -> Unit,
    onCreateConfirm: () -> Unit,
    onCancelCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    // 删除按钮先弹二次确认：收藏的删除是从所有收藏夹移出，不可撤销
    var confirmingRemove by remember { mutableStateOf(false) }
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
                        text = "新建后会自动勾选它，并把这部作品加入其中。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // ── 收藏夹列表（多选） ──────────────────────────
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
                            val checked = playlist.id in state.checkedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else Color.Transparent
                                    )
                                    .clickable(enabled = !state.submitting) { onToggle(playlist) }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (playlist.isSystem) R.drawable.ic_folder_fluent
                                        else R.drawable.ic_folder_open_fluent
                                    ),
                                    contentDescription = null,
                                    tint = if (checked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    // 「系统」标签跟在名字后面：它是名字的补充说明，
                                    // 放到行尾会跟勾选状态挤在一起，也容易被当成状态标记
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = playlist.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (checked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            // 名字很长时先让它省略，别把后面的「系统」标签挤出去
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (playlist.isSystem) {
                                            Spacer(Modifier.width(6.dp))
                                            SmallTagChip(text = "系统", tone = TagTone.Neutral)
                                        }
                                    }
                                    Text(
                                        text = "${playlist.worksCount} 部作品",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                PickerCheck(checked = checked)
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

                        if (state.checking) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "正在核对部分收藏夹…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    enabled = !state.loading && !state.submitting
                ) {
                    Text(
                        when {
                            state.submitting -> "处理中…"
                            !state.hasChanges -> "完成"
                            else -> "保存"
                        }
                    )
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 删除：只有打开前就已经收藏过才出现（新建收藏夹状态下不显示）
                if (canRemove && !state.creating) {
                    TextButton(
                        onClick = { confirmingRemove = true },
                        enabled = !state.submitting,
                        modifier = Modifier.heightIn(min = TouchTarget.compact)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_fluent),
                            contentDescription = null,
                            tint = ErrorColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除", color = ErrorColor)
                    }
                }
                TextButton(
                    onClick = { if (state.creating) onCancelCreate() else onDismiss() },
                    enabled = !state.submitting
                ) { Text("取消") }
            }
        }
    )

    // 删除的二次确认：收藏的删除 = 从所有收藏夹移出，同样不可恢复
    if (confirmingRemove) {
        ConfirmDeleteDialog(
            title = "删除收藏？",
            message = "会把「${workTitle.ifBlank { "这个作品" }}」从所有收藏夹中移出，" +
                "删除它在收藏里的全部内容，删除后无法恢复。",
            onConfirm = {
                confirmingRemove = false
                onRemove()
            },
            onDismiss = { confirmingRemove = false }
        )
    }
}

/**
 * 多选用的方形勾选框。
 *
 * 不用 Material 的 [androidx.compose.material3.Checkbox]：它自带 20dp 视觉 + 2dp 描边，
 * 放在 40dp 左右的行里偏重，也压不下尺寸（内部靠 minimumInteractiveComponentSize 撑开）。
 * 这里只画视觉，点击由整行负责。
 */
@Composable
private fun PickerCheck(checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (checked) cs.primary else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.45f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_check_fluent),
                contentDescription = null,
                tint = cs.onPrimary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/**
 * 收藏夹删除确认（收藏页长按收藏夹卡片触发）。
 *
 * 系统收藏夹走不到这里 —— 卡片对系统收藏夹根本不挂长按，
 * [com.asmrone.app.data.repo.AsmrRepository.deletePlaylist] 里还有第二道拦截。
 *
 * 文案按"删掉的就是这个文件夹里的全部内容"来说：收藏夹本身连同里面的
 * N 条归属记录一起没了，且没有回收站；再补一句作品本身不会被删，
 * 免得用户以为音声也一起被删了。
 */
@Composable
fun DeletePlaylistConfirmDialog(
    playlist: Playlist,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除收藏夹？") },
        text = {
            Text(
                "删除「${playlist.displayName}」会清空这个收藏夹中的所有内容" +
                    "（${playlist.worksCount} 部作品），删除后无法恢复。\n\n" +
                    "作品本身不会被删除，它在其他收藏夹里的记录也不受影响。"
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
