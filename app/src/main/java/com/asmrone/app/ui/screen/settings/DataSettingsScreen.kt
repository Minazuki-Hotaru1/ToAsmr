package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var confirmUpdate by remember { mutableStateOf(false) }

    fun startUpdate() {
        if (dictState.updating) return
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
    }

    SettingsScaffold(title = "数据", onBack = onBack) {
        // ── 缓存 ──
        // 单个"清除缓存"按钮会把再生成本完全不同的东西混在一起
        // （图片只是重下缩略图，音频要重新边播边下），所以给一个二级页分类处理。
        FlatSection(title = "缓存") {
            FlatRow(
                title = "缓存管理",
                description = "分类查看与清除各类缓存",
                onClick = onOpenCache,
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 搜索字典 ──
        FlatSection(title = "搜索索引") {
            FlatRow(
                title = if (dictState.updating) "正在更新…" else "更新搜索索引",
                description = buildString {
                    if (dictState.totalCount > 0) {
                        append("标签、声优、社团 · 本地 ").append(dictState.totalCount).append(" 条")
                        dictState.updatedAt?.let { append("\n上次更新 ").append(formatTagTime(it)) }
                    } else {
                        append("标签、声优、社团的本地索引\n未更新时只能按关键词搜索")
                    }
                    // 进度优先于结果提示：更新中时最该看到的是"到第几本了"
                    dictState.progress?.let { append("\n").append(it) }
                        ?: dictState.message?.let { append("\n").append(it) }
                },
                onClick = {
                    // 更新会重新下载三份索引（要联网、要时间），点一下就开跑太突然，
                    // 先问一句再动手
                    if (!dictState.updating) confirmUpdate = true
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

    if (confirmUpdate) {
        AlertDialog(
            onDismissRequest = { confirmUpdate = false },
            title = { Text("更新搜索索引") },
            text = { Text("将重新下载标签、声优、社团三份索引，需要联网。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpdate = false
                    startUpdate()
                }) { Text("更新") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpdate = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 本地下载文件夹：选一个公共目录里的文件夹。
 *
 * 保存位置（系统 / 本地）的开关在「下载」页顶栏，这里只管"本地"那一档落在哪 ——
 * 设置页放一个开关、下载页再放一个，两边状态容易对不上，用户也不知道哪个生效。
 *
 * 目录由**系统文件选择器**挑（`ACTION_OPEN_DOCUMENT_TREE`），拿回来再映射成文件系统路径：
 * 落盘仍然走 `java.io.File`（这样能原样保留作品内部的目录结构），
 * 所以这里只是借系统的选择界面，并不使用 SAF 的文档树写入。
 */
@Composable
private fun DownloadLocationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir by AppPrefs.localDownloadDir.collectAsState()
    var accessTick by remember { mutableIntStateOf(0) }
    var pickError by remember { mutableStateOf<String?>(null) }
    val hasAccess = remember(accessTick, dir) { LocalDownloadStore.hasAccess(context) }

    fun applyDir(picked: File) {
        AppPrefs.setLocalDownloadDir(picked.absolutePath)
        pickError = null
        scope.launch {
            // 换了根目录：重建索引，再让下载页的列表跟着刷（否则它仍显示旧目录里的作品）
            LocalDownloadStore.rescan()
            DownloadManager.refreshLibrary()
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = LocalDownloadStore.pathFromTreeUri(uri)
        if (picked == null) {
            // 云端 provider（网盘等）没有对应的文件系统路径，写不进去
            pickError = "这个位置不是本机存储，请选内部存储里的文件夹"
        } else {
            applyDir(picked)
        }
    }

    // 两条授权路径（API 30+ 跳设置页、API 28/29 弹运行时权限）都收在这一个函数里；
    // 授权成功后直接打开系统选择器，省一次点击
    val requestAccess = rememberLocalStorageRequester { granted ->
        if (granted) {
            accessTick++
            treePicker.launch(null)
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
            title = "切换本地下载文件夹",
            description = buildString {
                append(dir)
                if (!hasAccess) append("\n缺少存储权限，点此授予")
            },
            onClick = {
                pickError = null
                if (hasAccess) treePicker.launch(null) else requestAccess()
            },
            trailing = { RowChevron() }
        )
    }

    if (pickError != null) {
        pickError?.let { SectionHint(it) }
    }
}

/** 一行红色小字提示（放在分组下面，不占行高） */
@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = com.asmrone.app.ui.theme.ErrorColor,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
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