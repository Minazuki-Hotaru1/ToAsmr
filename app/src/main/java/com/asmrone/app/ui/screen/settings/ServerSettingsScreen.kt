package com.asmrone.app.ui.screen.settings

import com.asmrone.app.ui.theme.TouchTarget
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.data.audio.AudioCachePolicy
import com.asmrone.app.data.audio.AudioCacheMaintenance
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import com.asmrone.app.data.repo.updateAllDictionaries
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

    // ── 搜索字典（标签 / 声优 / 社团）—— 一次更新全部 ──────────
    var dictState by remember { mutableStateOf(readDictionaryUpdateState()) }

    // ── 音频缓存 ────────────────────────────────────────
    // 进入页面时刷新一次；清理 / 改上限之后也要重读（占用数字会变）
    var cacheStatus by remember { mutableStateOf(AudioCacheMaintenance.status()) }
    fun refreshCacheStatus() {
        cacheStatus = AudioCacheMaintenance.status()
    }

    SettingsSubPage(title = "服务器", onBack = onBack) {

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
                    text = "自建 Kikoeru 服务端，或其它镜像域名（需带 http/https，结尾不要 /）",
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
                        ?: "点击下方按钮，请求一次 /api/works 验证主机是否可用",
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

        Spacer(Modifier.height(12.dp))

        // ── 数据设置：搜索字典（一次更新全部）────────────────────
        //
        // 单独一个入口，**不混在「清空缓存」里**：三本字典都是一次性下载的静态数据，
        // 手动点"更新"才会变，把它们和"缓存"归成一类会让人以为随时会过期。
        //
        // 也**不拆成三行**：它们同时被搜索页读取，只要有一本旧了，
        // 高级搜索里就会出现"标签能搜到、社团搜不到"这种费解的情况。
        // 理由详见 DictionaryUpdateState 的注释。
        FlatSection(title = "数据设置") {
            FlatRow(
                title = if (dictState.updating) "正在更新搜索字典…" else "更新搜索字典",
                description = buildString {
                    if (dictState.totalCount > 0) {
                        append("本地已有 ").append(dictState.totalCount).append(" 条")
                        dictState.updatedAt?.let {
                            append(" · 上次更新 ").append(formatTagTime(it))
                        }
                    } else {
                        append("本地还没有搜索字典，更新后才能按标签 / 声优 / 社团搜索")
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
                        // 手动更新过也算"初始化过了"：这样下次冷启动不会再自动拉一遍
                        // （与首次进入时的那次自动拉取共用同一个标记）
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
        //
        // 为什么单独一块、还带上限设置：音频是**唯一会大量落盘**的内容
        // （单个音轨实测可到 92MB），所以必须让用户看得见占用、并自己划上限。
        // 放在「数据设置」而不是「缓存」里是因为它不只是"清一下"，
        // 还有上限这个需要用户决策的项。
        Spacer(Modifier.height(12.dp))
        AudioCacheSection(onFreed = { refreshCacheStatus() })

        Spacer(Modifier.height(12.dp))
        FlatSection(title = "缓存") {
            FlatRow(
                title = "清空缓存",
                description = "标签 / 声优 / 社团字典，以及作品元数据与文件树。" +
                    "不影响上面的搜索标签字典",
                onClick = {
                    AsmrRepository.clearCaches()
                    refreshCacheStatus()
                },
                trailing = { RowChevron() }
            )
        }

        Spacer(Modifier.height(24.dp))

        FlatSection(title = "关于网络") {
            FlatRow(
                title = "无法连接时怎么办",
                description = "本应用不再内置代理设置。若 asmr.one 不可直连，" +
                    "请在系统层开启代理 / VPN（如 Clash、v2rayNG 的 VPN 模式），" +
                    "再回到本页测试连通性。"
            )
        }
    }
}

/**
 * 音频缓存区块：占用 + 上限 + 清理。
 *
 * ## 为什么把三件事放在一起
 *
 * "占了多少"和"上限多少"是同一个决策的两面 —— 用户看到占了 800MB
 * 才会去想要不要调上限；只给一个"清空"按钮的话，他只能反复清空，
 * 而下次听又从头缓存一遍（那等于根本没解决问题）。
 *
 * ## 为什么显示"未缓存完"的数量
 *
 * 音声场景里"试听几秒就划走"非常常见，那会留下很小的 `.part` 文件。
 * 只报一个总占用数字，用户没法理解空间被什么占了；
 * 明说"其中 N 首未缓存完"才说得清，也顺带解释了"为什么删了还是占空间"。
 */
@Composable
private fun AudioCacheSection(onFreed: () -> Unit = {}) {
    val limitMb by AppPrefs.audioCacheLimitMb.collectAsState()
    val perTrackMb by AppPrefs.perTrackCacheLimitMb.collectAsState()
    var status by remember { mutableStateOf(AudioCacheMaintenance.status()) }
    var confirmingClear by remember { mutableStateOf(false) }

    FlatSection(title = "音频缓存") {
        FlatRow(
            title = if (status.usedBytes > 0) {
                "已缓存 ${status.usedText}"
            } else {
                "还没有缓存"
            },
            description = buildString {
                if (status.totalCount > 0) {
                    append(status.compositionText)
                    append("\n")
                }
                append("播放时自动缓存已听过的部分，下次不再消耗流量。")
                if (status.stalePartialCount > 0) {
                    append("\n其中 ${status.stalePartialCount} 首超过 ")
                    append(AudioCachePolicy.STALE_PARTIAL_DAYS)
                    append(" 天没有增长，可在清理时回收")
                }
            }
        )

        FlatDivider()

        // ── 单曲缓存上限 ──────────────────────────────────
        //
        // 与下面的"缓存总上限"是两个不同的量，所以标题必须把"单曲 / 总"
        // 说清楚 —— 否则用户不知道这两个数字分别管什么。
        FlatRow(
            title = "单曲缓存上限",
            description = buildString {
                append("一次播放里，一首曲子最多往后缓存 ")
                append(formatMb(perTrackMb))
                append("。\n")
                append("MP3 一般十几 MB，这条够用；")
                append("WAV / FLAC 单曲可以到 90 MB 以上，")
                append("设小了这类曲子会一直缓存不完整、每次都要重新下载。")
            }
        )
        ChoiceChips(
            choices = AudioCachePolicy.PER_TRACK_CHOICES_MB,
            selected = perTrackMb,
            onSelect = { AppPrefs.setPerTrackCacheLimitMb(it) }
        )

        FlatDivider()

        // ── 缓存总上限 ────────────────────────────────────
        FlatRow(
            title = "缓存总上限",
            description = "所有已缓存音频占用的磁盘上限（当前 ${formatMb(limitMb)}）。" +
                "超过后按「最久没播放」自动清理。"
        )
        ChoiceChips(
            choices = AudioCachePolicy.TOTAL_LIMIT_CHOICES_MB,
            selected = limitMb,
            onSelect = { mb ->
                // 改上限后立刻按新上限收敛一次：
                // 用户把 2GB 调到 512MB 时，期望是"现在就腾出空间"，
                // 而不是"等下次启动才清"
                AudioCacheMaintenance.applyLimit(mb)
                status = AudioCacheMaintenance.status()
                onFreed()
            }
        )

        if (status.usedBytes > 0) {
            FlatDivider()
            FlatRow(
                title = "清空音频缓存",
                description = if (confirmingClear) {
                    "再点一次确认，将释放 ${status.usedText}"
                } else {
                    "释放 ${status.usedText}（只影响已缓存的音频，不影响收藏与字典）"
                },
                onClick = {
                    if (!confirmingClear) {
                        confirmingClear = true
                    } else {
                        confirmingClear = false
                        AudioCacheMaintenance.clearAll()
                        status = AudioCacheMaintenance.status()
                        onFreed()
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
 * 一排量级选项（芯片）。
 *
 * 用芯片而不是输入框：这些是"划多大空间 / 一次愿意花多少流量"的**量级决策**，
 * 让用户敲数字既慢又容易敲出无意义的值（0、3、99999）。
 */
@Composable
private fun ChoiceChips(
    choices: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
            .heightIn(min = TouchTarget.minimum),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        choices.forEach { mb ->
            AsmrFilterChip(
                text = formatMb(mb),
                selected = selected == mb,
                onClick = { onSelect(mb) }
            )
        }
    }
}

/** MB 数 → 人话（1024 → 1 GB） */
private fun formatMb(mb: Int): String =
    if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

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
