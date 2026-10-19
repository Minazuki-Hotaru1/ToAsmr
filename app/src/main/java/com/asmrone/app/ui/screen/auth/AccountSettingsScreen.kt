package com.asmrone.app.ui.screen.auth

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.Account
import com.asmrone.app.ui.component.BlockingProgressOverlay
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.screen.settings.SettingsScaffold
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.SuccessColor
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 账号页（设置 → 账号）。
 *
 * 对齐参考实现 KikoFlu 的 `account_management_screen.dart`：
 *  - 顶部展示当前登录身份（未登录时引导去登录）；
 *  - 已保存账号列表：一键切换、删除；
 *  - 底部：添加账号 / 退出登录。
 *
 * 与参考实现的差别：本项目**不强制登录**，所以未登录时这一页只是"登录入口"，
 * 而不是挡住整个应用的门。
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit
) {
    val viewModel: AccountViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()
    val authState by AuthManager.state.collectAsState()

    var pendingSwitch by remember { mutableStateOf<Account?>(null) }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    SettingsScaffold(title = "账号", onBack = onBack) {

        // ── 当前身份 ──────────────────────────────────────
        FlatSection(title = "当前状态") {
            if (authState.isLoggedIn) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_person_fluent),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = authState.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (authState.isGuest) {
                                    Spacer(Modifier.width(6.dp))
                                    SmallTagChip(text = "游客", tone = TagTone.Neutral)
                                }
                            }
                            authState.user?.group?.let { group ->
                                Text(
                                    text = "用户组：$group",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_fluent),
                            contentDescription = null,
                            tint = SuccessColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "已登录，云端收藏可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessColor
                        )
                    }
                }
            } else if (authState.offline) {
                // 有本地账号但连不上服务器（对齐参考实现的"离线模式"）
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = authState.displayName.ifBlank { "已保存账号" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = authState.error ?: "网络连接失败，以离线模式启动。仅可访问本地收藏与历史。",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorColor
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.logout(); onOpenLogin() },
                        enabled = !ui.busy
                    ) { Text("重新登录") }
                }
            } else {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "未登录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "不登录也能浏览、搜索、播放，并使用本地收藏与收听历史。" +
                            "登录后可把收藏同步到账号（云端收藏）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenLogin) { Text("登录 / 注册") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 已保存账号 ────────────────────────────────────
        FlatSection(title = "已保存账号") {
            ui.accounts.forEachIndexed { index, account ->
                if (index > 0) FlatDivider()
                val isCurrent = authState.user?.name == account.username && authState.host == account.host
                FlatRow(
                    title = account.username,
                    description = buildString {
                        append(account.displayHost)
                        if (isCurrent) append(" · 当前使用")
                    },
                    enabled = !ui.busy,
                    onClick = { if (!isCurrent) pendingSwitch = account },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCurrent) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check_fluent),
                                    contentDescription = "当前账号",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            TextButton(onClick = { pendingDelete = account }) { Text("删除") }
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = { if (!isCurrent) pendingSwitch = account },
                                enabled = !isCurrent && !ui.busy
                            ) { Text(if (isCurrent) "使用中" else "切换") }
                        }
                    }
                )
            }
            if (ui.accounts.isEmpty()) {
                FlatRow(
                    title = "还没有保存的账号",
                    description = "登录后会自动保存"
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        FlatSection(title = "操作") {
            FlatRow(
                title = "添加账号 / 登录其它账号",
                description = "可在一台设备保存多个账号",
                onClick = onOpenLogin,
                enabled = !ui.busy,
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_fluent),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            if (authState.user != null) {
                FlatDivider()
                FlatRow(
                    title = "退出登录",
                    description = "已保存的账号会保留",
                    onClick = { confirmLogout = true },
                    enabled = !ui.busy,
                    trailing = {
                        Icon(
                            painter = painterResource(R.drawable.ic_sign_out_fluent),
                            contentDescription = null,
                            tint = ErrorColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        ui.error?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

    }

    // ── 切换账号确认（对齐参考实现的二次确认）──────────────
    pendingSwitch?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换账户") },
            text = { Text("确定要切换到账户「${account.username}」吗？\n\n服务器：${account.displayHost}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    viewModel.switchTo(account)
                }) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text("取消") }
            }
        )
    }

    // ── 删除账号确认 ────────────────────────────────────
    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账户") },
            text = {
                Text("确定要删除账户「${account.username}」吗？\n\n" +
                    "只会删除本机保存的用户名与密码，不会影响服务器上的账号数据。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.remove(account)
                }) { Text("删除", color = ErrorColor) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    // ── 退出登录确认 ────────────────────────────────────
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出登录吗？\n\n收藏与历史仍保留在本机，云端收藏需要重新登录后才能同步。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            }
        )
    }

    if (ui.busy) {
        BlockingProgressOverlay(title = "正在切换账号…", message = "正在向服务器申请新的登录凭据")
    }
}
