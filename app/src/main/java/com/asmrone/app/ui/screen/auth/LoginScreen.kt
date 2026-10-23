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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.BlockingProgressOverlay
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.screen.settings.SettingsOutlinedButton
import com.asmrone.app.ui.screen.settings.SettingsScaffold
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.SuccessColor
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 登录 / 注册页。
 *
 * 版式与「设置」体系完全一致：`SettingsScaffold` + `FlatSection` 分组卡片，
 * 组内用 `FlatDivider` 切行、控件用 34dp 小号按钮（[SettingsOutlinedButton]）。
 *
 * 刻意精简掉的部分（按需求）：
 *  - 「登录 / 注册」右侧的说明文字；
 *  - 「游客模式」整组与其二次确认弹窗；
 *  - 「关于登录」整组说明。
 *
 * 保留的交互：登录/注册同页切换、服务器地址可现场改、先测连通性再登录、
 * 注册时用户名与密码至少 5 位、自建服务端可填 Cookie。
 *
 * @param onBack 返回上一页
 * @param onLoggedIn 登录/注册成功后的回调（通常是返回账号页）
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit
) {
    val viewModel: AuthViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()

    val currentHost by AppPrefs.host.collectAsState()

    // 表单字段：主机默认取当前设置里的主机，允许现场改
    var host by remember(currentHost) { mutableStateOf(currentHost) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localHint by remember { mutableStateOf<String?>(null) }

    // 提交成功后回到账号页
    LaunchedEffect(ui.done) {
        if (ui.done) {
            onLoggedIn()
        }
    }

    val doSubmit = {
        submit(
            viewModel = viewModel,
            ui = ui,
            register = ui.registerMode,
            username = username,
            password = password,
            host = host,
            cookie = cookie,
            onLocalHint = { localHint = it }
        )
    }

    SettingsScaffold(
        title = if (ui.registerMode) "注册账号" else "登录",
        onBack = onBack
    ) {
        // ── 账号 ──────────────────────────────────────────
        FlatSection(title = "账号") {
            // 模式切换：只留两个胶囊，不再向右铺一行说明
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsmrFilterChip(
                    text = "登录",
                    selected = !ui.registerMode,
                    onClick = { if (ui.registerMode) viewModel.toggleMode(); localHint = null }
                )
                AsmrFilterChip(
                    text = "注册",
                    selected = ui.registerMode,
                    onClick = { if (!ui.registerMode) viewModel.toggleMode(); localHint = null }
                )
            }

            FlatDivider()

            FieldRow(label = "用户名") {
                CompactSearchField(
                    value = username,
                    onValueChange = { username = it; localHint = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "asmr.one 的用户名",
                    leadingIconRes = R.drawable.ic_person_fluent,
                    imeAction = ImeAction.Next
                )
            }

            FlatDivider()

            FieldRow(label = "密码") {
                CompactSearchField(
                    value = password,
                    onValueChange = { password = it; localHint = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (ui.registerMode) "至少 5 个字符" else "密码",
                    leadingIconRes = R.drawable.ic_lock_fluent,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (passwordVisible) R.drawable.ic_eye_off_fluent
                                    else R.drawable.ic_eye_fluent
                                ),
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    imeAction = ImeAction.Done,
                    onImeAction = { doSubmit() }
                )
            }
        }

        // ── 错误 / 本地校验提示 ───────────────────────────
        val hint = ui.error ?: localHint
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 主操作：紧跟在账号密码下方 ─────────────────────
        Button(
            onClick = { doSubmit() },
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minimum),
            enabled = !ui.submitting
        ) {
            Text(if (ui.registerMode) "注册并登录" else "登录")
        }

        Spacer(Modifier.height(20.dp))

        // ── 服务器 ────────────────────────────────────────
        FlatSection(title = "服务器") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                CompactSearchField(
                    value = host,
                    onValueChange = { host = it; localHint = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.asmr-200.com",
                    leadingIconRes = R.drawable.ic_globe_fluent
                )

                Spacer(Modifier.height(10.dp))

                // 常用主机快捷选择 + 连通性测试
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppPrefs.HOST_PRESETS.take(2).forEach { preset ->
                        androidx.compose.material3.TextButton(
                            onClick = { host = preset; localHint = null },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp
                            ),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Text(
                                text = preset.removePrefix("https://"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsOutlinedButton(
                        onClick = { viewModel.testConnection(host, cookie) },
                        enabled = !ui.testing && host.isNotBlank()
                    ) {
                        if (ui.testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (ui.testing) "测试中…" else "测试")
                    }
                }

                ui.testResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.testOk) SuccessColor else ErrorColor
                    )
                }
            }

            FlatDivider()

            FieldRow(label = "Cookie（自建服务端可选）") {
                CompactSearchField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "前面挂了 Cloudflare 会话时填写，官方站留空"
                )
            }
        }
    }

    // 提交中：阻断式遮罩，避免重复点击
    if (ui.submitting) {
        BlockingProgressOverlay(
            title = if (ui.registerMode) "正在注册…" else "正在登录…",
            message = "正在与服务器通信，请稍候"
        )
    }
}

/**
 * 组内的一行「标签 + 输入框」。
 *
 * 内边距取 16/14，与 `FlatRow` 的行高节奏对齐 —— 这是「设置」体系里
 * 自行画行的统一做法（见 `SettingsCommon.kt` 的行高约定）。
 */
@Composable
private fun FieldRow(
    label: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** 提交前的本地校验 + 转交 ViewModel */
private fun submit(
    viewModel: AuthViewModel,
    ui: LoginUiState,
    register: Boolean,
    username: String,
    password: String,
    host: String,
    cookie: String,
    onLocalHint: (String?) -> Unit
) {
    if (ui.submitting) return
    if (username.isBlank()) {
        onLocalHint("请输入用户名")
        return
    }
    if (password.isBlank()) {
        onLocalHint("请输入密码")
        return
    }
    if (host.isBlank()) {
        onLocalHint("请输入服务器地址")
        return
    }
    // 参考实现：注册时用户名与密码都要求至少 5 个字符（登录不做限制）
    if (register && username.trim().length < 5) {
        onLocalHint("用户名不能少于 5 个字符")
        return
    }
    if (register && password.length < 5) {
        onLocalHint("密码不能少于 5 个字符")
        return
    }
    onLocalHint(null)
    viewModel.submit(username, password, host, cookie)
}
