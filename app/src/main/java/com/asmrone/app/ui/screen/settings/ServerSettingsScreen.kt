package com.asmrone.app.ui.screen.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.FluentIcon
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.SuccessColor
import kotlinx.coroutines.launch

/**
 * 服务器设置（API 主机）。
 *
 * ## 布局
 *
 * ```
 *  当前主机   ┌────────────────────────────────────────┐
 *            │ ● api.asmr-200.com          [测试]     │
 *            │ https://api.asmr-200.com               │
 *            │ ✓ 连接正常 · 240 ms                    │
 *            └────────────────────────────────────────┘
 *  切换主机   ○ api.asmr.one
 *            ● api.asmr-200.com                     ✓
 *            ○ api.asmr-100.com
 *  自定义主机 [ https://……              ]
 *            ✓ 将切换到 https://example.com
 *            [使用此地址]          [恢复默认]
 * ```
 *
 * 设计取舍：
 *  - **选中态靠「行底色 + 勾」而不是把说明文字塞进选中行** —— 旧版把"当前使用"
 *    写在 description 里，于是选中行比其它行高 6dp，列表一滚动高度就在跳。
 *  - **每一行都显示完整地址**，不再只显示去掉 `https://` 的域名：
 *    四个官方主机只有数字不同，只看域名分不清自己点的是哪个。
 *  - **测试按钮跟着「当前主机」走**，测完把耗时一起显示；自定义区另有自己的
 *    「测试」，可以在切换**之前**验证自建地址，旧版只能切过去再试错。
 *  - `http://` 允许（自建 Kikoeru 常常没有证书），但只允许 http/https。
 *
 * 说明：App 内不提供 HTTP 代理配置。asmr.one 在部分网络下不可直连，
 * 请在系统层使用代理/VPN，App 侧只需要选对 API 主机。
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val host by AppPrefs.host.collectAsState()

    // 输入框里的草稿；跟随 host 变化重置（点了预设就应该看到那个地址）
    var draft by remember(host) { mutableStateOf(host) }
    var result by remember { mutableStateOf<HostCheck?>(null) }
    var draftCheck by remember { mutableStateOf<HostCheck?>(null) }
    var busy by remember { mutableStateOf(false) }
    var draftBusy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val normalizedDraft = remember(draft) { normalizeHostInput(draft) }
    val draftIsInvalid = draft.isNotBlank() && normalizedDraft == null
    val defaultHost = AppPrefs.HOST_PRESETS.first()

    fun applyHost(value: String) {
        AppPrefs.setHost(value)
        AsmrRepository.clearCaches()
        result = null
        draftCheck = null
    }

    SettingsScaffold(title = "服务器", onBack = onBack) {

        // ── 当前主机 ──────────────────────────────────────────
        FlatSection(title = "当前主机", contentPadding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FluentIcon(
                    res = R.drawable.ic_globe_fluent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hostDomain(host),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                } else {
                    SettingsOutlinedButton(onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            result = checkHost(host)
                            busy = false
                        }
                    }) { Text("测试") }
                }
            }
            if (result != null) CheckLine(result!!)
        }

        Spacer(Modifier.height(24.dp))

        // ── 预设主机 ──────────────────────────────────────────
        FlatSection(title = "切换主机") {
            AppPrefs.HOST_PRESETS.forEachIndexed { index, preset ->
                if (index > 0) FlatDivider()
                HostOptionRow(
                    address = preset,
                    selected = preset == host,
                    onClick = {
                        if (preset != host) applyHost(preset)
                        else draft = preset
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 自定义主机 ────────────────────────────────────────
        FlatSection(title = "自定义主机", contentPadding = PaddingValues(16.dp)) {
            CompactSearchField(
                value = draft,
                onValueChange = {
                    draft = it
                    draftCheck = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "api.example.com",
                leadingIconRes = R.drawable.ic_globe_fluent,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = {
                    normalizedDraft?.let { if (it != host) applyHost(it) }
                }
            )

            Spacer(Modifier.height(8.dp))
            when {
                draftIsInvalid -> HintLine(
                    text = "地址无效，需要 http:// 或 https:// 加域名",
                    color = ErrorColor,
                    iconRes = R.drawable.ic_close_circle_fluent
                )
                normalizedDraft != null && normalizedDraft != draft -> HintLine(
                    text = "将使用 $normalizedDraft",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconRes = R.drawable.ic_check_fluent
                )
                normalizedDraft == host -> HintLine(
                    text = "这就是当前主机",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconRes = R.drawable.ic_check_fluent
                )
                else -> HintLine(
                    text = "自建 Kikoeru 也可以填，允许 http://",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconRes = null
                )
            }

            if (draftCheck != null) CheckLine(draftCheck!!)

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsButton(
                    onClick = { normalizedDraft?.let { applyHost(it) } },
                    modifier = Modifier.weight(1f),
                    enabled = normalizedDraft != null && normalizedDraft != host
                ) { Text("使用此地址") }
                SettingsOutlinedButton(
                    onClick = {
                        draftBusy = true
                        draftCheck = null
                        scope.launch {
                            draftCheck = checkHost(normalizedDraft.orEmpty())
                            draftBusy = false
                        }
                    },
                    enabled = normalizedDraft != null && !draftBusy
                ) {
                    if (draftBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("测试")
                    }
                }
                SettingsOutlinedButton(
                    onClick = {
                        draft = defaultHost
                        if (host != defaultHost) applyHost(defaultHost)
                    },
                    enabled = host != defaultHost || draft != defaultHost
                ) { Text("恢复默认") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "asmr.one 在部分网络下无法直连，可先在系统层开启代理或 VPN。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 一次连通性测试的结果 —— [ok] 决定颜色，[text] 直接显示 */
private data class HostCheck(val ok: Boolean, val text: String)

/** 测一个主机（不切换）：成功时把耗时一起报出来，失败时给出可读原因 */
private suspend fun checkHost(value: String): HostCheck {
    if (value.isBlank()) return HostCheck(ok = false, text = "地址为空")
    val started = SystemClock.elapsedRealtime()
    val error = AsmrRepository.testConnection(hostOverride = value)
    val elapsed = SystemClock.elapsedRealtime() - started
    return if (error == null) {
        HostCheck(ok = true, text = "连接正常 · ${elapsed} ms")
    } else {
        HostCheck(ok = false, text = error)
    }
}

/** 预设行：单选圆点 + 域名 + 完整地址，选中时整行淡主色底 + 右侧勾 */
@Composable
private fun HostOptionRow(
    address: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(SettingsRowPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hostDomain(address),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            FluentIcon(
                res = R.drawable.ic_check_fluent,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp
            )
        }
    }
}

/** 结果行：图标 + 文案，红色/绿色由 [HostCheck.ok] 决定 */
@Composable
private fun CheckLine(check: HostCheck) {
    Spacer(Modifier.height(10.dp))
    HintLine(
        text = check.text,
        color = if (check.ok) SuccessColor else ErrorColor,
        iconRes = if (check.ok) R.drawable.ic_check_circle_fluent else R.drawable.ic_close_circle_fluent
    )
}

/** 一行小字提示，可带前置图标 */
@Composable
private fun HintLine(text: String, color: Color, iconRes: Int?) {
    Row(verticalAlignment = Alignment.Top) {
        if (iconRes != null) {
            FluentIcon(
                res = iconRes,
                contentDescription = null,
                tint = color,
                size = 14.dp
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 只取域名部分用于标题：`https://api.asmr-200.com` → `api.asmr-200.com` */
private fun hostDomain(address: String): String =
    address.substringAfter("://").substringBefore('/').ifEmpty { address }

/**
 * 规范化用户输入的主机地址。
 *
 * 返回 null 表示这个地址明显不可用（不是"我不喜欢它"）：
 *  - 去首尾空白与结尾的 `/`；
 *  - 没写协议就补 `https://`（用户几乎总是想 https）；
 *  - 只接受 http / https；
 *  - 主机名里必须有 `.`（`localhost` 这种在手机上指向自己，没有意义）；
 *  - 域名统一小写，**路径保持原样**（Kikoeru 可能挂在子路径下，路径大小写敏感）。
 *
 * 与 `AppPrefs.setHost` 的关系：那边只做 trim + trimEnd('/')，
 * 校验放在这里，不让"半个地址"进到偏好设置里污染所有后续请求。
 */
internal fun normalizeHostInput(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = withScheme.substringAfter("://")
    if (rest.isEmpty() || rest.contains("://") || rest.any { it.isWhitespace() }) return null

    val authority = rest.substringBefore('/')
    val path = rest.substringAfter('/', "")
    if (!authority.contains('.')) return null
    if (authority.startsWith('.') || authority.endsWith('.')) return null

    return buildString {
        append(scheme).append("://").append(authority.lowercase())
        if (path.isNotEmpty()) append('/').append(path)
    }
}
