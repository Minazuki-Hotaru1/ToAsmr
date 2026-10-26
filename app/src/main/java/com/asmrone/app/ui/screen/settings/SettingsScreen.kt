package com.asmrone.app.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.update.ReleaseInfo
import com.asmrone.app.data.update.UpdateChecker
import com.asmrone.app.data.update.UpdateResult
import com.asmrone.app.R
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.component.SettingSwitch
import com.asmrone.app.ui.theme.ThemeColor
import kotlinx.coroutines.launch

/**
 * 设置主页 —— 分组与排版完全沿用 comicApp 设置页：
 * 普通标题栏 + 扁平分组（`FlatSection` / `FlatRow` / `FlatDivider`），
 * 顶部滚动渐显毛玻璃由 [SettingsScaffold] 统一负责。
 */
@Composable
fun SettingsScreen(
    onOpenServer: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val host by AppPrefs.host.collectAsState()
    val authState by AuthManager.state.collectAsState()

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

        // ── 播放 ──
        //
        // 单独一组而不是塞进「外观」：这里管的是播放能不能活下来（电池优化白名单），
        // 与视觉无关。
        FlatSection(title = "播放") {
            FlatRow(
                title = "播放设置",
                description = "后台保护",
                onClick = onOpenPlayback,
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
                title = "缓存与本地数据",
                description = "缓存管理、下载文件夹、搜索索引",
                onClick = onOpenData,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "外观") {
            FlatRow(
                title = "外观",
                description = "主题、玻璃样式、首页加载、底部栏",
                onClick = onOpenAppearance,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── 测试设置：排查问题用的工具，平时用不到 ──
        FlatSection(title = "测试设置") {
            FlatRow(
                title = "测试设置",
                description = "网络日志",
                onClick = onOpenTest,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "其它") {
            FlatRow(
                title = "关于",
                description = "版本、项目仓库",
                onClick = onOpenAbout,
                trailing = { RowChevron() }
            )
        }
    }
}

/**
 * 外观设置二级页。
 *
 * 与 comicApp 一样分成四组：主题 / 液态玻璃 / 底部导航栏 / 首页。
 * 「液态玻璃」进二级页调参数（模糊、折射、透明度、色散），改动实时生效。
 *
 * 主题是**两个开关**而不是三选一：「跟随系统」决定要不要自己管，
 * 「暗色模式」只回答"现在是不是暗的"。三选一的芯片里，选了"跟随系统"之后
 * 用户仍然不知道当前到底是亮还是暗，得退回首页看；两个开关里第二个
 * 永远显示当前生效的样子，信息是完整的。
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenGlass: () -> Unit
) {
    val themeMode by AppPrefs.themeMode.collectAsState()
    val collapseBottomBar by AppPrefs.collapseBottomBar.collectAsState()
    val homeEnabled by AppPrefs.homeEnabled.collectAsState()

    val followSystem = themeMode == 0
    // 系统当前的深色状态 —— 只在"跟随系统"时用来决定第二个开关显示什么
    val systemDark = isSystemInDarkTheme()
    val darkNow = if (followSystem) systemDark else themeMode == 2

    SettingsScaffold(title = "外观", onBack = onBack) {
        FlatSection(title = "主题") {
            FlatRow(
                title = "跟随系统",
                trailing = {
                    SettingSwitch(
                        checked = followSystem,
                        onCheckedChange = { follow ->
                            // 关掉跟随的那一刻要"停在当前观感上"：
                            // 系统是暗的就锁成暗色，否则锁成亮色。
                            // 直接设成亮色会让暗色系统里的用户被闪一下。
                            AppPrefs.setThemeMode(
                                when {
                                    follow -> 0
                                    systemDark -> 2
                                    else -> 1
                                }
                            )
                        }
                    )
                }
            )
            FlatDivider()
            FlatRow(
                title = "暗色模式",
                trailing = {
                    SettingSwitch(
                        checked = darkNow,
                        onCheckedChange = { dark ->
                            // 跟随系统时拨这个开关 = 接管观感，同时把「跟随系统」关掉，
                            // 免得出现"跟随系统开着、但显示的是手动的模式"这种自相矛盾的状态。
                            AppPrefs.setThemeMode(if (dark) 2 else 1)
                        }
                    )
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        ThemeColorSection()

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

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "首页") {
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
        }
    }
}

/**
 * 主题色：两排色点，每排七个。
 *
 * 第一排是素雅的低饱和色，第二排是更醒目的高饱和色 —— 分两排而不是一行十二个，
 * 因为一行十二个在手机上每颗不到 20dp，点不准也看不出色差。
 *
 * 排布用 [Arrangement.SpaceEvenly] 而不是固定间距：这样不论屏幕宽窄，
 * 两端与点之间的间隙都自己均分，一行永远是满的，不会右边空一截。
 *
 * 不做成"带文字的单选行"：颜色本身就是信息，加文字会让这一组比上下两组都高、
 * 视觉上凸出来。选中态靠外圈 + 勾表达；名称留在 [ThemeColor.label] 里给无障碍朗读。
 */
@Composable
private fun ThemeColorSection() {
    val selectedIndex by AppPrefs.themeColor.collectAsState()
    val rows = ThemeColor.entries.chunked(ThemeColor.PER_ROW)
    FlatSection(title = "主题色") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { color ->
                        ThemeColorDot(
                            color = color,
                            selected = color.ordinal == selectedIndex,
                            onClick = { AppPrefs.setThemeColor(color.ordinal) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorDot(
    color: ThemeColor,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color.swatch, CircleShape)
            .border(2.dp, ring, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = color.label },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check_fluent),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 关于页 —— 版本、项目仓库、数据来源。
 *
 * 开源之后这一页只留"这个 App 是什么、从哪来、代码在哪"三件事：
 * 设计来源（移植自哪个项目）与法律声明两段都撤掉了。
 *
 * 「检查更新」直接查仓库的 release 接口（见 [UpdateChecker]），结果一律用对话框
 * 呈现：这里的三种结局都值得读一眼（有新版本要决定去不去下载、已是最新要确认、
 * 失败要知道为什么）。
 */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 版本号从安装包读，别写死 —— 写死的那份永远不会跟着 build.gradle 走
    val versionName = remember(context) { installedVersionName(context) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            result = UpdateChecker.check(versionName)
            checking = false
        }
    }

    SettingsScaffold(title = "关于", onBack = onBack) {
        FlatSection(title = "ToAsmr") {
            FlatRow(
                title = "版本",
                description = versionName,
                trailing = {
                    // 固定 88×34dp：内容在「检查更新」与转圈之间切换，宽度不能跟着变，
                    // 否则每点一次整行都会抖一下。文字与转圈都由按钮自己居中。
                    SettingsOutlinedButton(
                        onClick = { check() },
                        modifier = Modifier.width(UPDATE_BUTTON_WIDTH),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text("检查更新", maxLines = 1, softWrap = false)
                        }
                    }
                }
            )
            FlatDivider()
            FlatRow(
                title = "项目仓库",
                description = "帮我点个小⭐Star吧，这对我真的很重要",
                onClick = { openProjectRepository(context) },
                trailing = { RowChevron() }
            )
            FlatDivider()
            FlatRow(
                title = "数据来源",
                description = "asmr.one / Kikoeru 公开接口，不存储任何音频"
            )
        }
    }

    val current = result
    when (current) {
        is UpdateResult.Available -> UpdateDialog(
            info = current.info,
            currentVersion = versionName,
            onDismiss = { result = null },
            onDownload = {
                result = null
                openUrl(context, current.info.apkUrl ?: current.info.pageUrl)
            }
        )

        UpdateResult.UpToDate -> InfoDialog(
            title = "已是最新版本",
            message = "当前 $versionName 已是最新的版本。",
            onDismiss = { result = null }
        )

        is UpdateResult.Failed -> InfoDialog(
            title = "检查更新失败",
            message = current.message,
            confirmLabel = "重试",
            onConfirm = { result = null; check() },
            onDismiss = { result = null }
        )

        null -> Unit
    }
}

/**
 * 有新版本时的对话框：版本号、发布时间、release 说明，以及"是否装了 APK 资产"
 * 决定按钮文案（有资产就是「下载并安装」指向直链，没资产就「前往 Release 页」）。
 */
@Composable
private fun UpdateDialog(
    info: ReleaseInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                Text(
                    text = buildString {
                        append("当前版本 $currentVersion")
                        if (info.publishedAt.isNotBlank()) append(" · ${info.publishedAt} 发布")
                        if (info.apkBytes > 0) {
                            append(" · ${"%.1f".format(info.apkBytes / 1024f / 1024f)} MB")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(if (info.apkUrl != null) "下载并安装" else "前往 Release 页")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        }
    )
}

@Composable
private fun InfoDialog(
    title: String,
    message: String,
    confirmLabel: String = "知道了",
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); if (confirmLabel == "知道了") onDismiss() }) {
                Text(confirmLabel)
            }
        },
        dismissButton = if (confirmLabel == "知道了") null else {
            { TextButton(onClick = onDismiss) { Text("关闭") } }
        }
    )
}

/** 项目主页（仓库已改名 ToAsmr，GitHub 对旧名仍会 301，但这里直接写新名） */
private const val PROJECT_REPOSITORY_URL = "https://github.com/Minazuki-Hotaru1/ToAsmr"

/**
 * 「检查更新」按钮的固定宽度。
 *
 * 88dp = 四个中文字的 labelLarge（约 56dp）两侧各留 16dp，也装得下 14dp 的转圈。
 * 关键在两种内容**共用同一个宽度**：点击时不会把「版本」这一行挤一下。
 * 高度不用管，`SettingsOutlinedButton` 内部已经锁死 34dp。
 */
private val UPDATE_BUTTON_WIDTH = 88.dp

/**
 * 安装包的 versionName。
 *
 * 不带后缀（作者在 `build.gradle.kts` 里写的 `"0.5.3 "` 带了个尾空格，
 * 界面上一行字看不出差异，但复制出去就会多一格）。
 */
private fun installedVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim().orEmpty()
}.getOrDefault("")

private fun openProjectRepository(context: Context) = openUrl(context, PROJECT_REPOSITORY_URL)

/** 任何"在浏览器里打开"的入口都走这里：失败静默（拿不到浏览器就没得做） */
private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
