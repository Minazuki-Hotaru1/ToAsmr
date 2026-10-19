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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.asmrone.app.R
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.BlockingProgressOverlay
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.screen.settings.SettingsScaffold
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.SuccessColor
import com.asmrone.app.ui.theme.TouchTarget
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 登录 / 注册页。
 *
 * 对齐参考实现 KikoFlu 的 `login_screen.dart`，但按本项目「不强制登录」的定位做了简化：
 *  - 这里**不是**启动门禁，而是从「设置 → 账号」进入的二级页；
 *  - 保留参考实现的核心交互：登录/注册同页切换、服务器地址可现场切换、
 *    先测连通性再登录、游客模式（guest/guest）、注册时用户名与密码至少 5 位；
 *  - 去掉了参考实现的代理配置与 Cookie 高级面板（本项目已按要求移除 App 内代理）。
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
    var showGuestConfirm by remember { mutableStateOf(false) }
    var localHint by remember { mutableStateOf<String?>(null) }

    // 提交成功后回到账号页
    LaunchedEffect(ui.done) {
        if (ui.done) {
            onLoggedIn()
        }
    }

    SettingsScaffold(
        title = if (ui.registerMode) "注册账号" else "登录",
        onBack = onBack
    ) {
        // ── 登录 / 注册 切换 ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minimum),
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
            Spacer(Modifier.weight(1f))
            Text(
                text = if (ui.registerMode) "注册后会直接登录" else "登录后解锁云端收藏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── 账号凭据 ──────────────────────────────────────
        FlatSection(title = "账号") {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "用户名",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                CompactSearchField(
                    value = username,
                    onValueChange = { username = it; localHint = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "asmr.one 的用户名",
                    leadingIconRes = R.drawable.ic_person_fluent,
                    imeAction = ImeAction.Next
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "密码",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
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
                    onImeAction = {
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
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 服务器 ────────────────────────────────────────
        FlatSection(title = "服务器") {
            Column(modifier = Modifier.padding(16.dp)) {
                CompactSearchField(
                    value = host,
                    onValueChange = { host = it; localHint = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.asmr-200.com",
                    leadingIconRes = R.drawable.ic_globe_fluent
                )

                Spacer(Modifier.height(10.dp))

                // 常用主机快捷选择
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppPrefs.HOST_PRESETS.take(2).forEach { preset ->
                        TextButton(
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
                }

                Spacer(Modifier.height(6.dp))

                // 连通性测试（先验证主机再登录，省得把"网络错误"误报成"密码错误"）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
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
                        Text(if (ui.testing) "测试中…" else "测试连接")
                    }
                    Spacer(Modifier.width(10.dp))
                    ui.testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.testOk) SuccessColor else ErrorColor
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 自建服务端的高级项（Cookie）────────────────────
        FlatSection(title = "自建服务端（可选）") {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "若你的 Kikoeru 服务端需要额外的 Cookie（例如前面挂了 Cloudflare 会话），" +
                        "填在这里；官方站留空即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                CompactSearchField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Cookie（可留空）"
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 错误 / 本地校验提示 ───────────────────────────
        val hint = ui.error ?: localHint
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        // ── 主操作 ────────────────────────────────────────
        Button(
            onClick = {
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
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minimum),
            enabled = !ui.submitting
        ) {
            Text(if (ui.registerMode) "注册并登录" else "登录")
        }

        Spacer(Modifier.height(10.dp))

        TextButton(
            onClick = { viewModel.toggleMode() },
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.compact)
        ) {
            Text(
                text = if (ui.registerMode) "已有账号？返回登录" else "还没有账号？去注册",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── 游客模式 ──────────────────────────────────────
        FlatSection(title = "游客模式") {
            FlatRow(
                title = "以游客身份进入",
                description = "使用官方站的演示账号（guest）连接服务器，" +
                    "功能有限、可能不稳定；适合只想先看看内容的情况。",
                onClick = { showGuestConfirm = true },
                enabled = !ui.submitting
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "关于登录") {
            FlatRow(
                title = "不登录也能用",
                description = "首页、搜索、详情、播放、本地收藏与历史都不需要登录。" +
                    "登录后才解锁云端收藏等账号功能。"
            )
            FlatDivider()
            FlatRow(
                title = "密码保存在本机",
                description = "为了支持自动重登与多账号切换，密码会明文保存在应用私有目录中，" +
                    "不会上传到本应用以外的任何地方。"
            )
            FlatDivider()
            FlatRow(
                title = "账号由站点管理",
                description = "本应用只是调用 asmr.one / Kikoeru 的公开接口，" +
                    "注册与登录均发生在你所填写的服务器上。"
            )
        }
    }

    // 提交中：阻断式遮罩，避免重复点击
    if (ui.submitting) {
        BlockingProgressOverlay(
            title = if (ui.registerMode) "正在注册…" else "正在登录…",
            message = "正在与服务器通信，请稍候"
        )
    }

    // 游客模式二次确认（对齐参考实现的确认弹窗）
    if (showGuestConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGuestConfirm = false },
            title = { Text("游客模式确认") },
            text = {
                Text(
                    "游客模式功能有限：\n\n" +
                        "• 无法标记或评分作品\n" +
                        "• 无法创建播放列表\n" +
                        "• 无法同步进度\n\n" +
                        "游客模式使用演示账号连接服务器，可能不够稳定。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGuestConfirm = false
                    viewModel.loginAsGuest(host, cookie)
                }) { Text("继续以游客身份") }
            },
            dismissButton = {
                TextButton(onClick = { showGuestConfirm = false }) { Text("取消") }
            }
        )
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
