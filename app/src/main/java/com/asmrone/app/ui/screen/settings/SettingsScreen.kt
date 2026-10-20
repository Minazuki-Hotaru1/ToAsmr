package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.component.SettingSwitch
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 设置主页 —— 分组与排版完全沿用 comicApp 设置页：
 * 普通标题栏 + 扁平分组（`FlatSection` / `FlatRow` / `FlatDivider`），
 * 顶部滚动渐显毛玻璃由 [SettingsScaffold] 统一负责。
 */
@Composable
fun SettingsScreen(
    onOpenServer: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val host by AppPrefs.host.collectAsState()
    val homeEnabled by AppPrefs.homeEnabled.collectAsState()
    val authState by AuthManager.state.collectAsState()
    var showHttpLog by remember { mutableStateOf(false) }

    SettingsScaffold(title = "设置", collapseBottomBarOnScroll = true) {
        // ── 账号 ──
        FlatSection(title = "账号") {
            FlatRow(
                title = when {
                    authState.isLoggedIn -> authState.displayName
                    authState.offline -> "${authState.displayName}（离线）"
                    else -> "未登录"
                },
                description = when {
                    authState.isLoggedIn && authState.isGuest -> "游客模式"
                    authState.isLoggedIn -> "已登录"
                    authState.offline -> "当前离线"
                    else -> "登录后可同步云端收藏"
                },
                onClick = onOpenAccount,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── 服务器 ──
        //
        // 原来这里还有一条「连通性测试」，但点进去和「API 主机」是同一个页面
        // （测试按钮本来就在那个页面里）—— 现在只保留一个入口。
        FlatSection(title = "服务器") {
            FlatRow(
                title = "API 主机",
                description = host,
                onClick = onOpenServer,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── 数据 ──
        //
        // 从「服务器」页独立出来：搜索字典是一次性下载的静态数据，
        // 与"连哪台服务器"不是一类设置。
        FlatSection(title = "数据") {
            FlatRow(
                title = "搜索字典",
                description = "标签 / 声优 / 社团，用于按标签搜索",
                onClick = onOpenData,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "外观") {
            FlatRow(
                title = "外观",
                description = "明暗模式 · 液态玻璃 · 底部导航栏",
                onClick = onOpenAppearance,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── 其它：开关 + 工具 + 关于 ──
        FlatSection(title = "其它") {
            FlatRow(
                title = "加载首页榜单",
                description = "关闭后首页不再自动加载",
                trailing = {
                    SettingSwitch(
                        checked = homeEnabled,
                        onCheckedChange = { AppPrefs.setHomeEnabled(it) }
                    )
                }
            )

            FlatDivider()
            FlatRow(
                title = "网络日志",
                onClick = { showHttpLog = true },
                trailing = { RowChevron() }
            )
            FlatDivider()
            FlatRow(
                title = "关于",
                onClick = onOpenAbout,
                trailing = { RowChevron() }
            )
        }
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
                    fontFamily = FontFamily.Monospace,
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
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ToAsmr 网络日志", dump))
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

/**
 * 外观设置二级页。
 *
 * 与 comicApp 一样分成三组：主题 / 液态玻璃 / 底部导航栏。
 * 「液态玻璃」进二级页调参数（模糊、折射、透明度、色散），改动实时生效。
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenGlass: () -> Unit
) {
    val themeMode by AppPrefs.themeMode.collectAsState()
    val collapseBottomBar by AppPrefs.collapseBottomBar.collectAsState()

    SettingsScaffold(title = "外观", onBack = onBack) {
        FlatSection(title = "主题") {
            FlatRow(
                title = "明暗模式",
                description = "默认跟随系统"
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

        FlatSection(title = "液态玻璃") {
            FlatRow(
                title = "玻璃样式",
                description = "模糊、折射、色散与透明度",
                onClick = onOpenGlass,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "底部导航栏") {
            FlatRow(
                title = "随页面滚动收起",
                description = "滚动时自动收起 / 展开",
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

/** 关于页 */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    SettingsScaffold(title = "关于", onBack = onBack) {
        FlatSection(title = "ToAsmr") {
            FlatRow(title = "版本", description = "0.2.1")
            FlatDivider()
            FlatRow(
                title = "设计来源",
                description = "移植自 comicApp（ToComic）"
            )
            FlatDivider()
            FlatRow(
                title = "数据来源",
                description = "asmr.one / Kikoeru 公开接口，不存储任何音频"
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "声明") {
            FlatRow(
                title = "非官方项目",
                description = "与 asmr.one、DLsite 无隶属关系"
            )
        }
    }
}
