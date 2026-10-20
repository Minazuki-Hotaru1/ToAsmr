package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.asmrone.app.data.cache.CacheManager
import com.asmrone.app.ui.component.FlatDivider
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.screen.download.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 清除目标：一类，或全部 */
private sealed interface ClearTarget {
    data class One(val kind: CacheManager.Kind) : ClearTarget
    data object All : ClearTarget
}

/**
 * 「缓存」二级页 —— 各类本地数据分别占多少、能不能单独清掉。
 *
 * ## 为什么按类拆开而不是一个"清除缓存"按钮
 *
 * 这些缓存的**再生成本**差得很远：图片删了只是重新下缩略图（几百 KB、瞬时），
 * 音频分片删了下次播放要重新边播边下，搜索字典删了要重新走一遍更新流程。
 * 合成一个按钮时，用户想腾出几十 MB，却可能顺手把几 MB 的字典也清了，
 * 换来的是一段莫名其妙的"搜索变慢"。分开列出来，才谈得上取舍。
 *
 * 页面里的每个数字都是**当场扫盘**得到的（[CacheManager.usages] 在 IO 线程跑），
 * 清完立刻重算 —— 不做乐观更新，避免"数字还在、其实已经删了"的错觉。
 */
@Composable
fun CacheSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usages by remember { mutableStateOf<List<CacheManager.Usage>?>(null) }
    var pendingWrites by remember { mutableStateOf(0) }
    var target by remember { mutableStateOf<ClearTarget?>(null) }
    var clearing by remember { mutableStateOf(false) }

    suspend fun reload() {
        usages = withContext(Dispatchers.IO) { CacheManager.usages() }
        pendingWrites = withContext(Dispatchers.IO) { CacheManager.pendingWriteCount() }
    }

    LaunchedEffect(Unit) {
        CacheManager.init(context)
        reload()
    }

    fun runClear(what: ClearTarget) {
        if (clearing) return
        clearing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                when (what) {
                    is ClearTarget.All -> CacheManager.clearAll()
                    is ClearTarget.One -> CacheManager.clear(what.kind)
                }
            }
            reload()
            clearing = false
            target = null
        }
    }

    val total = usages?.sumOf { it.bytes } ?: 0L

    SettingsScaffold(title = "缓存管理", onBack = onBack) {
        FlatSection(title = "占用") {
            FlatRow(
                title = "合计",
                description = when {
                    usages == null -> "正在统计…"
                    else -> "共 ${CLEAR_ORDER.size} 类，按当前磁盘占用计算"
                },
                trailing = {
                    Text(
                        text = if (usages == null) "…" else formatBytes(total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            CLEAR_ORDER.forEach { kind ->
                val usage = usages?.firstOrNull { it.kind == kind }
                val size = usage?.bytes ?: 0L
                FlatDivider()
                FlatRow(
                    title = kind.title(),
                    description = kind.describe(usage),
                    enabled = size > 0L,
                    // 占用为 0 时不响应点击：点开一个"将释放 0 B"的确认框没有意义
                    onClick = if (size > 0L) ({ target = ClearTarget.One(kind) }) else null,
                    trailing = {
                        Text(
                            text = if (usage == null) "…" else formatBytes(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        FlatSection {
            FlatRow(
                title = "清除全部缓存",
                description = "已下载的作品、未同步的改动与登录状态都会保留",
                enabled = total > 0L,
                onClick = if (total > 0L) ({ target = ClearTarget.All }) else null,
                trailing = { RowChevron() }
            )
        }

        if (pendingWrites > 0) {
            Text(
                text = "有 $pendingWrites 条还没同步到云端的改动，清除缓存不会动它们。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
            )
        }
    }

    target?.let { what ->
        AlertDialog(
            onDismissRequest = { if (!clearing) target = null },
            title = { Text(titleFor(what)) },
            text = { Text(messageFor(what, total, usages)) },
            confirmButton = {
                TextButton(enabled = !clearing, onClick = { runClear(what) }) {
                    Text(
                        text = if (clearing) "正在清除…" else "清除",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !clearing, onClick = { target = null }) { Text("取消") }
            }
        )
    }
}

/** 列表顺序 = 从"最容易再生"到"最值得留一个确认" */
private val CLEAR_ORDER = listOf(
    CacheManager.Kind.IMAGES,
    CacheManager.Kind.AUDIO,
    CacheManager.Kind.LYRICS,
    CacheManager.Kind.WORK_META,
    CacheManager.Kind.TRACK_TREE,
    CacheManager.Kind.FOLDER_STATE,
    CacheManager.Kind.SYNC_MIRRORS,
    CacheManager.Kind.EXTERNAL_FILES
)

private fun CacheManager.Kind.title(): String = when (this) {
    CacheManager.Kind.IMAGES -> "图片缓存"
    CacheManager.Kind.AUDIO -> "音频缓存"
    CacheManager.Kind.WORK_META -> "作品信息"
    CacheManager.Kind.TRACK_TREE -> "文件列表"
    CacheManager.Kind.FOLDER_STATE -> "文件夹展开状态"
    CacheManager.Kind.LYRICS -> "歌词"
    CacheManager.Kind.SYNC_MIRRORS -> "收藏与列表"
    CacheManager.Kind.EXTERNAL_FILES -> "外部打开临时文件"
}

/** 「N 项 · 」前缀：条数只有统计出来之后才写，避免先闪一个 0 */
private fun countPrefix(usage: CacheManager.Usage?): String =
    usage?.count?.takeIf { it > 0 }?.let { "$it 项 · " }.orEmpty()

private fun CacheManager.Kind.describe(usage: CacheManager.Usage?): String = when (this) {
    CacheManager.Kind.IMAGES -> "封面与截图；清除后重新联网加载"
    CacheManager.Kind.AUDIO -> countPrefix(usage) + "边播边缓存的分片；清除后播放时重新缓存"
    CacheManager.Kind.WORK_META -> countPrefix(usage) + "标题 / 社团 / 声优 / 标签；7 天内不重复请求"
    CacheManager.Kind.TRACK_TREE -> countPrefix(usage) + "作品的文件清单；清除后重新拉取"
    CacheManager.Kind.FOLDER_STATE -> "上次进作品时展开了哪些文件夹"
    CacheManager.Kind.LYRICS -> "已解析的歌词；清除后播放时重新取"
    CacheManager.Kind.SYNC_MIRRORS -> countPrefix(usage) + "收藏 / 标记 / 收藏夹的本地镜像"
    CacheManager.Kind.EXTERNAL_FILES -> "分享到外部 App 时落盘的临时文件"
}

private fun titleFor(target: ClearTarget): String = when (target) {
    is ClearTarget.All -> "清除全部缓存"
    is ClearTarget.One -> "清除${target.kind.title()}"
}

private fun messageFor(
    target: ClearTarget,
    total: Long,
    usages: List<CacheManager.Usage>?
): String = when (target) {
    is ClearTarget.All ->
        "将释放约 ${formatBytes(total)}。已下载的作品、未同步的改动与登录状态都会保留。"
    is ClearTarget.One -> {
        val size = usages?.firstOrNull { it.kind == target.kind }?.bytes ?: 0L
        "占用 ${formatBytes(size)}，清除后会在下次使用时重新生成。"
    }
}
