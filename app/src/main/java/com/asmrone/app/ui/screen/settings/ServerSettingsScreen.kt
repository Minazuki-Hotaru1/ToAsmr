package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.SuccessColor
import kotlinx.coroutines.launch

/**
 * 服务器设置。
 *
 * 说明：**App 内不再提供 HTTP 代理配置**（已按要求移除）。
 * asmr.one 在部分网络下不可直连，此时请在**系统层**使用代理/VPN
 * （Android 上常见的是 Clash / v2rayNG 等以 VPN 模式接管全部流量），
 * App 侧只需要选择正确的 API 主机即可。
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val host by AppPrefs.host.collectAsState()

    var customHost by remember { mutableStateOf(host) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var testedCount by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()


    SettingsScaffold(title = "服务器", onBack = onBack) {

        // ── 预设主机 ────────────────────────────────────────
        FlatSection(title = "API 主机") {
            AppPrefs.HOST_PRESETS.forEachIndexed { index, preset ->
                if (index > 0) FlatDivider()
                val selected = preset == host
                FlatRow(
                    title = preset.removePrefix("https://"),
                    description = if (selected) "当前使用" else null,
                    onClick = {
                        AppPrefs.setHost(preset)
                        customHost = preset
                        AsmrRepository.clearCaches()
                        testResult = null
                    },
                    trailing = {
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_fluent),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 自定义主机 ──────────────────────────────────────
        FlatSection(title = "自定义主机") {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "自建 Kikoeru 或镜像域名（带 http/https，结尾不要 /）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                CompactSearchField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.example.com"
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AppPrefs.setHost(customHost)
                            AsmrRepository.clearCaches()
                            testResult = null
                        },
                        enabled = customHost.isNotBlank()
                    ) { Text("应用") }
                    OutlinedButton(onClick = { customHost = AppPrefs.HOST_PRESETS.first() }) {
                        Text("重置")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 连通性测试 ──────────────────────────────────────
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "连通性测试",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = testResult
                        ?: "请求一次 /api/works 验证主机是否可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        testResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        testOk -> SuccessColor
                        else -> ErrorColor
                    }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val error = AsmrRepository.testConnection()
                            testedCount += 1
                            testOk = error == null
                            testResult = if (error == null) {
                                "连接成功（第 $testedCount 次测试）"
                            } else {
                                "失败：$error"
                            }
                            testing = false
                        }
                    },
                    enabled = !testing
                ) { Text("测试当前配置") }
            }
        }
    }
}
