package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.audio.AudioCacheMaintenance
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.updateAllDictionaries
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.LiquidSlider
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.theme.ErrorColor
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
 * 「数据」二级页 —— 搜索字典 + 音频缓存。
 *
 * 这些都属于"本地数据"：字典是一次性下载的静态数据，音频缓存是会占用磁盘的落地内容。
 * 它们与"连哪台服务器"无关，所以从「服务器」页拆了出来。
 */
@Composable
fun DataSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dictState by remember { mutableStateOf(readDictionaryUpdateState()) }

    SettingsScaffold(title = "数据", onBack = onBack) {
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

        // ── 音频缓存 ────────────────────────────────────────
        AudioCacheSection()
    }
}

/**
 * 音频缓存区块：两个带停留节点的滑杆（单曲 / 总上限）+ 清空。
 *
 * 上限用滑杆而不是芯片：从 32MB 到「无限制」跨度很大，芯片一行放不下，
 * 滑杆能在一个控件里覆盖整个量级范围，松手后吸附到最近的节点。
 */
@Composable
private fun AudioCacheSection() {
    val limitMb by AppPrefs.audioCacheLimitMb.collectAsState()
    val perTrackMb by AppPrefs.perTrackCacheLimitMb.collectAsState()
    var status by remember { mutableStateOf(AudioCacheMaintenance.status()) }
    var confirmingClear by remember { mutableStateOf(false) }

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
                status = AudioCacheMaintenance.status()
            }
        )

        if (status.usedBytes > 0) {
            FlatDivider()
            FlatRow(
                title = if (confirmingClear) {
                    "再点一次确认"
                } else {
                    "清空音频缓存（释放 ${status.usedText}）"
                },
                description = if (confirmingClear) "将释放 ${status.usedText}" else null,
                onClick = {
                    if (!confirmingClear) {
                        confirmingClear = true
                    } else {
                        confirmingClear = false
                        AudioCacheMaintenance.clearAll()
                        status = AudioCacheMaintenance.status()
                    }
                },
                trailing = {
                    if (confirmingClear) {
                        Text(
                            text = "确认",
                            style = MaterialTheme.typography.labelLarge,
                            color = ErrorColor
                        )
                    } else {
                        RowChevron()
                    }
                }
            )
        }
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
