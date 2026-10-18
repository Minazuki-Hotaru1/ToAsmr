package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.model.AudioFormatPreference
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.player.AudioController
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.component.SettingSwitch
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.TopBarScrim
import com.asmrone.app.ui.theme.TouchTarget
import dev.chrisbanes.haze.rememberHazeState

/**
 * 设置主页 —— 分组与排版完全沿用 comicApp 设置页：
 * 透明渐变模糊顶栏 + 扁平分组（`FlatSection`/`FlatRow`/`FlatDivider`）。
 */
@Composable
fun SettingsScreen(
    onOpenServer: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLyricsPreview: () -> Unit
) {
    val scrollState = rememberScrollState()
    val hazeState = rememberHazeState()
    val host by AppPrefs.host.collectAsState()
    val homeEnabled by AppPrefs.homeEnabled.collectAsState()
    val authState by AuthManager.state.collectAsState()
    var showHttpLog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + 56.dp).roundToPx() })
    }

    CollapsibleBottomBarEffect { 0 to scrollState.value }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(
                Modifier.height(
                    with(density) { barHeight.toDp() } + 12.dp
                )
            )

            FlatSection(title = "账号") {
                FlatRow(
                    title = when {
                        authState.isLoggedIn -> authState.displayName
                        authState.offline -> "${authState.displayName}（离线）"
                        else -> "未登录"
                    },
                    description = when {
                        authState.isLoggedIn && authState.isGuest -> "游客模式 · 云端功能受限"
                        authState.isLoggedIn -> "已登录 · 云端收藏可用 · 点此管理账号"
                        authState.offline -> "网络连接失败，仅可使用本地内容"
                        else -> "登录后可同步云端收藏；不登录也能浏览与播放"
                    },
                    onClick = onOpenAccount,
                    trailing = { RowChevron() }
                )
            }

            Spacer(Modifier.height(24.dp))

            FlatSection(title = "服务器与网络") {
                FlatRow(
                    title = "API 主机",
                    description = host,
                    onClick = onOpenServer,
                    trailing = { RowChevron() }
                )
                FlatDivider()
                FlatRow(
                    title = "连通性测试",
                    description = "测试当前主机是否可用（网络问题请在系统层用 VPN 解决）",
                    onClick = onOpenServer,
                    trailing = { RowChevron() }
                )
            }

            Spacer(Modifier.height(24.dp))

            FlatSection(title = "播放") {
                FlatRow(
                    title = "音质偏好",
                    description = "作品同时提供 mp3 / wav 时优先播放哪一套（当前：${formatPreferenceLabel()}）",
                    onClick = onOpenPlayback,
                    trailing = { RowChevron() }
                )
                FlatDivider()
                FlatRow(
                    title = "播放与音量",
                    description = "仅 Wi-Fi 播放 / 省流量模式 / 音量增益",
                    onClick = onOpenPlayback,
                    trailing = { RowChevron() }
                )
                FlatDivider()
                FlatRow(
                    title = "睡眠定时",
                    description = sleepTimerSummary(),
                    onClick = onOpenPlayback,
                    trailing = { RowChevron() }
                )
            }

            Spacer(Modifier.height(24.dp))

            FlatSection(title = "外观") {
                FlatRow(
                    title = "主题与导航栏",
                    description = "明暗模式、底部导航栏收起行为",
                    onClick = onOpenAppearance,
                    trailing = { RowChevron() }
                )
            }

            Spacer(Modifier.height(24.dp))

            FlatSection(title = "通用") {
                FlatRow(
                    title = "加载首页榜单",
                    description = "关闭后首页不再自动请求，可省流量",
                    trailing = {
                        SettingSwitch(
                            checked = homeEnabled,
                            onCheckedChange = { AppPrefs.setHomeEnabled(it) }
                        )
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            FlatSection(title = "其它") {
                FlatRow(
                    title = "歌词效果预览",
                    description = "独立演示页：拖拽浏览 / 松手惯性 / 3 秒回位，演示数据",
                    onClick = onOpenLyricsPreview,
                    trailing = { RowChevron() }
                )
                FlatDivider()
                FlatRow(
                    title = "网络日志",
                    description = "查看最近 120 次请求：打到哪台主机、有没有带登录令牌、服务端回了什么",
                    onClick = { showHttpLog = true },
                    trailing = { RowChevron() }
                )
                FlatDivider()
                FlatRow(
                    title = "关于 AsmrOne",
                    description = "版本、数据来源与免责声明",
                    onClick = onOpenAbout,
                    trailing = { RowChevron() }
                )
            }

            Spacer(Modifier.height(100.dp))
        }

        TabTopBar(
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeight = it.height }
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        TopBarScrim(
            scrollFraction = (scrollState.value / 200f).coerceIn(0f, 1f),
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (showHttpLog) {
        HttpLogDialog(onDismiss = { showHttpLog = false })
    }
}

/**
 * 网络日志弹窗。
 *
 * 提供「复制」是刻意的：这类问题的定位完全依赖"请求到底发了什么"，
 * 让用户能一键把日志复制出来，比要一份 logcat 现实得多。
 */
@Composable
private fun HttpLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 每次打开都重新取，保证看到的是最新记录
    val dump = remember { HttpLog.dump() }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络日志") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "看每一行的「token=有/无」与「HTTP 状态码」：\n" +
                        "· token=无 且 401 → 登录状态没带上（请重新登录）\n" +
                        "· host 不是你预期的服务器 → 请求打到了别的主机\n" +
                        "· HTTP 5xx → 服务端问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = dump,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AsmrOne 网络日志", dump))
                copied = true
            }) { Text(if (copied) "已复制" else "复制全部") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { HttpLog.clear(); onDismiss() }) { Text("清空并关闭") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

private fun sleepTimerSummary(): String {
    val minutes = AppPrefs.sleepTimerMinutes.value
    return if (minutes <= 0) "未启用" else "默认 ${minutes} 分钟"
}

private fun formatPreferenceLabel(): String =
    AudioFormatPreference.fromValue(AppPrefs.audioFormat.value).label

/** 外观设置二级页 */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val themeMode by AppPrefs.themeMode.collectAsState()
    val collapseBottomBar by AppPrefs.collapseBottomBar.collectAsState()

    SettingsSubPage(title = "外观", onBack = onBack) {
        FlatSection(title = "主题") {
            FlatRow(
                title = "明暗模式",
                description = "与 comicApp 相同：默认跟随系统"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = TouchTarget.minimum),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0 to "跟随系统", 1 to "亮色", 2 to "暗色").forEach { (mode, label) ->
                    AsmrFilterChip(
                        text = label,
                        selected = themeMode == mode,
                        onClick = { AppPrefs.setThemeMode(mode) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "底部导航栏") {
            FlatRow(
                title = "随页面滚动收起",
                description = "向下滚动时收起，向上滚动时展开",
                trailing = {
                    SettingSwitch(
                        checked = collapseBottomBar,
                        onCheckedChange = { AppPrefs.setCollapseBottomBar(it) }
                    )
                }
            )
        }
    }
}

/** 播放设置二级页 */
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val wifiOnly by AppPrefs.wifiOnly.collectAsState()
    val lowBitrate by AppPrefs.lowBitrate.collectAsState()
    val sleepMinutes by AppPrefs.sleepTimerMinutes.collectAsState()
    val audioFormat by AppPrefs.audioFormat.collectAsState()
    var gain by remember { mutableFloatStateOf(AppPrefs.gain.value) }

    SettingsSubPage(title = "播放与音量", onBack = onBack) {

        // ── 音质偏好：作品同时有 mp3/wav 时播哪一套 ──────────
        FlatSection(title = "音质偏好") {
            FlatRow(
                title = "优先播放",
                description = "原站作品常同时提供 mp3 与 wav 两套目录，" +
                    "详情页的文件区也可以随时切换"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = TouchTarget.minimum),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AudioFormatPreference.entries.forEach { option ->
                    AsmrFilterChip(
                        text = option.label,
                        selected = audioFormat == option.value,
                        onClick = { AppPrefs.setAudioFormat(option.value) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "网络") {
            FlatRow(
                title = "仅 Wi-Fi 下播放",
                description = "移动网络下不自动播放，避免流量消耗",
                trailing = {
                    SettingSwitch(checked = wifiOnly, onCheckedChange = { AppPrefs.setWifiOnly(it) })
                }
            )
            FlatDivider()
            FlatRow(
                title = "省流量模式",
                description = "在服务器支持时请求较低码率的音轨",
                trailing = {
                    SettingSwitch(checked = lowBitrate, onCheckedChange = { AppPrefs.setLowBitrate(it) })
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "音量增益") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "增益",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(gain * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = gain,
                    onValueChange = {
                        gain = it
                        AppPrefs.setGain(it)
                        AudioController.setGain(it)
                    },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = "用于补偿录音本身的音量偏低（100% 为原始音量）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "睡眠定时") {
            FlatRow(title = "默认定时", description = "在播放器里也可以随时修改")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = TouchTarget.minimum),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0, 15, 30, 60, 90).forEach { minutes ->
                    AsmrFilterChip(
                        text = if (minutes == 0) "不定时" else "${minutes}分",
                        selected = sleepMinutes == minutes,
                        onClick = {
                            AppPrefs.setSleepTimerMinutes(minutes)
                            AudioController.setSleepTimer(minutes)
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 关于页 */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    SettingsSubPage(title = "关于", onBack = onBack) {
        FlatSection(title = "AsmrOne") {
            FlatRow(title = "版本", description = "0.2.1（修复详情页文件树闪退）")
            FlatDivider()
            FlatRow(
                title = "设计来源",
                description = "界面语言提炼自 comicApp（ToComic）：青绿薄荷配色 + 液态玻璃 + 8pt 网格"
            )
            FlatDivider()
            FlatRow(
                title = "数据来源",
                description = "asmr.one / Kikoeru 公开接口，本应用不存储、不分发任何音频内容"
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "声明") {
            FlatRow(
                title = "非官方项目",
                description = "与 asmr.one 及 DLsite 无隶属关系；仅供学习与个人使用"
            )
        }
    }
}
