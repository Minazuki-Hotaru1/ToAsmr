package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.clickable
import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.asmrone.app.data.audio.AudioCacheMaintenance
import com.asmrone.app.data.download.DownloadManager
import com.asmrone.app.data.download.DownloadStorage
import com.asmrone.app.data.download.LocalDownloadStore
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.updateAllDictionaries
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.LiquidSlider
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.component.rememberLocalStorageRequester
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 缓存上限里的「无限制」：用一个足够大的 MB 表示，避免维护层为 0 时误删全部缓存 */
private const val UNLIMITED_MB = Int.MAX_VALUE

/** 单曲缓存上限的停留节点：32MB → 1GB → 无限制 */
private val PER_TRACK_STOPS_MB = listOf(32, 64, 128, 256, 512, 1024, UNLIMITED_MB)

/** 缓存总上限的停留节点：256MB → 5GB → 无限制 */
private val TOTAL_STOPS_MB = listOf(256, 512, 1024, 2048, 4096, 5120, UNLIMITED_MB)

/**
 * 「数据」二级页 —— 缓存管理 + 搜索字典 + 音频缓存上限。
 *
 * 这些都属于"本地数据"：字典是一次性下载的静态数据，缓存是会占用磁盘的落地内容。
 * 它们与"连哪台服务器"无关，所以从「服务器」页拆了出来。
 */
@Composable
fun DataSettingsScreen(onBack: () -> Unit, onOpenCache: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dictState by remember { mutableStateOf(readDictionaryUpdateState()) }

    SettingsScaffold(title = "数据", onBack = onBack) {
        // ── 缓存 ──
        // 单个"清除缓存"按钮会把再生成本完全不同的东西混在一起
        // （图片只是重下缩略图，音频要重新边播边下），所以给一个二级页分类处理。
        FlatSection(title = "缓存") {
            FlatRow(
                title = "缓存管理",
                description = "查看图片、音频、详情等各类缓存的占用，按类清除",
                onClick = onOpenCache,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 搜索字典 ──
        FlatSection(title = "搜索字典") {
            FlatRow(
                title = if (dictState.updating) "正在更新搜索字典…" else "更新搜索字典",
                description = buildString {
                    if (dictState.totalCount > 0) {
                        append("本地已有 ").append(dictState.totalCount).append(" 条")
                        dictState.updatedAt?.let {
                            append(" · 上次更新 ").append(formatTagTime(it))
                        }
                    } else {
                        append("更新后才能按标签 / 声优 / 社团搜索")
                    }
                    // 进度优先于结果提示：更新中时最该看到的是"到第几本了"
                    dictState.progress?.let { append("\n").append(it) }
                        ?: dictState.message?.let { append("\n").append(it) }
                },
                onClick = {
                    if (dictState.updating) return@FlatRow
                    dictState = dictState.copy(updating = true, message = null, progress = null)
                    scope.launch {
                        val outcome = updateAllDictionaries { text ->
                            dictState = dictState.copy(progress = text)
                        }
                        // 手动更新过也算"初始化过了"：下次冷启动不再自动拉一遍
                        AppPrefs.setDictionariesInitialized(true)
                        // 重新从本地读，而不是相信返回值：某本失败时本地仍是旧的那份
                        val fresh = readDictionaryUpdateState()
                        dictState = fresh.copy(
                            updating = false,
                            progress = null,
                            message = outcome.message(fresh.totalCount)
                        )
                    }
                },
                trailing = {
                    if (dictState.updating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        RowChevron()
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 下载保存位置 ────────────────────────────────────
        DownloadLocationSection()

        Spacer(Modifier.height(12.dp))

        // ── 音频缓存 ────────────────────────────────────────
        AudioCacheSection()
    }
}

/**
 * 本地下载文件夹：选一个公共目录里的文件夹。
 *
 * 保存位置（系统 / 本地）的开关在「下载」页顶栏，这里只管"本地"那一档落在哪 ——
 * 设置页放一个开关、下载页再放一个，两边状态容易对不上，用户也不知道哪个生效。
 */
@Composable
private fun DownloadLocationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir by AppPrefs.localDownloadDir.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }
    var accessTick by remember { mutableIntStateOf(0) }
    val hasAccess = remember(accessTick, dir) { LocalDownloadStore.hasAccess(context) }

    // 两条授权路径（API 30+ 跳设置页、API 28/29 弹运行时权限）都收在这一个函数里
    val requestAccess = rememberLocalStorageRequester { granted ->
        if (granted) {
            accessTick++
            pickerOpen = true
        }
    }

    // 从系统授权页回来时刷新一次权限状态（否则要杀进程重进才认）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FlatSection(title = "下载") {
        FlatRow(
            title = "本地文件夹",
            description = buildString {
                append(dir)
                if (!hasAccess) {
                    append("\n还没有存储权限，点此去授予")
                } else {
                    append("\n「下载」页切到「本地」后，文件会存到这里")
                }
            },
            onClick = {
                if (hasAccess) {
                    pickerOpen = true
                } else {
                    requestAccess()
                }
            },
            trailing = { RowChevron() }
        )
    }

    if (pickerOpen) {
        LocalFolderPickerDialog(
            startDir = File(dir),
            onDismiss = { pickerOpen = false },
            onPick = { picked ->
                pickerOpen = false
                AppPrefs.setLocalDownloadDir(picked.absolutePath)
                scope.launch {
                    // 换了根目录：重建索引，再让下载页的列表跟着刷（否则它仍显示旧目录里的作品）
                    LocalDownloadStore.rescan()
                    DownloadManager.refreshLibrary()
                }
            }
        )
    }
}

/**
 * 极简目录选择器：只列子文件夹，点名字进入，底部确认当前目录。
 *
 * 没有用 SAF（`ACTION_OPEN_DOCUMENT_TREE`）：那套返回的是 tree Uri，
 * 写文件要逐级 `DocumentFile` 创建子目录，没法保留作品原来的目录结构；
 * 拿到「所有文件访问权限」后直接用 `File` 反而能原样落盘。
 */
@Composable
private fun LocalFolderPickerDialog(
    startDir: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember(startDir) {
        mutableStateOf(if (startDir.isDirectory) startDir else root)
    }
    val children = remember(dir) {
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地文件夹") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = dir.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // 上一级：到外置存储根就停住，避免把 /storage 也列出来
                if (dir.absolutePath != root.absolutePath) {
                    Text(
                        text = "↑ 上一级",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dir.parentFile?.let { dir = it } }
                            .padding(vertical = 10.dp)
                    )
                }
                children.forEach { child ->
                    Text(
                        text = child.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dir = child }
                            .padding(vertical = 10.dp)
                    )
                }
                if (children.isEmpty()) {
                    Text(
                        text = "（没有子文件夹）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "恢复默认（/storage/emulated/0/ToAsmr）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            dir = File(root, "ToAsmr").also { it.mkdirs() }
                        }
                        .padding(vertical = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!dir.exists()) dir.mkdirs()
                onPick(dir)
            }) {
                Text("使用此文件夹")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 音频缓存区块：两个带停留节点的滑杆（单曲 / 总上限）。
 *
 * 上限用滑杆而不是芯片：从 32MB 到「无限制」跨度很大，芯片一行放不下，
 * 滑杆能在一个控件里覆盖整个量级范围，松手后吸附到最近的节点。
 *
 * "清空"不在这里，统一收在「缓存管理」页（[CacheSettingsScreen]）——
 * 那里才看得到当前占用，也才能和其他类的缓存放在一起比较。
 */
@Composable
private fun AudioCacheSection() {
    val limitMb by AppPrefs.audioCacheLimitMb.collectAsState()
    val perTrackMb by AppPrefs.perTrackCacheLimitMb.collectAsState()

    FlatSection(title = "音频缓存") {
        CacheLimitSlider(
            title = "单曲缓存上限",
            stops = PER_TRACK_STOPS_MB,
            value = perTrackMb,
            onValueChange = { AppPrefs.setPerTrackCacheLimitMb(it) }
        )

        FlatDivider()

        CacheLimitSlider(
            title = "缓存总上限",
            stops = TOTAL_STOPS_MB,
            value = limitMb,
            onValueChange = { mb ->
                // 改上限后立刻按新上限收敛一次：用户调小时，期望是"现在就腾出空间"
                AudioCacheMaintenance.applyLimit(mb)
            }
        )
    }
}

/**
 * 带停留节点的缓存上限滑杆。
 *
 * 拖动时滑块连续跟随手指，松手后**吸附到最近的节点**并把该节点的值回调出去；
 * 滑杆下方把节点值逐个列出来（当前节点用主色），用户能一眼看出可选的档位。
 */
@Composable
private fun CacheLimitSlider(
    title: String,
    stops: List<Int>,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    val targetIndex = remember(stops, value) {
        stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0
    }
    var position by remember(value) { mutableFloatStateOf(targetIndex.toFloat()) }

    Column {
        FlatRow(title = title)
        LiquidSlider(
            value = { position },
            onValueChange = { position = it },
            onValueChangeFinished = {
                val index = position.roundToInt().coerceIn(0, stops.lastIndex)
                position = index.toFloat()
                onValueChange(stops[index])
            },
            valueRange = 0f..stops.lastIndex.toFloat(),
            visibilityThreshold = 0.01f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(40.dp)
        )
        // 节点值：**按节点位置居中**排布（不是 SpaceBetween 按文字宽度挤），
        // 这样每个标签的横向中心与上方滑杆的停留节点一一对齐；
        // 首尾两个会被夹在轨道两端内，避免"无上限"被裁掉。
        Layout(
            content = {
                stops.forEachIndexed { index, mb ->
                    Text(
                        text = if (mb == UNLIMITED_MB) "无上限" else formatShort(mb),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == position.roundToInt()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        maxLines = 1
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val placeables = measurables.map { it.measure(Constraints()) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                val last = (placeables.size - 1).coerceAtLeast(1)
                placeables.forEachIndexed { index, placeable ->
                    val center = width.toFloat() * index / last
                    val x = (center - placeable.width / 2f)
                        .coerceIn(0f, (width - placeable.width).coerceAtLeast(0).toFloat())
                    placeable.place(x.roundToInt(), 0)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}


/** 节点标签：短，能塞进一行 */
private fun formatShort(mb: Int): String =
    if (mb >= 1024) "${mb / 1024}G" else "${mb}M"


/**
 * 把字典的更新时间说成人话。
 *
 * 只显示到"分钟"级别：这些是低频数据，精确到秒没有意义。
 */
private fun formatTagTime(at: Long): String {
    if (at <= 0L) return "未知"
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 30L * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(at))
    }
}
